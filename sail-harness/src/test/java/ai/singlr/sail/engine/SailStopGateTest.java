/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Executes the real gate script under a fake {@code $HOME} layout — temp workspace repos, a
 * recording {@code sail-event.sh} stub — because the gate's contract (block JSON on stdout, one
 * nudge per run, fail open) lives in shell behavior, not in the Java that installs it.
 */
class SailStopGateTest {

  private static final String STOP_INPUT =
      "{\"session_id\":\"s\",\"hook_event_name\":\"Stop\",\"stop_hook_active\":false}";

  private static final String CODEX_STOP_INPUT =
      """
      {"session_id":"019f5813-4a30-7c73-ae8d-77a...","turn_id":"019f5813-4ab1-7292-8f37-97f...",\
      "transcript_path":"/home/dev/.codex/sessions/2026/07/12/rollout.jsonl",\
      "cwd":"/home/dev/workspace","hook_event_name":"Stop","model":"gpt-5.6-sol",\
      "permission_mode":"bypassPermissions","stop_hook_active":false,\
      "last_assistant_message":"done"}""";

  private static final String RUN_ID = "run-1";

  @TempDir Path home;

  private Path gate;

  record GateResult(int exitCode, String stdout, String stderr) {}

  @BeforeEach
  void installGateUnderFakeHome() throws Exception {
    gate = home.resolve("sail-stop-gate");
    writeExecutable(gate, SailStopGate.scriptContent());
    var helper = home.resolve(".sail/bin/sail-event.sh");
    Files.createDirectories(helper.getParent());
    writeExecutable(helper, "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$HOME/events.log\"\n");
  }

  @Test
  void aDirtyRepoBlocksWithAReasonNamingTheRepo() throws Exception {
    var repo = repo("sail");
    Files.writeString(repo.resolve("wip.txt"), "uncommitted");

    var result = runGate(STOP_INPUT, RUN_ID);

    assertEquals(0, result.exitCode(), result.stderr());
    var reason = blockReason(result);
    assertTrue(reason.contains("commit your work in sail"), reason);
    assertTrue(Files.exists(marker()), "the first block must drop the one-nudge-per-run marker");
  }

  @Test
  void aBlockPublishesNudgedAndNotStopped() throws Exception {
    var repo = repo("sail");
    Files.writeString(repo.resolve("wip.txt"), "uncommitted");

    runGate(STOP_INPUT, RUN_ID);

    var events = events();
    assertEquals(1, events.size(), events.toString());
    assertTrue(events.get(0).startsWith("agent_stop_nudged "), events.get(0));
    assertTrue(events.get(0).contains("commit your work in sail"), events.get(0));
    assertFalse(
        events.get(0).contains("agent_session_stopped"),
        "a blocked stop never happened, so it must not be announced");
  }

  @Test
  void aBranchAheadOfItsUpstreamBlocks() throws Exception {
    var repo = repo("sail");
    pushToFreshOrigin(repo, "main");
    Files.writeString(repo.resolve("more.txt"), "ahead");
    git(repo, "add", ".");
    git(repo, "commit", "-q", "-m", "ahead");

    var reason = blockReason(runGate(STOP_INPUT, RUN_ID));

    assertTrue(reason.contains("push main in sail"), reason);
    assertTrue(reason.contains("1 commits ahead of origin/main"), reason);
  }

  @Test
  void aBranchWithoutUpstreamBlocks() throws Exception {
    repo("sail");

    var reason = blockReason(runGate(STOP_INPUT, RUN_ID));

    assertTrue(reason.contains("push main in sail"), reason);
    assertTrue(reason.contains("no upstream"), reason);
  }

  @Test
  void aCleanPushedWorkspaceAllowsAndPublishesStopped() throws Exception {
    pushToFreshOrigin(repo("sail"), "main");

    var result = runGate(STOP_INPUT, RUN_ID);

    assertEquals(0, result.exitCode(), result.stderr());
    assertEquals("", result.stdout(), "an allowed stop must write nothing to stdout");
    assertEquals(List.of("agent_session_stopped"), events());
  }

