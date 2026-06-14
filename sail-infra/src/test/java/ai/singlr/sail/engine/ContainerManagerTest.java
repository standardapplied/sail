/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

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

  private static final String MULTI_JSON =
      """
      [
        {
          "name": "acme-health",
          "status": "Running",
          "state": {
            "network": {
              "eth0": {
                "addresses": [
                  {"family": "inet", "address": "10.0.0.42", "netmask": "24", "scope": "global"}
                ]
              }
            }
          }
        },
        {
          "name": "fintech-api",
          "status": "Stopped",
          "state": {}
        },
        {
          "name": "ml-pipeline",
          "status": "Running",
          "state": {
            "network": {
              "eth0": {
                "addresses": [
                  {"family": "inet", "address": "10.0.0.99", "netmask": "24", "scope": "global"}
                ]
              }
            }
          }
        }
      ]
      """;

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

  private static final String MULTI_WITH_CONFIG_JSON =
      """
      [
        {
          "name": "acme-health",
          "status": "Running",
          "config": {
            "limits.cpu": "4",
            "limits.memory": "12GB"
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
        },
        {
          "name": "fintech-api",
          "status": "Stopped",
          "config": {
            "limits.cpu": "2",
            "limits.memory": "8GB"
          },
          "state": {}
        },
        {
          "name": "personal-site",
          "status": "Running",
          "config": {},
          "state": {
            "network": {
              "eth0": {
                "addresses": [
                  {"family": "inet", "address": "10.0.0.99", "netmask": "24", "scope": "global"}
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
  void queryStateReturnsErrorForUnexpectedStatus() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus list ^acme-health$", FROZEN_JSON);
    var mgr = new ContainerManager(shell);

    var state = mgr.queryState("acme-health");

    assertInstanceOf(ContainerState.Error.class, state);
    assertTrue(((ContainerState.Error) state).message().contains("Frozen"));
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
  void listAllReturnsMultipleContainers() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus list --format json", MULTI_JSON);
    var mgr = new ContainerManager(shell);

    var containers = mgr.listAll();

    assertEquals(3, containers.size());
    assertEquals("acme-health", containers.get(0).name());
    assertInstanceOf(ContainerState.Running.class, containers.get(0).state());
    assertEquals("fintech-api", containers.get(1).name());
    assertInstanceOf(ContainerState.Stopped.class, containers.get(1).state());
    assertEquals("ml-pipeline", containers.get(2).name());
    assertInstanceOf(ContainerState.Running.class, containers.get(2).state());
  }

  @Test
  void listAllReturnsEmptyListWhenNoContainers() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus list --format json", EMPTY_JSON);
    var mgr = new ContainerManager(shell);

    var containers = mgr.listAll();

    assertTrue(containers.isEmpty());
  }

  @Test
  void listAllThrowsOnIncusFailure() {
    var shell =
        new ScriptedShellExecutor().onFail("incus list --format json", "daemon not running");
    var mgr = new ContainerManager(shell);

    var ex = assertThrows(IOException.class, mgr::listAll);
    assertTrue(ex.getMessage().contains("daemon not running"));
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
  void listAllIncludesResourceLimits() throws Exception {
    var shell =
        new ScriptedShellExecutor().onOk("incus list --format json", MULTI_WITH_CONFIG_JSON);
    var mgr = new ContainerManager(shell);

    var containers = mgr.listAll();

    assertEquals(3, containers.size());
    var acme = containers.get(0);
    assertNotNull(acme.limits());
    assertEquals("4", acme.limits().cpu());
    assertEquals("12GB", acme.limits().memory());

    var fintech = containers.get(1);
    assertNotNull(fintech.limits());
    assertEquals("2", fintech.limits().cpu());
    assertEquals("8GB", fintech.limits().memory());
  }

  @Test
  void listAllHandlesMissingConfig() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus list --format json", MULTI_JSON);
    var mgr = new ContainerManager(shell);

    var containers = mgr.listAll();

    for (var c : containers) {
      assertNull(c.limits());
    }
  }

  @Test
  void listAllHandlesEmptyConfig() throws Exception {
    var shell =
        new ScriptedShellExecutor().onOk("incus list --format json", MULTI_WITH_CONFIG_JSON);
    var mgr = new ContainerManager(shell);

    var containers = mgr.listAll();

    var personalSite = containers.get(2);
    assertNull(personalSite.limits());
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
}
