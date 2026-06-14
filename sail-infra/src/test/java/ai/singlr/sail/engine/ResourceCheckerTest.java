/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResourceCheckerTest {

  @Test
  void parseMemoryGb() {
    assertEquals(8192, ResourceChecker.parseMemoryMb("8GB"));
  }

  @Test
  void parseMemoryGbLowerCase() {
    assertEquals(12288, ResourceChecker.parseMemoryMb("12gb"));
  }

  @Test
  void parseMemoryMb() {
    assertEquals(512, ResourceChecker.parseMemoryMb("512MB"));
  }

  @Test
  void parseMemoryGiB() {
    assertEquals(12288, ResourceChecker.parseMemoryMb("12GiB"));
  }

  @Test
  void parseMemoryMiB() {
    assertEquals(1024, ResourceChecker.parseMemoryMb("1024MiB"));
  }

  @Test
  void parseMemoryNull() {
    assertEquals(0, ResourceChecker.parseMemoryMb(null));
  }

  @Test
  void parseMemoryBlank() {
    assertEquals(0, ResourceChecker.parseMemoryMb(""));
  }

  @Test
  void parseMemoryUnparseable() {
    assertEquals(0, ResourceChecker.parseMemoryMb("lots"));
  }

  @Test
  void parseMemoryFractional() {
    assertEquals(1536, ResourceChecker.parseMemoryMb("1.5GB"));
  }

  @Test
  void noOvercommit() {
    var containers =
        List.of(info("proj-a", "Running", "4", "8GB"), info("proj-b", "Running", "4", "8GB"));
    var host = new ResourceChecker.HostCapacity(12, 32768);

    var result = ResourceChecker.check(containers, host);

    assertFalse(result.isOvercommitted());
    assertEquals(8, result.allocatedCpu());
    assertEquals(16384, result.allocatedMemoryMb());
  }

  @Test
  void cpuOvercommitOnly() {
    var containers =
        List.of(
            info("proj-a", "Running", "4", "8GB"),
            info("proj-b", "Running", "4", "8GB"),
            info("proj-c", "Running", "4", "8GB"));
    var host = new ResourceChecker.HostCapacity(6, 65536);

    var result = ResourceChecker.check(containers, host);

    assertTrue(result.cpuOvercommitted());
    assertFalse(result.memoryOvercommitted());
    assertTrue(result.isOvercommitted());
    assertEquals(12, result.allocatedCpu());
  }

  @Test
  void memoryOvercommitOnly() {
    var containers =
        List.of(
            info("proj-a", "Running", "2", "12GB"),
            info("proj-b", "Running", "2", "12GB"),
            info("proj-c", "Running", "2", "12GB"));
    var host = new ResourceChecker.HostCapacity(12, 32768);

    var result = ResourceChecker.check(containers, host);

    assertFalse(result.cpuOvercommitted());
    assertTrue(result.memoryOvercommitted());
    assertTrue(result.isOvercommitted());
    assertEquals(36864, result.allocatedMemoryMb());
  }

  @Test
  void bothOvercommitted() {
    var containers =
        List.of(info("proj-a", "Running", "6", "16GB"), info("proj-b", "Running", "6", "16GB"));
    var host = new ResourceChecker.HostCapacity(6, 32768);

    var result = ResourceChecker.check(containers, host);

    assertTrue(result.cpuOvercommitted());
    assertFalse(result.memoryOvercommitted());
    assertTrue(result.isOvercommitted());
  }

  @Test
  void nullLimitsExcludedFromSum() {
    var containers =
        List.of(
            info("proj-a", "Running", "4", "8GB"),
            new ContainerManager.ContainerInfo(
                "proj-b", new ContainerState.Running("10.0.0.2"), null));
    var host = new ResourceChecker.HostCapacity(12, 32768);

    var result = ResourceChecker.check(containers, host);

    assertFalse(result.isOvercommitted());
    assertEquals(4, result.allocatedCpu());
    assertEquals(8192, result.allocatedMemoryMb());
  }

  @Test
  void emptyRunningListNotOvercommitted() {
    var host = new ResourceChecker.HostCapacity(12, 32768);

    var result = ResourceChecker.check(List.of(), host);

    assertFalse(result.isOvercommitted());
    assertEquals(0, result.allocatedCpu());
    assertEquals(0, result.allocatedMemoryMb());
  }

  @Test
  void nullCpuInLimitsSkipped() {
    var containers =
        List.of(
            new ContainerManager.ContainerInfo(
                "proj-a",
                new ContainerState.Running("10.0.0.1"),
                new ContainerManager.ResourceLimits(null, "8GB")));
    var host = new ResourceChecker.HostCapacity(12, 32768);

    var result = ResourceChecker.check(containers, host);

    assertEquals(0, result.allocatedCpu());
    assertEquals(8192, result.allocatedMemoryMb());
  }

  private static ContainerManager.ContainerInfo info(
      String name, String status, String cpu, String memory) {
    var state =
        "Running".equals(status)
            ? new ContainerState.Running("10.0.0.1")
            : new ContainerState.Stopped();
    return new ContainerManager.ContainerInfo(
        name, state, new ContainerManager.ResourceLimits(cpu, memory));
  }
}
