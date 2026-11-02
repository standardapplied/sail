/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.LegacyConversion;
import ai.singlr.sail.store.LegacyDatabase;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A fleet converted from Sail 0.58 syncs as one: a node, which keeps nothing synced, takes exactly
 * what the converted main kept in its first round — no closed spec, none of its own unsynced work —
 * and what it changes afterwards reaches main.
 */
@ActingAs(value = Actor.Lane.CLI, handle = "uday")
class LegacyConversionSyncTest {

  private static final String MAIN = "main-catalog";
  private static final String MESSAGE = "01a19d7c-c364-7000-b3af-32fa03112e01";

  @TempDir Path dir;

  private static void synced(LegacyDatabase legacy) {
    legacy.project("sail", "name: sail\nimage: ubuntu/24.04\nssh:\n  user: dev\n", "uday", "uday");
    legacy.file("sail", "setup-dev.sh", "echo up\n".getBytes(StandardCharsets.UTF_8));
    legacy.spec("open-a", "pending", "sail", List.of("done-c"));
    legacy.spec("done-c", "done", "sail", List.of());
    legacy.message(MESSAGE, "open-a", null, true);
  }

  private Path box(String name, String catalog, String hostYaml) throws IOException {
    var file = dir.resolve(name).resolve("sail.db");
    Files.createDirectories(file.getParent());
    try (var legacy = LegacyDatabase.create(file, catalog)) {
      synced(legacy);
      if (!catalog.equals(MAIN)) {
        legacy.syncsWith(MAIN);
        legacy.spec("unsynced", "draft", "sail", List.of());
      }
    }
    var host = file.resolveSibling("host.yaml");
    if (hostYaml != null) {
      Files.writeString(host, hostYaml);
    }
    LegacyConversion.at(file, host, LegacyDatabase.QUIET).orElseThrow().convert().orElseThrow();
    return file;
  }

  @Test
  void aConvertedNodeTakesExactlyWhatTheConvertedMainKeptAndItsNextChangeReachesMain()
      throws IOException {
    var mainFile = box("main", MAIN, null);
    var nodeFile =
        box("node", "node-catalog", "sync:\n  role: node\n  main: main.example\n  handle: uday\n");

    try (var main = SyncBox.opening(mainFile, "main");
        var node = SyncBox.opening(nodeFile, "node").syncsAs(Actor.sync("uday", Role.ADMIN))) {
      SyncBox.quiesce(main, node);

      SyncBox.assertEqualToMain(main, node);
      assertTrue(node.specs.findById("open-a").isPresent());
      assertTrue(node.specs.findById("done-c").isEmpty());
      assertTrue(node.specs.findById("unsynced").isEmpty());
      assertEquals(List.of(), node.specs.findById("open-a").orElseThrow().dependsOn());
      assertTrue(new ProjectStore(node.db).findByName("sail").isPresent());
      assertTrue(new FileStore(node.db).find("sail", "setup-dev.sh").isPresent());
      assertTrue(new MessageStore(node.db).findById(MESSAGE).orElseThrow().question());

      node.reassign("open-a", "rajesh");
      SyncBox.quiesce(main, node);

      assertEquals("rajesh", main.specs.findById("open-a").orElseThrow().assignee());
      SyncBox.assertEqualToMain(main, node);
    }
  }
}
