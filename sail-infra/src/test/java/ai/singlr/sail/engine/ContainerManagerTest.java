/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ContainerManagerTest {

  private static final String RUNNING_JSON =
      """
      [
        {
          "name": "acme-health",
          "status": "Running",
          "state": {
            "network": {
              "eth0": {
                "addresses": [
                  {"family": "inet", "address": "10.0.0.42", "netmask": "24", "scope": "global"},
                  {"family": "inet6", "address": "fd42::42", "netmask": "64", "scope": "global"}
                ]
              }
            }
          }
        }
      ]
      """;

  private static final String STOPPED_JSON =
      """
      [
        {
          "name": "acme-health",
          "status": "Stopped",
          "state": {}
        }
      ]
      """;

  private static final String EMPTY_JSON = "[]";

  private static final String FROZEN_JSON =
      """
      [
        {
          "name": "acme-health",
          "status": "Frozen",
          "state": {}
        }
      ]
      """;

  private static final String RUNNING_NO_NETWORK_JSON =
      """
      [
        {
          "name": "acme-health",
          "status": "Running",
          "state": {}
        }
      ]
      """;

  private static final String RUNNING_WITH_CONFIG_JSON =
      """
      [
        {
          "name": "acme-health",
          "status": "Running",
          "config": {
            "limits.cpu": "4",
            "limits.memory": "12GB",
            "security.nesting": "true"
          },
          "state": {
            "network": {
              "eth0": {
                "addresses": [
                  {"family": "inet", "address": "10.0.0.42", "netmask": "24", "scope": "global"}
                ]
              }
            }
          }
        }
      ]
      """;

  @Test
  void queryStateReturnsRunningWithIp() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus list ^acme-health$", RUNNING_JSON);
    var mgr = new ContainerManager(shell);

    var state = mgr.queryState("acme-health");

    assertInstanceOf(ContainerState.Running.class, state);
    assertEquals("10.0.0.42", ((ContainerState.Running) state).ipv4());
  }

  @Test
  void queryStateReturnsStopped() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus list ^acme-health$", STOPPED_JSON);
    var mgr = new ContainerManager(shell);

    var state = mgr.queryState("acme-health");

    assertInstanceOf(ContainerState.Stopped.class, state);
  }

  @Test
  void queryStateReturnsNotCreatedForEmptyArray() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus list ^acme-health$", EMPTY_JSON);
    var mgr = new ContainerManager(shell);

    var state = mgr.queryState("acme-health");

    assertInstanceOf(ContainerState.NotCreated.class, state);
  }

  @Test
  void aPausedContainerReadsAsFrozenNotAsAnError() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus list ^acme-health$", FROZEN_JSON);

    assertEquals(
        new ContainerState.Frozen(), new ContainerManager(shell).queryState("acme-health"));
  }

  @Test
  void aContainerIsLiveWhenItsProcessesExist() {
    assertTrue(new ContainerState.Running(null).live());
    assertTrue(new ContainerState.Frozen().live());
    assertFalse(new ContainerState.Stopped().live());
    assertFalse(new ContainerState.NotCreated().live());
    assertFalse(new ContainerState.Error("boom").live());
  }

  @Test
  void queryStateReturnsErrorForUnexpectedStatus() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list ^acme-health$", FROZEN_JSON.replace("Frozen", "Freezing"));

    assertEquals(
        new ContainerState.Error("Unexpected container status: Freezing"),
        new ContainerManager(shell).queryState("acme-health"));
  }

  @Test
  void queryStateReturnsErrorWhenIncusFails() throws Exception {
    var shell =
        new ScriptedShellExecutor().onFail("incus list ^acme-health$", "connection refused");
    var mgr = new ContainerManager(shell);

    var state = mgr.queryState("acme-health");

    assertInstanceOf(ContainerState.Error.class, state);
    assertTrue(((ContainerState.Error) state).message().contains("connection refused"));
  }

  @Test
  void queryStateRunningWithNoNetworkReturnsNullIp() throws Exception {
    var shell =
        new ScriptedShellExecutor().onOk("incus list ^acme-health$", RUNNING_NO_NETWORK_JSON);
    var mgr = new ContainerManager(shell);

    var state = mgr.queryState("acme-health");

    assertInstanceOf(ContainerState.Running.class, state);
    assertNull(((ContainerState.Running) state).ipv4());
  }

  @Test
  void startExecutesCorrectCommand() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var mgr = new ContainerManager(shell);

    mgr.start("acme-health");

    var cmds = shell.invocations();
    assertEquals(1, cmds.size());
    assertEquals("incus start acme-health", cmds.getFirst());
  }

  @Test
  void startThrowsOnFailure() {
    var shell = new ScriptedShellExecutor().onFail("incus start", "not found");
    var mgr = new ContainerManager(shell);

    var ex = assertThrows(IOException.class, () -> mgr.start("acme-health"));
    assertTrue(ex.getMessage().contains("Failed to start"));
    assertTrue(ex.getMessage().contains("acme-health"));
  }

  @Test
  void stopExecutesCorrectCommand() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var mgr = new ContainerManager(shell);

    mgr.stop("acme-health");

    var cmds = shell.invocations();
    assertEquals(1, cmds.size());
    assertEquals("incus stop acme-health", cmds.getFirst());
  }

  @Test
  void stopThrowsOnFailure() {
    var shell = new ScriptedShellExecutor().onFail("incus stop", "not found");
    var mgr = new ContainerManager(shell);

    var ex = assertThrows(IOException.class, () -> mgr.stop("acme-health"));
    assertTrue(ex.getMessage().contains("Failed to stop"));
  }

  @Test
  void forceDeleteExecutesCorrectCommand() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var mgr = new ContainerManager(shell);

    mgr.forceDelete("acme-health");

    var cmds = shell.invocations();
    assertEquals(1, cmds.size());
    assertEquals("incus delete acme-health --force", cmds.getFirst());
  }

  @Test
  void forceDeleteThrowsOnFailure() {
    var shell = new ScriptedShellExecutor().onFail("incus delete", "permission denied");
    var mgr = new ContainerManager(shell);

    var ex = assertThrows(IOException.class, () -> mgr.forceDelete("acme-health"));
    assertTrue(ex.getMessage().contains("Failed to delete"));
    assertTrue(ex.getMessage().contains("permission denied"));
  }

  @Test
  void queryInfoReturnsResourceLimits() throws Exception {
    var shell =
        new ScriptedShellExecutor().onOk("incus list ^acme-health$", RUNNING_WITH_CONFIG_JSON);
    var mgr = new ContainerManager(shell);

    var info = mgr.queryInfo("acme-health");

    assertEquals("acme-health", info.name());
    assertInstanceOf(ContainerState.Running.class, info.state());
    assertNotNull(info.limits());
    assertEquals("4", info.limits().cpu());
    assertEquals("12GB", info.limits().memory());
  }

  @Test
  void queryInfoReturnsNotCreatedWhenMissing() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus list ^missing$", EMPTY_JSON);
    var mgr = new ContainerManager(shell);

    var info = mgr.queryInfo("missing");

    assertInstanceOf(ContainerState.NotCreated.class, info.state());
    assertNull(info.limits());
  }

  @Test
  void setResourceLimitsAppliesCpuAndMemoryTogether() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus config set");
    var mgr = new ContainerManager(shell);

    mgr.setResourceLimits("acme-health", new ContainerManager.ResourceLimits("6", "24GB"));

    var cmds = shell.invocations();
    assertEquals(1, cmds.size());
    assertTrue(cmds.getFirst().contains("limits.cpu=6"));
    assertTrue(cmds.getFirst().contains("limits.memory=24GB"));
  }

  @Test
  void setDiskQuotaFallsBackToDeviceSetWhenOverrideExists() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("incus config device override", "device already exists")
            .onOk("incus config device set");
    var mgr = new ContainerManager(shell);

    mgr.setDiskQuota("acme-health", "200GB");

    var cmds = shell.invocations();
    assertEquals(2, cmds.size());
    assertTrue(cmds.getFirst().contains("device override"));
    assertTrue(cmds.get(1).contains("device set"));
  }

  @Test
  void restartThrowsOnFailure() {
    var shell = new ScriptedShellExecutor().onFail("incus restart", "permission denied");
    var mgr = new ContainerManager(shell);

    var ex = assertThrows(IOException.class, () -> mgr.restart("acme-health"));

    assertTrue(ex.getMessage().contains("Failed to restart"));
    assertTrue(ex.getMessage().contains("permission denied"));
  }

  /** A renamed project's container carries its new name as its project key: still told its own. */
  @Test
  void aRenameCarriesTheProjectKeyToTheNewName() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var mgr = new ContainerManager(shell);

    mgr.rename("old", "renamed");

    assertEquals(
        List.of("incus rename old renamed", "incus config set renamed user.sail.project=renamed"),
        shell.invocations());
  }

  /**
   * A key that cannot follow the name puts the name back, so the container is never left unowned.
   */
  @Test
  void aRenameWhoseKeyCannotFollowPutsTheNameBack() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus rename", "")
            .onFail("incus config set renamed", "disk full");
    var mgr = new ContainerManager(shell);

    var ex = assertThrows(IOException.class, () -> mgr.rename("old", "renamed"));

    assertTrue(ex.getMessage().contains("keeps the name 'old'"), ex.getMessage());
    assertTrue(ex.getMessage().contains("disk full"), ex.getMessage());
    assertEquals("incus rename renamed old", shell.invocations().getLast());
  }

  @Test
  void renameThrowsOnFailure() {
    var shell = new ScriptedShellExecutor().onFail("incus rename", "instance is running");
    var mgr = new ContainerManager(shell);

    var ex = assertThrows(IOException.class, () -> mgr.rename("old", "renamed"));

    assertTrue(ex.getMessage().contains("Failed to rename"));
    assertTrue(ex.getMessage().contains("instance is running"));
  }

  @Test
  void setHostnameIsANoOpWhenAlreadyCorrect() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus exec acme -- hostname", "acme");
    var mgr = new ContainerManager(shell);

    var changed = mgr.setHostname("acme");

    assertFalse(changed, "an already-correct hostname is left untouched");
    assertEquals(1, shell.invocations().size(), "only the read, no write");
  }

  @Test
  void setHostnameRealignsWhenItHasDrifted() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "stale", ""));
    var mgr = new ContainerManager(shell);

    var changed = mgr.setHostname("acme");

    assertTrue(changed, "a drifted hostname is corrected");
    var cmds = shell.invocations();
    assertTrue(cmds.stream().anyMatch(c -> c.contains("/etc/hostname")), "writes the new hostname");
    assertTrue(cmds.stream().anyMatch(c -> c.contains("/etc/hosts")), "realigns the hosts entry");
  }

  @Test
  void hostnameMatchesReadsWithoutWriting() throws Exception {
    var matching = new ScriptedShellExecutor().onOk("incus exec acme -- hostname", "acme\n");
    assertTrue(new ContainerManager(matching).hostnameMatches("acme"));
    assertEquals(1, matching.invocations().size(), "a pure probe: one read, never a write");

    var drifted = new ScriptedShellExecutor().onOk("incus exec acme -- hostname", "stale\n");
    assertFalse(new ContainerManager(drifted).hostnameMatches("acme"));

    var unreachable = new ScriptedShellExecutor().onFail("hostname", "container not running");
    assertFalse(new ContainerManager(unreachable).hostnameMatches("acme"));
  }

  @Test
  void setHostnameThrowsWhenTheWriteFails() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "stale", ""))
            .onFail("/etc/hostname", "read-only file system");
    var mgr = new ContainerManager(shell);

    var ex = assertThrows(IOException.class, () -> mgr.setHostname("acme"));

    assertTrue(ex.getMessage().contains("Failed to set hostname"));
    assertTrue(ex.getMessage().contains("read-only"));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"fix\"|workspace fix of project acme",
        "\"user.sail.template.of\": \"acme-fix\"|the template of project acme-fix",
        "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"other\"|a copy carrying the keys of project acme"
      })
  void aContainerAddressedByAProjectsNameThatIsNoProjectsIsRefusedNamingWhatItIs(
      String keys, String what) {
    var name = keys.contains("template") ? "acme-fix--tmpl" : "acme-fix";
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "incus list ^" + name + "$",
                "[{\"name\": \""
                    + name
                    + "\", \"status\": \"Running\", \"config\": {"
                    + keys
                    + "}}]");
    var containers = new ContainerManager(shell);

    for (Executable asked :
        List.<Executable>of(() -> containers.queryState(name), () -> containers.queryInfo(name))) {
      var refused = assertThrows(BoxInstances.Taken.class, asked);
      assertEquals(name, refused.project());
      assertEquals(
          "Project " + name + " cannot take instance " + name + ": it is " + what + ".",
          refused.reason());
    }
  }

  private static final String INFO =
      """
      Name: acme
      Status: STOPPING

      Log:

      lxc acme 20261120193002.104 WARN     conf - conf.c:lxc_map_ids:3621 - newuidmap binary is missing
      lxc acme 20261120193002.123 ERROR    start - start.c:__lxc_start:2107 - Failed to spawn container "acme"
      """;

  private static final String JOBS =
      """
      JOB UNIT             TYPE STATE
      812 docker.service   stop running
      813 shutdown.target  start waiting

      2 jobs listed.
      """;

  /**
   * #500's last red and #507's m1: an {@code incus stop} that runs out of time says why, from what
   * was read at that moment, in the error a person reads: systemd's own state inside the instance,
   * how many jobs it has queued, and where the rest is; Incus's lines and the units' names, which
   * that sentence could carry to a node's other members, stay in the owner-only log, Incus's log
   * cut to its last lines.
   */
  @Test
  void aStopThatTimesOutSaysWhatSystemdAndIncusSaidOfTheInstanceAndRecordsItAll(@TempDir Path dir)
      throws Exception {
    var log = dir.resolve("sail/containers.log");
    var box =
        new ScriptedShellExecutor()
            .onOk("incus info --show-log acme", INFO + "lxc filler\n".repeat(200))
            .on(
                "incus exec acme -- systemctl is-system-running",
                new ShellExec.Result(1, "stopping\n", ""))
            .onOk("incus exec acme -- systemctl list-jobs --no-pager", JOBS);

    var timedOut =
        assertThrows(
            CommandTimedOut.class,
            () -> manager(new TimingOut("incus stop acme", box), log).stop("acme"));

    assertEquals(
        "Command timed out after 120s: incus stop acme. Just after, systemd in acme said it is"
            + " stopping, with 2 jobs queued; Incus's log of acme and the rest are in "
            + log
            + ".",
        timedOut.getMessage());
    assertFalse(timedOut.getMessage().contains("docker.service"), timedOut.getMessage());
    assertFalse(timedOut.getMessage().contains("lxc "), timedOut.getMessage());
    var recorded = Files.readString(log);
    assertTrue(
        recorded.contains("-- systemd's state in acme: incus exec acme -- systemctl"), recorded);
    assertTrue(recorded.contains("812 docker.service   stop running"), recorded);
    assertTrue(
        recorded.lines().filter(line -> line.equals("lxc filler")).count()
            == InstanceTimeoutDiagnosis.LOG_LINES,
        "Incus's log is cut to its last lines");
    assertEquals(
        "rw-------",
        PosixFilePermissions.toString(
            Files.getPosixFilePermissions(log, LinkOption.NOFOLLOW_LINKS)));
  }

  /**
   * A start that runs out of time on an instance that no longer answers a command says so, without
   * Incus's words, and systemd's jobs are not asked of it; Incus's own log is still read, into a
   * log whose missing folders are made owner-only (#507's nit: a fresh home has no {@code
   * ~/.sail}).
   */
  @Test
  void aStartThatTimesOutOnAnInstanceThatNoLongerAnswersSaysSoAndAsksNoJobs(@TempDir Path dir)
      throws Exception {
    var log = dir.resolve("absent/logs/containers.log");
    var box =
        new ScriptedShellExecutor()
            .onOk("incus info --show-log acme", INFO)
            .onFail("incus exec acme --", "Error: Instance is not running");

    var timedOut =
        assertThrows(
            CommandTimedOut.class,
            () -> manager(new TimingOut("incus start acme", box), log).start("acme"));

    assertEquals(
        "Command timed out after 120s: incus start acme. Just after, acme no longer answered a"
            + " command; Incus's log of acme and the rest are in "
            + log
            + ".",
        timedOut.getMessage());
    assertTrue(Files.readString(log).contains("Failed to spawn container"));
    assertEquals(
        "rwx------",
        PosixFilePermissions.toString(
            Files.getPosixFilePermissions(dir.resolve("absent"), LinkOption.NOFOLLOW_LINKS)));
    assertTrue(
        box.invocations().stream().noneMatch(c -> c.contains("list-jobs")),
        box.invocations().toString());
  }

  /**
   * #507's m2: the whole diagnosis, which runs under the workspace's lock, is bounded at its one
   * budget however many of its questions hang; Incus's log is asked beside systemd, and what was
   * not answered in time is said so in the log.
   */
  @Test
  void aDiagnosisWhoseQuestionsAllHangEndsWithinItsOneBudget(@TempDir Path dir) throws Exception {
    var log = dir.resolve("sail/containers.log");
    var hanging = new Hanging(new ScriptedShellExecutor());
    var manager =
        new ContainerManager(
            new TimingOut("incus stop acme", hanging),
            new InstanceTimeoutDiagnosis(hanging, log, Duration.ofSeconds(2)));

    var started = System.nanoTime();
    var timedOut =
        assertTimeoutPreemptively(
            Duration.ofSeconds(8),
            () -> assertThrows(CommandTimedOut.class, () -> manager.stop("acme")));

    assertTrue(
        System.nanoTime() - started < Duration.ofMillis(3500).toNanos(),
        "one budget for every question, not one each");
    assertTrue(
        timedOut.getMessage().contains("acme no longer answered a command"), timedOut.getMessage());
    var recorded = Files.readString(log);
    assertTrue(
        recorded.contains("-- Incus's log of acme: incus info --show-log acme\ndid not answer"),
        recorded);
  }

  /** A start or stop that does not time out asks nothing more of the box. */
  @Test
  void aStopThatAnswersIsNotDiagnosed(@TempDir Path dir) throws Exception {
    var box = new ScriptedShellExecutor().onOk("incus stop acme");

    manager(box, dir.resolve("sail/containers.log")).stop("acme");

    assertEquals(List.of("incus stop acme"), box.invocations());
    assertFalse(Files.exists(dir.resolve("sail")));
  }

  private static ContainerManager manager(ShellExec shell, Path log) {
    return new ContainerManager(
        shell, new InstanceTimeoutDiagnosis(shell, log, Duration.ofSeconds(5)));
  }

  /** The box's answers, except that {@code command} runs out of time as Sail's own shell says. */
  private record TimingOut(String command, ShellExec box) implements ShellExec {
    @Override
    public Result exec(List<String> asked)
        throws IOException, InterruptedException, TimeoutException {
      if (String.join(" ", asked).equals(command)) {
        throw new CommandTimedOut("Command timed out after 120s: " + command, "");
      }
      return box.exec(asked);
    }

    @Override
    public Result exec(List<String> asked, Path workDir, Duration timeout)
        throws IOException, InterruptedException, TimeoutException {
      return box.exec(asked, workDir, timeout);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }

  /** Every command hangs until the bound it is given. */
  private record Hanging(ShellExec box) implements ShellExec {
    @Override
    public Result exec(List<String> asked)
        throws IOException, InterruptedException, TimeoutException {
      return exec(asked, null, Duration.ofSeconds(120));
    }

    @Override
    public Result exec(List<String> asked, Path workDir, Duration timeout)
        throws IOException, InterruptedException, TimeoutException {
      return new ShellExecutor(false).exec(List.of("sleep", "30"), null, timeout);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