  @Test
  void theSecondStopAfterANudgeAlwaysAllows() throws Exception {
    var repo = repo("sail");
    Files.writeString(repo.resolve("wip.txt"), "still dirty");

    var first = runGate(STOP_INPUT, RUN_ID);
    var second = runGate(STOP_INPUT, RUN_ID);

    assertFalse(first.stdout().isBlank(), "first stop must block");
    assertEquals("", second.stdout(), "the second stop always wins, even with a dirty worktree");
    var events = events();
    assertEquals(2, events.size(), events.toString());
    assertTrue(events.get(0).startsWith("agent_stop_nudged "), events.get(0));
    assertEquals("agent_session_stopped", events.get(1));
  }

  @Test
  void theVerbatimCodexStopPayloadBlocksADirtyRepo() throws Exception {
    var repo = repo("sail");
    Files.writeString(repo.resolve("wip.txt"), "uncommitted");

    var result = runGate(CODEX_STOP_INPUT, RUN_ID);

    assertEquals(0, result.exitCode(), result.stderr());
    var reason = blockReason(result);
    assertTrue(reason.contains("commit your work in sail"), reason);
  }

  @Test
  void theVerbatimCodexRetryPayloadHonorsStopHookActive() throws Exception {
    var repo = repo("sail");
    Files.writeString(repo.resolve("wip.txt"), "uncommitted");
    var retry = CODEX_STOP_INPUT.replace("\"stop_hook_active\":false", "\"stop_hook_active\":true");

    var result = runGate(retry, RUN_ID);

    assertEquals("", result.stdout(), "Codex re-stops with stop_hook_active=true after a block");
    assertEquals(List.of("agent_session_stopped"), events());
  }

  @Test
  void stopHookActiveAllowsRegardlessOfState() throws Exception {
    var repo = repo("sail");
    Files.writeString(repo.resolve("wip.txt"), "uncommitted");
    var input = STOP_INPUT.replace("\"stop_hook_active\":false", "\"stop_hook_active\":true");

    var result = runGate(input, RUN_ID);

    assertEquals("", result.stdout());
    assertEquals(List.of("agent_session_stopped"), events());
    assertFalse(Files.exists(marker()), "an honored stop_hook_active must not consume the nudge");
  }

  @Test
  void aBlankRunIdPassesUngated() throws Exception {
    var repo = repo("sail");
    Files.writeString(repo.resolve("wip.txt"), "uncommitted");

    var result = runGate(STOP_INPUT, null);

    assertEquals("", result.stdout(), "only sail-dispatched build sessions are gated");
    assertEquals(List.of("agent_session_stopped"), events());
  }

  @Test
  void garbageOnStdinFailsOpen() throws Exception {
    var repo = repo("sail");
    Files.writeString(repo.resolve("wip.txt"), "uncommitted");

    var result = runGate("this is not json", RUN_ID);

    assertEquals(0, result.exitCode(), result.stderr());
    assertEquals("", result.stdout(), "an unparseable stop payload must not jail the agent");
    assertEquals(List.of("agent_session_stopped"), events());
  }

  @Test
  void aMissingPullRequestBlocksWhenGhKnowsTheBranch() throws Exception {
    var repo = repo("sail");
    git(repo, "checkout", "-q", "-b", "agent/stop-gate");
    pushToFreshOrigin(repo, "agent/stop-gate");
    var bin = fakeGh("no pull requests found for branch agent/stop-gate", 1);

    var reason = blockReason(runGate(STOP_INPUT, RUN_ID, bin));

    assertTrue(reason.contains("open a pull request for agent/stop-gate in sail"), reason);
  }

  @Test
  void aGhFailureIsSkippedSilently() throws Exception {
    var repo = repo("sail");
    git(repo, "checkout", "-q", "-b", "agent/stop-gate");
    pushToFreshOrigin(repo, "agent/stop-gate");
    var bin = fakeGh("HTTP 401: authentication required", 1);

    var result = runGate(STOP_INPUT, RUN_ID, bin);

    assertEquals("", result.stdout(), "network or auth flake must never block a stop");
    assertEquals(List.of("agent_session_stopped"), events());
  }

