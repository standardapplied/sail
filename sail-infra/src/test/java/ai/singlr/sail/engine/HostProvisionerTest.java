/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostProvisionerTest {

  @TempDir Path tempDir;

  @Test
  void provisionZfsDryRunCompletesAllSteps() throws Exception {
    var shell = new ShellExecutor(true);
    var steps = new ArrayList<String>();
    var listener = new RecordingListener(steps);
    var provisioner = new HostProvisioner(shell, listener);

    var hostYaml =
        provisioner.provision(
            "zfs", "/dev/sdb", "devpool", "incusbr0", null, tempDir.resolve("host.yaml"));

    assertEquals("zfs", hostYaml.storageBackend());
    assertEquals("devpool", hostYaml.pool());
    assertEquals("/dev/sdb", hostYaml.poolDisk());
    assertEquals("incusbr0", hostYaml.bridge());
    assertEquals("singlr-base", hostYaml.baseProfile());
    assertEquals("ubuntu/24.04", hostYaml.image());
    assertNotNull(hostYaml.initializedAt());

    assertEquals(16, steps.size());
  }

  @Test
  void provisionZfsDryRunReportsIdempotentSteps() throws Exception {
    var shell = new ShellExecutor(true);
    var doneDetails = new ArrayList<String>();
    var listener =
        new ProvisionListener() {
          @Override
          public void onStep(int step, int total, String description) {}

          @Override
          public void onStepDone(int step, int total, String detail) {
            doneDetails.add(detail);
          }
        };
    var provisioner = new HostProvisioner(shell, listener);

    provisioner.provision(
        "zfs", "/dev/sdb", "devpool", "incusbr0", null, tempDir.resolve("host.yaml"));

    assertEquals("up to date", doneDetails.get(0));
    assertEquals("already installed", doneDetails.get(1));
    assertEquals("already installed", doneDetails.get(2));
    assertTrue(doneDetails.get(3).contains("bridge"));
    assertTrue(doneDetails.get(4).contains("already exists"));
    assertTrue(doneDetails.get(5).contains("already exists"));
    assertEquals("already cached", doneDetails.get(6));
  }

  @Test
  void provisionZfsDryRunDoesNotWriteHostYaml() throws Exception {
    var shell = new ShellExecutor(true);
    var provisioner = new HostProvisioner(shell, null);
    var hostYamlPath = tempDir.resolve("host.yaml");

    provisioner.provision("zfs", "/dev/sdb", "devpool", "incusbr0", null, hostYamlPath);

    assertFalse(Files.exists(hostYamlPath), "Dry-run should not write host.yaml");
  }

  @Test
  void provisionZfsWithNullListenerDoesNotThrow() throws Exception {
    var shell = new ShellExecutor(true);
    var provisioner = new HostProvisioner(shell, null);

    assertDoesNotThrow(
        () ->
            provisioner.provision(
                "zfs", "/dev/sdb", "devpool", "incusbr0", null, tempDir.resolve("h.yaml")));
  }

  @Test
  void provisionZfsReturnsHostYamlWithAllFields() throws Exception {
    var shell = new ShellExecutor(true);
    var provisioner = new HostProvisioner(shell, null);

    var result =
        provisioner.provision(
            "zfs", "/dev/nvme1n1", "mypool", "br0", null, tempDir.resolve("h.yaml"));

    assertEquals("zfs", result.storageBackend());
    assertEquals("mypool", result.pool());
    assertEquals("/dev/nvme1n1", result.poolDisk());
    assertEquals("br0", result.bridge());
    assertEquals("singlr-base", result.baseProfile());
    assertEquals("ubuntu/24.04", result.image());
  }

  @Test
  void freshZfsInstallRunsAllInstallCommands() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("apt-get")
            .onFail("which incus", "")
            .onFail("which zpool", "")
            .onOk("mkdir")
            .onOk("curl")
            .onOk("install -m 644")
            .on("echo $VERSION_CODENAME", new ShellExec.Result(0, "noble\n", ""))
            .on("dpkg --print-architecture", new ShellExec.Result(0, "amd64\n", ""))
            .onOk("incus admin init")
            .onOk("incus version", "6.3")
            .onOk("zpool create")
            .onFail("zpool list", "no such pool")
            .onFail("incus profile show", "not found")
            .onOk("incus profile create")
            .onOk("incus profile device")
            .onFail("incus image list", "")
            .onOk("incus image copy");

    var steps = new ArrayList<String>();
    var provisioner = new HostProvisioner(shell, new RecordingListener(steps));

    var result =
        provisioner.provision(
            "zfs", "/dev/sdb", "devpool", "incusbr0", null, tempDir.resolve("host.yaml"));

    assertEquals("devpool", result.pool());
    assertEquals("6.3", result.incusVersion());
    assertEquals(16, steps.size());
    assertTrue(steps.stream().anyMatch(s -> s.contains("done:3/8:installed")));

    var cmds = shell.invocations();
    assertTrue(cmds.size() > 10, "Should have executed multiple commands");
    var aptUpdate = indexOfContaining(cmds, "apt-get update");
    var installIncus = indexOfContaining(cmds, "apt-get install -y -qq incus");
    var incusInit = indexOfContaining(cmds, "incus admin init");
    var zpoolCreate = indexOfContaining(cmds, "zpool create");
    assertTrue(aptUpdate < installIncus, "apt-get update should precede incus install");
    assertTrue(incusInit < zpoolCreate, "incus init should precede zpool create");
  }

  @Test
  void writesHostYamlToFileSystem() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onOk("incus version", "6.3");

    var provisioner = new HostProvisioner(shell, null);
    var hostYamlPath = tempDir.resolve("etc/sail/host.yaml");

    provisioner.provision("zfs", "/dev/sdb", "devpool", "incusbr0", null, hostYamlPath);

    assertTrue(Files.exists(hostYamlPath), "host.yaml should be written");
    var content = Files.readString(hostYamlPath);
    assertTrue(content.contains("devpool"));
    assertTrue(content.contains("/dev/sdb"));
    assertTrue(content.contains("incusbr0"));
    assertTrue(
        content.contains("storage_backend: zfs") || content.contains("storage_backend: \"zfs\""));
  }

  @Test
  void zpoolCreateFailureThrowsIOException() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("apt-get")
            .onOk("which incus")
            .onOk("which zpool")
            .onOk("incus admin init")
            .onOk("incus version", "6.3")
            .onFail("zpool list", "no such pool")
            .onFail("zpool create", "cannot open '/dev/sdb': no such device");

    var provisioner = new HostProvisioner(shell, null);

    var ex =
        assertThrows(
            IOException.class,
            () ->
                provisioner.provision(
                    "zfs", "/dev/sdb", "devpool", "incusbr0", null, tempDir.resolve("h.yaml")));
    assertTrue(ex.getMessage().contains("Failed to create ZFS pool"));
  }

  @Test
  void incusInitFailureThrowsIOException() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("apt-get")
            .onOk("which incus")
            .onOk("which zpool")
            .onFail("incus admin init", "permission denied");

    var provisioner = new HostProvisioner(shell, null);

    var ex =
        assertThrows(
            IOException.class,
            () ->
                provisioner.provision(
                    "zfs", "/dev/sdb", "devpool", "incusbr0", null, tempDir.resolve("h.yaml")));
    assertTrue(ex.getMessage().contains("Incus init failed"));
  }

  @Test
  void incusInitAlreadyExistsIsNotAnError() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .on("incus admin init", new ShellExec.Result(1, "", "network already exists"))
            .onOk("incus version", "6.3");

    var provisioner = new HostProvisioner(shell, null);

    assertDoesNotThrow(
        () ->
            provisioner.provision(
                "zfs", "/dev/sdb", "devpool", "incusbr0", null, tempDir.resolve("h.yaml")));
  }

  @Test
  void cacheImageFailureThrowsIOException() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus version", "6.3")
            .onFail("incus image list", "")
            .onFail("incus image copy", "network unreachable");

    var provisioner = new HostProvisioner(shell, null);

    var ex =
        assertThrows(
            IOException.class,
            () ->
                provisioner.provision(
                    "zfs", "/dev/sdb", "devpool", "incusbr0", null, tempDir.resolve("h.yaml")));
    assertTrue(ex.getMessage().contains("Failed to cache image"));
  }

  @Test
  void incusVersionFailureReportsUnknown() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("incus version", "command not found");

    var provisioner = new HostProvisioner(shell, null);
    var result =
        provisioner.provision(
            "zfs", "/dev/sdb", "devpool", "incusbr0", null, tempDir.resolve("host.yaml"));

    assertEquals("unknown", result.incusVersion());
  }

  @Test
  void dirBackendSkipsZfsInstall() throws Exception {
    var shell = new ShellExecutor(true);
    var steps = new ArrayList<String>();
    var listener = new RecordingListener(steps);
    var provisioner = new HostProvisioner(shell, listener);

    provisioner.provision("dir", null, "devpool", "incusbr0", null, tempDir.resolve("host.yaml"));

    assertTrue(
        steps.stream().anyMatch(s -> s.contains("skipped:3/8:not needed (dir backend)")),
        "Step 3 should be skipped for dir backend");
    assertFalse(
        steps.stream().anyMatch(s -> s.contains("done:3/8")),
        "Step 3 should not report done for dir backend");
  }

  @Test
  void dirBackendCreatesIncusStoragePool() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus version", "6.3")
            .onFail("incus storage show", "not found")
            .onOk("incus storage create");

    var provisioner = new HostProvisioner(shell, new RecordingListener(new ArrayList<>()));

    provisioner.provision("dir", null, "devpool", "incusbr0", null, tempDir.resolve("host.yaml"));

    var cmds = shell.invocations();
    assertTrue(
        cmds.stream().anyMatch(c -> c.contains("incus storage create devpool dir")),
        "Should call 'incus storage create devpool dir'");
  }

  @Test
  void dirBackendStoragePoolAlreadyExistsIsIdempotent() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus version", "6.3")
            .onOk("incus storage show");

    var steps = new ArrayList<String>();
    var provisioner = new HostProvisioner(shell, new RecordingListener(steps));

    provisioner.provision("dir", null, "devpool", "incusbr0", null, tempDir.resolve("host.yaml"));

    assertTrue(
        steps.stream().anyMatch(s -> s.contains("done:5/8:devpool (already exists)")),
        "Step 5 should report pool already exists");
  }

  @Test
  void dirBackendStoragePoolCreationFailureThrows() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus version", "6.3")
            .onFail("incus storage show", "not found")
            .onFail("incus storage create", "permission denied");

    var provisioner = new HostProvisioner(shell, null);

    var ex =
        assertThrows(
            IOException.class,
            () ->
                provisioner.provision(
                    "dir", null, "devpool", "incusbr0", null, tempDir.resolve("h.yaml")));
    assertTrue(ex.getMessage().contains("Failed to create dir storage pool"));
  }

  @Test
  void dirBackendReturnsNullPoolDisk() throws Exception {
    var shell = new ShellExecutor(true);
    var provisioner = new HostProvisioner(shell, null);

    var result =
        provisioner.provision(
            "dir", null, "devpool", "incusbr0", null, tempDir.resolve("host.yaml"));

    assertEquals("dir", result.storageBackend());
    assertEquals("devpool", result.pool());
    assertNull(result.poolDisk());
  }

  @Test
  void dirBackendDryRunCompletesAllSteps() throws Exception {
    var shell = new ShellExecutor(true);
    var steps = new ArrayList<String>();
    var listener = new RecordingListener(steps);
    var provisioner = new HostProvisioner(shell, listener);

    provisioner.provision("dir", null, "devpool", "incusbr0", null, tempDir.resolve("host.yaml"));

    assertEquals(15, steps.size());
  }

  @Test
  void ufwActiveAddsAllBridgeRules() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus version", "6.3")
            .on("ufw status", new ShellExec.Result(0, "Status: active\n", ""))
            .onOk("ufw allow")
            .onOk("ufw route");

    var steps = new ArrayList<String>();
    var provisioner = new HostProvisioner(shell, new RecordingListener(steps));

    provisioner.provision("dir", null, "devpool", "incusbr0", null, tempDir.resolve("host.yaml"));

    assertTrue(steps.stream().anyMatch(s -> s.contains("done:8/8:bridge rules added")));

    var cmds = shell.invocations();
    assertTrue(cmds.stream().anyMatch(c -> c.contains("ufw allow in on incusbr0")));
    assertTrue(cmds.stream().anyMatch(c -> c.contains("ufw allow out on incusbr0")));
    assertTrue(cmds.stream().anyMatch(c -> c.contains("ufw route allow in on incusbr0")));
    assertTrue(cmds.stream().anyMatch(c -> c.contains("ufw route allow out on incusbr0")));
  }

  @Test
  void ufwNotInstalledSkipsFirewallStep() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus version", "6.3")
            .onFail("ufw status", "command not found");

    var steps = new ArrayList<String>();
    var provisioner = new HostProvisioner(shell, new RecordingListener(steps));

    provisioner.provision("dir", null, "devpool", "incusbr0", null, tempDir.resolve("host.yaml"));

    assertTrue(steps.stream().anyMatch(s -> s.contains("skipped:8/8:UFW not active")));
  }

  @Test
  void ufwInactiveSkipsFirewallStep() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus version", "6.3")
            .on("ufw status", new ShellExec.Result(0, "Status: inactive\n", ""));

    var steps = new ArrayList<String>();
    var provisioner = new HostProvisioner(shell, new RecordingListener(steps));

    provisioner.provision("dir", null, "devpool", "incusbr0", null, tempDir.resolve("host.yaml"));

    assertTrue(steps.stream().anyMatch(s -> s.contains("skipped:8/8:UFW not active")));
  }

  @Test
  void ufwRuleFailureReportsWarning() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus version", "6.3")
            .on("ufw status", new ShellExec.Result(0, "Status: active\n", ""))
            .onOk("ufw allow")
            .onFail("ufw route", "permission denied");

    var steps = new ArrayList<String>();
    var provisioner = new HostProvisioner(shell, new RecordingListener(steps));

    provisioner.provision("dir", null, "devpool", "incusbr0", null, tempDir.resolve("host.yaml"));

    assertTrue(steps.stream().anyMatch(s -> s.contains("done:8/8:some rules failed")));
  }

  private static int indexOfContaining(List<String> list, String substring) {
    for (var i = 0; i < list.size(); i++) {
      if (list.get(i).contains(substring)) return i;
    }
    return -1;
  }

  /** Records all step events for assertion. */
  private record RecordingListener(List<String> events) implements ProvisionListener {
    @Override
    public void onStep(int step, int total, String description) {
      events.add("step:" + step + "/" + total + ":" + description);
    }

    @Override
    public void onStepDone(int step, int total, String detail) {
      events.add("done:" + step + "/" + total + ":" + detail);
    }

    @Override
    public void onStepSkipped(int step, int total, String detail) {
      events.add("skipped:" + step + "/" + total + ":" + detail);
    }
  }
}
