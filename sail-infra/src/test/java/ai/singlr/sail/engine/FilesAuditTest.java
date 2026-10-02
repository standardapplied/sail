/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.ContentFixtures;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.DataMigrator;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.MaterializedFilesMigration;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncBox;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * C3 and C4 for files: whether a disk copy is this box's output or a person's edit is decided from
 * what the box recorded writing, never from retained history, so no box republishes, resurrects or
 * overwrites a version by mistaking its own output for a person's edit, or the reverse.
 */
@ActingAs
class FilesAuditTest {

  @TempDir Path tempDir;
  private SyncBox mainBox;
  private SyncBox nodeBox;
  private Sqlite mainDb;
  private Sqlite nodeDb;
  private FileStore main;
  private FileStore node;
  private Path projectsDir;
  private Path copy;

  @BeforeEach
  void setUp() {
    mainBox = new SyncBox(tempDir, "main");
    nodeBox = new SyncBox(tempDir, "node");
    mainDb = mainBox.db;
    nodeDb = nodeBox.db;
    main = new FileStore(mainDb);
    node = new FileStore(nodeDb);
    projectsDir = tempDir.resolve("projects");
    copy = projectsDir.resolve("acme/files/app.conf");
  }

  @AfterEach
  void tearDown() {
    nodeBox.close();
    mainBox.close();
  }

  private void converged() {
    SyncBox.assertConvergedWithin(2, mainBox, nodeBox);
  }

  private FileMaterializer.Report materialize() throws IOException {
    return new FileMaterializer(main, projectsDir).materialize("acme");
  }

  private void nodePushes(String text) {
    ContentFixtures.put(node, "acme", "app.conf", text);
    SyncBox.round(mainDb, nodeDb, "file");
  }

  private String nodePushesMoreThanHistoryKeeps() {
    return SyncBox.pushPastHistory(mainDb, nodeDb, "acme", "app.conf");
  }

  private void mainCompacts() {
    new BlobStore(mainDb).gc(BlobStore.Compaction.ALL, true);
  }

  private static long records(Sqlite db) {
    return db.queryOne("SELECT count(*) FROM materialized_files", row -> row.integer(0))
        .orElseThrow();
  }

  @Test
  void mainRefreshesItsOwnCopyAfterNodesPushedMoreThanHistoryKeepsAndMainCompacted()
      throws Exception {
    ContentFixtures.put(main, "acme", "app.conf", "v0");
    materialize();
    SyncBox.round(mainDb, nodeDb, "file");
    var last = nodePushesMoreThanHistoryKeeps();
    mainCompacts();

    var report = materialize();

    assertEquals(new FileMaterializer.Report(1, 0, List.of()), report);
    assertEquals(last, Files.readString(copy));
    converged();
  }

  @Test
  void aHandEditAfterCompactionIsAPersonsEditAndIsKeptWithItsNote() throws Exception {
    ContentFixtures.put(main, "acme", "app.conf", "v0");
    materialize();
    SyncBox.round(mainDb, nodeDb, "file");
    nodePushesMoreThanHistoryKeeps();
    mainCompacts();
    Files.writeString(copy, "mine");

    var report = materialize();

    assertEquals(new FileMaterializer.Report(0, 0, List.of("app.conf")), report);
    assertEquals("mine", Files.readString(copy));
    converged();
  }

  @Test
  void aHandRevertToAnEarlierVersionIsAPersonsEditAndIsKept() throws Exception {
    ContentFixtures.put(main, "acme", "app.conf", "v1");
    materialize();
    ContentFixtures.put(main, "acme", "app.conf", "v2");
    materialize();
    Files.writeString(copy, "v1");

    var report = materialize();

    assertEquals("v1", Files.readString(copy), "the person's revert was overwritten");
    assertEquals(List.of("app.conf"), report.skipped());
  }

  @Test
  void aDeletionAfterMoreRevisionsThanHistoryKeepsRemovesTheCopyAndTheRecord() throws Exception {
    ContentFixtures.put(main, "acme", "app.conf", "v0");
    materialize();
    SyncBox.round(mainDb, nodeDb, "file");
    nodePushesMoreThanHistoryKeeps();
    Acting.system(() -> node.delete("acme", "app.conf"));
    SyncBox.round(mainDb, nodeDb, "file");
    mainCompacts();

    var report = materialize();

    assertEquals(new FileMaterializer.Report(0, 1, List.of()), report);
    assertFalse(Files.exists(copy));
    assertEquals(0, records(mainDb), "the record went with the copy");
    converged();
  }

  @Test
  void aModeOnlyChangeFollowedByMoreRevisionsThanHistoryKeepsIsStillRefreshed() throws Exception {
    ContentFixtures.put(main, "acme", "app.conf", "v0");
    materialize();
    SyncBox.round(mainDb, nodeDb, "file");
    var row = node.find("acme", "app.conf").orElseThrow();
    node.put(
        new FileStore.FileRow("acme", "app.conf", row.contentHash(), row.size(), 0600, row.kind()));
    SyncBox.round(mainDb, nodeDb, "file");
    assertEquals(new FileMaterializer.Report(0, 0, List.of()), materialize());
    assertEquals(0600, WorkspaceFiles.mode(copy));
    var last = nodePushesMoreThanHistoryKeeps();
    mainCompacts();

    var report = materialize();

    assertEquals(new FileMaterializer.Report(1, 0, List.of()), report);
    assertEquals(last, Files.readString(copy));
    converged();
  }

