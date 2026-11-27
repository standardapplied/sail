/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ChatItem.Asked;
import ai.singlr.sail.engine.ChatItem.Change;
import ai.singlr.sail.engine.ChatItem.ChangeKind;
import ai.singlr.sail.engine.ChatItem.Choice;
import ai.singlr.sail.engine.ChatItem.Detail;
import ai.singlr.sail.engine.ChatItem.ToolKind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every piece of a chat that holds what the agent wrote keeps it to its bound and says when it was
 * cut, the same way each time: its text's beginning, and its one {@code clipped} flag.
 */
class ChatItemTest {

  private static final String NAME = "n".repeat(LiveStream.NAME_CODE_POINTS);

  private static final String SUMMARY = "s".repeat(LiveStream.SUMMARY_CODE_POINTS);

  private static final String CONTENT = "c".repeat(LiveStream.CONTENT_CODE_POINTS);

  private static String over(String whole) {
    return whole + whole.charAt(0);
  }

  @Test
  void aDetailIsClippedToItsBoundAndSaysSo() {
    assertEquals(
        new Detail.Command(CONTENT, false, true), new Detail.Command(over(CONTENT), false, false));
    assertEquals(new Detail.Read(CONTENT, true), new Detail.Read(over(CONTENT), false));
    assertEquals(
        new Detail.Search(CONTENT, "w", true), new Detail.Search(over(CONTENT), "w", false));
    assertEquals(
        new Detail.Search("q", CONTENT, true), new Detail.Search("q", over(CONTENT), false));
    assertEquals(new Detail.Fetch(CONTENT, true), new Detail.Fetch(over(CONTENT), false));
    assertEquals(
        new Detail.Other(CONTENT, "{}", true), new Detail.Other(over(CONTENT), "{}", false));
    assertEquals(new Detail.Other("t", CONTENT, true), new Detail.Other("t", over(CONTENT), false));
  }

  @Test
  void eachPartOfAChangeIsClippedAndTheChangeSaysSo() {
    assertEquals(
        change(CONTENT, null, "b", "a", true), change(over(CONTENT), null, "b", "a", false));
    assertEquals(change("p", CONTENT, "b", "a", true), change("p", over(CONTENT), "b", "a", false));
    assertEquals(
        change("p", null, CONTENT, "a", true), change("p", null, over(CONTENT), "a", false));
    assertEquals(
        change("p", null, "b", CONTENT, true), change("p", null, "b", over(CONTENT), false));
  }

  private static Change change(String path, String from, String before, String after, boolean cut) {
    return new Change(ChangeKind.MOVE, path, from, before, after, false, cut);
  }

  @Test
  void anItemIsClippedToItsBoundAndSaysSo() {
    assertEquals(
        new ChatItem.Session(NAME, "m", List.of(), List.of(), true),
        new ChatItem.Session(over(NAME), "m", List.of(), List.of(), false));
    assertEquals(
        new ChatItem.Session("m", NAME, List.of(), List.of(), true),
        new ChatItem.Session("m", over(NAME), List.of(), List.of(), false));
    assertEquals(
        new ChatItem.Session("m", "d", List.of("a", NAME), List.of(), true),
        new ChatItem.Session("m", "d", List.of("a", over(NAME)), List.of(), false));
    assertEquals(
        new ChatItem.Session("m", "d", List.of(), List.of(NAME), true),
        new ChatItem.Session("m", "d", List.of(), List.of(over(NAME)), false));
    assertEquals(new ChatItem.ModeChanged(NAME, true), new ChatItem.ModeChanged(over(NAME), false));
    assertEquals(
        new ChatItem.Text(null, CONTENT, true, true),
        new ChatItem.Text(null, over(CONTENT), true, false));
    assertEquals(
        new ChatItem.Tool(null, "t", ToolKind.OTHER, SUMMARY, null, true),
        new ChatItem.Tool(null, "t", ToolKind.OTHER, over(SUMMARY), null, false));
    assertEquals(
        new ChatItem.ToolDone(null, "t", CONTENT, false, true),
        new ChatItem.ToolDone(null, "t", over(CONTENT), false, false));
    assertEquals(
        new ChatItem.Plan("r", "t", CONTENT, true),
        new ChatItem.Plan("r", "t", over(CONTENT), false));
    assertEquals(
        new ChatItem.Retrying(1, 2, 3, SUMMARY, true),
        new ChatItem.Retrying(1, 2, 3, over(SUMMARY), false));
    assertEquals(
        new ChatItem.Compacted(NAME, 1, 2, true), new ChatItem.Compacted(over(NAME), 1, 2, false));
    assertEquals(
        new ChatItem.Refused("r", SUMMARY, true), new ChatItem.Refused("r", over(SUMMARY), false));
    assertEquals(
        new ChatItem.Failed(CONTENT, null, true), new ChatItem.Failed(over(CONTENT), null, false));
  }

