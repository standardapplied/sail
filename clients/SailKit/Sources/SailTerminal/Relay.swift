import Synchronization

/// A function one thread sets and others call, as often as output comes. It is held in a class:
/// a function held in a `Mutex` itself is wrapped in another pair of reabstraction thunks each time
/// it is read out, so the thousandth call runs through two thousand of them and a flood overflows
/// the caller's stack.
public final class Relay<Argument: Sendable>: Sendable {
    private final class Held: Sendable {
        let call: @Sendable (Argument) -> Void

        init(_ call: @escaping @Sendable (Argument) -> Void) {
            self.call = call
        }
    }

    private let held = Mutex<Held?>(nil)

    public init() {}

    /// Calls `call` from now on, in place of any function before; nil calls nothing.
    public func set(_ call: (@Sendable (Argument) -> Void)?) {
        let next = call.map(Held.init)
        held.withLock { $0 = next }
    }

    /// Whether a function is set.
    public var isSet: Bool {
        held.withLock { $0 != nil }
    }

    /// Calls the function set, if any, on this thread, outside the lock.
    public func callAsFunction(_ argument: Argument) {
        held.withLock { $0 }?.call(argument)
    }
}
