/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncusDeviceManagerTest {

  @TempDir Path locks;

  private static final String CONTAINER = "light-grid";
  private static final Path HOST = Path.of("/var/lib/sail/run/api.sock");
  private static final Path GUEST = Path.of("/var/lib/sail/run/api.sock");

  @Test
  void constructorRejectsNullShell() {
    assertThrows(NullPointerException.class, () -> new IncusDeviceManager(null));
  }

  @Test
  void ensureAddsDeviceWhenAbsent() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("config device get " + CONTAINER, "Device not found")
            .onOk("config device add " + CONTAINER);
    var mgr = new IncusDeviceManager(shell, locks);

    var result = mgr.ensureEventSocket(CONTAINER, HOST, GUEST);

    assertEquals(IncusDeviceManager.EnsureResult.ADDED, result);
    var added =
        shell.invocations().stream()
            .anyMatch(cmd -> cmd.contains("config device add " + CONTAINER));
    assertTrue(added);
  }

  @Test
  void ensureSkipsWhenSameSourceExists() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("config device get " + CONTAINER, HOST + "\n");
    var mgr = new IncusDeviceManager(shell, locks);

    var result = mgr.ensureEventSocket(CONTAINER, HOST, GUEST);

    assertEquals(IncusDeviceManager.EnsureResult.ALREADY_PRESENT, result);
    var noAdd = shell.invocations().stream().noneMatch(cmd -> cmd.contains("config device add"));
    assertTrue(noAdd);
  }

  @Test
  void ensureReplacesWhenSourceDiffers() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("config device get " + CONTAINER, "/old/path/sail.sock\n")
            .onOk("config device remove " + CONTAINER)
            .onOk("config device add " + CONTAINER);
    var mgr = new IncusDeviceManager(shell, locks);

    var result = mgr.ensureEventSocket(CONTAINER, HOST, GUEST);

    assertEquals(IncusDeviceManager.EnsureResult.REPLACED, result);
    var sawRemove = shell.invocations().stream().anyMatch(c -> c.contains("config device remove"));
    var sawAdd = shell.invocations().stream().anyMatch(c -> c.contains("config device add"));
    assertTrue(sawRemove);
    assertTrue(sawAdd);
  }

  @Test
  void ensureRejectsInvalidContainerName() {
    var mgr = new IncusDeviceManager(new ScriptedShellExecutor(), locks);
    assertThrows(Exception.class, () -> mgr.ensureEventSocket("../bad", HOST, GUEST));
  }

  @Test
  void ensureRejectsNullPaths() {
    var mgr = new IncusDeviceManager(new ScriptedShellExecutor(), locks);
    assertThrows(NullPointerException.class, () -> mgr.ensureEventSocket(CONTAINER, null, GUEST));
    assertThrows(NullPointerException.class, () -> mgr.ensureEventSocket(CONTAINER, HOST, null));
  }

  @Test
  void addFailurePropagates() {
    var shell =
        new ScriptedShellExecutor()
            .onFail("config device get " + CONTAINER, "not found")
            .onFail("config device add " + CONTAINER, "permission denied");
    var mgr = new IncusDeviceManager(shell, locks);

    var ex = assertThrows(IOException.class, () -> mgr.ensureEventSocket(CONTAINER, HOST, GUEST));
    assertTrue(ex.getMessage().contains("permission denied"));
  }

  @Test
  void aRemoveRefusedWhileReplacingSaysWhy() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("config device get " + CONTAINER, "/old/path\n")
            .onFail("config device remove " + CONTAINER, "rejected");
    var mgr = new IncusDeviceManager(shell, locks);

    var ex = assertThrows(IOException.class, () -> mgr.ensureEventSocket(CONTAINER, HOST, GUEST));

    assertEquals("Failed to remove sail-api-sock from light-grid: rejected", ex.getMessage());
  }

  @Test
  void currentSourceNullWhenAbsent() throws Exception {
    var shell = new ScriptedShellExecutor().onFail("config device get " + CONTAINER, "missing");
    assertNull(new IncusDeviceManager(shell, locks).currentEventSocketSource(CONTAINER));
  }

  @Test
  void currentSourceReturnsValue() throws Exception {
    var shell =
        new ScriptedShellExecutor().onOk("config device get " + CONTAINER, "/some/host/sock\n");
    assertEquals(
        "/some/host/sock",
        new IncusDeviceManager(shell, locks).currentEventSocketSource(CONTAINER));
  }

  @Test
  void listDevicesParsesYamlKeys() throws Exception {
    var output =
        """
        eth0:
          name: eth0
          type: nic
        root:
          type: disk
        """;
    var shell = new ScriptedShellExecutor().onOk("config device show " + CONTAINER, output);

    var devices = new IncusDeviceManager(shell, locks).listDevices(CONTAINER);

    assertTrue(devices.contains("eth0"));
    assertTrue(devices.contains("root"));
  }

  @Test
  void listDevicesEmptyWhenBlank() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("config device show " + CONTAINER, "");

    assertTrue(new IncusDeviceManager(shell, locks).listDevices(CONTAINER).isEmpty());
  }

  @Test
  void listDevicesPropagatesFailure() {
    var shell = new ScriptedShellExecutor().onFail("config device show", "boom");

    assertThrows(
        IOException.class, () -> new IncusDeviceManager(shell, locks).listDevices(CONTAINER));
  }

  @Test
  void allOnlyTargetsSailManagedContainers() throws Exception {
    var probe =
        new ScriptedShellExecutor()
            .onOk("incus config device get legacy sail-api-sock source", "/run/sail/api");
    var devices = new IncusDeviceManager(probe);

    assertTrue(
        devices.sailManaged("legacy"),
        "the API-socket device is the instance-level provenance Sail itself attached");
    assertFalse(
        devices.sailManaged("web"),
        "a catalog row is name-level intent, never instance provenance: a stale entry left by"
            + " destroy-without-purge must not hand a foreign container with a colliding name"
            + " the API mount and box credential");
    assertFalse(
        devices.sailManaged("foreign"),
        "an unrelated Incus instance must never receive the API mount and box credential");
  }

  @Test
  void anInvalidInstanceNameIsForeignBeforeAnyProbeRuns() throws Exception {
    var probe = new ScriptedShellExecutor();
    var devices = new IncusDeviceManager(probe);

    assertFalse(devices.sailManaged("Not_A_Project"));
    assertTrue(probe.invocations().isEmpty(), "an invalid name must never reach incus");
  }
}
