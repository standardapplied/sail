/// The rule of every line of words with its actions (the files panel's line, the file pane's): the
/// actions at the trailing end of the words' first row while both fit side by side; where they do
/// not, the words keep their row whole and the actions take a row of their own under them.
enum OneLine {
    /// Whether actions `actions` wide go under words `words` wide in `room`.
    static func actionsUnder(words: Double, actions: Double, room: Double) -> Bool {
        actions > 0 && words + actions > room
    }
}
