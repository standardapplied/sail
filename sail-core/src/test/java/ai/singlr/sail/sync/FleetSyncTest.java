/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end fleet sync against realistic state — the integration coverage the unit tests lacked. A
 * fresh node syncs every entity from a main seeded the way a real one is: specs that depend on each
 * other, a project catalogued by an older sail (a {@code projects} row with no change-log entry,
 * backfilled by {@code migrate}), shared files, and the FDE roster. Reproduces, as regressions, the
 * three bugs that reached a real box: the {@code depends_on} foreign key that aborted the round,
 * the un-journaled project invisible to sync, and the roster pull that those aborts skipped.
 */
class FleetSyncTest {

  @TempDir Path dir;
  private final SyncEngine engine = new SyncEngine();

  private Box main;
  private Box node;

  private final class Box implements AutoCloseable {
    final Sqlite db;
    final SpecStore specs;
    final FileStore files;
    final ProjectStore projects;
    final FdeStore fdes;
    final SpecReplica specReplica;
    final FileReplica fileReplica;
    final ProjectReplica projectReplica;

    Box(String id) {
      this.db = Sqlite.open(dir.resolve(id + ".db"));
      new SchemaManager(db).migrate();
      var changeLog = new ChangeLog(db);
      var conflicts = new SyncConflicts(db);
      var syncState = new SyncState(db);
      this.specs = new SpecStore(db);
      this.files = new FileStore(db);
      this.projects = new ProjectStore(db);
      this.fdes = new FdeStore(db);
      this.specReplica = new SpecReplica(id, specs, changeLog, conflicts, syncState);
      this.fileReplica = new FileReplica(id, files, changeLog, conflicts, syncState);
      this.projectReplica = new ProjectReplica(id, projects, changeLog, conflicts, syncState);
    }

    @Override
    public void close() {
      db.close();
    }
  }

  @BeforeEach
  void setUp() {
    main = new Box("main");
    node = new Box("node");
  }

  @AfterEach
  void tearDown() {
    node.close();
    main.close();
  }

  private void syncFromMain() {
    engine.reconcile(node.specReplica, main.specReplica);
    engine.reconcile(node.fileReplica, main.fileReplica);
    engine.reconcile(node.projectReplica, main.projectReplica);
    pullRoster();
  }

  private void pullRoster() {
    for (var fde : main.fdes.list()) {
      node.fdes.replicate(
          fde.handle(), fde.displayName(), fde.email(), fde.role(), fde.status(), fde.createdAt());
    }
  }

  private static SpecStore.SpecRow spec(String id, String title, String status, List<String> deps) {
    return new SpecStore.SpecRow(
        id,
        "acme",
        title,
        SpecStatus.fromWire(status),
        null,
        null,
        null,
        null,
        null,
        0,
        "uday",
        "",
        "",
        "uday",
        deps,
        List.of());
  }

