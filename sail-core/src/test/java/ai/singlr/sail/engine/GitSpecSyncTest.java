package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitSpecSyncTest {

  @Test
  void localFactoryUsesNormalizedPathAndDefaultTimeout() throws Exception {
    var shell = repositoryShell("## main...origin/main\n");
    var sync = GitSpecSync.local(shell, Path.of("/tmp/../specs"));

    var status = sync.status();

    assertEquals(GitSpecSync.State.CLEAN, status.state());
    assertTrue(shell.invocations().stream().anyMatch(command -> command.contains("git -C /specs")));
  }

  @Test
  void statusReadErrorUsesGenericMessageWhenGitIsSilent() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("rev-parse --is-inside-work-tree", "true\n")
            .onOk("branch --show-current", "main\n")
            .onOk("rev-parse --abbrev-ref --symbolic-full-name @{u}", "origin/main\n")
            .on("status --porcelain=v1 -b", new ShellExec.Result(1, "", ""));

    var status = sync(shell).status();

    assertEquals(GitSpecSync.State.ERROR, status.state());
    assertEquals("Specs Git status could not be read.", status.message());
  }

  @Test
  void dirtyStatusHandlesShortPorcelainEntries() throws Exception {
    var status = sync(repositoryShell("## main...origin/main\n?\n")).status();

    assertEquals(GitSpecSync.State.DIRTY, status.state());
    assertFalse(status.conflicted());
  }

  @Test
  void dirtyStatusHandlesNonConflictEntries() throws Exception {
    var status = sync(repositoryShell("## main...origin/main\n?? spec.yaml\n")).status();

    assertEquals(GitSpecSync.State.DIRTY, status.state());
    assertFalse(status.conflicted());
  }

  @Test
  void reportsMissingRepository() throws Exception {
    var shell = new ScriptedShellExecutor().onFail("rev-parse --is-inside-work-tree", "fatal");
    var sync = sync(shell);

    var status = sync.status();

    assertEquals(GitSpecSync.State.NOT_A_REPOSITORY, status.state());
    assertFalse(status.repository());
    assertEquals("Specs are not backed by a Git repository.", status.message());
  }

  @Test
  void reportsCleanRepository() throws Exception {
    var shell = repositoryShell("## main...origin/main\n");
    var sync = sync(shell);

    var status = sync.status();

    assertEquals(GitSpecSync.State.CLEAN, status.state());
    assertEquals("main", status.branch());
    assertEquals("origin/main", status.upstream());
    assertTrue(status.repository());
    assertFalse(status.dirty());
  }

  @Test
  void reportsDirtyBeforeAheadBehind() throws Exception {
    var shell = repositoryShell("## main...origin/main [ahead 1, behind 2]\n M spec.yaml\n");
    var sync = sync(shell);

    var status = sync.status();

    assertEquals(GitSpecSync.State.DIRTY, status.state());
    assertEquals(1, status.ahead());
    assertEquals(2, status.behind());
    assertTrue(status.dirty());
  }

  @Test
  void reportsConflictedBeforeDirty() throws Exception {
    var shell = repositoryShell("## main...origin/main\nUU spec.yaml\n");
    var sync = sync(shell);

    var status = sync.status();

    assertEquals(GitSpecSync.State.CONFLICTED, status.state());
    assertTrue(status.conflicted());
  }

  @Test
  void reportsNoUpstream() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("rev-parse --is-inside-work-tree", "true\n")
            .onOk("branch --show-current", "main\n")
            .onFail("rev-parse --abbrev-ref --symbolic-full-name @{u}", "no upstream")
            .onOk("status --porcelain=v1 -b", "## main\n");
    var sync = sync(shell);

    var status = sync.status();

    assertEquals(GitSpecSync.State.NO_UPSTREAM, status.state());
    assertNull(status.upstream());
  }

  @Test
  void reportsAheadBehindAndDiverged() throws Exception {
    assertEquals(
        GitSpecSync.State.AHEAD,
        sync(repositoryShell("## main...origin/main [ahead 3]\n")).status().state());
    assertEquals(
        GitSpecSync.State.BEHIND,
        sync(repositoryShell("## main...origin/main [behind 4]\n")).status().state());
    assertEquals(
        GitSpecSync.State.DIVERGED,
        sync(repositoryShell("## main...origin/main [ahead 1, behind 2]\n")).status().state());
  }

  @Test
  void rejectsBlankCommandToken() {
    var shell = new ScriptedShellExecutor();

    assertThrows(IllegalArgumentException.class, () -> new GitSpecSync(shell, List.of("git", " ")));
  }

  @Test
  void rejectsControlCharactersInRemote() {
    var shell = new ScriptedShellExecutor().onFail("rev-parse --is-inside-work-tree", "fatal");
    var sync = sync(shell);

    assertThrows(
        IllegalArgumentException.class, () -> sync.init("ssh://host/specs.git\nboom", "main"));
  }

  @Test
  void pullFailsWhenDirty() throws Exception {
    var shell = repositoryShell("## main...origin/main\n M spec.yaml\n");
    var sync = sync(shell);

    var thrown = assertThrows(IllegalStateException.class, sync::pull);

    assertEquals("Commit or stash local spec changes before pulling.", thrown.getMessage());
  }

  @Test
  void pushFailsWhenBehind() throws Exception {
    var shell = repositoryShell("## main...origin/main [behind 1]\n");
    var sync = sync(shell);

    var thrown = assertThrows(IllegalStateException.class, sync::push);

    assertEquals("Pull remote spec changes before pushing.", thrown.getMessage());
  }

  @Test
  void pullRunsFastForwardOnlyAndRefreshesStatus() throws Exception {
    var shell =
        new SequencedShell(
            List.of(
                ok("true\n"),
                ok("main\n"),
                ok("origin/main\n"),
                ok("## main...origin/main [behind 1]\n"),
                ok("Fast-forward\n"),
                ok("true\n"),
                ok("main\n"),
                ok("origin/main\n"),
                ok("## main...origin/main\n")));
    var sync = new GitSpecSync(shell, List.of("git", "-C", "/specs"), Duration.ofSeconds(5));

    var result = sync.pull();

    assertEquals("pull", result.operation());
    assertEquals(GitSpecSync.State.BEHIND, result.before().state());
    assertEquals(GitSpecSync.State.CLEAN, result.after().state());
    assertTrue(result.changed());
    assertTrue(
        shell.invocations().stream().anyMatch(command -> command.contains("pull --ff-only")));
  }

  @Test
  void initAddsRemoteWhenProvided() throws Exception {
    var shell =
        new SequencedShell(
            List.of(
                fail("fatal"),
                ok("Initialized empty Git repository\n"),
                ok(""),
                ok("true\n"),
                ok("trunk\n"),
                ok("origin/trunk\n"),
                ok("## trunk...origin/trunk\n")));
    var sync = new GitSpecSync(shell, List.of("git", "-C", "/specs"), Duration.ofSeconds(5));

    var result = sync.init("ssh://host/specs.git", "trunk");

    assertTrue(result.changed());
    assertEquals(GitSpecSync.State.NOT_A_REPOSITORY, result.before().state());
    assertEquals(GitSpecSync.State.CLEAN, result.after().state());
    assertTrue(
        shell.invocations().stream()
            .anyMatch(command -> command.contains("remote add origin ssh://host/specs.git")));
  }

  @Test
  void reportsStatusReadError() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("rev-parse --is-inside-work-tree", "true\n")
            .onOk("branch --show-current", "main\n")
            .onOk("rev-parse --abbrev-ref --symbolic-full-name @{u}", "origin/main\n")
            .onFail("status --porcelain=v1 -b", "status failed");
    var status = sync(shell).status();

    assertEquals(GitSpecSync.State.ERROR, status.state());
    assertEquals("status failed", status.message());
  }

  @Test
  void pullAllowsAheadButMapsPullFailure() {
    var shell =
        repositoryShell("## main...origin/main [ahead 1]\n")
            .onFail("pull --ff-only", "cannot fast-forward");
    var sync = sync(shell);

    var thrown = assertThrows(java.io.IOException.class, sync::pull);

    assertTrue(thrown.getMessage().contains("Failed to pull specs"));
  }

  @Test
  void pullRejectsUnsafeStates() {
    assertPullRejected(repositoryShell("## main...origin/main [ahead 1, behind 1]\n"));
    assertPullRejected(repositoryShell("## main...origin/main\nUU spec.yaml\n"));
    assertPullRejected(noUpstreamShell());
    assertPullRejected(
        new ScriptedShellExecutor().onFail("rev-parse --is-inside-work-tree", "fatal"));
    assertPullRejected(statusErrorShell());
  }

  @Test
  void pushRunsAndRefreshesStatus() throws Exception {
    var shell = repositoryShell("## main...origin/main [ahead 1]\n").onOk("push", "pushed\n");
    var sync = sync(shell);

    var result = sync.push();

    assertEquals("push", result.operation());
    assertEquals(GitSpecSync.State.AHEAD, result.before().state());
    assertTrue(shell.invocations().stream().anyMatch(command -> command.contains("push")));
  }

  @Test
  void pushMapsPushFailure() {
    var shell = repositoryShell("## main...origin/main [ahead 1]\n").onFail("push", "rejected");
    var sync = sync(shell);

    var thrown = assertThrows(java.io.IOException.class, sync::push);

    assertTrue(thrown.getMessage().contains("Failed to push specs"));
  }

  @Test
  void pushRejectsUnsafeStates() {
    assertPushRejected(repositoryShell("## main...origin/main\n M spec.yaml\n"));
    assertPushRejected(repositoryShell("## main...origin/main [ahead 1, behind 1]\n"));
    assertPushRejected(repositoryShell("## main...origin/main\nUU spec.yaml\n"));
    assertPushRejected(noUpstreamShell());
    assertPushRejected(
        new ScriptedShellExecutor().onFail("rev-parse --is-inside-work-tree", "fatal"));
    assertPushRejected(statusErrorShell());
  }

  @Test
  void initReturnsUnchangedForExistingRepository() throws Exception {
    var sync = sync(repositoryShell("## main...origin/main\n"));

    var result = sync.init("ssh://host/specs.git", "main");

    assertEquals("init", result.operation());
    assertFalse(result.changed());
    assertEquals(GitSpecSync.State.CLEAN, result.after().state());
  }

  @Test
  void initUsesMainByDefaultAndCanSkipRemote() throws Exception {
    var shell =
        new SequencedShell(
            List.of(
                fail("fatal"),
                ok("Initialized empty Git repository\n"),
                ok("true\n"),
                ok("main\n"),
                fail("no upstream"),
                ok("## main\n")));
    var sync = new GitSpecSync(shell, List.of("git", "-C", "/specs"), Duration.ofSeconds(5));

    var result = sync.init("", "");

    assertTrue(result.changed());
    assertEquals(GitSpecSync.State.NO_UPSTREAM, result.after().state());
    assertTrue(
        shell.invocations().stream()
            .anyMatch(command -> command.contains("init --initial-branch main")));
  }

  @Test
  void initMapsInitAndRemoteFailures() {
    var initFailure = new SequencedShell(List.of(fail("fatal"), fail("init failed")));
    var initSync =
        new GitSpecSync(initFailure, List.of("git", "-C", "/specs"), Duration.ofSeconds(5));

    assertThrows(java.io.IOException.class, () -> initSync.init(null, "main"));

    var remoteFailure =
        new SequencedShell(List.of(fail("fatal"), ok("Initialized\n"), fail("bad remote")));
    var remoteSync =
        new GitSpecSync(remoteFailure, List.of("git", "-C", "/specs"), Duration.ofSeconds(5));

    assertThrows(java.io.IOException.class, () -> remoteSync.init("ssh://host/specs.git", "main"));
  }

  @Test
  void rejectsMissingCommandAndNullLocalPath() {
    var shell = new ScriptedShellExecutor();

    assertThrows(IllegalArgumentException.class, () -> new GitSpecSync(shell, List.of()));
    assertThrows(NullPointerException.class, () -> GitSpecSync.local(shell, null));
  }

  private static void assertPullRejected(ScriptedShellExecutor shell) {
    assertThrows(IllegalStateException.class, () -> sync(shell).pull());
  }

  private static void assertPushRejected(ScriptedShellExecutor shell) {
    assertThrows(IllegalStateException.class, () -> sync(shell).push());
  }

  private static ScriptedShellExecutor noUpstreamShell() {
    return new ScriptedShellExecutor()
        .onOk("rev-parse --is-inside-work-tree", "true\n")
        .onOk("branch --show-current", "main\n")
        .onFail("rev-parse --abbrev-ref --symbolic-full-name @{u}", "no upstream")
        .onOk("status --porcelain=v1 -b", "## main\n");
  }

  private static ScriptedShellExecutor statusErrorShell() {
    return new ScriptedShellExecutor()
        .onOk("rev-parse --is-inside-work-tree", "true\n")
        .onOk("branch --show-current", "main\n")
        .onOk("rev-parse --abbrev-ref --symbolic-full-name @{u}", "origin/main\n")
        .onFail("status --porcelain=v1 -b", "boom");
  }

  private static ShellExec.Result ok(String stdout) {
    return new ShellExec.Result(0, stdout, "");
  }

  private static ShellExec.Result fail(String stderr) {
    return new ShellExec.Result(1, "", stderr);
  }

  private static final class SequencedShell implements ShellExec {
    private final ArrayDeque<Result> results;
    private final List<String> invocations = new ArrayList<>();

    private SequencedShell(List<Result> results) {
      this.results = new ArrayDeque<>(results);
    }

    @Override
    public Result exec(List<String> command) {
      return exec(command, null, Duration.ZERO);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) {
      invocations.add(String.join(" ", command));
      return results.removeFirst();
    }

    @Override
    public boolean isDryRun() {
      return false;
    }

    private List<String> invocations() {
      return List.copyOf(invocations);
    }
  }

  private static GitSpecSync sync(ScriptedShellExecutor shell) {
    return new GitSpecSync(shell, List.of("git", "-C", "/specs"), Duration.ofSeconds(5));
  }

  private static ScriptedShellExecutor repositoryShell(String porcelain) {
    return new ScriptedShellExecutor()
        .onOk("rev-parse --is-inside-work-tree", "true\n")
        .onOk("branch --show-current", "main\n")
        .onOk("rev-parse --abbrev-ref --symbolic-full-name @{u}", "origin/main\n")
        .onOk("status --porcelain=v1 -b", porcelain);
  }
}
