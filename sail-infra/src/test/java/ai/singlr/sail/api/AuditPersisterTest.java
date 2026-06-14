/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditPersisterTest {

  @Test
  void rejectsNullPath() {
    assertThrows(IllegalArgumentException.class, () -> new AuditPersister(null, 16));
  }

  @Test
  void rejectsNonPositiveCapacity(@TempDir Path dir) {
    assertThrows(
        IllegalArgumentException.class, () -> new AuditPersister(dir.resolve("e.jsonl"), 0));
  }

  @Test
  void nameIsStable(@TempDir Path dir) {
    var persister = new AuditPersister(dir.resolve("e.jsonl"), 8);
    assertEquals("audit-persister", persister.name());
  }

  @Test
  void appendsOneLinePerEvent(@TempDir Path dir) throws Exception {
    var path = dir.resolve("e.jsonl");
    var persister = new AuditPersister(path, 8);
    persister.onEvent(Event.of("p", null, "t", "a", "h").withId(1L));
    persister.onEvent(Event.of("p", null, "t", "a", "h").withId(2L));
    var lines = Files.readString(path).split("\n");
    assertEquals(2, Arrays.stream(lines).filter(l -> !l.isBlank()).count());
  }

  @Test
  void createsParentDirectory(@TempDir Path dir) throws Exception {
    var path = dir.resolve("nested/down/here/events.jsonl");
    var persister = new AuditPersister(path, 4);
    persister.onEvent(Event.of("p", null, "t", "a", "h").withId(1L));
    assertTrue(Files.exists(path));
  }

  @Test
  void recentReturnsLastN(@TempDir Path dir) {
    var persister = new AuditPersister(dir.resolve("e.jsonl"), 4);
    for (var i = 1; i <= 6; i++) {
      persister.onEvent(Event.of("p", null, "t", "a", "h").withId(i));
    }
    var recent = persister.recent(3);
    assertEquals(3, recent.size());
    assertEquals(4L, recent.get(0).id());
    assertEquals(5L, recent.get(1).id());
    assertEquals(6L, recent.get(2).id());
  }

  @Test
  void recentClampsToBufferSize(@TempDir Path dir) {
    var persister = new AuditPersister(dir.resolve("e.jsonl"), 4);
    persister.onEvent(Event.of("p", null, "t", "a", "h").withId(1L));
    assertEquals(1, persister.recent(100).size());
  }

  @Test
  void recentRejectsNonPositiveLimit(@TempDir Path dir) {
    var persister = new AuditPersister(dir.resolve("e.jsonl"), 4);
    assertThrows(IllegalArgumentException.class, () -> persister.recent(0));
  }

  @Test
  void filterAcceptsAll(@TempDir Path dir) {
    var persister = new AuditPersister(dir.resolve("e.jsonl"), 4);
    var event = Event.of("p", null, "t", "a", "h").withId(1L);
    assertTrue(persister.filter().test(event));
  }

  @Test
  void eventsFilePathReturnsConfiguredPath(@TempDir Path dir) {
    var path = dir.resolve("e.jsonl");
    var persister = new AuditPersister(path, 4);
    assertEquals(path, persister.eventsFilePath());
  }

  @Test
  void atDefaultUsesHomeDotSail() {
    var persister = AuditPersister.atDefault();
    assertTrue(persister.eventsFilePath().toString().endsWith("/.sail/events.jsonl"));
  }

  @Test
  void ringBufferDropsOldestPastCapacity(@TempDir Path dir) {
    var persister = new AuditPersister(dir.resolve("e.jsonl"), 2);
    persister.onEvent(Event.of("p", null, "t", "a", "h").withId(1L));
    persister.onEvent(Event.of("p", null, "t", "a", "h").withId(2L));
    persister.onEvent(Event.of("p", null, "t", "a", "h").withId(3L));
    var recent = persister.recent(2);
    assertEquals(2L, recent.get(0).id());
    assertEquals(3L, recent.get(1).id());
  }
}