  @Test
  void migratesImportDoesNotRepublishMainsStaleCopyOverANodesNewerVersion() throws Exception {
    ContentFixtures.put(main, "acme", "app.conf", "v1");
    materialize();
    SyncBox.round(mainDb, nodeDb, "file");
    nodePushes("v2");

    var imported = new FileImporter(projectsDir, main).importAll();

    assertEquals(0, imported.imported(), "the import took main's own stale copy as an edit");
    assertEquals("v2", ContentFixtures.text(main, "acme", "app.conf"));
    SyncBox.round(mainDb, nodeDb, "file");
    assertEquals("v2", ContentFixtures.text(node, "acme", "app.conf"));
    converged();
  }

  @Test
  void migratesImportDoesNotResurrectAFileANodeDeleted() throws Exception {
    ContentFixtures.put(main, "acme", "app.conf", "v1");
    materialize();
    SyncBox.round(mainDb, nodeDb, "file");
    Acting.system(() -> node.delete("acme", "app.conf"));
    SyncBox.round(mainDb, nodeDb, "file");
    assertTrue(main.find("acme", "app.conf").isEmpty());

    new FileImporter(projectsDir, main).importAll();

    assertTrue(main.find("acme", "app.conf").isEmpty(), "the import resurrected the deleted file");
    assertEquals(1, materialize().deleted(), "left for the materializer to remove");
    converged();
  }

  @Test
  void theRecordGoesWithMainsEraseANodesAdoptionAndANodesPrune() throws Exception {
    var nodeProjects = tempDir.resolve("node-projects");
    ContentFixtures.put(main, "acme", "app.conf", "v1");
    materialize();
    SyncBox.round(mainDb, nodeDb, "file");
    new FileMaterializer(node, nodeProjects).materialize("acme");
    ContentFixtures.put(node, "acme", "draft.conf", "only here");
    new FileMaterializer(node, nodeProjects).materialize("acme");
    assertEquals(1, records(mainDb));
    assertEquals(2, records(nodeDb));

    var erasure = new Erasure(mainDb);
    erasure.erase(erasure.closure(List.of(new Erasure.Target(Erasure.PROJECT, "acme"))), "local");
    assertEquals(0, records(mainDb), "main's erase");
    SyncBox.round(mainDb, nodeDb, "file");
    assertEquals(1, records(nodeDb), "the node adopted main's erasure of what main held");
    new Erasure(nodeDb).discard(List.of(new Erasure.Target(Erasure.FILE, "acme/draft.conf")));
    assertEquals(0, records(nodeDb), "the node's own prune");

    assertEquals("v1", Files.readString(copy), "an erased file's copy stays with the project");
    assertEquals("only here", Files.readString(nodeProjects.resolve("acme/files/draft.conf")));
    converged();
  }

  @Test
  void theUpgradeOfAMainThatCompactedPublishesNeitherItsStaleCopyNorADeletedFilesCopy()
      throws Exception {
    ContentFixtures.put(main, "acme", "app.conf", "v0");
    ContentFixtures.put(main, "acme", "gone.conf", "v0");
    new FileMaterializer(main, projectsDir).materialize("acme");
    SyncBox.round(mainDb, nodeDb, "file");
    var last = nodePushesMoreThanHistoryKeeps();
    SyncBox.pushPastHistory(mainDb, nodeDb, "acme", "gone.conf");
    Acting.system(() -> node.delete("acme", "gone.conf"));
    SyncBox.round(mainDb, nodeDb, "file");
    mainCompacts();
    mainDb.execute("DELETE FROM materialized_files");

    var seed =
        new DataMigrator(mainDb, List.of(new MaterializedFilesMigration(projectsDir)))
            .run(
                ProjectRegistry.loadFromDisk(tempDir.resolve("no-projects")),
                DataMigration.Prompter.NON_INTERACTIVE)
            .getFirst();
    var imported = new FileImporter(projectsDir, main).importAll();
    var report = materialize();

    assertEquals(2, seed.report().ambiguous(), "neither copy matches what compaction left");
    assertEquals(0, imported.imported(), "the import republished a copy the seed could not tell");
    assertEquals(last, ContentFixtures.text(main, "acme", "app.conf"));
    assertTrue(main.find("acme", "gone.conf").isEmpty(), "the import resurrected a deleted file");
    assertEquals(
        new FileMaterializer.Report(0, 0, List.of(), List.of("app.conf", "gone.conf")),
        report,
        "both copies are kept and reported apart until the person publishes or discards them");
    assertEquals("v0", Files.readString(copy));
    Files.delete(copy);
    assertEquals(new FileMaterializer.Report(1, 0, List.of(), List.of("gone.conf")), materialize());
    assertEquals(last, Files.readString(copy), "a discarded copy is refreshed");
    converged();
  }

  @Test
  void aRenameReKeysTheRecordAndAMaterializeAfterItWritesNothingTwice() throws Exception {
    Acting.system(() -> new ProjectStore(mainDb).upsert("acme", "name: acme\n"));
    ContentFixtures.put(main, "acme", "app.conf", "v1");
    materialize();
    var hash = main.find("acme", "app.conf").orElseThrow().contentHash();

    ProjectCatalogRename.rename(mainDb, "acme", "globex");
    Files.move(projectsDir.resolve("acme"), projectsDir.resolve("globex"));
    var materializer = new FileMaterializer(main, projectsDir);

    assertTrue(main.copyOf("globex/app.conf", hash, 0644).ours());
    assertFalse(main.copyOf("acme/app.conf", hash, 0644).ours());
    assertEquals(new FileMaterializer.Report(0, 0, List.of()), materializer.materialize("globex"));
    assertEquals(new FileMaterializer.Report(0, 0, List.of()), materializer.materialize("acme"));
    assertEquals("v1", Files.readString(projectsDir.resolve("globex/files/app.conf")));
    assertEquals(1, records(mainDb));
    converged();
  }
}
