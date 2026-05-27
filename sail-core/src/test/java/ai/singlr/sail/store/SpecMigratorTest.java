/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecMigratorTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SpecStore store;
  private SpecMigrator migrator;
  private Path specsDir;

  @BeforeEach
  void setUp() throws IOException {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    store = new SpecStore(db);
    migrator = new SpecMigrator(store);
    specsDir = tempDir.resolve("specs");
    Files.createDirectories(specsDir);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private void writeSpec(String id, String yaml) throws IOException {
    var dir = specsDir.resolve(id);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("spec.yaml"), yaml);
  }

  private void writeSpecWithContent(String id, String yaml, String body, String plan)
      throws IOException {
    writeSpec(id, yaml);
    if (body != null) Files.writeString(specsDir.resolve(id).resolve("spec.md"), body);
    if (plan != null) Files.writeString(specsDir.resolve(id).resolve("plan.md"), plan);
  }

  @Test
  void importsSingleSpec() throws IOException {
    writeSpec(
        "auth-flow",
        """
        id: auth-flow
        title: OAuth 2.0 flow
        status: pending
        """);

    var result = migrator.importFromDirectory(specsDir);
    assertEquals(1, result.imported());
    assertEquals(0, result.skipped());
    assertTrue(result.errors().isEmpty());

    var found = store.findById("auth-flow");
    assertTrue(found.isPresent());
    assertEquals("OAuth 2.0 flow", found.get().title());
    assertEquals("pending", found.get().status());
  }

  @Test
  void importsSpecWithContent() throws IOException {
    writeSpecWithContent(
        "payment",
        """
        id: payment
        title: Payment integration
        status: draft
        """,
        "# Payment Spec\n\nDetails here.",
        "## Plan\n\n1. Step one");

    migrator.importFromDirectory(specsDir);

    var content = store.getContent("payment");
    assertTrue(content.isPresent());
    assertEquals("# Payment Spec\n\nDetails here.", content.get().body());
    assertEquals("## Plan\n\n1. Step one", content.get().plan());
  }

  @Test
  void skipsExistingSpecs() throws IOException {
    writeSpec(
        "existing",
        """
        id: existing
        title: Already in DB
        status: done
        """);
    migrator.importFromDirectory(specsDir);
    assertEquals(1, migrator.importFromDirectory(specsDir).skipped());
  }

  @Test
  void idempotentOnRerun() throws IOException {
    writeSpec(
        "idempotent",
        """
        id: idempotent
        title: Test idempotency
        status: pending
        """);

    var first = migrator.importFromDirectory(specsDir);
    var second = migrator.importFromDirectory(specsDir);

    assertEquals(1, first.imported());
    assertEquals(0, first.skipped());
    assertEquals(0, second.imported());
    assertEquals(1, second.skipped());
  }

  @Test
  void importsMultipleSpecs() throws IOException {
    writeSpec("a", "id: a\ntitle: First\nstatus: pending\n");
    writeSpec("b", "id: b\ntitle: Second\nstatus: done\n");
    writeSpec("c", "id: c\ntitle: Third\nstatus: in_progress\n");

    var result = migrator.importFromDirectory(specsDir);
    assertEquals(3, result.imported());
  }

  @Test
  void handlesNonexistentDirectory() {
    var result = migrator.importFromDirectory(tempDir.resolve("nonexistent"));
    assertEquals(0, result.imported());
    assertEquals(0, result.skipped());
  }

  @Test
  void skipsDirsWithoutSpecYaml() throws IOException {
    Files.createDirectories(specsDir.resolve("empty-dir"));
    var result = migrator.importFromDirectory(specsDir);
    assertEquals(0, result.imported());
  }

  @Test
  void mapsArchivedStatus() throws IOException {
    writeSpec("old", "id: old\ntitle: Archived\nstatus: archive\n");

    migrator.importFromDirectory(specsDir);

    var found = store.findById("old");
    assertTrue(found.isPresent());
    assertEquals("archived", found.get().status());
  }

  @Test
  void importsDependencies() throws IOException {
    writeSpec("base", "id: base\ntitle: Base\nstatus: done\n");
    writeSpec(
        "child",
        """
        id: child
        title: Child
        status: pending
        depends_on:
          - base
        """);

    migrator.importFromDirectory(specsDir);

    var child = store.findById("child");
    assertTrue(child.isPresent());
    assertEquals(1, child.get().dependsOn().size());
    assertEquals("base", child.get().dependsOn().getFirst());
  }

  @Test
  void importsRepos() throws IOException {
    writeSpec(
        "multi-repo",
        """
        id: multi-repo
        title: Multi repo
        status: pending
        repos:
          - backend
          - frontend
        """);

    migrator.importFromDirectory(specsDir);

    var found = store.findById("multi-repo");
    assertTrue(found.isPresent());
    assertEquals(2, found.get().repos().size());
  }

  @Test
  void inferIdFromDirectoryName() throws IOException {
    writeSpec("inferred", "title: No explicit ID\nstatus: draft\n");

    migrator.importFromDirectory(specsDir);

    assertTrue(store.findById("inferred").isPresent());
  }

  @Test
  void recordsErrorsForMalformedSpecs() throws IOException {
    var dir = specsDir.resolve("broken");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("spec.yaml"), "not: valid: yaml: [[[");

    var result = migrator.importFromDirectory(specsDir);
    assertEquals(0, result.imported());
    assertEquals(1, result.errors().size());
    assertTrue(result.errors().getFirst().startsWith("broken:"));
  }
}
