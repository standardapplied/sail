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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The box's instances as one {@code incus list} reads them, and what each is from its keys. */
class BoxInstancesTest {

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

  private static List<ContainerManager.ContainerInfo> listed(ShellExec shell) throws Exception {
    return new BoxInstances(shell).all().stream().map(BoxInstances.Instance::info).toList();
  }

  @Test
  void everyInstanceIsListedWithItsState() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus list --format json", MULTI_JSON);
    var containers = listed(shell);

    assertEquals(3, containers.size());
    assertEquals("acme-health", containers.get(0).name());
    assertInstanceOf(ContainerState.Running.class, containers.get(0).state());
    assertEquals("fintech-api", containers.get(1).name());
    assertInstanceOf(ContainerState.Stopped.class, containers.get(1).state());
    assertEquals("ml-pipeline", containers.get(2).name());
    assertInstanceOf(ContainerState.Running.class, containers.get(2).state());
  }

  @Test
  void theContainersLeaveOutVirtualMachines() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "incus list --format json",
                """
                [{"name": "acme", "status": "Running", "type": "container", "state": {}},
                 {"name": "win", "status": "Stopped", "type": "virtual-machine", "state": {}},
                 {"name": "docs", "status": "Stopped", "type": "container", "state": {}}]
                """);

    var containers = new BoxInstances(shell).containers();

    assertEquals(
        List.of("acme", "docs"), containers.stream().map(BoxInstances.Instance::name).toList());
  }

  @Test
  void instancesCarryTheirPoolKeysAndBirthAsIncusListsThem() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "incus list --format json",
                """
                [{"name": "acme-w", "status": "Frozen", "created_at": "2026-11-12T09:00:00Z",
                  "config": {"user.sail.project": "acme", "user.sail.workspace": "w", "user.blank": ""},
                  "expanded_devices": {"root": {"path": "/", "pool": "tank", "type": "disk"}}},
                 {"name": "bare", "status": "Stopped"}]
                """);

    var instances = new BoxInstances(shell).all();

    var workspace = instances.getFirst();
    assertEquals("acme-w", workspace.name());
    assertEquals(new ContainerState.Frozen(), workspace.state());
    assertEquals("tank", workspace.pool());
    assertEquals("2026-11-12T09:00:00Z", workspace.createdAt());
    assertEquals(Optional.of("acme"), workspace.key("user.sail.project"));
    assertEquals(Optional.empty(), workspace.key("user.blank"));
    assertEquals(Optional.empty(), workspace.key("user.sail.template"));
    var bare = instances.getLast();
    assertEquals(
        new BoxInstances.Instance(
            new ContainerManager.ContainerInfo("bare", new ContainerState.Stopped(), null),
            null,
            null,
            Map.of(),
            null),
        bare);
  }

  @Test
  void instancesFailLoudWhenIncusCannotList() {
    var shell = new ScriptedShellExecutor().onFail("incus list --format json", "daemon down");

    var failure = assertThrows(IOException.class, () -> new BoxInstances(shell).all());

    assertEquals("incus list failed: daemon down", failure.getMessage());
  }

  @Test
  void aBoxWithNoInstanceListsNone() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus list --format json", EMPTY_JSON);
    var containers = listed(shell);

    assertTrue(containers.isEmpty());
  }

  @Test
  void aListIncusRefusesFailsNamingWhy() {
    var shell =
        new ScriptedShellExecutor().onFail("incus list --format json", "daemon not running");
    var ex = assertThrows(IOException.class, () -> listed(shell));
    assertTrue(ex.getMessage().contains("daemon not running"));
  }

  @Test
  void eachInstanceCarriesItsResourceLimits() throws Exception {
    var shell =
        new ScriptedShellExecutor().onOk("incus list --format json", MULTI_WITH_CONFIG_JSON);
    var containers = listed(shell);

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
  void anInstanceWithNoConfigHasNoLimits() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus list --format json", MULTI_JSON);
    var containers = listed(shell);

    for (var c : containers) {
      assertNull(c.limits());
    }
  }

  @Test
  void anInstanceWithEmptyConfigHasNoLimits() throws Exception {
    var shell =
        new ScriptedShellExecutor().onOk("incus list --format json", MULTI_WITH_CONFIG_JSON);
    var containers = listed(shell);

    var personalSite = containers.get(2);
    assertNull(personalSite.limits());
  }

  private static final String EVERY_KIND =
      """
      [{"name": "acme", "status": "Running", "config": {}},
       {"name": "acme-fix", "status": "Running",
        "config": {"user.sail.project": "acme", "user.sail.workspace": "fix", "user.sail.ready": "1",
                   "user.sail.template.of": "acme"}},
       {"name": "acme-half", "status": "Running",
        "config": {"user.sail.project": "acme", "user.sail.workspace": "half"}},
       {"name": "acme--tmpl", "status": "Stopped", "config": {"user.sail.template.of": "acme"}},
       {"name": "acme-backup", "status": "Stopped",
        "config": {"user.sail.project": "acme", "user.sail.workspace": "fix"}},
       {"name": "tmpl-copy", "status": "Stopped", "config": {"user.sail.template.of": "acme"}},
       {"name": "scratch", "status": "Stopped"}]
      """;

  @Test
  void eachInstanceIsOneKindToldFromItsKeysAndItsName() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus list --format json", EVERY_KIND);

    var kinds = new BoxInstances(shell).all().stream().map(BoxInstances.Instance::kind).toList();

    assertEquals(
        List.of(
            new BoxInstances.Plain(),
            new BoxInstances.Workspace("acme", "fix", true),
            new BoxInstances.Workspace("acme", "half", false),
            new BoxInstances.Template("acme"),
            new BoxInstances.Stray("acme", "fix"),
            new BoxInstances.Stray("acme", null),
            new BoxInstances.Plain()),
        kinds);
    assertEquals(
        List.of("acme", "scratch"),
        new BoxInstances(shell).plain().stream().map(BoxInstances.Instance::name).toList());
  }

  @Test
  void aProjectsInstancesAreItsContainerItsWorkspacesAndItsTemplateAndNoCopy() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus list --format json", EVERY_KIND);

    var acme =
        new BoxInstances(shell)
            .all().stream()
                .filter(instance -> instance.belongsTo("acme"))
                .map(BoxInstances.Instance::name)
                .toList();

    assertEquals(List.of("acme", "acme-fix", "acme-half", "acme--tmpl"), acme);
  }

  @Test
  void noProjectIsGivenAnInstanceThatIsAWorkspaceATemplateOrACopy() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus list --format json", EVERY_KIND);
    var box = new BoxInstances(shell);

    assertEquals(
        List.of(
            "Instance 'acme-fix' is workspace 'fix' of project acme, not a project's container:"
                + " project acme-fix may not take it.",
            "Instance 'acme--tmpl' is the template of project acme, not a project's container:"
                + " project acme--tmpl may not take it.",
            "Instance 'acme-backup' is a copy carrying the keys of project acme, not a project's"
                + " container: project acme-backup may not take it."),
        List.of("acme-fix", "acme--tmpl", "acme-backup").stream()
            .map(
                name ->
                    assertThrows(
                            IllegalStateException.class,
                            () -> BoxInstances.requireNotTakenFor(name, box.named(name)))
                        .getMessage())
            .toList());
    BoxInstances.requireNotTakenFor("acme", box.named("acme"));
    BoxInstances.requireNotTakenFor("ghost", box.named("ghost"));
  }
}
