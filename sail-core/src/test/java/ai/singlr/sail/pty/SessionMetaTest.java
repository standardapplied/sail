/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionMetaTest {

  @TempDir Path dir;

  private static SessionMeta sample() {
    return new SessionMeta(
        new PtySession.Origin(
            "s1", "inst-1", "uday", "acme", "design-talk", List.of("claude", "--resume")),
        "/home/dev/workspace",
        132,
        43,
        "uday",
        7,
        4242,
        1_700_000_000_000L,
        "0.42.0",
        true);
  }

  @Test
  void everyFieldRoundTripsAndTheFileIsOwnerOnly() throws Exception {
    var path = dir.resolve("s1.meta");
    var meta = sample();

    meta.write(path);

    assertEquals(meta, SessionMeta.read(path));
    assertFalse(Files.exists(dir.resolve("s1.meta.tmp")), "the temp file is renamed away");
    var permissions = Files.getPosixFilePermissions(path);
    assertEquals(
        java.util.EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        permissions,
        "session facts are as private as the ring");
  }

  @Test
  void aRewriteReplacesTheWholeFileAndACrashMidWriteLeavesTheOldOne() throws Exception {
    var path = dir.resolve("s1.meta");
    sample().write(path);
    Files.writeString(dir.resolve("s1.meta.tmp"), "{ half a");

    var resized =
        new SessionMeta(sample().origin(), "/tmp", 80, 24, "", -1, 4242, 1L, "0.42.0", false);
    resized.write(path);

    assertEquals(resized, SessionMeta.read(path), "the newest write wins, whole");
  }

  @Test
  void aGarbledOrPartialSidecarIsRefusedWithTheReason() throws Exception {
    var path = dir.resolve("s1.meta");
    Files.writeString(path, "{\"version\": 1, \"name\": \"s1\"}");
    var partial = assertThrows(IOException.class, () -> SessionMeta.read(path));
    assertTrue(partial.getMessage().contains("s1.meta"), partial.getMessage());
    assertTrue(partial.getMessage().contains("missing field"), partial.getMessage());

    Files.writeString(path, "not json at all {{{");
    var garbled = assertThrows(IOException.class, () -> SessionMeta.read(path));
    assertTrue(garbled.getMessage().contains("unreadable"), garbled.getMessage());

    Files.writeString(path, "{\"version\": 2}");
    var future = assertThrows(IOException.class, () -> SessionMeta.read(path));
    assertTrue(future.getMessage().contains("version 2"), future.getMessage());

    assertThrows(IOException.class, () -> SessionMeta.read(dir.resolve("absent.meta")));
  }
}
