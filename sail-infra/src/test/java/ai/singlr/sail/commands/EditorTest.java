/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class EditorTest {

  @TempDir Path dir;

  @Test
  void anUnsetOrBlankEditorIsVi() {
    assertEquals("vi \"$1\"", Editor.script(null));
    assertEquals("vi \"$1\"", Editor.script("  "));
    assertEquals("code --wait \"$1\"", Editor.script(" code --wait "));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void anEditorWithArgumentsRunsOnTheFileAsItsLastArgumentEvenAcrossSpaces() throws Exception {
    var file =
        Files.writeString(Files.createDirectories(dir.resolve("my notes")).resolve("a b"), "");

    var exit = Editor.command("printf '%s|' --wait >").edit(file);

    assertEquals(0, exit);
    assertEquals("--wait|", Files.readString(file));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void theEditorsExitStatusIsAnswered() throws Exception {
    assertEquals(3, Editor.command("exit 3;").edit(dir.resolve("unused")));
  }
}
