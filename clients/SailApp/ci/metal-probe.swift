import AppKit
import Metal
import QuartzCore

func line(_ s: String) { print(s); fflush(stdout) }

let shaders = """
#include <metal_stdlib>
using namespace metal;
struct Quad { float2 origin; float2 size; float4 color; };
struct Out { float4 position [[position]]; float2 uv; float4 color; };
vertex Out quadVertex(uint v [[vertex_id]], uint i [[instance_id]], constant Quad *quads [[buffer(0)]], constant float2 &viewport [[buffer(1)]]) {
    float2 corner = float2(v & 1, v >> 1);
    float2 p = quads[i].origin + corner * quads[i].size;
    Out o;
    o.position = float4(p / viewport * float2(2, -2) + float2(-1, 1), 0, 1);
    o.uv = corner;
    o.color = quads[i].color;
    return o;
}
fragment float4 tint(Out in [[stage_in]], texture2d<float> atlas [[texture(0)]]) {
    constexpr sampler s(filter::nearest);
    float a = atlas.sample(s, in.uv).r;
    return float4(in.color.rgb * a, in.color.a * a);
}
"""

func offscreen(_ device: MTLDevice) throws {
    let started = Date()
    let library = try device.makeLibrary(source: shaders, options: nil)
    let pipeline = MTLRenderPipelineDescriptor()
    pipeline.vertexFunction = library.makeFunction(name: "quadVertex")
    pipeline.fragmentFunction = library.makeFunction(name: "tint")
    pipeline.colorAttachments[0].pixelFormat = .bgra8Unorm
    pipeline.colorAttachments[0].isBlendingEnabled = true
    pipeline.colorAttachments[0].sourceRGBBlendFactor = .one
    pipeline.colorAttachments[0].destinationRGBBlendFactor = .oneMinusSourceAlpha
    pipeline.colorAttachments[0].sourceAlphaBlendFactor = .one
    pipeline.colorAttachments[0].destinationAlphaBlendFactor = .oneMinusSourceAlpha
    let state = try device.makeRenderPipelineState(descriptor: pipeline)

    let w = 64, h = 64
    let target = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: w, height: h, mipmapped: false)
    target.usage = [.renderTarget]
    target.storageMode = .private
    guard let texture = device.makeTexture(descriptor: target) else { line("offscreen: no render target"); return }

    let atlasDescriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .r8Unorm, width: 2, height: 2, mipmapped: false)
    atlasDescriptor.usage = [.shaderRead]
    guard let atlas = device.makeTexture(descriptor: atlasDescriptor) else { line("offscreen: no atlas"); return }
    let coverage: [UInt8] = [255, 255, 255, 255]
    atlas.replace(region: MTLRegionMake2D(0, 0, 2, 2), mipmapLevel: 0, withBytes: coverage, bytesPerRow: 2)

    var quads: [Float] = [0, 0, 32, 64, 1, 0, 0, 1, 32, 0, 32, 64, 0, 1, 0, 1]
    var viewport: [Float] = [Float(w), Float(h)]
    guard let queue = device.makeCommandQueue(), let buffer = queue.makeCommandBuffer(),
          let readback = device.makeBuffer(length: w * h * 4, options: .storageModeShared) else { line("offscreen: no queue"); return }
    let pass = MTLRenderPassDescriptor()
    pass.colorAttachments[0].texture = texture
    pass.colorAttachments[0].loadAction = .clear
    pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 1, alpha: 1)
    pass.colorAttachments[0].storeAction = .store
    guard let encoder = buffer.makeRenderCommandEncoder(descriptor: pass) else { line("offscreen: no encoder"); return }
    encoder.setRenderPipelineState(state)
    encoder.setVertexBytes(&quads, length: quads.count * 4, index: 0)
    encoder.setVertexBytes(&viewport, length: 8, index: 1)
    encoder.setFragmentTexture(atlas, index: 0)
    encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4, instanceCount: 2)
    encoder.endEncoding()
    guard let blit = buffer.makeBlitCommandEncoder() else { line("offscreen: no blit"); return }
    blit.copy(from: texture, sourceSlice: 0, sourceLevel: 0, sourceOrigin: MTLOrigin(x: 0, y: 0, z: 0),
              sourceSize: MTLSize(width: w, height: h, depth: 1), to: readback, destinationOffset: 0,
              destinationBytesPerRow: w * 4, destinationBytesPerImage: w * h * 4)
    blit.endEncoding()
    buffer.commit()
    buffer.waitUntilCompleted()
    let px = readback.contents().bindMemory(to: UInt8.self, capacity: w * h * 4)
    func at(_ x: Int, _ y: Int) -> String { let i = (y * w + x) * 4; return "bgra(\(px[i]),\(px[i+1]),\(px[i+2]),\(px[i+3]))" }
    line("offscreen: status=\(buffer.status.rawValue) error=\(String(describing: buffer.error)) left=\(at(10, 32)) right=\(at(50, 32)) (want left red bgra(0,0,255,255), right green bgra(0,255,0,255)) in \(Int(Date().timeIntervalSince(started) * 1000)) ms")
    line("offscreen: gpu time \(String(format: "%.3f", (buffer.gpuEndTime - buffer.gpuStartTime) * 1000)) ms")
}

