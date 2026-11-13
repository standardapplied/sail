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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
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

  @Test
  void renameInvokesIncusRename() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var mgr = new ContainerManager(shell);

    mgr.rename("old", "renamed");

    assertEquals("incus rename old renamed", shell.invocations().getFirst());
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
        "\"user.sail.project\": \"acme\", \"user.sail.workspace\": \"fix\"|workspace 'fix' of project acme",
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
      var refused = assertThrows(IllegalStateException.class, asked).getMessage();
      assertTrue(
          refused.startsWith(
              "Instance '"
                  + name
                  + "' is "
                  + what
                  + ", not a project's container: project "
                  + name
                  + " may not take it."),
          refused);
    }
  }
}