  /**
   * Every part of a request is bounded by the content bound, the card's own, since a person decides
   * on all of it: the reader refuses a request any part of which is longer, so none is clipped.
   */
  @Test
  void aRequestsWordsAreBoundedAsWhatIsDecidedOn() {
    assertEquals(permission(CONTENT, "why", true), permission(over(CONTENT), "why", false));
    assertEquals(permission("words", CONTENT, true), permission("words", over(CONTENT), false));
    assertEquals(
        new Asked(CONTENT, "h", List.of(), false, true),
        new Asked(over(CONTENT), "h", List.of(), false, false));
    assertEquals(
        new Asked("q", CONTENT, List.of(), false, true),
        new Asked("q", over(CONTENT), List.of(), false, false));
    assertEquals(new Choice(CONTENT, "a", true), new Choice(over(CONTENT), "a", false));
    assertEquals(new Choice("l", CONTENT, true), new Choice("l", over(CONTENT), false));
  }

  private static ChatItem.Permission permission(String words, String reason, boolean cut) {
    return new ChatItem.Permission("r", "t", ToolKind.COMMAND, words, reason, null, List.of(), cut);
  }

  @Test
  void textAtItsBoundIsWholeAndUnmarked() {
    assertEquals(CONTENT, new Detail.Command(CONTENT, false, false).line());
    assertFalse(new Detail.Command(CONTENT, false, false).clipped());
    assertFalse(permission(CONTENT, null, false).clipped());
    assertFalse(new ChatItem.ModeChanged(NAME, false).clipped());
    assertNull(new Detail.Command(null, false, false).line(), "what was not said");
    assertTrue(new Detail.Command("ls", false, true).clipped(), "a piece once cut stays so");
  }

  @Test
  void anEditIsClippedWhenAnyOfItsChangesIs() {
    assertFalse(new Detail.Edit(List.of(change("p", null, "b", "a", false))).clipped());
    assertTrue(
        new Detail.Edit(
                List.of(change("p", null, "b", "a", false), change("p", null, "b", "a", true)))
            .clipped());
  }

  @Test
  void whatAnItemHoldsCannotChangeAfterItIsMade() {
    var words = new ArrayList<>(List.of("Allow it."));
    var changes = new ArrayList<>(List.of(change("p", null, "b", "a", false)));
    var choices = new ArrayList<>(List.of(new Choice("l", null, false)));
    var asked = new ArrayList<>(List.of(new Asked("q", null, choices, false, false)));
    var always = new ArrayList<>(List.of(new ChatItem.Always(words)));
    var commands = new ArrayList<>(List.of("/review"));

    var kept =
        List.of(
            new ChatItem.Always(words).words(),
            new Detail.Edit(changes).changes(),
            new Asked("q", null, choices, false, false).choices(),
            new ChatItem.Question("r", "t", asked).questions(),
            new ChatItem.Permission("r", "t", ToolKind.COMMAND, "w", null, null, always, false)
                .always(),
            new ChatItem.Session("m", "d", commands, commands, false).commands());
    words.clear();
    changes.clear();
    choices.clear();
    asked.clear();
    always.clear();
    commands.clear();

    for (var list : kept) {
      assertEquals(1, list.size());
      assertThrows(UnsupportedOperationException.class, list::clear);
    }
  }

  @Test
  void aClipNeverSplitsACharacter() {
    var faces = "😀".repeat(LiveStream.NAME_CODE_POINTS + 1);

    var mode = new ChatItem.ModeChanged(faces, false);

    assertEquals(new ChatItem.ModeChanged("😀".repeat(LiveStream.NAME_CODE_POINTS), true), mode);
  }
}
