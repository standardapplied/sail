/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.sync.SyncBox;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A revision that reaches a box by sync is what that box reads next, with nothing else run. */
class CatalogRevisionBySyncTest {

  private static final String ACME =
      """
      name: acme
      agent:
        type: claude-code
        notifications:
          url: https://ntfy.sh/acme
          events: [spec_dispatched]
      """;

  @TempDir Path dir;

  @Test
  void aRevisionAppliedBySyncIsWhatTheNodesNextReadTheLoopAndTheNextNotificationUse() {
    try (var main = new SyncBox(dir, "main");
        var node = new SyncBox(dir, "node").syncsAs(Actor.sync("node", Role.ADMIN))) {
      var authored = new ProjectStore(main.db);
      var reader = ProjectReader.ofCatalog(new ProjectStore(node.db));
      var notifications = new CatalogNotificationsResolver(reader);
      assertTrue(reader.read("acme").isEmpty(), "the node holds no project yet");

      Acting.system(() -> authored.upsert("acme", ACME));
      SyncBox.round(main, node);
      assertEquals("https://ntfy.sh/acme", notifications.resolve("acme").url());

      Acting.system(() -> authored.upsert("acme", ACME.replace("ntfy.sh/acme", "ntfy.sh/moved")));
      SyncBox.round(main, node);

      assertEquals("https://ntfy.sh/moved", reader.require("acme").agent().notifications().url());
      assertEquals(
          "https://ntfy.sh/moved",
          ReviewWiring.definitions(reader).apply("acme").agent().notifications().url());
      assertEquals("https://ntfy.sh/moved", notifications.resolve("acme").url());
    }
  }
}
