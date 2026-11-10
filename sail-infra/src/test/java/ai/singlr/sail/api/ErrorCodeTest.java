/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/** A code reads the same to every client, whatever the locale the box runs in. */
class ErrorCodeTest {

  @Test
  void aCodeIsItsNameInLowerCaseUnderATurkishDefaultLocaleToo() {
    var before = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));

      assertEquals("forbidden_not_assignee", ErrorCode.FORBIDDEN_NOT_ASSIGNEE.code());
    } finally {
      Locale.setDefault(before);
    }
  }
}
