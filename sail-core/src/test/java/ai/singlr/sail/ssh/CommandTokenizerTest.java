/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandTokenizerTest {

  @Test
  void splitsSimpleCommand() {
    assertEquals(List.of("sail", "spec", "list"), CommandTokenizer.split("sail spec list"));
  }

  @Test
  void collapsesRepeatedWhitespace() {
    assertEquals(List.of("sail", "spec"), CommandTokenizer.split("  sail   spec  "));
  }

  @Test
  void emptyAndBlankProduceNoTokens() {
    assertTrue(CommandTokenizer.split("").isEmpty());
    assertTrue(CommandTokenizer.split("   ").isEmpty());
  }

  @Test
  void honorsDoubleQuotes() {
    assertEquals(
        List.of("sail", "spec", "create", "my spec title"),
        CommandTokenizer.split("sail spec create \"my spec title\""));
  }

  @Test
  void honorsSingleQuotesLiterally() {
    assertEquals(
        List.of("sail", "spec", "set", "a \"b\" c"),
        CommandTokenizer.split("sail spec set 'a \"b\" c'"));
  }

  @Test
  void honorsBackslashEscapeOutsideQuotes() {
    assertEquals(List.of("a b", "c"), CommandTokenizer.split("a\\ b c"));
  }

  @Test
  void honorsEscapesInsideDoubleQuotes() {
    assertEquals(List.of("a\"b\\c"), CommandTokenizer.split("\"a\\\"b\\\\c\""));
  }

  @Test
  void literalBackslashInsideDoubleQuotesWhenNotAnEscape() {
    assertEquals(List.of("a\\b"), CommandTokenizer.split("\"a\\b\""));
  }

  @Test
  void rejectsUnterminatedQuotes() {
    assertThrows(IllegalArgumentException.class, () -> CommandTokenizer.split("sail \"oops"));
    assertThrows(IllegalArgumentException.class, () -> CommandTokenizer.split("sail 'oops"));
  }
}
