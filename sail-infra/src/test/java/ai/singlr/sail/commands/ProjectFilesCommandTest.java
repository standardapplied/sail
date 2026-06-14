/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.FileMaterializer;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ProjectFilesCommandTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private FileStore files;
  private Path projectsDir;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    files = new FileStore(db);
    projectsDir = tempDir.resolve("projects");
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private Path filesDir(String project) {
    return projectsDir.resolve(project).resolve("files");
  }

  private static String b64(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes());
  }

  @Test
  void parentUsageListsEverySubcommand() {
    var usage = new CommandLine(new ProjectFilesCommand()).getUsageMessage();
    assertTrue(usage.contains("add"));
    assertTrue(usage.contains("ls"));
    assertTrue(usage.contains("cat"));
    assertTrue(usage.contains("rm"));
    assertTrue(usage.contains("pull"));
  }

  @Test
  void parentRunPrintsUsageWithoutError() {
    assertEquals(0, new CommandLine(new ProjectFilesCommand()).execute());
  }

  @Test
  void addStoresTheFileAndMaterializesItLocally() throws Exception {
    var source = tempDir.resolve("deploy.sh");
    Files.writeString(source, "echo hi");

    var path = ProjectFilesCommand.Add.store(files, "acme", source, "scripts/deploy.sh");
    new FileMaterializer(files, projectsDir).materialize("acme");

    assertEquals("scripts/deploy.sh", path);
    assertEquals(b64("echo hi"), files.find("acme", "scripts/deploy.sh").orElseThrow().content());
    assertEquals("echo hi", Files.readString(filesDir("acme").resolve("scripts/deploy.sh")));
  }

  @Test
  void addDefaultsTheStoredPathToTheSourceFileName() throws Exception {
    var source = tempDir.resolve("notes.md");
    Files.writeString(source, "hello");

    var path = ProjectFilesCommand.Add.store(files, "acme", source, null);

    assertEquals("notes.md", path);
  }

  @Test
  void addRejectsAPathThatEscapesTheProject() throws Exception {
    var source = tempDir.resolve("evil");
    Files.writeString(source, "x");

    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectFilesCommand.Add.store(files, "acme", source, "../escape"));
  }

  @Test
  void addReportsExitOneWhenTheSourceIsMissing() {
    var cmd = new CommandLine(new ProjectFilesCommand.Add());
    cmd.setErr(new java.io.PrintWriter(new java.io.StringWriter()));
    assertEquals(1, cmd.execute("acme", tempDir.resolve("nope").toString()));
  }

  @Test
  void lsRendersHumanAndJson() {
    files.put("acme", "a.txt", b64("AAAA"));
    files.put("acme", "b.txt", b64("B"));

    var human = ProjectFilesCommand.Ls.render(files.list("acme"), "acme", false);
    assertTrue(human.contains("a.txt"));
    assertTrue(human.contains("4 B"));

    var json = ProjectFilesCommand.Ls.render(files.list("acme"), "acme", true);
    assertTrue(json.contains("\"path\": \"a.txt\""));
    assertTrue(json.contains("\"bytes\": 1"));
  }

  @Test
  void lsIsCleanWhenEmpty() {
    assertTrue(ProjectFilesCommand.Ls.render(List.of(), "acme", false).contains("No shared files"));
  }

  @Test
  void catDecodesContentAndIsEmptyWhenAbsent() {
    files.put("acme", "a.txt", b64("payload"));

    assertArrayEquals(
        "payload".getBytes(), ProjectFilesCommand.Cat.read(files, "acme", "a.txt").orElseThrow());
    assertTrue(ProjectFilesCommand.Cat.read(files, "acme", "missing").isEmpty());
  }

  @Test
  void rmTombstonesAndRemovesTheLocalCopy() throws Exception {
    var source = tempDir.resolve("a.txt");
    Files.writeString(source, "data");
    ProjectFilesCommand.Add.store(files, "acme", source, "a.txt");
    new FileMaterializer(files, projectsDir).materialize("acme");
    assertTrue(Files.exists(filesDir("acme").resolve("a.txt")));

    var removed = ProjectFilesCommand.Rm.unshare(files, projectsDir, "acme", "a.txt");

    assertTrue(removed);
    assertTrue(files.find("acme", "a.txt").isEmpty());
    assertFalse(Files.exists(filesDir("acme").resolve("a.txt")));
  }

  @Test
  void rmReturnsFalseWhenTheFileWasNotShared() throws Exception {
    assertFalse(ProjectFilesCommand.Rm.unshare(files, projectsDir, "acme", "ghost.txt"));
  }

  @Test
  void exportWritesEveryTargetAndCountsDeletionsAndSkips() throws Exception {
    files.put("acme", "a.txt", b64("A"));
    var report = ProjectFilesCommand.Export.export(files, projectsDir, files.projectsWithFiles());
    assertEquals(1, report.written());

    Files.writeString(filesDir("acme").resolve("a.txt"), "LOCAL EDIT");
    files.put("acme", "a.txt", b64("A2"));
    var second = ProjectFilesCommand.Export.export(files, projectsDir, List.of("acme"));

    assertEquals(0, second.written());
    assertEquals(List.of("acme/a.txt"), second.skipped());
  }

  @Test
  void exportRejectsBothProjectAndAll() {
    var cmd = new CommandLine(new ProjectFilesCommand.Export());
    cmd.setErr(new java.io.PrintWriter(new java.io.StringWriter()));
    assertEquals(1, cmd.execute("acme", "--all"));
  }

  @Test
  void exportRejectsNeitherProjectNorAll() {
    var cmd = new CommandLine(new ProjectFilesCommand.Export());
    cmd.setErr(new java.io.PrintWriter(new java.io.StringWriter()));
    assertEquals(1, cmd.execute());
  }
}
