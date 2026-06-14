/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class SnapshotManagerTest {

  private static final String SNAPSHOTS_JSON =
      """
      [
        {
          "name": "snap-20260219-100000",
          "created_at": "2026-02-19T10:00:00.123456789Z",
          "expires_at": "0001-01-01T00:00:00Z",
          "stateful": false
        },
        {
          "name": "pre-agent",
          "created_at": "2026-02-19T14:30:22.987654321Z",
          "expires_at": "0001-01-01T00:00:00Z",
          "stateful": false
        }
      ]
      """;

  private static final String EMPTY_JSON = "[]";

  @Test
  void createExecutesCorrectCommand() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var mgr = new SnapshotManager(shell);

    mgr.create("acme-health", "pre-agent");

    var cmds = shell.invocations();
    assertEquals(1, cmds.size());
    assertEquals("incus snapshot create acme-health pre-agent", cmds.getFirst());
  }

  @Test
  void createThrowsOnFailure() {
    var shell = new ScriptedShellExecutor().onFail("incus snapshot create", "not found");
    var mgr = new SnapshotManager(shell);

    var ex = assertThrows(IOException.class, () -> mgr.create("acme-health", "pre-agent"));
    assertTrue(ex.getMessage().contains("Failed to create snapshot"));
    assertTrue(ex.getMessage().contains("pre-agent"));
    assertTrue(ex.getMessage().contains("acme-health"));
  }

  @Test
  void restoreExecutesCorrectCommand() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var mgr = new SnapshotManager(shell);

    mgr.restore("acme-health", "pre-agent");

    var cmds = shell.invocations();
    assertEquals(1, cmds.size());
    assertEquals("incus snapshot restore acme-health pre-agent", cmds.getFirst());
  }

  @Test
  void restoreThrowsOnFailure() {
    var shell = new ScriptedShellExecutor().onFail("incus snapshot restore", "no such snapshot");
    var mgr = new SnapshotManager(shell);

    var ex = assertThrows(IOException.class, () -> mgr.restore("acme-health", "pre-agent"));
    assertTrue(ex.getMessage().contains("Failed to restore snapshot"));
    assertTrue(ex.getMessage().contains("pre-agent"));
  }

  @Test
  void listParsesSnapshotJson() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus snapshot list acme-health", SNAPSHOTS_JSON);
    var mgr = new SnapshotManager(shell);

    var snapshots = mgr.list("acme-health");

    assertEquals(2, snapshots.size());
    assertEquals("snap-20260219-100000", snapshots.get(0).name());
    assertTrue(snapshots.get(0).createdAt().startsWith("2026-02-19T10:00:00"));
    assertEquals("pre-agent", snapshots.get(1).name());
    assertTrue(snapshots.get(1).createdAt().startsWith("2026-02-19T14:30:22"));
  }

  @Test
  void listReturnsEmptyForNoSnapshots() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus snapshot list acme-health", EMPTY_JSON);
    var mgr = new SnapshotManager(shell);

    var snapshots = mgr.list("acme-health");

    assertTrue(snapshots.isEmpty());
  }

  @Test
  void listThrowsOnIncusFailure() {
    var shell =
        new ScriptedShellExecutor().onFail("incus snapshot list acme-health", "daemon not running");
    var mgr = new SnapshotManager(shell);

    var ex = assertThrows(IOException.class, () -> mgr.list("acme-health"));
    assertTrue(ex.getMessage().contains("Failed to list snapshots"));
    assertTrue(ex.getMessage().contains("daemon not running"));
  }

  @Test
  void deleteExecutesCorrectCommand() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var mgr = new SnapshotManager(shell);

    mgr.delete("acme-health", "pre-agent");

    var cmds = shell.invocations();
    assertEquals(1, cmds.size());
    assertEquals("incus snapshot delete acme-health pre-agent", cmds.getFirst());
  }

  @Test
  void deleteThrowsOnFailure() {
    var shell = new ScriptedShellExecutor().onFail("incus snapshot delete", "no such snapshot");
    var mgr = new SnapshotManager(shell);

    var ex = assertThrows(IOException.class, () -> mgr.delete("acme-health", "pre-agent"));
    assertTrue(ex.getMessage().contains("Failed to delete snapshot"));
    assertTrue(ex.getMessage().contains("pre-agent"));
  }

  @Test
  void defaultLabelHasCorrectFormat() {
    var label = SnapshotManager.defaultLabel();

    assertTrue(
        label.matches("snap-\\d{8}-\\d{6}"), "Label should match snap-YYYYMMDD-HHmmss: " + label);
  }
}
