/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.PromptConversation.Rendered;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins every prompt a live member is given, whole, as text. The expected strings here never change;
 * only the expressions that produce them do.
 */
class LiveMemberPromptGoldenTest {

  private static final String ROOM =
      """
      ## How this room works

      - What you say is your reply: sail posts the text of each of your turns to the room
        as your message. Do not run `spec comment` to answer — your reply would be posted
        twice.
      - `spec comment` is for one thing only, a question only a human can settle:
        `spec comment %s --body <text> --question`. The flag pages the engineer on the
        board until a human replies.
      - Each prompt after this one is what was posted since your last turn. Between turns
        you stay in the room, and nothing is needed from you to remain here.
      - Never change a spec's status.

      """;

  private static final String FULL_ACCESS =
      """
      ## Your lane: full access

      Your tools are unrestricted. Answer, critique, draft, or work in the workspace when
      asked — diagrams, files, experiments: this session holds the repo reservation.
      Nothing forces a commit: leave work in the worktree and say where it is, or commit
      and push when the change is worth keeping and the conversation asks for it.

      """;

  private static final String READ_ONLY =
      """
      ## Your lane: read only

      This membership is read only, and the harness enforces it: file edits and spec
      state changes are denied, and the only shell commands that run are the `spec` CLI
      and `cd` — use the Read and Grep tools for files (git commands are denied too). Do
      not fight a denial; work within the lane. If the conversation asks for code changes
      or shapes real work, describe the change or the spec it deserves — a human can
      dispatch, or re-engage you with full access.

      """;

  private static final String CONVERSATION =
      """
      ## Conversation in this room

      uday (2026-08-11T12:00:00Z):
      what is it stuck on?

      claude/room-7 (2026-08-11T12:00:00Z):
      The token refresh.""";

  private static SpecStore.SpecRow spec() {
    return new SpecStore.SpecRow(
        "auth",
        "acme",
        "OAuth flow",
        SpecStatus.REVIEW,
        "uday",
        null,
        null,
        null,
        null,
        0,
        "uday",
        "",
        "",
        null,
        List.of(),
        List.of());
  }

  private static MessageStore.MessageRow message(String id, String author, String body) {
    return new MessageStore.MessageRow(
        id,
        "auth",
        author,
        body,
        null,
        "2026-08-11T12:00:00Z",
        "1-a",
        null,
        MessageStore.Mark.PLAIN);
  }

  private static List<MessageStore.MessageRow> conversation() {
    return List.of(
        message("m1", "uday", "what is it stuck on?"),
        message("m2", "claude/room-7", "The token refresh."));
  }

  @Test
  void aFullMemberOfASpecsRoom() {
    assertEquals(
        """
        You are the engaged agent of spec "OAuth flow" (id: auth, status: review): a member of \
        its room. Humans post here and you answer, and the conversation continues across turns \
        in this one session.

        """
            + ROOM.formatted("auth")
            + FULL_ACCESS
            + """
            Draft this spec's body with `spec content auth --body-file <file>` when the
            conversation is shaping it, or create sibling specs with
            `spec create --id <id> --title "<title>" --body-file <file>`. New specs are born \
            draft; a human promotes them on the board.

            ## Spec body

            Build the OAuth flow.

            """
            + CONVERSATION,
        only(LiveMemberPrompt.fresh(spec(), "Build the OAuth flow.", conversation(), true)));
  }

  @Test
  void aReadOnlyMemberOfASpecsRoom() {
    assertEquals(
        """
        You are the engaged agent of spec "OAuth flow" (id: auth, status: review): a member of \
        its room. Humans post here and you answer, and the conversation continues across turns \
        in this one session.

        """
            + ROOM.formatted("auth")
            + READ_ONLY
            + """
            ## Spec body

            Build the OAuth flow.

            """
            + CONVERSATION,
        only(LiveMemberPrompt.fresh(spec(), "Build the OAuth flow.", conversation(), false)));
  }

  @Test
  void aFullMemberOfARoomWithNoSpec() {
    assertEquals(
        """
        You are a member of the room "Lounge" (id: lounge). Humans and agents talk here; no \
        spec is attached — this is a conversation, not a work-item. Humans post and you answer, \
        and the conversation continues across turns in this one session.

        """
            + ROOM.formatted("lounge")
            + FULL_ACCESS
            + """
            When the conversation shapes real work, capture it as a spec with
            `spec create --id <id> --title "<title>" --body-file <file>`. New specs are born \
            draft; a human promotes them on the board.

            """
            + CONVERSATION,
        only(LiveMemberPrompt.freshForRoom("lounge", "Lounge", conversation(), true)));
  }

  @Test
  void aReadOnlyMemberOfARoomWithNoSpecAndNothingSaidYet() {
    assertEquals(
        """
        You are a member of the room "Lounge" (id: lounge). Humans and agents talk here; no \
        spec is attached — this is a conversation, not a work-item. Humans post and you answer, \
        and the conversation continues across turns in this one session.

        """
            + ROOM.formatted("lounge")
            + READ_ONLY,
        only(LiveMemberPrompt.freshForRoom("lounge", "Lounge", List.of(), false)));
  }

