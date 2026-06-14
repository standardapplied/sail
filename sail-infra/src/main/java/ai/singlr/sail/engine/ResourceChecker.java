/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Checks whether running containers overcommit host resources. Pure utility — no shell execution,
 * no side effects.
 */
public final class ResourceChecker {

  private ResourceChecker() {}

  private static final Pattern MEMORY_PATTERN =
      Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*(GB|GiB|MB|MiB)$", Pattern.CASE_INSENSITIVE);

  /** Host resource capacity (logical threads and memory in MB). */
  public record HostCapacity(int threads, long memoryMb) {}

  /** Result of an overcommit check. */
  public record OvercommitStatus(
      int allocatedCpu, int hostThreads, long allocatedMemoryMb, long hostMemoryMb) {

    public boolean cpuOvercommitted() {
      return allocatedCpu > hostThreads;
    }

    public boolean memoryOvercommitted() {
      return allocatedMemoryMb > hostMemoryMb;
    }

    public boolean isOvercommitted() {
      return cpuOvercommitted() || memoryOvercommitted();
    }
  }

  /**
   * Parses a memory string like "8GB", "512MB", "12GiB", "1024MiB" into megabytes. Returns 0 for
   * null, blank, or unparseable values.
   */
  public static long parseMemoryMb(String memory) {
    if (Strings.isBlank(memory)) {
      return 0;
    }
    var matcher = MEMORY_PATTERN.matcher(memory.trim());
    if (!matcher.matches()) {
      return 0;
    }
    var value = Double.parseDouble(matcher.group(1));
    var unit = matcher.group(2).toUpperCase();
    return switch (unit) {
      case "GB" -> Math.round(value * 1024);
      case "GIB" -> Math.round(value * 1024);
      case "MB" -> Math.round(value);
      case "MIB" -> Math.round(value);
      default -> 0;
    };
  }

  /**
   * Checks overcommit for a list of running containers against host capacity. Containers with null
   * resource limits are excluded from the sum.
   */
  public static OvercommitStatus check(
      List<ContainerManager.ContainerInfo> running, HostCapacity host) {
    var totalCpu = 0;
    var totalMemoryMb = 0L;
    for (var container : running) {
      var limits = container.limits();
      if (limits == null) {
        continue;
      }
      if (limits.cpu() != null) {
        try {
          totalCpu += Integer.parseInt(limits.cpu());
        } catch (NumberFormatException ignored) {
        }
      }
      totalMemoryMb += parseMemoryMb(limits.memory());
    }
    return new OvercommitStatus(totalCpu, host.threads(), totalMemoryMb, host.memoryMb());
  }
}
