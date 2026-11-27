/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ChatItem.Asked;
import ai.singlr.sail.engine.ChatItem.Change;
import ai.singlr.sail.engine.ChatItem.ChangeKind;
import ai.singlr.sail.engine.ChatItem.Choice;
import ai.singlr.sail.engine.ChatItem.Detail;
import ai.singlr.sail.engine.ChatItem.ToolKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every piece of a chat that holds what the agent wrote keeps it to its bound and says when it was
 * cut, the same way each time: its text's beginning, and its one {@code clipped} flag.
 */
class ChatItemTest {

  private static final String NAME = "n".repeat(ChatItem.NAME_CODE_POINTS);

  private static final String SUMMARY = "s".repeat(ChatItem.SUMMARY_CODE_POINTS);

  private static final String CONTENT = "c".repeat(ChatItem.CONTENT_CODE_POINTS);

  private static String over(String whole) {
    return whole + whole.charAt(0);
  }

  @Test
  void aDetailIsClippedToItsBoundAndSaysSo() {
    assertEquals(new Detail.Command(CONTENT, true), new Detail.Command(over(CONTENT), false));
    assertEquals(new Detail.Read(CONTENT, true), new Detail.Read(over(CONTENT), false));
    assertEquals(
        new Detail.Search(CONTENT, "w", true), new Detail.Search(over(CONTENT), "w", false));
    assertEquals(
        new Detail.Search("q", CONTENT, true), new Detail.Search("q", over(CONTENT), false));
    assertEquals(new Detail.Fetch(CONTENT, true), new Detail.Fetch(over(CONTENT), false));
    assertEquals(new Detail.Other(NAME, "{}", true), new Detail.Other(over(NAME), "{}", false));
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

  @Test
  void aRequestsWordsAreClippedAndSaySo() {
    assertEquals(permission(SUMMARY, "why", true), permission(over(SUMMARY), "why", false));
    assertEquals(permission("words", SUMMARY, true), permission("words", over(SUMMARY), false));
    assertEquals(
        new Asked(CONTENT, "h", List.of(), false, true),
        new Asked(over(CONTENT), "h", List.of(), false, false));
    assertEquals(
        new Asked("q", SUMMARY, List.of(), false, true),
        new Asked("q", over(SUMMARY), List.of(), false, false));
    assertEquals(new Choice(SUMMARY, "a", true), new Choice(over(SUMMARY), "a", false));
    assertEquals(new Choice("l", CONTENT, true), new Choice("l", over(CONTENT), false));
  }

  private static ChatItem.Permission permission(String words, String reason, boolean cut) {
    return new ChatItem.Permission("r", "t", ToolKind.COMMAND, words, reason, null, List.of(), cut);
  }

  @Test
  void textAtItsBoundIsWholeAndUnmarked() {
    assertEquals(CONTENT, new Detail.Command(CONTENT, false).line());
    assertFalse(new Detail.Command(CONTENT, false).clipped());
    assertFalse(permission(SUMMARY, null, false).clipped());
    assertFalse(new ChatItem.ModeChanged(NAME, false).clipped());
    assertNull(new Detail.Command(null, false).line(), "what was not said");
    assertTrue(new Detail.Command("ls", true).clipped(), "a piece once cut stays so");
  }

  @Test
  void aClipNeverSplitsACharacter() {
    var faces = "😀".repeat(ChatItem.NAME_CODE_POINTS + 1);

    var mode = new ChatItem.ModeChanged(faces, false);

    assertEquals(new ChatItem.ModeChanged("😀".repeat(ChatItem.NAME_CODE_POINTS), true), mode);
  }
}
