/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.store.ProjectStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectResourceReconcilerTest {

  private static SailYaml.Resources resources(int cpu, String memory, String disk) {
    return new SailYaml.Resources(cpu, memory, disk);
  }

  @Test
  void appliesCpuMemoryAndDiskWhenTheLiveContainerDrifts() throws Exception {
    var shell = new FakeShell().running("acme", "2", "4GB").disk("acme", "50GB");

    var outcome =
        new ProjectResourceReconciler(new ContainerManager(shell))
            .reconcile("acme", resources(8, "16GB", "100GB"));

    assertTrue(outcome.present());
    assertTrue(outcome.limitsApplied());
    assertTrue(outcome.diskApplied());
    assertTrue(shell.ran("incus config set acme limits.cpu=8 limits.memory=16GB"));
    assertTrue(shell.ranPrefix("incus config device override acme root size=100GB"));
  }

  @Test
  void isANoOpWhenAlreadyAtTheDesiredSize() throws Exception {
    var shell = new FakeShell().running("acme", "8", "16GB").disk("acme", "100GB");

    var outcome =
        new ProjectResourceReconciler(new ContainerManager(shell))
            .reconcile("acme", resources(8, "16GB", "100GB"));

    assertFalse(outcome.changed());
    assertFalse(shell.ranPrefix("incus config set acme"), "no limit write");
    assertFalse(shell.ranPrefix("incus config device override"), "no disk write");
    assertFalse(shell.ranPrefix("incus config device set"), "no disk write");
  }

  @Test
  void skipsAContainerThatDoesNotExistYet() throws Exception {
    var shell = new FakeShell();

    var outcome =
        new ProjectResourceReconciler(new ContainerManager(shell))
            .reconcile("ghost", resources(8, "16GB", "100GB"));

    assertFalse(outcome.present());
    assertFalse(outcome.changed());
  }

  @Test
  void reportsButDoesNotFailWhenADiskShrinkIsRefused() throws Exception {
    var shell =
        new FakeShell()
            .running("acme", "8", "16GB")
            .disk("acme", "100GB")
            .failDevice("Failed to update device: not enough space");

    var outcome =
        new ProjectResourceReconciler(new ContainerManager(shell))
            .reconcile("acme", resources(8, "16GB", "20GB"));

    assertTrue(outcome.present());
    assertFalse(outcome.diskApplied());
    assertTrue(outcome.diskSkipped().contains("not enough space"));
  }

  @Test
  void nullResourcesAreSkipped() throws Exception {
    var outcome =
        new ProjectResourceReconciler(new ContainerManager(new FakeShell()))
            .reconcile("acme", null);
    assertFalse(outcome.present());
  }

  @Test
  void reconcileCatalogResizesOnlyDriftedProjectsWithAContainer() {
    var shell =
        new FakeShell()
            .running("acme", "2", "4GB")
            .disk("acme", "50GB")
            .running("static", "8", "16GB")
            .disk("static", "100GB");

    var rows =
        List.of(
            row("acme", "name: acme\nresources:\n  cpu: 8\n  memory: 16GB\n  disk: 100GB\n"),
            row("static", "name: static\nresources:\n  cpu: 8\n  memory: 16GB\n  disk: 100GB\n"),
            row("ghost", "name: ghost\nresources:\n  cpu: 4\n  memory: 8GB\n  disk: 50GB\n"),
            row("noresources", "name: noresources\n"));

    var outcome = new ProjectResourceReconciler(new ContainerManager(shell)).reconcileCatalog(rows);

    assertEquals(List.of("acme"), outcome.resized());
    assertTrue(outcome.diskSkipped().isEmpty());
  }

  private static ProjectStore.ProjectRow row(String name, String definition) {
    return new ProjectStore.ProjectRow(name, definition, "uday", "now", "uday", "now");
  }

  private static final class FakeShell implements ShellExec {
    private final List<String> commands = new ArrayList<>();
    private final java.util.Map<String, Result> listJson = new java.util.HashMap<>();
    private final java.util.Map<String, String> diskByName = new java.util.HashMap<>();
    private String deviceFailure;

    FakeShell running(String name, String cpu, String memory) {
      var json =
          "[{\"name\":\""
              + name
              + "\",\"status\":\"Running\",\"state\":{\"network\":{}},"
              + "\"config\":{\"limits.cpu\":\""
              + cpu
              + "\",\"limits.memory\":\""
              + memory
              + "\"}}]";
      listJson.put(name, new Result(0, json, ""));
      return this;
    }

    FakeShell disk(String name, String size) {
      diskByName.put(name, size);
      return this;
    }

    FakeShell failDevice(String message) {
      this.deviceFailure = message;
      return this;
    }

    boolean ran(String command) {
      return commands.contains(command);
    }

    boolean ranPrefix(String prefix) {
      return commands.stream().anyMatch(c -> c.startsWith(prefix));
    }

    @Override
    public Result exec(List<String> command) {
      commands.add(String.join(" ", command));
      if (command.get(0).equals("incus") && command.get(1).equals("list")) {
        var name = command.get(2).replace("^", "").replace("$", "");
        return listJson.getOrDefault(name, new Result(0, "[]", ""));
      }
      if (command.contains("get")) {
        var name = command.get(4);
        return new Result(0, diskByName.getOrDefault(name, ""), "");
      }
      if (deviceFailure != null && command.contains("device")) {
        return new Result(1, "", deviceFailure);
      }
      return new Result(0, "", "");
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
