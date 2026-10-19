/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectTeardownTest {

  @TempDir Path projectsDir;

  private Path project(String name) throws IOException {
    var files = Files.createDirectories(projectsDir.resolve(name).resolve("files").resolve("src"));
    Files.writeString(files.resolve("Main.java"), "class Main {}");
    Files.writeString(projectsDir.resolve(name).resolve("sail.yaml"), "name: " + name + "\n");
    return projectsDir.resolve(name);
  }

  @Test
  void itDeletesThePresentContainerAndThenTheWholeDirectory() throws Exception {
    var acme = project("acme");
    var beta = project("beta");
    var shell = new ScriptedShellExecutor().onOk("incus delete acme --force");

    new ProjectTeardown(shell, projectsDir).tearDown("acme", true);

    assertEquals(List.of("incus delete acme --force"), shell.invocations());
    assertFalse(Files.exists(acme));
    assertTrue(Files.isDirectory(beta), "another project's directory stays");
  }

  @Test
  void anAbsentContainerIsNeverAskedOfIncusAndAnAbsentDirectoryIsNothingToDo() throws Exception {
    var shell = new ScriptedShellExecutor();
    var acme = project("acme");
    var teardown = new ProjectTeardown(shell, projectsDir);

    teardown.tearDown("acme", false);
    teardown.tearDown("acme", false);

    assertEquals(List.of(), shell.invocations());
    assertFalse(Files.exists(acme));
  }

  @Test
  void aContainerIncusWillNotDeleteKeepsTheDirectory() throws Exception {
    var acme = project("acme");
    var shell = new ScriptedShellExecutor().onFail("incus delete acme", "in use");

    var failure =
        assertThrows(
            IOException.class,
            () -> new ProjectTeardown(shell, projectsDir).tearDown("acme", true));

    assertEquals("Failed to delete container 'acme': in use", failure.getMessage());
    assertTrue(Files.isDirectory(acme));
  }

  @Test
  void aDryRunPrintsWhatItWouldDeleteAndDeletesNothing() throws Exception {
    var acme = project("acme");
    var out = new ByteArrayOutputStream();
    var original = System.out;
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    try {
      new ProjectTeardown(new ShellExecutor(true), projectsDir).tearDown("acme", true);
    } finally {
      System.setOut(original);
    }

    assertEquals(
        "[dry-run] incus delete acme --force\n[dry-run] rm -rf " + acme + "\n",
        out.toString(StandardCharsets.UTF_8));
    assertTrue(Files.isDirectory(acme));
  }

  @Test
  void aNameThatIsNoProjectsNeverReachesIncusOrTheDisk() {
    var shell = new ScriptedShellExecutor();

    assertThrows(
        IllegalArgumentException.class,
        () -> new ProjectTeardown(shell, projectsDir).tearDown("..", true));
    assertEquals(List.of(), shell.invocations());
    assertTrue(Files.isDirectory(projectsDir));
  }
}