  @Test
  void anExistingPullRequestAllows() throws Exception {
    var repo = repo("sail");
    git(repo, "checkout", "-q", "-b", "agent/stop-gate");
    pushToFreshOrigin(repo, "agent/stop-gate");
    var bin = fakeGh("", 0);

    var result = runGate(STOP_INPUT, RUN_ID, bin);

    assertEquals("", result.stdout());
    assertEquals(List.of("agent_session_stopped"), events());
  }

  @Test
  void stdoutCarriesTheBlockJsonAndNothingElse() throws Exception {
    var repo = repo("sail");
    Files.writeString(repo.resolve("wip.txt"), "uncommitted");

    var result = runGate(STOP_INPUT, RUN_ID);

    assertEquals(1, result.stdout().strip().lines().count(), result.stdout());
    var block = YamlUtil.parseMap(result.stdout());
    assertEquals(Set.of("decision", "reason"), block.keySet());
    assertEquals("block", block.get("decision"));
  }

  @Test
  void aMissingEventHelperStillGates() throws Exception {
    Files.delete(home.resolve(".sail/bin/sail-event.sh"));
    var repo = repo("sail");
    Files.writeString(repo.resolve("wip.txt"), "uncommitted");

    var result = runGate(STOP_INPUT, RUN_ID);

    assertEquals(0, result.exitCode(), result.stderr());
    assertEquals("block", YamlUtil.parseMap(result.stdout()).get("decision"));
  }

  @Test
  void aNonRepoWorkspaceDirectoryIsIgnored() throws Exception {
    Files.createDirectories(home.resolve("workspace/notes"));
    pushToFreshOrigin(repo("sail"), "main");

    var result = runGate(STOP_INPUT, RUN_ID);

    assertEquals("", result.stdout());
    assertEquals(List.of("agent_session_stopped"), events());
  }

  @Test
  void scriptPathAndTimeoutConstantsMatch() {
    assertEquals("/home/dev/.sail/bin/sail-stop-gate", SailStopGate.SCRIPT_PATH);
    assertEquals(15, SailStopGate.HOOK_TIMEOUT_SECONDS);
    assertTrue(SailStopGate.scriptContent().startsWith("#!/bin/sh"));
  }

  @Test
  void constructorRejectsNullShell() {
    assertThrows(NullPointerException.class, () -> new SailStopGate(null));
  }

  @Test
  void installWritesTheExecutableGateScript() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    new SailStopGate(shell).install("light-grid");

