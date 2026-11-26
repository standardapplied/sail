/// How a test waits on the clients it drives and the hosts they talk to.
public enum Patience {
    /// How long, in seconds, a client under test waits for an answer whose timing the test is not
    /// about: no shorter than the time limit of any suite that uses it, so a test whose process
    /// stalls fails by its own limit, never because its client gave up on a host the test had not
    /// yet been scheduled to play. A limit a test is about keeps its own value.
    public static let answer = 120
}
