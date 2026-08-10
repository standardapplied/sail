/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PromptConversationTest {

  @Test
  void keepsNewestContentWithinTheAggregateBudgetInChronologicalOrder() {
    var messages =
        List.of("old:" + "a".repeat(20_000) + ":old-end", "middle:" + "b".repeat(20_000), "new");

    var rendered = PromptConversation.renderNewest(messages, message -> message + "\n").text();

    assertEquals(PromptConversation.MAX_CODE_POINTS, rendered.codePointCount(0, rendered.length()));
    assertFalse(rendered.contains(":old-end"));
    assertTrue(rendered.contains("middle:"));
    assertTrue(rendered.endsWith("new\n"));
  }

  @Test
  void reportsExactlyTheEntriesRenderedInFull() {
    var omitted = "omitted:" + "a".repeat(40_000);
    var truncated = "truncated:" + "b".repeat(40_000);
    var messages = List.of(omitted, truncated, "whole");

    var rendered = PromptConversation.renderNewest(messages, message -> message + "\n");

    assertTrue(rendered.text().contains("truncated:"), "the boundary entry contributes a prefix");
    assertEquals(
        List.of("whole"),
        rendered.fullyRendered(),
        "delivery derives from presentation: the truncated boundary entry and the omitted tail"
            + " were not presented in full and stay owed a full delivery");
  }

  @Test
  void underBudgetEveryEntryIsFullyRendered() {
    var rendered = PromptConversation.renderNewest(List.of("a", "b"), value -> value);

    assertEquals("ab", rendered.text());
    assertEquals(List.of("a", "b"), rendered.fullyRendered());
  }

  @Test
  void truncatesWithoutSplittingAUnicodeCodePoint() {
    var message = "😀".repeat(PromptConversation.MAX_CODE_POINTS + 1);

    var rendered = PromptConversation.renderNewest(List.of(message), value -> value).text();

    assertEquals(PromptConversation.MAX_CODE_POINTS, rendered.codePointCount(0, rendered.length()));
    assertEquals(PromptConversation.MAX_CODE_POINTS * 2, rendered.length());
    assertTrue(Character.isLowSurrogate(rendered.charAt(rendered.length() - 1)));
  }
}