  private static String b64(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes());
  }

  private void seedRealisticMain() {
    main.specs.create(spec("oauth", "OAuth flow", "done", List.of()));
    main.specs.create(spec("billing", "Billing", "pending", List.of("oauth")));
    main.files.put("acme", "scripts/deploy.sh", b64("deploy"));
    main.projects.upsert("acme", "name: acme\nimage: ubuntu/24.04\n", "uday");
    main.db.execute(
        "INSERT INTO projects (name, definition, created_at, updated_at)"
            + " VALUES ('outline', 'name: outline\n', '2026-01-01', '2026-01-01')");
    main.fdes.add("uday", "Uday Chandra", "uday@example.com", "admin");
    main.fdes.add("mady", "Mady M", "mady@example.com", "member");
  }

  @Test
  void aFreshNodePullsEverythingFromARealisticMain() {
    seedRealisticMain();
    assertEquals(1, main.projects.backfillRevisions(), "the legacy project becomes syncable");

    syncFromMain();

    assertEquals("OAuth flow", node.specs.findById("oauth").orElseThrow().title());
    assertEquals(
        List.of("oauth"),
        node.specs.findById("billing").orElseThrow().dependsOn(),
        "a dependent spec replicates without a foreign-key abort");

    assertEquals(
        "name: acme\nimage: ubuntu/24.04\n",
        node.projects.findByName("acme").orElseThrow().definition());
    assertTrue(
        node.projects.findByName("outline").isPresent(),
        "a project catalogued by an older sail, made syncable by backfill, replicates");

    assertEquals("deploy", decode(node.files.find("acme", "scripts/deploy.sh").orElseThrow()));

    assertEquals(
        2, node.fdes.list().size(), "the roster pulled — the step the aborts used to skip");
    assertEquals("Mady M", node.fdes.byHandle("mady").orElseThrow().displayName());
  }

  @Test
  void aSyncedProjectNeverCarriesMainsIdentityOrKeysToANode() {
    main.projects.upsert(
        "acme",
        "name: acme\n"
            + "git:\n  name: Uday Chandra\n  email: uday@example.com\n"
            + "ssh:\n  authorized_keys:\n    - ssh-ed25519 UDAYKEY uday@main\n",
        "uday");

    syncFromMain();

    var onNode = node.projects.findByName("acme").orElseThrow().definition();
    assertTrue(onNode.contains("${GIT_NAME}"), "the node sees a placeholder, not Uday's name");
    assertTrue(onNode.contains("${SSH_PUBLIC_KEY}"), "and a placeholder, not Uday's key");
    assertFalse(onNode.contains("Uday Chandra"));
    assertFalse(onNode.contains("uday@example.com"));
    assertFalse(onNode.contains("UDAYKEY"), "main's SSH key never lands on the node");
  }

  @Test
  void twoBoxesScrubbingTheSameLegacyProjectConvergeWithoutAConflict() {
    var legacy = "name: outline\ngit:\n  name: Uday\n  email: uday@example.com\n";
    insertLegacyProject(main, "outline", legacy);
    insertLegacyProject(node, "outline", legacy);

    assertEquals(1, main.projects.canonicalizeDefinitions());
    assertEquals(1, node.projects.canonicalizeDefinitions());

    var report = engine.reconcile(node.projectReplica, main.projectReplica);

    assertEquals(0, report.conflicts(), "identical redacted content converges, never conflicts");
    assertTrue(
        node.projects.findByName("outline").orElseThrow().definition().contains("${GIT_NAME}"));
  }

  private static void insertLegacyProject(Box box, String name, String definition) {
    box.db.execute(
        "INSERT INTO projects (name, definition, created_at, updated_at) VALUES (?, ?, ?, ?)",
        name,
        definition,
        "2026-01-01",
        "2026-01-01");
  }

  @Test
  void anUnjournaledProjectStaysInvisibleUntilBackfilled() {
    main.db.execute(
        "INSERT INTO projects (name, definition, created_at, updated_at)"
            + " VALUES ('outline', 'name: outline\n', '2026-01-01', '2026-01-01')");

    syncFromMain();
    assertFalse(
        node.projects.findByName("outline").isPresent(),
        "without backfill, a catalogued-but-un-journaled project does not sync");

    main.projects.backfillRevisions();
    syncFromMain();

    assertTrue(node.projects.findByName("outline").isPresent(), "backfill makes it replicate");
  }

  @Test
  void anUnjournaledSpecStaysInvisibleUntilBackfilled() {
    main.specs.create(spec("oauth", "OAuth flow", "done", List.of()));
    main.db.execute("DELETE FROM change_log WHERE entity_type = 'spec' AND entity_id = ?", "oauth");

    syncFromMain();
    assertTrue(
        node.specs.findById("oauth").isEmpty(),
        "without backfill, a spec predating the change log does not sync");

    main.specs.backfillRevisions();
    syncFromMain();

    assertEquals("OAuth flow", node.specs.findById("oauth").orElseThrow().title());
  }

  private static String decode(FileStore.FileRow row) {
    return new String(Base64.getDecoder().decode(row.content()));
  }
}
