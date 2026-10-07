/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.authority.Refusal;
import ai.singlr.sail.authority.WriteRefused;
import ai.singlr.sail.config.FileLimits;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A2 through the host CLI's direct writes on Ada's node, where the operator acts with the role
 * main's roster gives her ({@link CliOperator}): the catalog, a shared file and a rename are each
 * decided by their type's rule as the journal records them, so a write the rule refuses lands
 * nowhere — the audit of 2026-09-29 found each of them unchecked.
 */
class CatalogAuthorityAuditTest {

  private static final SyncConfig ADAS_NODE = new SyncConfig("node", "sail@main", "ada", "ada");
  private static final String DEFINITION = "name: acme\n";

  @TempDir Path tempDir;
  private Path catalog;
  private Sqlite db;

  @BeforeEach
  void setUp() {
    catalog = tempDir.resolve("sail.db");
    db = Sqlite.open(catalog);
    new SchemaManager(db).migrate();
    new FdeStore(db).add("bob", null, null, "member");
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private Actor operatorWithRole(String role) {
    new FdeStore(db).add("ada", null, null, role);
    return CliOperator.of(ADAS_NODE, new FdeStore(db));
  }

  @Test
  void aViewerOperatorsCatalogEditIsRefusedBeforeAndAtTheWrite() {
    var viewer = operatorWithRole("viewer");
    var readOnly =
        new Refusal(
            Refusal.Kind.READ_ONLY,
            "Your credential is read-only and cannot change projects.",
            "Ask an admin for a member or admin credential.");

    var upFront =
        assertThrows(
            WriteRefused.class, () -> ProjectCatalog.requireRecordable(catalog, "acme", viewer));
    var atTheWrite =
        assertThrows(
            WriteRefused.class, () -> ProjectCatalog.record(catalog, "acme", DEFINITION, viewer));

    assertEquals(readOnly, upFront.refusal(), "asked before a command changes anything");
    assertEquals(readOnly, atTheWrite.refusal(), "and decided where the revision is recorded");
    assertTrue(new ProjectStore(db).findByName("acme").isEmpty(), "no catalog row written");
  }

  @Test
  void aMemberOperatorsCatalogEditLands() {
    var member = operatorWithRole("member");

    ProjectCatalog.requireRecordable(catalog, "acme", member);
    ProjectCatalog.record(catalog, "acme", DEFINITION, member);

    assertEquals(DEFINITION, new ProjectStore(db).findByName("acme").orElseThrow().definition());
  }

  @Test
  void aViewerOperatorsSharedFileIsRefusedBeforeItsBytesAreKept() {
    var viewer = operatorWithRole("viewer");
    var files = new SharedProjectFiles(new FileStore(db), tempDir, "acme", FileLimits.defaults());
    var bytes = "hello".getBytes(StandardCharsets.UTF_8);

    var refused =
        assertThrows(
            WriteRefused.class,
            () ->
                Actor.call(
                    viewer,
                    () ->
                        files.put(
                            "notes.md", new ByteArrayInputStream(bytes), bytes.length, 0644)));

    assertEquals(
        new Refusal(
            Refusal.Kind.READ_ONLY,
            "Your credential is read-only and cannot change files.",
            "Ask an admin for a member or admin credential."),
        refused.refusal());
    assertTrue(new FileStore(db).find("acme", "notes.md").isEmpty(), "no file row written");
    assertEquals(
        0L,
        db.queryOne("SELECT count(*) FROM blobs", row -> row.integer(0)).orElseThrow(),
        "and nothing ingested for it");
  }

  @Test
  void aMembersRenameMovesNoSpecAndAnAdminsMovesEveryOne() {
    var member = operatorWithRole("member");
    Acting.system(() -> new ProjectStore(db).upsert("acme", DEFINITION));
    Acting.as("bob", () -> new SpecStore(db).create(bobsSpec()));

    var refused =
        assertThrows(
            WriteRefused.class,
            () -> Actor.call(member, () -> ProjectCatalogRename.rename(db, "acme", "acme2")));

    assertEquals(
        new Refusal(
            Refusal.Kind.ADMIN_ONLY,
            "Renaming project 'acme' moves every spec and file in it, whoever's they are, and is"
                + " an admin-only action.",
            "Ask an admin to rename it."),
        refused.refusal());
    assertEquals("acme", new SpecStore(db).findById("theirs").orElseThrow().project());
    assertTrue(new ProjectStore(db).findByName("acme").isPresent(), "the old name stands");
    assertFalse(new ProjectStore(db).findByName("acme2").isPresent(), "and no new one is made");

    Acting.as("root", () -> ProjectCatalogRename.rename(db, "acme", "acme2"));

    assertEquals("acme2", new SpecStore(db).findById("theirs").orElseThrow().project());
  }

  private static SpecStore.SpecRow bobsSpec() {
    return new SpecStore.SpecRow(
        "theirs",
        "acme",
        "theirs",
        SpecStatus.PENDING,
        "bob",
        null,
        null,
        null,
        null,
        0,
        null,
        "",
        "",
        null,
        List.of(),
        List.of());
  }
}
