import Darwin
import Dispatch
import Metal
import QuartzCore
import SailLook
import SailSurface
import SailVT
import Synchronization

/// Draws ``ScreenFrame``s with Metal: into a `CAMetalLayer`'s drawable for the view, or into a
/// texture read back as an image. One render pass per frame: the backgrounds, then the glyphs and
/// decorations from the atlases, each an instanced draw of pixel rectangles. Instances go into one
/// of three buffers, written only once the frame that last read it has completed; a frame that
/// finds every buffer in flight is not drawn (``DrawOutcome/busy``), and the caller draws on a
/// later refresh. Used from one thread, the view's.
public final class TerminalRenderer {
    public let device: any MTLDevice
    public private(set) var faces: TerminalFaces
    /// The CPU time the last frame took on its thread: instances laid out, buffers written, the
    /// pass encoded. The thread's own clock, so time the machine gave other work is not counted.
    public private(set) var lastEncodeTime = Duration.zero
    /// The same work on the wall clock, which counts whatever else the machine ran meanwhile.
    public private(set) var lastEncodeWallTime = Duration.zero
    private var offscreen: (any MTLTexture)?

    private let fonts: TerminalFonts
    private let queue: any MTLCommandQueue
    private let solidPipeline: any MTLRenderPipelineState
    private let glyphPipeline: any MTLRenderPipelineState
    private let ledger: Ledger
    private let sides: AtlasSides
    private var store: GlyphStore
    private var builder: InstanceBuilder
    private var builtGeneration = 0
    private var buffers: [FrameBuffers]

    private struct FrameBuffers {
        var solids: (any MTLBuffer)?
        var glyphs: (any MTLBuffer)?
    }

    /// The frames in flight, told complete by Metal's completion handlers on their own thread.
    final class Ledger: Sendable {
        let state: Mutex<FrameLedger>

        init(_ ledger: FrameLedger) {
            state = Mutex(ledger)
        }

        func begin() -> (frame: UInt64, buffer: Int)? {
            state.withLock { $0.begin() }
        }

        func complete(_ frame: UInt64) {
            state.withLock { $0.complete(frame) }
        }

        var completed: UInt64 {
            state.withLock { $0.completed }
        }

        var begun: UInt64 {
            state.withLock { $0.begun }
        }
    }

    public convenience init(fonts: TerminalFonts, points: Double, scale: Double) throws(RenderError) {
        guard let device = MTLCreateSystemDefaultDevice() else { throw .unavailable("this Mac offers no Metal device") }
        try self.init(device: device, fonts: fonts, points: points, scale: scale, sides: AtlasSides())
    }

    init(device: any MTLDevice, fonts: TerminalFonts, points: Double, scale: Double, sides: AtlasSides) throws(RenderError) {
        self.device = device
        self.fonts = fonts
        self.sides = sides
        guard let queue = device.makeCommandQueue() else { throw .unavailable("no Metal command queue could be made") }
        self.queue = queue
        let library: any MTLLibrary
        do {
            library = try device.makeLibrary(source: Shaders.source, options: nil)
        } catch {
            throw .unavailable("the terminal's shaders did not compile: \(error)")
        }
        solidPipeline = try Self.pipeline(device, library, vertex: "solidVertex", fragment: "solidFragment")
        glyphPipeline = try Self.pipeline(device, library, vertex: "glyphVertex", fragment: "glyphFragment")
        let frames: FrameLedger
        do {
            frames = try FrameLedger()
        } catch {
            throw .unavailable("the frame buffers could not be counted: \(error)")
        }
        ledger = Ledger(frames)
        buffers = Array(repeating: FrameBuffers(), count: frames.buffers)
        let faces = try Self.faces(fonts, points: points, scale: scale)
        self.faces = faces
        store = try GlyphStore(device: device, faces: faces, sides: sides)
        builder = InstanceBuilder(metrics: faces.metrics)
        store.completed = { [ledger] in ledger.completed }
    }

    public var metrics: CellMetrics {
        faces.metrics
    }

    /// Bytes written into the atlases so far: glyphs drawn, and copies when one grew.
    public var atlasUploadBytes: Int {
        store.uploaded
    }

    /// Glyphs left undrawn because no atlas slot was free of frames in flight.
    public var unplacedGlyphs: Int {
        store.unplaced
    }