final class InputView: NSView, NSTextInputClient {
    var log: [String] = []
    var marked = NSAttributedString()
    override var acceptsFirstResponder: Bool { true }
    override func keyDown(with event: NSEvent) {
        log.append("keyDown(\(event.keyCode))")
        let handled = inputContext?.handleEvent(event) ?? false
        log.append("handled=\(handled) context=\(inputContext != nil)")
    }
    func insertText(_ string: Any, replacementRange: NSRange) { log.append("insert(\(string))"); marked = NSAttributedString() }
    func doCommand(by selector: Selector) { log.append("command(\(selector))") }
    func setMarkedText(_ string: Any, selectedRange: NSRange, replacementRange: NSRange) {
        marked = (string as? NSAttributedString) ?? NSAttributedString(string: string as? String ?? "")
        log.append("marked(\(marked.string))")
    }
    func unmarkText() { log.append("unmark"); marked = NSAttributedString() }
    func selectedRange() -> NSRange { NSRange(location: NSNotFound, length: 0) }
    func markedRange() -> NSRange { marked.length > 0 ? NSRange(location: 0, length: marked.length) : NSRange(location: NSNotFound, length: 0) }
    func hasMarkedText() -> Bool { marked.length > 0 }
    func attributedSubstring(forProposedRange range: NSRange, actualRange: NSRangePointer?) -> NSAttributedString? { nil }
    func validAttributesForMarkedText() -> [NSAttributedString.Key] { [] }
    func firstRect(forCharacterRange range: NSRange, actualRange: NSRangePointer?) -> NSRect { .zero }
    func characterIndex(for point: NSPoint) -> Int { NSNotFound }
}

final class Probe: NSObject, NSApplicationDelegate {
    var window: NSWindow?
    var ticks = 0
    var started = CACurrentMediaTime()
    var presented = 0
    var layer = CAMetalLayer()
    var link: CADisplayLink?
    var input: InputView?

