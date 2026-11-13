/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.engine.WorkspaceTemplate;
import ai.singlr.sail.engine.Workspaces;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ProjectWorkspaceCommandTest {

  private static final WorkspaceTemplate.Template V1 =
      new WorkspaceTemplate.Template("acme", "acme--tmpl", "v1", "a1b2", "tank", true);

  @Test
  void aCreateSaysEachPhaseItTookInSeconds() {
    var created =
        new Workspaces.Created(
            "acme",
            "fix-login",
            "acme-fix-login",
            V1,
            new Workspaces.Timings(210L, 900, 300, 1410, Map.of()));

    assertEquals(
        "Created fix-login from v1 in 1.4 s (clone 0.2 s, start 0.9 s, ready 0.3 s)",
        ProjectWorkspaceCommand.createdLine(created));
  }

  @Test
  void eachLifecycleChangeSaysWhatItDid() {
    var started =
        new Workspaces.Changed(
            "acme",
            "fix-login",
            "acme-fix-login",
            Workspaces.Change.STARTED,
            new Workspaces.Timings(null, 350, 450, 800, Map.of()));

    assertEquals(
        "Started fix-login in 0.8 s (start 0.4 s, ready 0.5 s)",
        ProjectWorkspaceCommand.changedLine(started));
    assertEquals(
        List.of(
            "fix-login is already running; nothing was done.",
            "Stopped fix-login.",
            "fix-login is already stopped; nothing was done.",
            "Deleted fix-login (acme-fix-login)."),
        List.of(
                Workspaces.Change.ALREADY_RUNNING,
                Workspaces.Change.STOPPED,
                Workspaces.Change.ALREADY_STOPPED,
                Workspaces.Change.DELETED)
            .stream()
            .map(
                change ->
                    ProjectWorkspaceCommand.changedLine(
                        new Workspaces.Changed(
                            "acme", "fix-login", "acme-fix-login", change, null)))
            .toList());
  }

  @Test
  void theListIsATableWithItsDefaultWorkspaceFirst() {
    var listing =
        new Workspaces.Listing(
            "acme",
            List.of(
                new Workspaces.Row("main", "acme", "running", null, null, "10.0.0.2"),
                new Workspaces.Row(
                    "fix-login",
                    "acme-fix-login",
                    "frozen",
                    "v1",
                    "2026-11-12T10:00:00.000Z",
                    null)));

    assertEquals(
        List.of(
            "  NAME                     STATE       TEMPLATE  CREATED                  IPV4",
            "  main                     running     -         -                        10.0.0.2",
            "  fix-login                frozen      v1        2026-11-12T10:00:00.000Z -"),
        ProjectWorkspaceCommand.lines(listing));
  }

  @Test
  void aTemplateBuildSaysWhetherItBuiltAnything() {
    assertEquals("Built acme's template v1 on tank (acme--tmpl).", WorkspaceVerbs.templateLine(V1));
    assertEquals(
        "acme's template v1 is current: it was built from the definition as it is, so nothing was"
            + " done.",
        WorkspaceVerbs.templateLine(
            new WorkspaceTemplate.Template("acme", "acme--tmpl", "v1", "a1b2", "tank", false)));
  }

  @Test
  void aCreateRefusesJsonWithADryRunBeforeItReadsAnything() {
    var ran = SailCli.run("project", "workspace", "create", "acme", "w", "--json", "--dry-run");

    assertEquals(1, ran.exit());
    assertTrue(ran.err().contains("--json and --dry-run cannot be combined"), ran.err());
  }

  @Test
  void theVerbsAreProjectCommands() {
    var project = new CommandLine(new Sail()).getSubcommands().get("project");

    assertEquals(
        List.of("create", "list", "shell", "start", "stop", "delete"),
        List.copyOf(project.getSubcommands().get("workspace").getSubcommands().keySet()));
    assertEquals(
        List.of("build"),
        List.copyOf(project.getSubcommands().get("template").getSubcommands().keySet()));
  }

  @Test
  void aDeleteAsksNamingTheWorkspaceItsInstanceAndTheWorkItLoses() {
    assertEquals(
        "Delete workspace fix-login of acme (instance acme-fix-login) and everything in it, its"
            + " uncommitted work included? This cannot be undone.",
        ProjectWorkspaceCommand.deletePrompt("acme", "fix-login"));
  }

  @Test
  void aDeleteThroughJsonNeedsYes() {
    var named = new ProjectWorkspaceCommand.Target();
    named.project = "acme";
    named.name = "fix-login";
    named.json = true;

    assertEquals(
        "--json requires --yes (nobody answers a prompt through JSON).",
        assertThrows(CliCommand.Refused.class, () -> ProjectWorkspaceCommand.confirm(false, named))
            .getMessage());
    ProjectWorkspaceCommand.confirm(true, named);
  }
}
