/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** The character type a local session runs in, chosen from the host's own environment. */
class LocalPtyAccessTest {

  private static final Predicate<String> HAS_C_UTF8 = "C.UTF-8"::equals;
  private static final Predicate<String> HAS_NONE = name -> false;

  @Test
  void aHostWhoseCharacterTypeIsUtf8GivesItsSessionsNothingMore() {
    assertEquals(Map.of(), LocalPtyAccess.locale(Map.of("LANG", "de_DE.UTF-8"), HAS_NONE));
    assertEquals(Map.of(), LocalPtyAccess.locale(Map.of("LANG", "en_GB.utf8"), HAS_NONE));
    assertEquals(
        Map.of(),
        LocalPtyAccess.locale(Map.of("LC_ALL", "fr_FR.UTF-8", "LANG", "C"), HAS_NONE),
        "LC_ALL decides before LANG");
    assertEquals(
        Map.of(),
        LocalPtyAccess.locale(Map.of("LC_CTYPE", "UTF-8", "LANG", "POSIX"), HAS_NONE),
        "LC_CTYPE decides before LANG");
  }

  @Test
  void aHostWithNoUtf8CharacterTypeGivesCUtf8WhereTheSystemHasItElseEnUs() {
    assertEquals(Map.of("LANG", "C.UTF-8"), LocalPtyAccess.locale(Map.of(), HAS_C_UTF8));
    assertEquals(Map.of("LANG", "en_US.UTF-8"), LocalPtyAccess.locale(Map.of(), HAS_NONE));
    assertEquals(Map.of("LANG", "C.UTF-8"), LocalPtyAccess.locale(Map.of("LANG", "C"), HAS_C_UTF8));
  }

  @Test
  void aVariableThatWouldMaskTheChosenLocaleIsGivenItToo() {
    assertEquals(
        Map.of("LC_ALL", "C.UTF-8", "LANG", "C.UTF-8"),
        LocalPtyAccess.locale(Map.of("LC_ALL", "POSIX"), HAS_C_UTF8));
    assertEquals(
        Map.of("LC_CTYPE", "en_US.UTF-8", "LANG", "en_US.UTF-8"),
        LocalPtyAccess.locale(Map.of("LC_CTYPE", "C", "LANG", "en_GB.UTF-8"), HAS_NONE));
    assertEquals(
        Map.of("LC_ALL", "C.UTF-8", "LC_CTYPE", "C.UTF-8", "LANG", "C.UTF-8"),
        LocalPtyAccess.locale(
            Map.of("LC_ALL", "C", "LC_CTYPE", "en_US.UTF-8", "LANG", " "), HAS_C_UTF8));
  }
}