    /// The font at another size or scale: new atlases, and every row laid out again.
    public func setFont(points: Double, scale: Double) throws(RenderError) {
        let faces = try Self.faces(fonts, points: points, scale: scale)
        let store = try GlyphStore(device: device, faces: faces, sides: sides)
        store.completed = { [ledger] in ledger.completed }
        self.faces = faces
        self.store = store
        builder = InstanceBuilder(metrics: faces.metrics)
    }

    /// Lays every row out again at the next frame, as after a frame that could not be drawn.
    public func invalidate() {
        builder.invalidate()
    }

    /// Draws `frame` into the drawable `next` gives (a layer's next one) and presents it, asking
    /// for it only once a buffer is free; `presented` is told the frame's number and when it
    /// reached the display (zero when it was dropped).
    public func draw(
        _ frame: ScreenFrame, overlay: Overlay, from next: () -> (any CAMetalDrawable)?,
        presented: @escaping @Sendable (UInt64, Double) -> Void
    ) -> DrawOutcome {
        guard let slot = ledger.begin() else { return .busy }
        guard let drawable = next(), let commandBuffer = queue.makeCommandBuffer(),
              encode(frame, overlay: overlay, into: drawable.texture, commandBuffer: commandBuffer, slot: slot, newItems: Self.newItemsPerFrame)
        else {
            ledger.complete(slot.frame)
            return .unavailable
        }
        let ledger = ledger
        let number = slot.frame
        commandBuffer.addCompletedHandler { _ in ledger.complete(number) }
        drawable.addPresentedHandler { drawable in presented(number, drawable.presentedTime) }
        commandBuffer.present(drawable)
        commandBuffer.commit()
        return .drawn(number)
    }

    /// How many new glyphs a frame drawn on the display rasterizes at most (Core Text, on the main
    /// thread, about half a millisecond each on the CI runner): the rest are drawn by the next
    /// refreshes, so a screen of new text never stalls one frame.
    public static let newItemsPerFrame = 32

    /// Whether the last frame drew every glyph; false while new glyphs wait for the next refresh.
    public var lastFrameComplete: Bool {
        builder.complete
    }

    /// How long an offscreen frame may take on the GPU before reading it back is given up.
    public static let offscreenDeadline = 10.0

    /// `frame` drawn offscreen at `size` pixels and read back, every row laid out afresh, drawing at
    /// most `newItems` new glyphs (all of them unless told).
    public func image(of frame: ScreenFrame, overlay: Overlay, size: PixelSize, newItems: Int = .max) throws(RenderError) -> RenderedImage {
        let done = DispatchSemaphore(value: 0)
        let (commandBuffer, readback) = try submitOffscreen(frame, overlay: overlay, size: size, newItems: newItems) { done.signal() }
        try finished(commandBuffer, done)
        let bytes = [UInt8](UnsafeRawBufferPointer(start: readback.contents(), count: size.width * size.height * 4))
        return RenderedImage(width: size.width, height: size.height, bgra: bytes)
    }

    /// `frame` drawn offscreen at `size` pixels as ``image(of:overlay:size:newItems:)`` draws it,
    /// into a target kept between calls, with nothing read back: the CPU side's cost alone.
    public func drawOffscreen(_ frame: ScreenFrame, overlay: Overlay, size: PixelSize, newItems: Int = .max) throws(RenderError) {
        let done = DispatchSemaphore(value: 0)
        try finished(submit(frame, overlay: overlay, size: size, readback: nil, newItems: newItems) { done.signal() }, done)
    }

    /// Encodes and commits `frame` offscreen, after `wait` (when given) reaches `value`: so a test
    /// holds a frame in flight for as long as it likes; `completed` is told once the frame's buffer
    /// is free again. Returns the command buffer and the buffer the pixels are read back into.
    func submitOffscreen(
        _ frame: ScreenFrame, overlay: Overlay, size: PixelSize, wait: (event: any MTLEvent, value: UInt64)? = nil,
        newItems: Int = .max, completed: @escaping @Sendable () -> Void = {}
    ) throws(RenderError) -> (any MTLCommandBuffer, any MTLBuffer) {
        guard size.width > 0, size.height > 0 else { throw .unavailable("an image of \(size.width)x\(size.height) pixels has nothing to draw into") }
        guard let readback = device.makeBuffer(length: size.width * size.height * 4, options: .storageModeShared) else {
            throw .unavailable("no buffer of \(size.width)x\(size.height) pixels could be made to read the frame back into")
        }
        return (try submit(frame, overlay: overlay, size: size, readback: readback, wait: wait, newItems: newItems, completed: completed), readback)
    }

