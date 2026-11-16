/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.engine.ProjectApplier;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** What {@code sail project repo add} writes into the project's container. */
class ProjectAddRepoCommandTest {

  private static final String STORE = "acme/home/dev/.git-credentials.sail-new";

  /**
   * Adding a repo on another host writes the store an apply writes: the person's token for every
   * host the project's repos name, so the earlier repo's host keeps its own, and no host the
   * project does not name gets one.
   */
  @Test
  void addingARepoOnAnotherHostKeepsTheEarlierHostsTokenAndAddsNoOther() throws Exception {
    var shell =
        new Pushing(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onOk("test -d /home/dev/workspace/api")
                .onOk("rev-parse --is-inside-work-tree --show-prefix", "true\n\n")
                .onOk(
                    "git -C /home/dev/workspace/api remote get-url origin",
                    "https://github.com/acme/api.git\n")
                .onFail("test -d /home/dev/workspace/web", "not found"));
    var stored =
        new WorkspaceVerbs.Keeper(
            Optional.of("eng"),
            Map.of(
                "github.com", "ghp_token_for_github",
                "git.example.com", "tok_for_git_example",
                "gitlab.com", "glpat_token_for_gitlab"),
            url -> true,
            Map.of());

    var result =
        ProjectAddRepoCommand.cloned(
            new ProjectApplier(shell, new PrintStream(OutputStream.nullOutputStream())),
            "acme",
            acme(),
            stored);

    assertEquals(
        "https://oauth2:ghp_token_for_github@github.com\n"
            + "https://oauth2:tok_for_git_example@git.example.com\n",
        shell.pushed.get(STORE));
    assertEquals(1, result.added(), "only the added repo is cloned");
    var logins =
        shell.shell.invocations().stream().filter(command -> command.contains("gh auth login"));
    assertEquals(
        List.of(true),
        logins.map(command -> command.contains(" sh github.com ")).toList(),
        "gh is logged in once, for the GitHub host alone");
  }

  /** A repo whose host asks for a token none is stored for is refused before anything is run. */
  @Test
  void aRepoWhoseHostAsksForATokenNoneIsStoredForIsRefusedBeforeAnythingRuns() {
    var shell = new Pushing(new ScriptedShellExecutor());

    var refused =
        assertThrows(
            ApiException.class,
            () ->
                ProjectAddRepoCommand.cloned(
                    new ProjectApplier(shell, new PrintStream(OutputStream.nullOutputStream())),
                    "acme",
                    acme(),
                    new WorkspaceVerbs.Keeper(
                        Optional.of("eng"),
                        Map.of("github.com", "ghp_token_for_github"),
                        url -> true,
                        Map.of())));

    assertEquals(
        "git.example.com asks for credentials to fetch acme's repo web, and eng keeps no token for"
            + " it.",
        refused.getMessage());
    assertEquals(List.of(), shell.shell.invocations());
  }

  private static ProjectDefinition acme() {
    return new ProjectDefinition(
        "acme",
        null,
        null,
        null,
        null,
        null,
        List.of(
            new ProjectDefinition.Repo("https://github.com/acme/api.git", "api", null),
            new ProjectDefinition.Repo("https://git.example.com/acme/web.git", "web", null)),
        null,
        null,
        null,
        null);
  }

  /** A shell that keeps what each push carried, read while its source file is still there. */
  private static final class Pushing implements ShellExec {
    private final ScriptedShellExecutor shell;
    private final Map<String, String> pushed = new LinkedHashMap<>();

    Pushing(ScriptedShellExecutor shell) {
      this.shell = shell;
    }

    @Override
    public Result exec(List<String> command) throws IOException {
      keep(command);
      return shell.exec(command);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) throws IOException {
      keep(command);
      return shell.exec(command, workDir, timeout);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }

    private void keep(List<String> command) throws IOException {
      if (command.size() > 3 && command.subList(0, 3).equals(List.of("incus", "file", "push"))) {
        pushed.put(command.getLast(), Files.readString(Path.of(command.get(command.size() - 2))));
      }
    }
  }
}