    var cmds = shell.invocations();
    assertEquals(2, cmds.size());
    assertTrue(cmds.get(0).contains("mkdir -p /home/dev/.sail/bin"));
    assertTrue(cmds.get(1).contains("chmod 0755"));
    assertTrue(cmds.get(1).contains("/home/dev/.sail/bin/sail-stop-gate"));
  }

  @Test
  void installPropagatesMkdirFailure() {
    var shell = new ScriptedShellExecutor().onFail("mkdir", "permission denied");

    var ex = assertThrows(IOException.class, () -> new SailStopGate(shell).install("light-grid"));
    assertTrue(ex.getMessage().contains("permission denied"));
  }

  @Test
  void installPropagatesWriteFailure() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("mkdir -p /home/dev/.sail/bin")
            .onFail("printf '%s'", "disk full");

    var ex = assertThrows(IOException.class, () -> new SailStopGate(shell).install("light-grid"));
    assertTrue(ex.getMessage().contains("disk full"));
  }

  @Test
  void installRejectsInvalidContainerName() {
    var gate = new SailStopGate(new ScriptedShellExecutor());
    assertThrows(Exception.class, () -> gate.install("../bad"));
  }

  @Test
  void aDirtyRepoOutsideTheSpecReposIsNotNudged() throws Exception {
    pushToFreshOrigin(repo("manatee-nexus"), "main");
    var other = repo("manatee-assessments");
    Files.writeString(other.resolve("wip.txt"), "leftover from another run");
    writeSessionRepos(List.of("manatee-nexus"));

    var result = runGate(STOP_INPUT, RUN_ID);

    assertEquals("", result.stdout(), "a repo outside the spec's repos must not block the stop");
    assertEquals(List.of("agent_session_stopped"), events());
  }

  @Test
  void aDirtySpecRepoStillBlocksWhenScoped() throws Exception {
    var target = repo("manatee-nexus");
    Files.writeString(target.resolve("wip.txt"), "uncommitted");
    repo("manatee-assessments");
    writeSessionRepos(List.of("manatee-nexus"));

    var reason = blockReason(runGate(STOP_INPUT, RUN_ID));

    assertTrue(reason.contains("commit your work in manatee-nexus"), reason);
    assertFalse(reason.contains("manatee-assessments"), reason);
  }

  private void writeSessionRepos(List<String> repos) throws IOException {
    var session = home.resolve(".sail/runs/" + RUN_ID + "/agent-session.json");
    Files.createDirectories(session.getParent());
    var quoted = repos.stream().map(r -> "\"" + r + "\"").toList();
    Files.writeString(
        session,
        "{\"spec_id\": \"s\", \"repos\": [" + String.join(", ", quoted) + "]}",
        StandardCharsets.UTF_8);
  }

  private String blockReason(GateResult result) {
    var block = YamlUtil.parseMap(result.stdout());
    assertEquals("block", block.get("decision"), result.stdout());
    return (String) block.get("reason");
  }

  private Path marker() {
    return home.resolve(".sail/runs/" + RUN_ID + "/stop-nudged");
  }

  private List<String> events() throws IOException {
    var log = home.resolve("events.log");
    return Files.exists(log) ? Files.readAllLines(log) : List.of();
  }

  private GateResult runGate(String stdin, String runId) throws Exception {
    return runGate(stdin, runId, null);
  }

  private GateResult runGate(String stdin, String runId, Path pathPrefix) throws Exception {
    var pb = new ProcessBuilder("/bin/sh", gate.toString());
    var env = pb.environment();
    env.put("HOME", home.toString());
    env.remove("SAIL_RUN_ID");
    if (runId != null) {
      env.put("SAIL_RUN_ID", runId);
    }
    if (pathPrefix != null) {
      env.put("PATH", pathPrefix + ":" + env.getOrDefault("PATH", "/usr/bin:/bin"));
    }
    var process = pb.start();
    try (var in = process.getOutputStream()) {
      in.write(stdin.getBytes(StandardCharsets.UTF_8));
    }
    var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    var stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the gate must finish well inside its 15s");
    return new GateResult(process.exitValue(), stdout, stderr);
  }

  private Path repo(String name) throws Exception {
    var dir = home.resolve("workspace").resolve(name);
    Files.createDirectories(dir);
    git(dir, "init", "-q", "-b", "main");
    Files.writeString(dir.resolve("README.md"), "seed\n");
    git(dir, "add", ".");
    git(dir, "commit", "-q", "-m", "seed");
    return dir;
  }

  private void pushToFreshOrigin(Path repo, String branch) throws Exception {
    var origin = home.resolve("origins").resolve(repo.getFileName() + ".git");
    Files.createDirectories(origin);
    git(origin, "init", "-q", "--bare");
    git(repo, "remote", "add", "origin", origin.toString());
    git(repo, "push", "-q", "-u", "origin", branch);
  }

  private void git(Path dir, String... args) throws Exception {
    var cmd =
        new ArrayList<>(
            List.of(
                "git",
                "-C",
                dir.toString(),
                "-c",
                "user.name=sail-test",
                "-c",
                "user.email=test@sail.local"));
    cmd.addAll(List.of(args));
    var pb = new ProcessBuilder(cmd);
    pb.environment().put("HOME", home.toString());
    pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
    var process = pb.start();
    var stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue(), "git " + String.join(" ", args) + " failed: " + stderr);
  }

  private Path fakeGh(String stderrLine, int exitCode) throws Exception {
    var bin = home.resolve("fakebin");
    Files.createDirectories(bin);
    writeExecutable(
        bin.resolve("gh"),
        "#!/bin/sh\nprintf '%s\\n' \"" + stderrLine + "\" >&2\nexit " + exitCode + "\n");
    return bin;
  }

  private static void writeExecutable(Path path, String content) throws IOException {
    Files.writeString(path, content);
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
  }
}
