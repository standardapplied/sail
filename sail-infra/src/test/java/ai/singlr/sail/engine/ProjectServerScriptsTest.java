/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The scripts {@code serve} sends into a container, run here for real against real files. */
@EnabledOnOs(OS.LINUX)
class ProjectServerScriptsTest {

  @TempDir Path dir;

  @Test
  void aFileIsWrittenOnceAndAgainOnlyWhenItsTextChangesTrailingNewlineIncluded() throws Exception {
    var file = dir.resolve("units/sail-process-web.service").toString();
    var shell = new Here();

    assertTrue(ProjectServer.writeOnce(shell, "acme", file, "[Unit]\nDescription=web\n"));
    assertFalse(ProjectServer.writeOnce(shell, "acme", file, "[Unit]\nDescription=web\n"));
    assertTrue(ProjectServer.writeOnce(shell, "acme", file, "[Unit]\nDescription=web"));
    assertEquals("[Unit]\nDescription=web", Files.readString(Path.of(file)));
  }

  @Test
  void theLineThatSaysWhyIsTheLastErrorSinceTheLastStartElseTheLastLine() throws Exception {
    var log = dir.resolve("web.log");
    Files.writeString(
        log,
        """
        sail: starting web
        Error: an old failure from the run before
        sail: starting web
        > next dev
        node:internal/modules/cjs/loader:1228
        Error: Cannot find module 'next'

        Node.js v24.14.1
        """);
    var shell = new Here();

    assertEquals(
        "Error: Cannot find module 'next'",
        ProjectServer.lastLine(shell, "acme", log.toString(), "sail: starting web"));

    Files.writeString(
        log,
        "sail: starting web\nError: from the run before\nsail: starting web\nlistening soon\nbye\n");
    assertEquals(
        "bye", ProjectServer.lastLine(shell, "acme", log.toString(), "sail: starting web"));
    assertEquals(
        "", ProjectServer.lastLine(shell, "acme", dir.resolve("none.log").toString(), "x"));
  }
}
