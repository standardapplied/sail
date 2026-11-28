## Debouncing a Function in Swift

A debounced function delays its execution until a set amount of time has passed without it being called again, so rapid repeated calls (such as keystrokes in a search field) collapse into a single invocation. In Swift, you can implement this with a `DispatchWorkItem` that is cancelled and rescheduled on every call, or with a `Task` that is cancelled and recreated.

```swift
final class Debouncer {
    private let delay: TimeInterval
    private let queue: DispatchQueue
    private var workItem: DispatchWorkItem?

    init(delay: TimeInterval, queue: DispatchQueue = .main) {
        self.delay = delay
        self.queue = queue
    }

    func debounce(_ action: @escaping () -> Void) {
        workItem?.cancel()
        let item = DispatchWorkItem(block: action)
        workItem = item
        queue.asyncAfter(deadline: .now() + delay, execute: item)
    }
}

let debouncer = Debouncer(delay: 0.3)
debouncer.debounce { print("Searching for: query") }
```

- Each call cancels the pending work item, so only the last call within the window runs.
- The action runs on the queue you supply (main by default), so UI work is safe, but captured references may need `[weak self]` to avoid retain cycles.
  - If the owner of the debouncer is deallocated while work is pending, the closure still runs unless you cancel it in `deinit`.

1. Create a `Debouncer` with the desired delay in seconds.
2. Store it as a property so it lives as long as the caller needs it.
3. Call `debounce { ... }` from each event handler; only the final call within the delay window executes.

> Choose the delay carefully: too short and it barely reduces calls, too long and the UI feels unresponsive.
