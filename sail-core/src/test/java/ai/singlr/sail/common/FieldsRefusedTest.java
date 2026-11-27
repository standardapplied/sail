/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldsRefusedTest {

  @Test
  void aReadIsReturnedAndItsRefusalIsSaidUnderTheFieldWithOrWithoutWords() {
    var worded =
        assertThrows(
            FieldsRefused.class,
            () ->
                FieldsRefused.naming(
                    "name",
                    () -> {
                      throw new IllegalArgumentException("Too long.");
                    }));
    var wordless =
        assertThrows(
            FieldsRefused.class,
            () ->
                FieldsRefused.naming(
                    "name",
                    () -> {
                      throw new IllegalArgumentException();
                    }));

    assertEquals("acme", FieldsRefused.naming("name", () -> "acme"));
    assertEquals(Map.of("name", "Too long."), worded.fields());
    assertEquals("Too long.", worded.getMessage());
    assertEquals(Map.of("name", "name is refused."), wordless.fields());
    assertEquals("name is refused.", wordless.getMessage());
  }
}
