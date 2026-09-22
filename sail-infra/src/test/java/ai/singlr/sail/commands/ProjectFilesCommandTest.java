/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.ProjectFiles;
import ai.singlr.sail.config.FileLimits;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.FileMaterializer;
import ai.singlr.sail.engine.FileSource;
import ai.singlr.sail.engine.HostFileSource;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.SharedProjectFiles;
import ai.singlr.sail.engine.WorkspaceFiles;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;

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

    var path = share(files, "acme", "scripts/deploy.sh", source);
    new FileMaterializer(files, projectsDir).materialize("acme");

    assertEquals("scripts/deploy.sh", path);
    assertEquals(
        b64("echo hi"),
        ai.singlr.sail.store.ContentFixtures.encoded(files, "acme", "scripts/deploy.sh"));
    assertEquals("echo hi", Files.readString(filesDir("acme").resolve("scripts/deploy.sh")));
  }

  @Test
  void addSymlinkPreservesTargetPermissions() throws Exception {
    var data = Files.createDirectories(tempDir.resolve("isolated"));
    var output = tempDir.resolve("add-output.txt");
    var builder =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + tempDir,
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty("java.class.path"),
                ProjectFilesCommandTest.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile());
    builder.environment().put("SAIL_DATA_DIR", data.toString());
    var process = builder.start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "isolated add timed out");
      assertEquals(0, process.exitValue(), Files.readString(output));
    } finally {
      process.destroyForcibly();
    }
  }

  public static void main(String[] args) throws Exception {
    var source =
        Files.writeString(Path.of(System.getProperty("user.home"), "restricted.txt"), "private");
    WorkspaceFiles.mode(source, 0600);
    var link = Files.createSymbolicLink(source.resolveSibling("linked.txt"), source);
    try (var database = Sqlite.open(SailPaths.controlPlaneDb())) {
      new SchemaManager(database).migrate();
      assertEquals(
          0, new CommandLine(new ProjectFilesCommand.Add()).execute("-p", "acme", link.toString()));
      assertEquals(0600, new FileStore(database).find("acme", "linked.txt").orElseThrow().mode());
      var copy = SailPaths.projectsDir().resolve("acme/files/linked.txt");
      assertEquals("private", Files.readString(copy));
      assertEquals(0600, WorkspaceFiles.mode(copy));
      assertEquals(0600, WorkspaceFiles.mode(source));
      var big = source.resolveSibling("big.bin");
      try (var raf = new java.io.RandomAccessFile(big.toFile(), "rw")) {
        raf.setLength(FileLimits.DEFAULT_MAX + 1);
      }
      assertEquals(
          1, new CommandLine(new ProjectFilesCommand.Add()).execute("-p", "acme", big.toString()));
      assertTrue(new FileStore(database).find("acme", "big.bin").isEmpty());
    }
  }

  @Test
  void storeUsesTheGivenRelativePath() throws Exception {
    var source = tempDir.resolve("notes.md");
    Files.writeString(source, "hello");

    var path = share(files, "acme", "docs/notes.md", source);

    assertEquals("docs/notes.md", path);
    assertTrue(files.find("acme", "docs/notes.md").isPresent());
  }

  @Test
  void addRejectsAPathThatEscapesTheProject() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> share(files, "acme", "../escape", tempDir.resolve("x")));
  }

  @Test
  void shareProblemAcceptsRealFilesAndFlagsTheRest() throws Exception {
    var host = new HostFileSource();
    var limits = FileLimits.defaults();
    var ok = tempDir.resolve("ok.txt");
    Files.writeString(ok, "hi");
    assertNull(ProjectFilesCommand.Add.shareProblem(host, ok, "ok.txt", limits));

    assertEquals(
        "unsafe path", ProjectFilesCommand.Add.shareProblem(host, ok, "../escape", limits));
    assertEquals(
        "unreadable",
        ProjectFilesCommand.Add.shareProblem(host, tempDir.resolve("gone"), "gone", limits));
  }

  @Test
  void shareProblemRejectsAnOversizedFileNamingTheCapAndWhereToRaiseIt() throws Exception {
    var big = tempDir.resolve("big.bin");
    Files.writeString(big, "12345");

    var problem =
        ProjectFilesCommand.Add.shareProblem(
            new HostFileSource(), big, "big.bin", new FileLimits(4));

    assertTrue(problem.contains("5 bytes"), problem);
    assertTrue(problem.contains("limits.file_max (4 bytes)"), problem);
    assertTrue(problem.contains("host.yaml"), problem);
  }

  @Test
  void aBulkShareConsultsTheCapOnceAndSkipsWhatItCannotShare() throws Exception {
    var root = Files.createDirectory(tempDir.resolve("root"));
    var selected = new java.util.ArrayList<Path>();
    for (var name : List.of("a.txt", "b.txt", "c.txt")) {
      selected.add(Files.writeString(root.resolve(name), name));
    }
    selected.add(Files.writeString(root.resolve("big.txt"), "too large"));
    var loads = new java.util.concurrent.atomic.AtomicInteger();
    var shared =
        capped(
            new SharedProjectFiles(files, projectsDir, "acme", new FileLimits(5)),
            () -> {
              loads.incrementAndGet();
              return new FileLimits(5);
            });

    var report = ProjectFilesCommand.Add.share(shared, new HostFileSource(), root, selected);

    assertEquals(1, loads.get());
    assertEquals(3, report.count());
    assertEquals(1, report.skipped().size());
    assertTrue(report.skipped().getFirst().startsWith("big.txt (File of 9 bytes"));
    assertEquals(3, files.list("acme").size());
    assertTrue(Files.exists(filesDir("acme").resolve("a.txt")));
  }

  @Test
  void aBulkShareWithACorruptCapFailsBeforeAnyFileIsTouched() throws Exception {
    var root = Files.createDirectory(tempDir.resolve("root"));
    var selected = List.of(Files.writeString(root.resolve("a.txt"), "a"));
    var corrupt =
        capped(
            new SharedProjectFiles(files, projectsDir, "acme", FileLimits.defaults()),
            () -> {
              throw new IllegalArgumentException(
                  "limits.file_max must be an integer number of bytes");
            });

    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProjectFilesCommand.Add.share(corrupt, untouchable(), root, selected));

    assertTrue(failure.getMessage().contains("limits.file_max"));
    assertTrue(files.list("acme").isEmpty());
  }

  private static ProjectFiles capped(ProjectFiles delegate, Supplier<FileLimits> limits) {
    return new ProjectFiles() {
      @Override
      public FileLimits limits() {
        return limits.get();
      }

      @Override
      public List<FileStore.FileRow> list() {
        return delegate.list();
      }

      @Override
      public Optional<FileStore.FileRow> find(String path) {
        return delegate.find(path);
      }

      @Override
      public InputStream open(FileStore.FileRow row) {
        return delegate.open(row);
      }

      @Override
      public String put(String path, InputStream bytes, long size, int mode) {
        return delegate.put(path, bytes, size, mode);
      }

      @Override
      public boolean remove(String path) throws IOException {
        return delegate.remove(path);
      }

      @Override
      public FileMaterializer.Report materialize() throws IOException {
        return delegate.materialize();
      }
    };
  }

  private static FileSource untouchable() {
    return new FileSource() {
      @Override
      public List<ai.singlr.sail.engine.FilePicker.Entry> children(Path dir) {
        throw new AssertionError("a file was touched");
      }

      @Override
      public boolean isDirectory(Path path) {
        throw new AssertionError("a file was touched");
      }

      @Override
      public long size(Path file) {
        throw new AssertionError("a file was touched");
      }

      @Override
      public List<Path> walkFiles(Path dir) {
        throw new AssertionError("a file was touched");
      }

      @Override
      public InputStream open(Path file) {
        throw new AssertionError("a file was touched");
      }

      @Override
      public int mode(Path file) {
        throw new AssertionError("a file was touched");
      }
    };
  }

  @Test
  void addReportsExitOneWhenTheSourceIsMissing() {
    var cmd = new CommandLine(new ProjectFilesCommand.Add());
    cmd.setErr(new java.io.PrintWriter(new java.io.StringWriter()));
    assertEquals(1, cmd.execute("-p", "acme", tempDir.resolve("nope").toString()));
  }

  @Test
  void lsRendersHumanTableAndJson() {
    ai.singlr.sail.store.ContentFixtures.put(files, "acme", "a.txt", "AAAA");
    ai.singlr.sail.store.ContentFixtures.put(files, "acme", "b.txt", "B");

    var captured = new ByteArrayOutputStream();
    Banner.printProjectFilesTable(
        files.list("acme"),
        "acme",
        new PrintStream(captured, true, StandardCharsets.UTF_8),
        Ansi.OFF);
    var human = captured.toString(StandardCharsets.UTF_8);
    assertTrue(human.contains("PATH"));
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
  void catStreamsContentAndIsEmptyWhenAbsent() throws Exception {
    ai.singlr.sail.store.ContentFixtures.put(files, "acme", "a.txt", "payload");

    assertArrayEquals(
        "payload".getBytes(),
        ProjectFilesCommand.Cat.read(files, "acme", "a.txt").orElseThrow().readAllBytes());
    assertTrue(ProjectFilesCommand.Cat.read(files, "acme", "missing").isEmpty());
  }

  @Test
  void rmTombstonesAndRemovesTheLocalCopy() throws Exception {
    var source = tempDir.resolve("a.txt");
    Files.writeString(source, "data");
    share(files, "acme", "a.txt", source);
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
    ai.singlr.sail.store.ContentFixtures.put(files, "acme", "a.txt", "A");
    var report = ProjectFilesCommand.Export.export(files, projectsDir, files.projectsWithFiles());
    assertEquals(1, report.written());

    Files.writeString(filesDir("acme").resolve("a.txt"), "LOCAL EDIT");
    ai.singlr.sail.store.ContentFixtures.put(files, "acme", "a.txt", "A2");
    var second = ProjectFilesCommand.Export.export(files, projectsDir, List.of("acme"));

    assertEquals(0, second.written());
    assertEquals(List.of("acme/a.txt"), second.skipped());
  }

  @Test
  void exportRejectsBothProjectAndAll() {
    var cmd = new CommandLine(new ProjectFilesCommand.Export());
    cmd.setErr(new java.io.PrintWriter(new java.io.StringWriter()));
    assertEquals(1, cmd.execute("-p", "acme", "--all"));
  }

  private String share(FileStore files, String project, String path, Path source)
      throws IOException {
    if (!Files.exists(source)) Files.writeString(source, "x");
    try (var input = Files.newInputStream(source)) {
      return new SharedProjectFiles(files, projectsDir, project, FileLimits.defaults())
          .put(path, input, Files.size(source), 0644);
    }
  }
}
