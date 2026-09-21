/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FileLimitsTest {
  @Test
  void declaredOversizeIsRejectedWithoutReading() {
    var unreadable =
        new InputStream() {
          @Override
          public int read() {
            throw new AssertionError("content was read");
          }
        };
    assertThrows(IllegalArgumentException.class, () -> new FileLimits(4).bounded(unreadable, 5));
    assertThrows(IllegalArgumentException.class, () -> new FileLimits(4).bounded(unreadable, -1));
  }

  @Test
  void aBodyThatOutrunsItsDeclarationIsCutOffAtTheCap() throws Exception {
    try (var input = new FileLimits(4).bounded(new ByteArrayInputStream(new byte[20]), 2)) {
      assertEquals(4, input.read(new byte[40]));
      assertThrows(java.io.IOException.class, input::read);
    }
    try (var input = new FileLimits(4).bounded(new ByteArrayInputStream(new byte[4]), 4)) {
      assertEquals(4, input.transferTo(java.io.OutputStream.nullOutputStream()));
    }
  }

  @Test
  void hostConfigurationRejectsAnUnrepresentableManifestAndKeepsItsLimit() {
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                HostYaml.fromMap(
                    Map.of("limits", Map.of("file_max", 8L * 1024 * 1024 * 1024 + 1))));
    assertTrue(error.getMessage().contains("manifest"));
    var host = HostYaml.fromMap(Map.of("limits", Map.of("file_max", 12345)));
    assertEquals(12345, host.withSync(SyncConfig.unset()).limits().fileMax());
    assertEquals(host.limits(), HostYaml.fromMap(host.toMap()).limits());
    assertEquals(FileLimits.DEFAULT_MAX, HostYaml.fromMap(Map.of()).limits().fileMax());
  }
}