    func applicationDidFinishLaunching(_ notification: Notification) {
        for s in NSScreen.screens {
            line("screen: frame=\(s.frame) scale=\(s.backingScaleFactor) maxFPS=\(s.maximumFramesPerSecond) name=\(s.localizedName) colorSpace=\(String(describing: s.colorSpace?.localizedName))")
        }
        let window = NSWindow(contentRect: NSRect(x: 100, y: 100, width: 400, height: 300), styleMask: [.titled, .resizable], backing: .buffered, defer: false)
        let view = NSView(frame: window.contentLayoutRect)
        view.wantsLayer = true
        layer.device = MTLCreateSystemDefaultDevice()
        layer.pixelFormat = .bgra8Unorm
        view.layer = layer
        window.contentView = view
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        self.window = window
        let input = InputView(frame: NSRect(x: 0, y: 0, width: 10, height: 10))
        view.addSubview(input)
        line("makeFirstResponder: \(window.makeFirstResponder(input))")
        self.input = input
        let link = view.displayLink(target: self, selector: #selector(tick(_:)))
        link.add(to: .main, forMode: .common)
        self.link = link
        started = CACurrentMediaTime()
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { self.finish() }
    }

    @objc func tick(_ link: CADisplayLink) {
        ticks += 1
        guard let drawable = layer.nextDrawable(), let queue = layer.device?.makeCommandQueue(), let buffer = queue.makeCommandBuffer() else { return }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = drawable.texture
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].clearColor = MTLClearColor(red: 0.8, green: 0.4, blue: 0.3, alpha: 1)
        pass.colorAttachments[0].storeAction = .store
        buffer.makeRenderCommandEncoder(descriptor: pass)?.endEncoding()
        drawable.addPresentedHandler { _ in DispatchQueue.main.async { self.presented += 1 } }
        buffer.present(drawable)
        buffer.commit()
    }

    func finish() {
        let elapsed = CACurrentMediaTime() - started
        line("displayLink: \(ticks) ticks, \(presented) presented in \(String(format: "%.2f", elapsed)) s; window key=\(window?.isKeyWindow ?? false) app active=\(NSApp.isActive) scale=\(window?.backingScaleFactor ?? 0) drawableSize=\(layer.drawableSize) maxDrawables=\(layer.maximumDrawableCount)")
        if let view = window?.contentView, let bitmap = view.bitmapImageRepForCachingDisplay(in: view.bounds) {
            view.cacheDisplay(in: view.bounds, to: bitmap)
            line("cacheDisplay: \(bitmap.pixelsWide)x\(bitmap.pixelsHigh)")
        }
        let key = NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: [], timestamp: 0, windowNumber: window?.windowNumber ?? 0, context: nil, characters: "a", charactersIgnoringModifiers: "a", isARepeat: false, keyCode: 0)
        line("synthesized key event: \(key != nil)")
        func press(_ chars: String, _ ignoring: String, _ code: UInt16, _ flags: NSEvent.ModifierFlags) {
            for type in [NSEvent.EventType.keyDown, .keyUp] {
                if let e = NSEvent.keyEvent(with: type, location: .zero, modifierFlags: flags, timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window?.windowNumber ?? 0, context: nil, characters: chars, charactersIgnoringModifiers: ignoring, isARepeat: false, keyCode: code) { window?.sendEvent(e) }
            }
        }
        press("a", "a", 0, [])
        press("\u{3}", "c", 8, [.control])
        press("", "e", 14, [.option])
        press("e", "e", 14, [])
        press("\r", "\r", 36, [])
        line("input log: \(input?.log ?? [])")
        line("window key after events=\(window?.isKeyWindow ?? false) firstResponder=\(String(describing: window?.firstResponder))")
        line("input source: \(NSTextInputContext.current?.selectedKeyboardInputSource ?? "nil") / \(input?.inputContext?.selectedKeyboardInputSource ?? "nil")")
        NSApp.terminate(nil)
    }
}

let device = MTLCreateSystemDefaultDevice()
line("MTLCreateSystemDefaultDevice: \(device?.name ?? "nil")")
for d in MTLCopyAllDevices() {
    line("device: name=\(d.name) lowPower=\(d.isLowPower) headless=\(d.isHeadless) removable=\(d.isRemovable) unified=\(d.hasUnifiedMemory) apple7=\(d.supportsFamily(.apple7)) mac2=\(d.supportsFamily(.mac2)) metal3=\(d.supportsFamily(.metal3)) maxBuffer=\(d.maxBufferLength) maxThreadgroup=\(d.maxThreadsPerThreadgroup)")
}
if let device {
    do { try offscreen(device) } catch { line("offscreen: threw \(error)") }
}
let app = NSApplication.shared
let probe = Probe()
app.delegate = probe
app.setActivationPolicy(.regular)
app.run()
