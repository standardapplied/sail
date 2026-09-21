/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContainerFileSourceTest {

  private static final String WORKSPACE = "/home/dev/workspace";

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
    var shell = new ScriptedShellExecutor().onOk("stat -c %s " + WORKSPACE + "/a.txt", "  2048\n");
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
            .onOk("stat -c %a", "750");
    try (var input = source(shell).open(Path.of(WORKSPACE, "a.txt"))) {
      assertArrayEquals("raw\u0000content".getBytes(), input.readAllBytes());
    }
    assertEquals(0750, source(shell).mode(Path.of(WORKSPACE, "a.txt")));
  }

  @Test
  void runSurfacesStderrAsIoExceptionOnFailure() {
    var shell = new ScriptedShellExecutor().onFail("stat", "stat: cannot stat");
    var ex = assertThrows(IOException.class, () -> source(shell).size(Path.of(WORKSPACE, "gone")));
    assertTrue(ex.getMessage().contains("acme"));
    assertTrue(ex.getMessage().contains("cannot stat"));
  }
}
