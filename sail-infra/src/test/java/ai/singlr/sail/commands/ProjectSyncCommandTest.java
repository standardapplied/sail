/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.engine.AgentContextInstaller;
import ai.singlr.sail.engine.ContainerSailSetup;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ProjectSyncCommandTest {

  private static AgentContextInstaller.Result context(List<String> pushed) {
    return new AgentContextInstaller.Result(pushed);
  }

  @Test
  void humanLineReportsBackfilledSetupAndRegeneratedContext() {
    var line =
        ProjectSyncCommand.humanLine(
            "web",
            ContainerSailSetup.Result.BACKFILLED,
            context(List.of("/home/dev/workspace/CLAUDE.md")));

    assertTrue(line.contains("web"));
    assertTrue(line.contains("backfilled"));
    assertTrue(line.contains("1 file regenerated"));
  }

  @Test
  void contextLabelPluralizesFileCount() {
    var label = ProjectSyncCommand.contextLabel(context(List.of("a", "b")));

    assertTrue(label.contains("2 files regenerated"));
  }

  @Test
  void contextLabelFlagsAMissingDescriptor() {
    assertTrue(ProjectSyncCommand.contextLabel(null).contains("no descriptor"));
  }

  @Test
  void contextLabelFlagsAProjectWithNoAgent() {
    assertTrue(ProjectSyncCommand.contextLabel(context(List.of())).contains("no agent"));
  }

  @Test
  void jsonRowCarriesSetupStateAndContextLists() {
    var row =
        ProjectSyncCommand.jsonRow(
            "web",
            ContainerSailSetup.Result.ALREADY_PRESENT,
            context(List.of("/home/dev/workspace/SECURITY.md")));

    assertEquals("web", row.get("project"));
    assertEquals("already_present", row.get("setup"));
    assertEquals(List.of("/home/dev/workspace/SECURITY.md"), row.get("context_pushed"));
  }

  @Test
  void jsonRowMarksAMissingDescriptor() {
    var row = ProjectSyncCommand.jsonRow("legacy", ContainerSailSetup.Result.BACKFILLED, null);

    assertEquals("no_descriptor", row.get("context"));
    assertFalse(row.containsKey("context_pushed"));
  }

  @Test
  void registeredUnderProject() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    cmd.execute("project", "--help");

    assertTrue(sw.toString().contains("sync"));
  }

  @Test
  void helpAdvertisesAllFlags() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exit = cmd.execute("project", "sync", "--help");

    assertEquals(0, exit);
    var help = sw.toString();
    assertTrue(help.contains("--all"));
    assertTrue(help.contains("--dry-run"));
    assertTrue(help.contains("--json"));
  }

  @Test
  void rejectsBareInvocationWithoutNameOrAll() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setErr(new PrintWriter(sw));

    var exit = cmd.execute("project", "sync");

    assertNotEquals(0, exit);
  }

  @Test
  void rejectsNameAndAllTogether() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setErr(new PrintWriter(sw));

    var exit = cmd.execute("project", "sync", "light-grid", "--all");

    assertNotEquals(0, exit);
  }
}
