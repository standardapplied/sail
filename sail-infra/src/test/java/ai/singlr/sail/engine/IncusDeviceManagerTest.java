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

class IncusDeviceManagerTest {

  private static final String CONTAINER = "light-grid";
  private static final Path HOST = Path.of("/run/user/1000/sail/api.sock");
  private static final Path GUEST = Path.of("/run/sail/api.sock");

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
    var mgr = new IncusDeviceManager(shell);

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
    var mgr = new IncusDeviceManager(shell);

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
    var mgr = new IncusDeviceManager(shell);

    var result = mgr.ensureEventSocket(CONTAINER, HOST, GUEST);

    assertEquals(IncusDeviceManager.EnsureResult.REPLACED, result);
    var sawRemove = shell.invocations().stream().anyMatch(c -> c.contains("config device remove"));
    var sawAdd = shell.invocations().stream().anyMatch(c -> c.contains("config device add"));
    assertTrue(sawRemove);
    assertTrue(sawAdd);
  }

  @Test
  void ensureRejectsInvalidContainerName() {
    var mgr = new IncusDeviceManager(new ScriptedShellExecutor());
    assertThrows(Exception.class, () -> mgr.ensureEventSocket("../bad", HOST, GUEST));
  }

  @Test
  void ensureRejectsNullPaths() {
    var mgr = new IncusDeviceManager(new ScriptedShellExecutor());
    assertThrows(NullPointerException.class, () -> mgr.ensureEventSocket(CONTAINER, null, GUEST));
    assertThrows(NullPointerException.class, () -> mgr.ensureEventSocket(CONTAINER, HOST, null));
  }

  @Test
  void addFailurePropagates() {
    var shell =
        new ScriptedShellExecutor()
            .onFail("config device get " + CONTAINER, "not found")
            .onFail("config device add " + CONTAINER, "permission denied");
    var mgr = new IncusDeviceManager(shell);

    var ex = assertThrows(IOException.class, () -> mgr.ensureEventSocket(CONTAINER, HOST, GUEST));
    assertTrue(ex.getMessage().contains("permission denied"));
  }

  @Test
  void removeIsNoopWhenAbsent() throws Exception {
    var shell = new ScriptedShellExecutor().onFail("config device get " + CONTAINER, "absent");
    var mgr = new IncusDeviceManager(shell);

    mgr.removeEventSocket(CONTAINER);

    var anyRemove = shell.invocations().stream().anyMatch(c -> c.contains("config device remove"));
    assertFalse(anyRemove);
  }

  @Test
  void removeWhenPresentExecutesIncusRemove() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("config device get " + CONTAINER, HOST + "\n")
            .onOk("config device remove " + CONTAINER);
    var mgr = new IncusDeviceManager(shell);

    mgr.removeEventSocket(CONTAINER);

    var sawRemove = shell.invocations().stream().anyMatch(c -> c.contains("config device remove"));
    assertTrue(sawRemove);
  }

  @Test
  void removeFailurePropagates() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("config device get " + CONTAINER, HOST + "\n")
            .onFail("config device remove " + CONTAINER, "rejected");
    var mgr = new IncusDeviceManager(shell);

    assertThrows(IOException.class, () -> mgr.removeEventSocket(CONTAINER));
  }

  @Test
  void currentSourceNullWhenAbsent() throws Exception {
    var shell = new ScriptedShellExecutor().onFail("config device get " + CONTAINER, "missing");
    assertNull(new IncusDeviceManager(shell).currentEventSocketSource(CONTAINER));
  }

  @Test
  void currentSourceReturnsValue() throws Exception {
    var shell =
        new ScriptedShellExecutor().onOk("config device get " + CONTAINER, "/some/host/sock\n");
    assertEquals(
        "/some/host/sock", new IncusDeviceManager(shell).currentEventSocketSource(CONTAINER));
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

    var devices = new IncusDeviceManager(shell).listDevices(CONTAINER);

    assertTrue(devices.contains("eth0"));
    assertTrue(devices.contains("root"));
  }

  @Test
  void listDevicesEmptyWhenBlank() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("config device show " + CONTAINER, "");

    assertTrue(new IncusDeviceManager(shell).listDevices(CONTAINER).isEmpty());
  }

  @Test
  void listDevicesPropagatesFailure() {
    var shell = new ScriptedShellExecutor().onFail("config device show", "boom");

    assertThrows(IOException.class, () -> new IncusDeviceManager(shell).listDevices(CONTAINER));
  }
}
