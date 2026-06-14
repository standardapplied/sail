/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SailYaml;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class PodmanCommandsTest {

  @Test
  void fullServiceWithAllFields() {
    var env = new LinkedHashMap<String, String>();
    env.put("POSTGRES_DB", "acme");
    env.put("POSTGRES_USER", "dev");
    var service =
        new SailYaml.Service(
            "postgres:16", List.of(5432), env, null, List.of("pgdata:/var/lib/postgresql/data"));

    var cmd = PodmanCommands.buildRunCommand("postgres", service);

    assertEquals(
        List.of(
            "podman",
            "run",
            "-d",
            "--restart=always",
            "--name",
            "postgres",
            "-p",
            "5432:5432",
            "-e",
            "POSTGRES_DB=acme",
            "-e",
            "POSTGRES_USER=dev",
            "-v",
            "pgdata:/var/lib/postgresql/data",
            "postgres:16"),
        cmd);
  }

  @Test
  void minimalServiceImageOnly() {
    var service = new SailYaml.Service("redis:7", null, null, null, null);

    var cmd = PodmanCommands.buildRunCommand("redis", service);

    assertEquals(
        List.of("podman", "run", "-d", "--restart=always", "--name", "redis", "redis:7"), cmd);
  }

  @Test
  void multiplePorts() {
    var service =
        new SailYaml.Service("redpanda:latest", List.of(9092, 8082, 9644), null, null, null);

    var cmd = PodmanCommands.buildRunCommand("redpanda", service);

    assertTrue(cmd.containsAll(List.of("-p", "9092:9092")));
    assertTrue(cmd.containsAll(List.of("-p", "8082:8082")));
    assertTrue(cmd.containsAll(List.of("-p", "9644:9644")));
  }

  @Test
  void customCommandIsSplitOnWhitespace() {
    var service =
        new SailYaml.Service(
            "redpanda:latest", null, null, "redpanda start --smp 1 --memory 512M", null);

    var cmd = PodmanCommands.buildRunCommand("redpanda", service);

    var imageIdx = cmd.indexOf("redpanda:latest");
    assertTrue(imageIdx > 0);
    assertEquals("redpanda", cmd.get(imageIdx + 1));
    assertEquals("start", cmd.get(imageIdx + 2));
    assertEquals("--smp", cmd.get(imageIdx + 3));
    assertEquals("1", cmd.get(imageIdx + 4));
    assertEquals("--memory", cmd.get(imageIdx + 5));
    assertEquals("512M", cmd.get(imageIdx + 6));
  }

  @Test
  void emptyCommandIsIgnored() {
    var service = new SailYaml.Service("postgres:16", null, null, "", null);

    var cmd = PodmanCommands.buildRunCommand("postgres", service);

    assertEquals("postgres:16", cmd.getLast());
  }

  @Test
  void blankCommandIsIgnored() {
    var service = new SailYaml.Service("postgres:16", null, null, "   ", null);

    var cmd = PodmanCommands.buildRunCommand("postgres", service);

    assertEquals("postgres:16", cmd.getLast());
  }

  @Test
  void multipleVolumes() {
    var service =
        new SailYaml.Service(
            "meilisearch:latest",
            null,
            null,
            null,
            List.of("msdata:/meili_data", "msconfig:/config"));

    var cmd = PodmanCommands.buildRunCommand("meilisearch", service);

    var firstV = cmd.indexOf("-v");
    assertEquals("msdata:/meili_data", cmd.get(firstV + 1));
    var secondV = cmd.subList(firstV + 1, cmd.size()).indexOf("-v") + firstV + 1;
    assertEquals("msconfig:/config", cmd.get(secondV + 1));
  }

  @Test
  void returnedListIsImmutable() {
    var service = new SailYaml.Service("redis:7", null, null, null, null);

    var cmd = PodmanCommands.buildRunCommand("redis", service);

    assertThrows(UnsupportedOperationException.class, () -> cmd.add("extra"));
  }

  @Test
  void environmentOrderPreserved() {
    var env = new LinkedHashMap<String, String>();
    env.put("A", "1");
    env.put("B", "2");
    env.put("C", "3");
    var service = new SailYaml.Service("img:latest", null, env, null, null);

    var cmd = PodmanCommands.buildRunCommand("svc", service);

    var firstE = cmd.indexOf("-e");
    assertEquals("A=1", cmd.get(firstE + 1));
    assertEquals("-e", cmd.get(firstE + 2));
    assertEquals("B=2", cmd.get(firstE + 3));
    assertEquals("-e", cmd.get(firstE + 4));
    assertEquals("C=3", cmd.get(firstE + 5));
  }
}