    private func finished(_ commandBuffer: any MTLCommandBuffer, _ done: DispatchSemaphore) throws(RenderError) {
        guard done.wait(timeout: .now() + Self.offscreenDeadline) == .success else {
            throw .unavailable("the GPU did not finish an offscreen frame within \(Int(Self.offscreenDeadline)) s")
        }
        if let error = commandBuffer.error { throw .unavailable("the offscreen frame failed on the GPU: \(error)") }
    }

    private func submit(
        _ frame: ScreenFrame, overlay: Overlay, size: PixelSize, readback: (any MTLBuffer)?, wait: (event: any MTLEvent, value: UInt64)? = nil,
        newItems: Int, completed: @escaping @Sendable () -> Void
    ) throws(RenderError) -> any MTLCommandBuffer {
        guard size.width > 0, size.height > 0 else { throw .unavailable("an image of \(size.width)x\(size.height) pixels has nothing to draw into") }
        guard let slot = ledger.begin() else { throw .unavailable("every frame buffer is in flight") }
        guard let target = offscreenTarget(size), let commandBuffer = queue.makeCommandBuffer() else {
            ledger.complete(slot.frame)
            throw .unavailable("no offscreen target of \(size.width)x\(size.height) pixels could be made")
        }
        if let wait { commandBuffer.encodeWaitForEvent(wait.event, value: wait.value) }
        builder.invalidate()
        guard encode(frame, overlay: overlay, into: target, commandBuffer: commandBuffer, slot: slot, newItems: newItems) else {
            ledger.complete(slot.frame)
            throw .unavailable("the offscreen frame could not be encoded")
        }
        if let readback {
            guard let blit = commandBuffer.makeBlitCommandEncoder() else {
                ledger.complete(slot.frame)
                throw .unavailable("the offscreen frame could not be read back")
            }
            blit.copy(
                from: target, sourceSlice: 0, sourceLevel: 0, sourceOrigin: MTLOrigin(x: 0, y: 0, z: 0),
                sourceSize: MTLSize(width: size.width, height: size.height, depth: 1), to: readback, destinationOffset: 0,
                destinationBytesPerRow: size.width * 4, destinationBytesPerImage: size.width * size.height * 4
            )
            blit.endEncoding()
        }
        let ledger = ledger
        let number = slot.frame
        commandBuffer.addCompletedHandler { _ in
            ledger.complete(number)
            completed()
        }
        commandBuffer.commit()
        return commandBuffer
    }

