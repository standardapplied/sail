/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerminalFilePickerTest {

  private static final Path ROOT = Path.of("/ws");

  private static final FileSource TREE =
      new MapSource(
          Map.of(
              ROOT,
              List.of(
                  new FilePicker.Entry(ROOT.resolve("a.txt"), false, 3),
                  new FilePicker.Entry(ROOT.resolve("src"), true, 0)),
              ROOT.resolve("src"),
              List.of(new FilePicker.Entry(ROOT.resolve("src/b.txt"), false, 5))));

  private TerminalFilePicker picker(int... keys) {
    var bytes = new byte[keys.length];
    for (var i = 0; i < keys.length; i++) {
      bytes[i] = (byte) keys[i];
    }
    return new TerminalFilePicker(
        TREE, ROOT, new ByteArrayInputStream(bytes), new PrintStream(new ByteArrayOutputStream()));
  }

  @Test
  void checkingAFileThenConfirmingReturnsIt() throws IOException {
    var result = picker(' ', 's').drive();
    assertTrue(result.isPresent());
    assertEquals(List.of(ROOT.resolve("a.txt")), List.copyOf(result.orElseThrow()));
  }

  @Test
  void quittingReturnsEmpty() throws IOException {
    assertTrue(picker('q').drive().isEmpty());
  }

  @Test
  void closedInputCancelsInsteadOfSpinning() throws IOException {
    assertTrue(picker().drive().isEmpty());
  }

  @Test
  void arrowsNavigateIntoAFolderAndBackOut() throws IOException {
    var result = picker(27, '[', 'B', 27, '[', 'C', ' ', 27, '[', 'D', 's').drive();
    assertTrue(result.isPresent());
    assertEquals(List.of(ROOT.resolve("src/b.txt")), List.copyOf(result.orElseThrow()));
  }

  @Test
  void redrawEmitsCursorAndClearSequences() throws IOException {
    var captured = new ByteArrayOutputStream();
    var p =
        new TerminalFilePicker(
            TREE,
            ROOT,
            new ByteArrayInputStream(new byte[] {'q'}),
            new PrintStream(captured, true, StandardCharsets.UTF_8));
    p.drive();
    var esc = (char) 27;
    assertTrue(captured.toString(StandardCharsets.UTF_8).contains(esc + "[0J"));
  }

  @Test
  void notAvailableWithoutAnInteractiveConsole() {
    assertFalse(TerminalFilePicker.isAvailable());
  }

  private record MapSource(Map<Path, List<FilePicker.Entry>> tree) implements FileSource {
    @Override
    public List<FilePicker.Entry> children(Path dir) {
      return tree.getOrDefault(dir, List.of());
    }

    @Override
    public boolean isDirectory(Path path) {
      return tree.containsKey(path);
    }

    @Override
    public long size(Path file) {
      return 0;
    }

    @Override
    public List<Path> walkFiles(Path dir) {
      return List.of();
    }

    @Override
    public byte[] read(Path file) {
      return new byte[0];
    }
  }
}
