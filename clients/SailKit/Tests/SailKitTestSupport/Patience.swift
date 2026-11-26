/// How a test waits on the clients it drives and the fakes they talk to.
public enum Patience {
    /// How long, in seconds, a client under test waits for a fake's answer: past every socket
    /// suite's time limit, so a test whose process stalls fails by its own limit, never because its
    /// client gave up on a fake the test had not yet been scheduled to play. A limit a test is about
    /// keeps its own value.
    public static let answer = 120
}