    /// The offscreen target of `size`, made once and kept until a frame of another size: frames on
    /// the one queue draw into it in turn.
    private func offscreenTarget(_ size: PixelSize) -> (any MTLTexture)? {
        if let target = offscreen, target.width == size.width, target.height == size.height { return target }
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: size.width, height: size.height, mipmapped: false)
        descriptor.usage = [.renderTarget]
        descriptor.storageMode = .private
        offscreen = device.makeTexture(descriptor: descriptor)
        return offscreen
    }

    private func encode(
        _ frame: ScreenFrame, overlay: Overlay, into target: any MTLTexture, commandBuffer: any MTLCommandBuffer,
        slot: (frame: UInt64, buffer: Int), newItems: Int
    ) -> Bool {
        let started = (cpu: Duration.threadCPU, wall: ContinuousClock.now)
        defer {
            lastEncodeTime = Duration.threadCPU - started.cpu
            lastEncodeWallTime = ContinuousClock.now - started.wall
        }
        store.begin(slot.frame, newItems: newItems)
        if store.generation != builtGeneration {
            builder.invalidate()
            builtGeneration = store.generation
        }
        let instances = builder.build(frame, overlay: overlay, glyphs: store)
        let glyphCount = instances.glyphs.count + instances.decorations.count
        guard let solidBuffer = write([instances.backgrounds], into: &buffers[slot.buffer].solids),
              let glyphBuffer = write([instances.glyphs, instances.decorations], into: &buffers[slot.buffer].glyphs)
        else { return false }
        let pass = MTLRenderPassDescriptor()
        guard let attachment = pass.colorAttachments[0] else { return false }
        attachment.texture = target
        attachment.loadAction = .clear
        attachment.storeAction = .store
        attachment.clearColor = MTLClearColor(
            red: Double(instances.clear.x), green: Double(instances.clear.y), blue: Double(instances.clear.z), alpha: 1
        )
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else { return false }
        var viewport = SIMD2<Float>(Float(target.width), Float(target.height))
        if !instances.backgrounds.isEmpty {
            encoder.setRenderPipelineState(solidPipeline)
            encoder.setVertexBuffer(solidBuffer, offset: 0, index: 0)
            encoder.setVertexBytes(&viewport, length: MemoryLayout<SIMD2<Float>>.size, index: 1)
            encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4, instanceCount: instances.backgrounds.count)
        }
        if glyphCount > 0 {
            encoder.setRenderPipelineState(glyphPipeline)
            encoder.setVertexBuffer(glyphBuffer, offset: 0, index: 0)
            encoder.setVertexBytes(&viewport, length: MemoryLayout<SIMD2<Float>>.size, index: 1)
            encoder.setFragmentTexture(store.alpha.texture, index: 0)
            encoder.setFragmentTexture(store.colour.texture, index: 1)
            encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4, instanceCount: glyphCount)
        }
        encoder.endEncoding()
        return true
    }

    /// Copies the parts one after another into the frame's buffer, made larger first when they do
    /// not fit. The frame that last read this buffer has completed, so replacing or writing it is
    /// safe.
    private func write<Instance>(_ parts: [[Instance]], into buffer: inout (any MTLBuffer)?) -> (any MTLBuffer)? {
        let length = max(4096, parts.reduce(0) { $0 + $1.count } * MemoryLayout<Instance>.stride)
        if buffer.map({ $0.length < length }) ?? true {
            buffer = device.makeBuffer(length: length.nextPowerOfTwo, options: .storageModeShared)
        }
        guard let buffer else { return nil }
        var offset = 0
        for part in parts {
            part.withUnsafeBytes { raw in
                guard let base = raw.baseAddress else { return }
                (buffer.contents() + offset).copyMemory(from: base, byteCount: raw.count)
                offset += raw.count
            }
        }
        return buffer
    }

    private static func faces(_ fonts: TerminalFonts, points: Double, scale: Double) throws(RenderError) -> TerminalFaces {
        do {
            return try fonts.faces(points: points, scale: scale)
        } catch {
            throw .unavailable("the terminal font at \(points) pt: \(error)")
        }
    }

    private static func pipeline(
        _ device: any MTLDevice, _ library: any MTLLibrary, vertex: String, fragment: String
    ) throws(RenderError) -> any MTLRenderPipelineState {
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = library.makeFunction(name: vertex)
        descriptor.fragmentFunction = library.makeFunction(name: fragment)
        guard let attachment = descriptor.colorAttachments[0] else { throw .unavailable("the \(vertex) pipeline has no colour attachment") }
        attachment.pixelFormat = .bgra8Unorm
        attachment.isBlendingEnabled = true
        attachment.sourceRGBBlendFactor = .one
        attachment.destinationRGBBlendFactor = .oneMinusSourceAlpha
        attachment.sourceAlphaBlendFactor = .one
        attachment.destinationAlphaBlendFactor = .oneMinusSourceAlpha
        do {
            return try device.makeRenderPipelineState(descriptor: descriptor)
        } catch {
            throw .unavailable("the \(vertex) pipeline could not be made: \(error)")
        }
    }
}

/// What became of a frame asked to be drawn.
public enum DrawOutcome: Equatable, Sendable {
    case drawn(UInt64)
    /// Every instance buffer is read by a frame in flight; nothing was written.
    case busy
    /// No drawable or command buffer came.
    case unavailable
}

public enum RenderError: Error, Equatable, CustomStringConvertible {
    case unavailable(String)

    public var description: String {
        switch self {
        case let .unavailable(what): what
        }
    }
}

extension Int {
    var nextPowerOfTwo: Int {
        self <= 1 ? 1 : 1 << (Int.bitWidth - (self - 1).leadingZeroBitCount)
    }
}

extension Duration {
    /// The CPU time the calling thread has run so far.
    public static var threadCPU: Duration {
        .nanoseconds(Int64(clamping: clock_gettime_nsec_np(CLOCK_THREAD_CPUTIME_ID)))
    }
}
