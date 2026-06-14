/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContainerExecTest {

  @Test
  void asDevUserBuildsCorrectPrefix() {
    var cmd = ContainerExec.asDevUser("acme", List.of("podman", "ps"));

    assertEquals("incus", cmd.get(0));
    assertEquals("exec", cmd.get(1));
    assertEquals("acme", cmd.get(2));
    assertEquals("--user", cmd.get(3));
    assertEquals("1000", cmd.get(4));
    assertEquals("--group", cmd.get(5));
    assertEquals("1000", cmd.get(6));
    assertEquals("--env", cmd.get(7));
    assertEquals("HOME=/home/dev", cmd.get(8));
    assertEquals("--env", cmd.get(9));
    assertEquals("XDG_RUNTIME_DIR=/run/user/1000", cmd.get(10));
    assertEquals("--env", cmd.get(11));
    assertEquals("DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus", cmd.get(12));
    assertEquals("--", cmd.get(13));
  }

  @Test
  void asDevUserAppendsArgs() {
    var cmd =
        ContainerExec.asDevUser("proj", List.of("podman", "logs", "--tail", "50", "postgres"));

    assertEquals("podman", cmd.get(14));
    assertEquals("logs", cmd.get(15));
    assertEquals("--tail", cmd.get(16));
    assertEquals("50", cmd.get(17));
    assertEquals("postgres", cmd.get(18));
    assertEquals(19, cmd.size());
  }

  @Test
  void asDevUserWithEmptyArgs() {
    var cmd = ContainerExec.asDevUser("test", List.of());

    assertEquals(14, cmd.size());
    assertEquals("--", cmd.getLast());
  }

  @Test
  void asDevUserRejectsInvalidContainerName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ContainerExec.asDevUser("proj;touch-owned", List.of("echo", "hi")));
  }

  @Test
  void asDevUserReturnsUnmodifiableList() {
    var cmd = ContainerExec.asDevUser("proj", List.of("echo", "hi"));

    assertThrows(UnsupportedOperationException.class, () -> cmd.add("extra"));
  }

  @Test
  void queryServicePortsExtractsHostPorts() throws Exception {
    var podmanJson =
        """
        [
          {
            "Names": ["postgres"],
            "Image": "postgres:16",
            "Ports": [
              {"host_ip": "", "container_port": 5432, "host_port": 5432, "range": 1, "protocol": "tcp"}
            ]
          },
          {
            "Names": ["redis"],
            "Image": "redis:7",
            "Ports": [
              {"host_ip": "", "container_port": 6379, "host_port": 6379, "range": 1, "protocol": "tcp"}
            ]
          }
        ]
        """;
    var shell = new ScriptedShellExecutor().onOk("podman ps --format json", podmanJson);

    var ports = ContainerExec.queryServicePorts(shell, "acme");

    assertEquals(List.of(5432, 6379), ports);
  }

  @Test
  void queryServicePortsReturnsSorted() throws Exception {
    var podmanJson =
        """
        [
          {
            "Names": ["redis"],
            "Ports": [
              {"host_port": 6379, "container_port": 6379}
            ]
          },
          {
            "Names": ["postgres"],
            "Ports": [
              {"host_port": 5432, "container_port": 5432}
            ]
          },
          {
            "Names": ["kafka"],
            "Ports": [
              {"host_port": 9092, "container_port": 9092}
            ]
          }
        ]
        """;
    var shell = new ScriptedShellExecutor().onOk("podman ps --format json", podmanJson);

    var ports = ContainerExec.queryServicePorts(shell, "proj");

    assertEquals(List.of(5432, 6379, 9092), ports);
  }

  @Test
  void queryServicePortsDeduplicates() throws Exception {
    var podmanJson =
        """
        [
          {
            "Names": ["svc1"],
            "Ports": [
              {"host_port": 5432, "container_port": 5432}
            ]
          },
          {
            "Names": ["svc2"],
            "Ports": [
              {"host_port": 5432, "container_port": 5432}
            ]
          }
        ]
        """;
    var shell = new ScriptedShellExecutor().onOk("podman ps --format json", podmanJson);

    var ports = ContainerExec.queryServicePorts(shell, "proj");

    assertEquals(List.of(5432), ports);
  }

  @Test
  void queryServicePortsReturnsEmptyOnFailure() throws Exception {
    var shell = new ScriptedShellExecutor().onFail("podman ps", "error");

    var ports = ContainerExec.queryServicePorts(shell, "proj");

    assertEquals(List.of(), ports);
  }

  @Test
  void queryServicePortsReturnsEmptyForNoContainers() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("podman ps --format json", "[]");

    var ports = ContainerExec.queryServicePorts(shell, "proj");

    assertEquals(List.of(), ports);
  }

  @Test
  void queryServicePortsSkipsContainersWithNoPorts() throws Exception {
    var podmanJson =
        """
        [
          {
            "Names": ["app"],
            "Image": "myapp:latest"
          },
          {
            "Names": ["postgres"],
            "Ports": [
              {"host_port": 5432, "container_port": 5432}
            ]
          }
        ]
        """;
    var shell = new ScriptedShellExecutor().onOk("podman ps --format json", podmanJson);

    var ports = ContainerExec.queryServicePorts(shell, "proj");

    assertEquals(List.of(5432), ports);
  }
}