  @Test
  void aLaterPromptIsTheNewMessagesAlone() {
    assertEquals(
        """
        uday (2026-08-11T12:00:00Z):
        what is it stuck on?

        claude/room-7 (2026-08-11T12:00:00Z):
        The token refresh.""",
        only(LiveMemberPrompt.messages(conversation())));
    assertEquals(List.of(), LiveMemberPrompt.messages(List.of()));
  }

  @Test
  void aPromptNamesTheMessagesItHandsOverWhole() {
    assertEquals(
        List.of(conversation()),
        LiveMemberPrompt.messages(conversation()).stream().map(Rendered::fullyRendered).toList());
    assertEquals(
        List.of(conversation()),
        LiveMemberPrompt.fresh(spec(), "Build the OAuth flow.", conversation(), true).stream()
            .map(Rendered::fullyRendered)
            .toList());
    assertEquals(
        List.of(List.of()),
        LiveMemberPrompt.freshForRoom("lounge", "Lounge", List.of(), false).stream()
            .map(Rendered::fullyRendered)
            .toList());
  }

  @Test
  void messagesFillPromptsOldestFirstAndNoneIsCutOrLeftOut() {
    var first = message("m1", "uday", "a".repeat(15_000));
    var second = message("m2", "uday", "b".repeat(15_000));
    var third = message("m3", "uday", "c".repeat(15_000));

    var pages = LiveMemberPrompt.messages(List.of(first, second, third));

    assertEquals(
        List.of(
            new Rendered<>(
                BY + "a".repeat(15_000) + "\n\n" + BY + "b".repeat(15_000), List.of(first, second)),
            new Rendered<>(BY + "c".repeat(15_000), List.of(third))),
        pages);
  }

  @Test
  void aMessageLongerThanAPromptContinuesInTheNextAndIsWholeOnlyWhereItEnds() {
    var brief = message("m1", "uday", "before the long one");
    var huge = message("m2", "uday", "a".repeat(70_000) + " and the last instruction");
    var after = message("m3", "uday", "after");
    var written = BY + "a".repeat(70_000) + " and the last instruction";

    var pages = LiveMemberPrompt.messages(List.of(brief, huge, after));

    assertEquals(
        List.of(
            new Rendered<>(BY + "before the long one", List.of(brief)),
            new Rendered<>(written.substring(0, SLICE) + CONTINUES, List.of()),
            new Rendered<>(CONTINUED + written.substring(SLICE, 2 * SLICE) + CONTINUES, List.of()),
            new Rendered<>(
                CONTINUED + written.substring(2 * SLICE) + "\n\n" + BY + "after",
                List.of(huge, after))),
        pages);
    assertEquals(
        List.of(48, 31_961, 31_998, 6_267),
        pages.stream().map(page -> page.text().codePointCount(0, page.text().length())).toList(),
        "a slice fills its prompt to the budget of 32,000 before its trailing blank line goes");
  }

  @Test
  void aSliceSharesAPromptWithWhatCameBeforeWhenItFits() {
    var brief = message("m1", "uday", "before");
    var huge = message("m2", "uday", "a".repeat(40_000));
    var written = BY + "a".repeat(40_000);

    assertEquals(
        List.of(
            new Rendered<>(
                BY + "before\n\n" + written.substring(0, SLICE) + CONTINUES, List.of(brief)),
            new Rendered<>(CONTINUED + written.substring(SLICE), List.of(huge))),
        LiveMemberPrompt.messages(List.of(brief, huge)));
  }

  @Test
  void aSliceNeverSplitsACodePoint() {
    var emoji = message("m1", "uday", "😀".repeat(40_000));

    assertEquals(
        List.of(
            new Rendered<>(BY + "😀".repeat(SLICE - 29) + CONTINUES, List.of()),
            new Rendered<>(CONTINUED + "😀".repeat(40_000 - (SLICE - 29)), List.of(emoji))),
        LiveMemberPrompt.messages(List.of(emoji)));
  }

  @Test
  void aFreshSessionsRoomFollowsItsOpeningPageByPage() {
    var first = message("m1", "uday", "a".repeat(20_000));
    var second = message("m2", "claude/room-7", "b".repeat(20_000));

    var pages = LiveMemberPrompt.freshForRoom("lounge", "Lounge", List.of(first, second), false);

    assertEquals(
        List.of(
            new Rendered<>(
                only(LiveMemberPrompt.freshForRoom("lounge", "Lounge", List.of(), false))
                    + "## Conversation in this room\n\n"
                    + BY
                    + "a".repeat(20_000),
                List.of(first)),
            new Rendered<>(
                "claude/room-7 (2026-08-11T12:00:00Z):\n" + "b".repeat(20_000), List.of(second))),
        pages);
  }

  private static final String BY = "uday (2026-08-11T12:00:00Z):\n";
  private static final String CONTINUES = "\n[continues in the next prompt]";
  private static final String CONTINUED = "[continued from the previous prompt]\n";
  private static final int SLICE = 31_930;

  /** The text of a prompt that is the only one. */
  private static String only(List<Rendered<MessageStore.MessageRow>> pages) {
    assertEquals(1, pages.size());
    return pages.getFirst().text();
  }
}
