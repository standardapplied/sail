/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.sync.SyncBox;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContainerFileSourceTest {

  private static final String WORKSPACE = "/home/dev/workspace";

  @TempDir Path tempDir;

  private ContainerFileSource source(ScriptedShellExecutor shell) {
    return new ContainerFileSource(shell, "acme");
  }

  @Test
  void childrenParsesTabSeparatedFindOutputIncludingNamesWithSpaces() throws Exception {
    var shell =
        new ScriptedShellExecutor().onOk("find " + WORKSPACE, "d\t4096\tsrc\nf\t12\tMy Notes.md\n");

    var entries = source(shell).children(Path.of(WORKSPACE));

    assertEquals(2, entries.size());
    var dir = entries.get(0);
    assertEquals(Path.of(WORKSPACE, "src"), dir.path());
    assertTrue(dir.directory());
    var file = entries.get(1);
    assertEquals(Path.of(WORKSPACE, "My Notes.md"), file.path());
    assertFalse(file.directory());
    assertEquals(12, file.size());
  }

  @Test
  void childrenSkipsBlankAndMalformedLines() throws Exception {
    var shell =
        new ScriptedShellExecutor().onOk("find " + WORKSPACE, "\nf\t10\tok.txt\ngarbage\n\n");

    var entries = source(shell).children(Path.of(WORKSPACE));

    assertEquals(1, entries.size());
    assertEquals(Path.of(WORKSPACE, "ok.txt"), entries.getFirst().path());
  }

  @Test
  void childrenToleratesUnparseableSizeAsZero() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("find " + WORKSPACE, "f\tNaN\tweird.bin\n");

    var entries = source(shell).children(Path.of(WORKSPACE));

    assertEquals(0, entries.getFirst().size());
  }

  @Test
  void isDirectoryReflectsTestExitCode() throws Exception {
    var dirShell = new ScriptedShellExecutor().onOk("test -d " + WORKSPACE + "/src");
    assertTrue(source(dirShell).isDirectory(Path.of(WORKSPACE, "src")));

    var fileShell = new ScriptedShellExecutor().onFail("test -d " + WORKSPACE + "/a.txt", "");
    assertFalse(source(fileShell).isDirectory(Path.of(WORKSPACE, "a.txt")));
  }

  @Test
  void sizeParsesStatOutput() throws Exception {
    var shell =
        new ScriptedShellExecutor().onOk("stat -L -c %s -- " + WORKSPACE + "/a.txt", "  2048\n");
    assertEquals(2048, source(shell).size(Path.of(WORKSPACE, "a.txt")));
  }

  @Test
  void walkFilesPrunesIgnoredDirectoriesAndReturnsEachPath() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("find " + WORKSPACE, WORKSPACE + "/a.txt\n" + WORKSPACE + "/src/b.txt\n");

    var files = source(shell).walkFiles(Path.of(WORKSPACE));

    assertEquals(List.of(Path.of(WORKSPACE, "a.txt"), Path.of(WORKSPACE, "src/b.txt")), files);
    var invoked = shell.invocations().getFirst();
    assertTrue(invoked.contains("-prune"));
    for (var junk : FilePicker.IGNORED_DIRS) {
      assertTrue(invoked.contains("-name " + junk), "should prune " + junk);
    }
  }

  @Test
  void readsRawBytesFromCatAndCapturesMode() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("cat -- " + WORKSPACE + "/a.txt", "raw\u0000content")
            .onOk("stat -L -c %a", "750");
    try (var input = source(shell).open(Path.of(WORKSPACE, "a.txt"))) {
      assertArrayEquals("raw\u0000content".getBytes(), input.readAllBytes());
    }
    assertEquals(0750, source(shell).mode(Path.of(WORKSPACE, "a.txt")));
  }

  @ParameterizedTest
  @ValueSource(ints = {0600, 0750})
  void pickedSymlinkPreservesTargetPermissionsAcrossSync(int mode) throws Exception {
    var target = Files.writeString(tempDir.resolve("restricted target.txt"), "private");
    WorkspaceFiles.mode(target, mode);
    var workspace = Files.createDirectory(tempDir.resolve("workspace"));
    var link =
        Files.createSymbolicLink(
            workspace.resolve("linked file.txt"), workspace.relativize(target));
    var source = new ContainerFileSource(new LocalContainerShell(), "acme");
    var picked =
        FilePicker.step(FilePicker.State.at(workspace), FilePicker.list(source, workspace), "1");
    assertEquals(List.of(link), FilePicker.selectedFiles(source, picked.state()));
    assertEquals(Files.size(target), source.size(link), "the size is the target's, not the link's");

    try (var main = new SyncBox(tempDir, "main");
        var node = new SyncBox(tempDir, "node")) {
      var files = new FileStore(main.db);
      var shared =
          new SharedProjectFiles(
              files,
              tempDir.resolve("main-projects"),
              "acme",
              ai.singlr.sail.config.FileLimits.defaults());
      var path = workspace.relativize(link).toString();
      try (var input = source.open(link)) {
        shared.put(path, input, source.size(link), source.mode(link));
      }
      SyncBox.round(main.db, node.db, "file");
      var synced = new FileStore(node.db);
      var projects = tempDir.resolve("node-projects");
      var report = new FileMaterializer(synced, projects).materialize("acme");
      var copy = projects.resolve("acme/files").resolve(path);

      assertAll(
          () -> assertEquals(mode, source.mode(link)),
          () -> assertEquals(mode, files.find("acme", path).orElseThrow().mode()),
          () -> assertEquals(mode, synced.find("acme", path).orElseThrow().mode()),
          () -> assertEquals(mode, WorkspaceFiles.mode(copy)),
          () -> assertEquals("private", Files.readString(copy)),
          () -> assertEquals(1, report.written()),
          () -> assertTrue(report.skipped().isEmpty()),
          () -> assertFalse(Files.isSymbolicLink(copy)),
          () -> assertEquals(mode, WorkspaceFiles.mode(target)));
    }
  }

  @Test
  void refusesPermissionsForADanglingSymlink() throws Exception {
    var link = Files.createSymbolicLink(tempDir.resolve("dangling"), Path.of("missing"));
    var source = new ContainerFileSource(new LocalContainerShell(), "acme");

    assertThrows(IOException.class, () -> source.mode(link));
  }

  @Test
  void runSurfacesStderrAsIoExceptionOnFailure() {
    var shell = new ScriptedShellExecutor().onFail("stat", "stat: cannot stat");
    var ex = assertThrows(IOException.class, () -> source(shell).size(Path.of(WORKSPACE, "gone")));
    assertTrue(ex.getMessage().contains("acme"));
    assertTrue(ex.getMessage().contains("cannot stat"));
  }

  private record LocalContainerShell(ShellExecutor delegate) implements ShellExec {
    LocalContainerShell() {
      this(new ShellExecutor(false, Duration.ofSeconds(10)));
    }

    @Override
    public Result exec(List<String> command)
        throws IOException, InterruptedException, TimeoutException {
      return delegate.exec(arguments(command));
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout)
        throws IOException, InterruptedException, TimeoutException {
      return delegate.exec(arguments(command), workDir, timeout);
    }

    @Override
    public InputStream stream(List<String> command) throws IOException {
      return delegate.stream(arguments(command));
    }

    @Override
    public boolean isDryRun() {
      return false;
    }

    private static List<String> arguments(List<String> command) {
      assertEquals(List.of("incus", "exec", "acme"), command.subList(0, 3));
      var separator = command.indexOf("--");
      assertTrue(separator >= 0);
      return command.subList(separator + 1, command.size());
    }
  }
}
