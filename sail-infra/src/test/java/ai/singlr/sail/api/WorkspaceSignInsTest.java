/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Whether a workspace holds a harness's own sign-in: its file is a regular file and not empty, as
 * {@code test -f} and {@code test -s} stat it, never opened; read as the dev user in one command
 * for every harness, and nothing said when the workspace does not answer.
 */
class WorkspaceSignInsTest {

  private static final List<String> FILES =
      List.of(".claude/.credentials.json", ".codex/auth.json");

  @TempDir Path home;

  /** {@link WorkspaceSignIns#HELD} run here by {@code sh}, {@code home} its {@code $HOME}. */
  private String heldHere(List<String> files) throws Exception {
    var command = new ArrayList<>(List.of("sh", "-c", WorkspaceSignIns.HELD, "sail-sign-ins"));
    command.addAll(files);
    var process = new ProcessBuilder(command).redirectErrorStream(true);
    process.environment().put("HOME", home.toString());
    var started = process.start();
    started.getOutputStream().close();
    if (!started.waitFor(30, TimeUnit.SECONDS)) {
      started.destroyForcibly();
      throw new AssertionError("the read did not end");
    }
    var said = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, started.exitValue(), said);
    return said;
  }

  private void write(String file, String content) throws IOException {
    var path = home.resolve(file);
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
  }

  /**
   * A file that is there and not empty is held; an empty one, a missing one, a directory at its
   * path and Claude Code's own state beside its sign-in are not; a file its reader may not open is
   * held all the same, since nothing opens it.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void aSignInIsHeldWhenItsFileIsThereAndNotEmptyWhetherOrNotItCouldBeRead() throws Exception {
    assumeFalse("root".equals(System.getProperty("user.name")), "root opens a file of mode 000");
    write(".claude.json", "{\"oauthAccount\": {}}");
    write(".codex/auth.json", "");
    Files.createDirectories(home.resolve(".claude/.credentials.json/x"));

    assertEquals("", heldHere(FILES), "nothing is held: Claude Code's state is not its sign-in");

    Files.delete(home.resolve(".claude/.credentials.json/x"));
    Files.delete(home.resolve(".claude/.credentials.json"));

    write(".claude/.credentials.json", "{}");
    write(".codex/auth.json", "{}");
    Files.setPosixFilePermissions(
        home.resolve(".claude/.credentials.json"), PosixFilePermissions.fromString("---------"));

    assertEquals(".claude/.credentials.json\n.codex/auth.json\n", heldHere(FILES));
  }

  /**
   * A sign-in's path that is a link to a file in the home that is there and not empty is held, as
   * the harness reading through it would find it; a link to nothing is not.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void aLinkToAFileThatIsThereAndNotEmptyIsHeldAndALinkToNothingIsNot() throws Exception {
    write(".config/claude/credentials.json", "{}");
    Files.createDirectories(home.resolve(".claude"));
    Files.createDirectories(home.resolve(".codex"));
    Files.createSymbolicLink(
        home.resolve(".claude/.credentials.json"), Path.of("../.config/claude/credentials.json"));
    Files.createSymbolicLink(
        home.resolve(".codex/auth.json"), Path.of("../.config/codex/auth.json"));

    assertEquals(".claude/.credentials.json\n", heldHere(FILES));
  }

  /**
   * The read waits on its workspace for {@link WorkspaceSignIns#TIMEOUT}, never Incus's own
   * default, and a workspace that does not answer in it says nothing.
   */
  @Test
  void aWorkspaceThatDoesNotAnswerInTheBoundSaysNothing() throws Exception {
    var bounds = new ArrayList<Duration>();
    var hanging =
        new ShellExec() {
          @Override
          public Result exec(List<String> command) {
            throw new AssertionError("the read is unbounded: " + command);
          }

          @Override
          public Result exec(List<String> command, Path workDir, Duration timeout)
              throws InterruptedException, TimeoutException {
            bounds.add(timeout);
            return ScriptedShellExecutor.hang(command, timeout);
          }

          @Override
          public boolean isDryRun() {
            return false;
          }
        };

    assertEquals(Optional.empty(), new WorkspaceSignIns(hanging).held("acme-try", FILES));
    assertEquals(List.of(WorkspaceSignIns.TIMEOUT), bounds);
  }

  /** Asked of no file, as for a project that installs no harness, nothing runs. */
  @Test
  void askedOfNoFileNothingRuns() throws Exception {
    var shell = new ScriptedShellExecutor();

    assertEquals(Optional.of(Set.of()), new WorkspaceSignIns(shell).held("acme-try", List.of()));
    assertEquals(List.of(), shell.invocations());
  }

  /**
   * The read is one command as the dev user in the running workspace, naming each file; its answer
   * is taken only for the files asked, and a workspace that fails or does not answer says nothing.
   */
  @Test
  void theReadIsOneCommandAsTheDevUserAndAFailedOneSaysNothing() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus exec acme-try ", ".codex/auth.json\n.claude.json\n")
            .onFail("incus exec acme-off ", "Error: Instance is not running")
            .onThrow("incus exec acme-gone ", new IOException("incus did not answer"));
    var signIns = new WorkspaceSignIns(shell);

    assertEquals(Optional.of(Set.of(".codex/auth.json")), signIns.held("acme-try", FILES));
    assertEquals(
        List.of(
            ContainerExec.asDevUser(
                "acme-try",
                List.of(
                    "sh",
                    "-c",
                    WorkspaceSignIns.HELD,
                    "sail-sign-ins",
                    ".claude/.credentials.json",
                    ".codex/auth.json"))),
        shell.arguments());
    assertEquals(Optional.empty(), signIns.held("acme-off", FILES));
    assertEquals(Optional.empty(), signIns.held("acme-gone", FILES));
  }
}
