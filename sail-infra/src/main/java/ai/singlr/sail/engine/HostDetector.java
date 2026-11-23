/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Detects host system information: OS, CPU, RAM, hostname. Reads everything from files under {@code
 * /etc} and {@code /proc} — no shell commands needed, works in dry-run mode.
 */
public final class HostDetector {

  private static final String SUPPORTED_OS = "ubuntu";
  private static final double MIN_VERSION = 24.04;

  private final Path osReleasePath;
  private final Path memInfoPath;
  private final Path hostnamePath;
  private final Path cpuInfoPath;

  public HostDetector() {
    this(
        Path.of("/etc/os-release"),
        Path.of("/proc/meminfo"),
        Path.of("/etc/hostname"),
        Path.of("/proc/cpuinfo"));
  }

  /** Constructor with injectable paths for testing. */
  public HostDetector(Path osReleasePath, Path memInfoPath, Path hostnamePath, Path cpuInfoPath) {
    this.osReleasePath = osReleasePath;
    this.memInfoPath = memInfoPath;
    this.hostnamePath = hostnamePath;
    this.cpuInfoPath = cpuInfoPath;
  }

  /** Detected host information. */
  public record HostInfo(
      String hostname,
      String osId,
      String osVersionId,
      String osPrettyName,
      int cores,
      int threads,
      long memoryMb,
      boolean supported) {}

  /** Detects host system information. All reads are from files — no shell commands needed. */
  public HostInfo detect() throws IOException {
    var osRelease = parseOsRelease();
    var osId = osRelease.getOrDefault("ID", "unknown");
    var versionId = osRelease.getOrDefault("VERSION_ID", "0");
    var prettyName = osRelease.getOrDefault("PRETTY_NAME", osId + " " + versionId);
    var hostname = readHostname();
    var cpuLines = Files.readAllLines(cpuInfoPath);
    var cores = countCores(cpuLines);
    var threads = countProcessors(cpuLines);
    var memoryMb = parseMemInfo();
    var supported = isSupported(osId, versionId);

    return new HostInfo(hostname, osId, versionId, prettyName, cores, threads, memoryMb, supported);
  }

  /** Parses /etc/os-release into a key-value map. Visible for testing. */
  Map<String, String> parseOsRelease() throws IOException {
    return parseOsRelease(Files.readAllLines(osReleasePath));
  }

  /** Parses os-release lines into a key-value map. Visible for testing. */
  static Map<String, String> parseOsRelease(List<String> lines) {
    var map = new HashMap<String, String>();
    for (var line : lines) {
      var eq = line.indexOf('=');
      if (eq > 0) {
        var key = line.substring(0, eq);
        var value = line.substring(eq + 1);
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
          value = value.substring(1, value.length() - 1);
        }
        map.put(key, value);
      }
    }
    return map;
  }

  /** Parses /proc/meminfo for MemTotal, returns MB. Visible for testing. */
  long parseMemInfo() throws IOException {
    return parseMemInfo(Files.readAllLines(memInfoPath));
  }

  /** Parses meminfo lines for MemTotal, returns MB. Visible for testing. */
  static long parseMemInfo(List<String> lines) {
    for (var line : lines) {
      if (line.startsWith("MemTotal:")) {
        var trimmed = line.substring("MemTotal:".length()).trim();
        var parts = trimmed.split("\\s+");
        if (parts.length >= 1) {
          return Long.parseLong(parts[0]) / 1024;
        }
      }
    }
    return 0;
  }

  static boolean isSupported(String osId, String versionId) {
    if (!SUPPORTED_OS.equals(osId)) {
      return false;
    }
    try {
      return Double.parseDouble(versionId) >= MIN_VERSION;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /** Reads hostname from /etc/hostname, falling back to "hostname" shell command. */
  private String readHostname() throws IOException {
    if (Files.exists(hostnamePath)) {
      return Files.readString(hostnamePath).strip();
    }
    try {
      var pb = new ProcessBuilder("hostname");
      pb.redirectErrorStream(true);
      var process = pb.start();
      process.getOutputStream().close();
      process.getErrorStream().close();
      String output;
      try (var printed = process.getInputStream()) {
        output = new String(printed.readAllBytes(), StandardCharsets.UTF_8).strip();
      }
      process.waitFor();
      if (!output.isEmpty()) {
        return output;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception ignored) {
    }
    return "unknown";
  }

  /** Counts logical processors (threads) by counting "processor" lines in /proc/cpuinfo. */
  static int countProcessors(List<String> lines) {
    var count = 0;
    for (var line : lines) {
      if (line.startsWith("processor")) {
        count++;
      }
    }
    return count;
  }

  /**
   * Counts physical cores by counting unique {@code (physical id, core id)} pairs in /proc/cpuinfo.
   * Falls back to thread count if physical/core id lines are absent.
   */
  static int countCores(List<String> lines) {
    var seen = new HashSet<String>();
    String physicalId = null;
    for (var line : lines) {
      if (line.startsWith("physical id")) {
        physicalId = line.substring(line.indexOf(':') + 1).trim();
      } else if (line.startsWith("core id")) {
        var coreId = line.substring(line.indexOf(':') + 1).trim();
        if (physicalId != null) {
          seen.add(physicalId + ":" + coreId);
        }
      }
    }
    return seen.isEmpty() ? countProcessors(lines) : seen.size();
  }
}
