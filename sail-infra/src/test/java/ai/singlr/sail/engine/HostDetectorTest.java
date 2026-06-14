/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostDetectorTest {

  @Test
  void parsesOsRelease() {
    var lines =
        List.of(
            "PRETTY_NAME=\"Ubuntu 24.04.1 LTS\"",
            "NAME=\"Ubuntu\"",
            "VERSION_ID=\"24.04\"",
            "VERSION=\"24.04.1 LTS (Noble Numbat)\"",
            "ID=ubuntu",
            "ID_LIKE=debian");

    var map = HostDetector.parseOsRelease(lines);

    assertEquals("Ubuntu 24.04.1 LTS", map.get("PRETTY_NAME"));
    assertEquals("ubuntu", map.get("ID"));
    assertEquals("24.04", map.get("VERSION_ID"));
    assertEquals("debian", map.get("ID_LIKE"));
  }

  @Test
  void parsesOsReleaseWithoutQuotes() {
    var lines = List.of("ID=fedora", "VERSION_ID=41");

    var map = HostDetector.parseOsRelease(lines);

    assertEquals("fedora", map.get("ID"));
    assertEquals("41", map.get("VERSION_ID"));
  }

  @Test
  void parsesMemInfo() {
    var lines =
        List.of(
            "MemTotal:       32631920 kB",
            "MemFree:         1234567 kB",
            "MemAvailable:   20000000 kB");

    var mb = HostDetector.parseMemInfo(lines);

    assertEquals(31867, mb);
  }

  @Test
  void parsesMemInfoReturnsZeroIfMissing() {
    var lines = List.of("MemFree:         1234567 kB");

    var mb = HostDetector.parseMemInfo(lines);

    assertEquals(0, mb);
  }

  @Test
  void ubuntuTwentyFourIsSupported() {
    assertTrue(HostDetector.isSupported("ubuntu", "24.04"));
  }

  @Test
  void ubuntuTwentyFourPointOneIsSupported() {
    assertTrue(HostDetector.isSupported("ubuntu", "24.10"));
  }

  @Test
  void ubuntuTwentyTwoIsNotSupported() {
    assertFalse(HostDetector.isSupported("ubuntu", "22.04"));
  }

  @Test
  void fedoraIsNotSupported() {
    assertFalse(HostDetector.isSupported("fedora", "41"));
  }

  @Test
  void invalidVersionIsNotSupported() {
    assertFalse(HostDetector.isSupported("ubuntu", "rolling"));
  }

  @Test
  void countsProcessors() {
    var lines =
        List.of(
            "processor\t: 0",
            "model name\t: Intel Xeon E-2286G",
            "processor\t: 1",
            "model name\t: Intel Xeon E-2286G",
            "processor\t: 2",
            "model name\t: Intel Xeon E-2286G");

    assertEquals(3, HostDetector.countProcessors(lines));
  }

  @Test
  void countsZeroProcessorsForEmpty() {
    assertEquals(0, HostDetector.countProcessors(List.of()));
  }

  @Test
  void countsCoresFromPhysicalAndCoreId() {
    var lines =
        List.of(
            "processor\t: 0",
            "physical id\t: 0",
            "core id\t\t: 0",
            "processor\t: 1",
            "physical id\t: 0",
            "core id\t\t: 1",
            "processor\t: 2",
            "physical id\t: 0",
            "core id\t\t: 2",
            "processor\t: 3",
            "physical id\t: 0",
            "core id\t\t: 3",
            "processor\t: 4",
            "physical id\t: 0",
            "core id\t\t: 4",
            "processor\t: 5",
            "physical id\t: 0",
            "core id\t\t: 5",
            "processor\t: 6",
            "physical id\t: 0",
            "core id\t\t: 0",
            "processor\t: 7",
            "physical id\t: 0",
            "core id\t\t: 1",
            "processor\t: 8",
            "physical id\t: 0",
            "core id\t\t: 2",
            "processor\t: 9",
            "physical id\t: 0",
            "core id\t\t: 3",
            "processor\t: 10",
            "physical id\t: 0",
            "core id\t\t: 4",
            "processor\t: 11",
            "physical id\t: 0",
            "core id\t\t: 5");

    assertEquals(6, HostDetector.countCores(lines));
    assertEquals(12, HostDetector.countProcessors(lines));
  }

  @Test
  void countsCoresFallsBackToThreadsWhenNoCoreId() {
    var lines = List.of("processor\t: 0", "processor\t: 1", "processor\t: 2");

    assertEquals(3, HostDetector.countCores(lines));
  }

  @Test
  void countsCoresMultiSocket() {
    var lines =
        List.of(
            "processor\t: 0",
            "physical id\t: 0",
            "core id\t\t: 0",
            "processor\t: 1",
            "physical id\t: 1",
            "core id\t\t: 0");

    assertEquals(2, HostDetector.countCores(lines));
  }

  @TempDir Path tempDir;

  @Test
  void detectReadsAllFilesEndToEnd() throws Exception {
    var osRelease = tempDir.resolve("os-release");
    Files.writeString(osRelease, "ID=ubuntu\nVERSION_ID=\"24.04\"\nPRETTY_NAME=\"Ubuntu 24.04\"\n");
    var memInfo = tempDir.resolve("meminfo");
    Files.writeString(memInfo, "MemTotal:       16384000 kB\nMemFree:       8000000 kB\n");
    var hostname = tempDir.resolve("hostname");
    Files.writeString(hostname, "test-host\n");
    var cpuInfo = tempDir.resolve("cpuinfo");
    Files.writeString(cpuInfo, "processor\t: 0\nmodel\t: Xeon\nprocessor\t: 1\nmodel\t: Xeon\n");

    var detector = new HostDetector(osRelease, memInfo, hostname, cpuInfo);
    var info = detector.detect();

    assertEquals("test-host", info.hostname());
    assertEquals("ubuntu", info.osId());
    assertEquals("24.04", info.osVersionId());
    assertEquals("Ubuntu 24.04", info.osPrettyName());
    assertEquals(2, info.cores());
    assertEquals(2, info.threads());
    assertEquals(16000, info.memoryMb());
    assertTrue(info.supported());
  }

  @Test
  void detectUnsupportedOs() throws Exception {
    var osRelease = tempDir.resolve("os-release2");
    Files.writeString(osRelease, "ID=fedora\nVERSION_ID=41\nPRETTY_NAME=\"Fedora 41\"\n");
    var memInfo = tempDir.resolve("meminfo2");
    Files.writeString(memInfo, "MemTotal:       8192000 kB\n");
    var hostname = tempDir.resolve("hostname2");
    Files.writeString(hostname, "fedora-box\n");
    var cpuInfo = tempDir.resolve("cpuinfo2");
    Files.writeString(cpuInfo, "processor\t: 0\n");

    var detector = new HostDetector(osRelease, memInfo, hostname, cpuInfo);
    var info = detector.detect();

    assertEquals("fedora", info.osId());
    assertFalse(info.supported());
  }

  @Test
  void detectFallsBackWhenHostnameFileMissing() throws Exception {
    var osRelease = tempDir.resolve("os-release3");
    Files.writeString(osRelease, "ID=ubuntu\nVERSION_ID=\"24.04\"\n");
    var memInfo = tempDir.resolve("meminfo3");
    Files.writeString(memInfo, "MemTotal:       8192000 kB\n");
    var missingHostname = tempDir.resolve("nonexistent-hostname");
    var cpuInfo = tempDir.resolve("cpuinfo3");
    Files.writeString(cpuInfo, "processor\t: 0\n");

    var detector = new HostDetector(osRelease, memInfo, missingHostname, cpuInfo);
    var info = detector.detect();

    assertNotNull(info.hostname());
    assertFalse(info.hostname().isEmpty());
  }

  @Test
  void parsesOsReleaseSkipsBlankLines() {
    var map = HostDetector.parseOsRelease(List.of("", "ID=ubuntu", "", "VERSION_ID=24.04"));
    assertEquals("ubuntu", map.get("ID"));
    assertEquals("24.04", map.get("VERSION_ID"));
  }

  @Test
  void parsesOsReleaseSingleCharValue() {
    var map = HostDetector.parseOsRelease(List.of("X=a"));
    assertEquals("a", map.get("X"));
  }

  @Test
  void parsesOsReleaseUnmatchedQuote() {
    var map = HostDetector.parseOsRelease(List.of("NAME=\"Ubuntu Broken"));
    assertEquals("\"Ubuntu Broken", map.get("NAME"));
  }

  @Test
  void parsesOsReleaseLineWithNoEquals() {
    var map = HostDetector.parseOsRelease(List.of("no-equals-here", "ID=ubuntu"));
    assertEquals("ubuntu", map.get("ID"));
    assertEquals(1, map.size());
  }

  @Test
  void parseMemInfoReturnsZeroWhenMemTotalAbsent() {
    var mb = HostDetector.parseMemInfo(List.of("MemFree:       1234 kB"));
    assertEquals(0, mb);
  }
}
