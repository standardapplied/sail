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

    var rendered = PromptConversation.renderNewest(messages, message -> message + "\n");

    assertEquals(PromptConversation.MAX_CODE_POINTS, rendered.codePointCount(0, rendered.length()));
    assertFalse(rendered.contains(":old-end"));
    assertTrue(rendered.contains("middle:"));
    assertTrue(rendered.endsWith("new\n"));
  }

  @Test
  void truncatesWithoutSplittingAUnicodeCodePoint() {
    var message = "😀".repeat(PromptConversation.MAX_CODE_POINTS + 1);

    var rendered = PromptConversation.renderNewest(List.of(message), value -> value);

    assertEquals(PromptConversation.MAX_CODE_POINTS, rendered.codePointCount(0, rendered.length()));
    assertEquals(PromptConversation.MAX_CODE_POINTS * 2, rendered.length());
    assertTrue(Character.isLowSurrogate(rendered.charAt(rendered.length() - 1)));
  }
}
