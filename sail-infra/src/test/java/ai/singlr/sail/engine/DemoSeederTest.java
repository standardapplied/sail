/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.SyncRequest;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncBox;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemoSeederTest {

  @TempDir Path dir;
  private Sqlite db;
  private ProjectStore store;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(dir.resolve("test.db"));
    new SchemaManager(db).migrate();
    store = new ProjectStore(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void seedsTheDemoIntoAnEmptyCatalog() {
    assertTrue(DemoSeeder.seedIfAbsent(db, true));

    var row = store.findByName(DemoProject.NAME).orElseThrow();
    assertEquals(DemoProject.DEFINITION, row.definition());
  }

  @Test
  void aNodeNeverSeedsTheDemoForItComesFromMainLikeAnyProject() {
    assertFalse(DemoSeeder.seedIfAbsent(db, false));

    assertTrue(store.findByName(DemoProject.NAME).isEmpty());
    assertNull(store.latestRev(DemoProject.NAME), "nothing for its first round to drop");
  }

  @Test
  void aSeedUntouchedSinceIsLetGoWhenTheBoxSyncsAsANode() {
    DemoSeeder.seedIfAbsent(db, true);

    assertTrue(DemoSeeder.unseed(db));

    assertTrue(store.findByName(DemoProject.NAME).isEmpty());
    assertNull(store.latestRev(DemoProject.NAME), "nothing left to offer or drop");
    assertFalse(DemoSeeder.unseed(db));
  }

  @Test
  void aNodesFirstRoundLetsItsSeedGoWithNoNotice() throws Exception {
    DemoSeeder.seedIfAbsent(db, true);
    var ada = Actor.sync("ada", Role.MEMBER).withScope(ProjectScope.of(List.of("acme")));
    try (var main = new SyncBox("main")) {
      var syncing =
          new SyncOperations(
              db,
              "box",
              dir.resolve("projects"),
              () -> new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", "ada", "box"),
              target -> PipedSyncChannel.to(main.server(ada)),
              SyncOperations.PLAIN_NOTICES);
      syncing.prepare();

      var report =
          Actor.call(
              new Actor("ada", Role.MEMBER, Actor.Lane.CLI),
              () -> syncing.sync(new SyncRequest(null)));

      assertEquals(List.of(), report.settled());
    }
    assertTrue(store.findByName(DemoProject.NAME).isEmpty());
    assertNull(store.latestRev(DemoProject.NAME), "nothing left to offer or drop");
  }

  @Test
  void aDemoAnyoneChangedStaysAsWork() {
    DemoSeeder.seedIfAbsent(db, true);
    Acting.as("ada", () -> store.upsert(DemoProject.NAME, "name: demo\ndescription: mine\n"));

    assertFalse(DemoSeeder.unseed(db));
    assertEquals(
        "name: demo\ndescription: mine\n",
        store.findByName(DemoProject.NAME).orElseThrow().definition());
  }

  @Test
  void aDemoHoldingASpecStaysAsWork() {
    DemoSeeder.seedIfAbsent(db, true);
    Acting.as(
        "ada",
        () ->
            new SpecStore(db)
                .create(
                    new SpecStore.SpecRow(
                        "tour",
                        DemoProject.NAME,
                        "Tour",
                        SpecStatus.DRAFT,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        "ada",
                        null,
                        null,
                        "ada",
                        List.of(),
                        List.of())));

    assertFalse(DemoSeeder.unseed(db));
    assertTrue(store.findByName(DemoProject.NAME).isPresent());
  }

  @Test
  void aDemoMainTookStays() {
    DemoSeeder.seedIfAbsent(db, true);
    db.execute("UPDATE projects SET base_rev = rev WHERE name = ?", DemoProject.NAME);

    assertFalse(DemoSeeder.unseed(db));
    assertTrue(store.findByName(DemoProject.NAME).isPresent());
  }

  @Test
  void isIdempotentAndNeverClobbersAnExistingDemo() {
    Acting.system(() -> store.upsert(DemoProject.NAME, "name: demo\ndescription: mine\n"));

    assertFalse(DemoSeeder.seedIfAbsent(db, true), "must not re-seed when a demo already exists");
    assertEquals(
        "name: demo\ndescription: mine\n",
        store.findByName(DemoProject.NAME).orElseThrow().definition(),
        "a customised demo is left untouched");
  }

  @Test
  void doesNotResurrectAPurgedDemo() {
    DemoSeeder.seedIfAbsent(db, true);
    assertTrue(Acting.system(() -> store.delete(DemoProject.NAME)), "purge tombstones the demo");
    assertTrue(store.findByName(DemoProject.NAME).isEmpty());

    assertFalse(
        DemoSeeder.seedIfAbsent(db, true),
        "a purged demo must not be re-seeded on the next daemon start");
    assertTrue(
        store.findByName(DemoProject.NAME).isEmpty(),
        "and the tombstone stands, so sync keeps it gone");
  }

  @Test
  void seededDemoDefinitionParsesAsAValidProject() {
    DemoSeeder.seedIfAbsent(db, true);
    var definition = store.findByName(DemoProject.NAME).orElseThrow().definition();

    var config = SailYaml.fromMap(YamlUtil.parseMap(definition));
    assertEquals("demo", config.name());
  }

  @Test
  void bundlesTheOutlineSetupScript() {
    assertTrue(DemoProject.files().containsKey("outline/setup.sh"));
    assertTrue(DemoProject.files().get("outline/setup.sh").contains("Outline Dev Environment"));
  }
}
