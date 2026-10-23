/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.FileLimits;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.RetentionConfig;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.WebauthnConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            "zfs",
            "/dev/sdb",
            "devpool",
            "incusbr0",
            null,
            tempDir.resolve("host.yaml"),
            tempDir.resolve("sail.db"));

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
        "zfs",
        "/dev/sdb",
        "devpool",
        "incusbr0",
        null,
        tempDir.resolve("host.yaml"),
        tempDir.resolve("sail.db"));

    assertEquals("a library update never restarts Incus", doneDetails.get(0));
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

    provisioner.provision(
        "zfs", "/dev/sdb", "devpool", "incusbr0", null, hostYamlPath, tempDir.resolve("sail.db"));

    assertFalse(Files.exists(hostYamlPath), "Dry-run should not write host.yaml");
  }

  @Test
  void provisionZfsWithNullListenerDoesNotThrow() throws Exception {
    var shell = new ShellExecutor(true);
    var provisioner = new HostProvisioner(shell, null);

    assertDoesNotThrow(
        () ->
            provisioner.provision(
                "zfs",
                "/dev/sdb",
                "devpool",
                "incusbr0",
                null,
                tempDir.resolve("h.yaml"),
                tempDir.resolve("sail.db")));
  }

  @Test
  void provisionZfsReturnsHostYamlWithAllFields() throws Exception {
    var shell = new ShellExecutor(true);
    var provisioner = new HostProvisioner(shell, null);

    var result =
        provisioner.provision(
            "zfs",
            "/dev/nvme1n1",
            "mypool",
            "br0",
            null,
            tempDir.resolve("h.yaml"),
            tempDir.resolve("sail.db"));

    assertEquals("zfs", result.storageBackend());
    assertEquals("mypool", result.pool());
    assertEquals("/dev/nvme1n1", result.poolDisk());
    assertEquals("br0", result.bridge());
    assertEquals("singlr-base", result.baseProfile());
    assertEquals("ubuntu/24.04", result.image());
  }

  @Test
  void aFreshZfsBoxRefreshesAptOnceAfterTheZabblySourceThenInstallsAndHoldsIncusAndZfs()
      throws Exception {
    var shell =
        bareBox(
            new ScriptedShellExecutor()
                .onFail("which incus", "")
                .onFail("which zpool", "")
                .onOk("dpkg-query", UNHELD)
                .onOk("apt-mark hold"));
    var steps = new ArrayList<String>();

    provision(shell, "zfs", new RecordingListener(steps));

    assertEquals(
        List.of(
            apt("update"),
            apt("install", "-y", "incus"),
            DPKG_QUERY,
            List.of("apt-mark", "hold", "incus", "incus-base", "incus-client"),
            apt("install", "-y", "zfsutils-linux")),
        packageCommands(shell));
    var source = indexOfContaining(shell.invocations(), HostPackages.ZABBLY_SOURCE);
    assertTrue(source >= 0 && source < shell.arguments().indexOf(apt("update")));
    assertTrue(steps.contains("done:1/8:a library update never restarts Incus"), steps.toString());
    assertTrue(
        steps.contains("done:2/8:installed; incus held (sudo sail host incus upgrade moves it)"),
        steps.toString());
    assertTrue(steps.contains("done:3/8:installed"), steps.toString());
    assertNoAptRanOnAClock(shell);
  }

  @Test
  void aProvisionedBoxRunsNoAptAndSaysNothingOfTheHold() throws Exception {
    var shell =
        bareBox(
            new ScriptedShellExecutor()
                .onOk("which incus")
                .onOk("which zpool")
                .onOk("dpkg-query", HELD));
    var steps = new ArrayList<String>();

    provision(shell, "zfs", new RecordingListener(steps));

    assertEquals(List.of(DPKG_QUERY), packageCommands(shell));
    assertTrue(steps.contains("done:2/8:already installed"), steps.toString());
    assertTrue(steps.contains("done:3/8:already installed"), steps.toString());
  }

  @Test
  void aBoxWithIncusButNoZpoolRefreshesAptOnceThenInstallsZfs() throws Exception {
    var shell =
        bareBox(
            new ScriptedShellExecutor()
                .onOk("which incus")
                .onFail("which zpool", "")
                .onOk("dpkg-query", HELD));

    provision(shell, "zfs", null);

    assertEquals(
        List.of(DPKG_QUERY, apt("update"), apt("install", "-y", "zfsutils-linux")),
        packageCommands(shell));
    assertEquals(-1, indexOfContaining(shell.invocations(), HostPackages.ZABBLY_SOURCE));
  }

  @Test
  void anAptDownloadShowsItsLatestPercentAsTheStepsProgressAndCompletes() throws Exception {
    var shell =
        bareBox(
            new ScriptedShellExecutor()
                .onFail("which incus", "")
                .onOk(
                    "apt-get -q -o APT::Status-Fd=1 -o Acquire::Retries=3 -o DPkg::Lock::Timeout=600"
                        + " -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold"
                        + " install",
                    """
                Reading package lists...
                dlstatus:1:12.5000:Retrieving file 1 of 3
                Get:1 https://pkgs.zabbly.com/incus/stable noble/main amd64 incus 1:6.18 [9 MB]
                dlstatus:2:57.0000:Retrieving file 2 of 3
                pmstatus:incus:80.0000:Installing incus (amd64)
                """)
                .onOk("dpkg-query", HELD));
    var progress = new ArrayList<String>();
    var steps = new ArrayList<String>();

    provision(shell, "dir", new ProgressListener(steps, progress));

    assertEquals(
        List.of(
            "12% Retrieving file 1 of 3",
            "57% Retrieving file 2 of 3", "80% Installing incus (amd64)"),
        progress);
    assertTrue(steps.contains("done:2/8:installed"), steps.toString());
  }

  @Test
  void aFetchThatFailsEndsTheStepWithAptsOwnErrorLine() {
    var shell =
        bareBox(
            new ScriptedShellExecutor()
                .onFail("which incus", "")
                .on(
                    " install -y incus",
                    new ShellExec.Result(
                        100,
                        "",
                        "W: Some index files failed to download.\n"
                            + "E: Failed to fetch https://pkgs.zabbly.com/incus/stable/pool/main/i/incus"
                            + "/incus_6.18_amd64.deb  Connection timed out\n")));
    var steps = new ArrayList<String>();

    var failure =
        assertThrows(
            IOException.class, () -> provision(shell, "dir", new RecordingListener(steps)));

    assertEquals(
        "apt-get install incus failed: E: Failed to fetch"
            + " https://pkgs.zabbly.com/incus/stable/pool/main/i/incus/incus_6.18_amd64.deb "
            + " Connection timed out",
        failure.getMessage());
    assertEquals("failed", steps.getLast());
  }

  @Test
  void aLockStillHeldAfterTheWaitShowsTheWaitThenFailsNamingTheHolder() {
    var waiting =
        "Waiting for cache lock: Could not get lock /var/lib/dpkg/lock-frontend. It is held by"
            + " process 812 (unattended-upgr)...\n";
    var shell =
        bareBox(
            new ScriptedShellExecutor()
                .onFail("which incus", "")
                .on(
                    String.join(" ", apt("update")),
                    new ShellExec.Result(
                        100,
                        waiting + waiting,
                        "E: Unable to acquire the dpkg frontend lock (/var/lib/dpkg/lock-frontend), is"
                            + " another process using it?\n")));
    var progress = new ArrayList<String>();

    var failure =
        assertThrows(
            IOException.class,
            () -> provision(shell, "dir", new ProgressListener(new ArrayList<>(), progress)));

    assertEquals(List.of(Apt.LOCK_WAIT, Apt.LOCK_WAIT), progress);
    assertEquals(
        "apt-get update failed: E: Unable to acquire the dpkg frontend lock"
            + " (/var/lib/dpkg/lock-frontend), is another process using it? Could not get lock"
            + " /var/lib/dpkg/lock-frontend. It is held by process 812 (unattended-upgr).",
        failure.getMessage());
  }

  @Test
  void writesHostYamlToFileSystem() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onOk("incus version", "6.3");

    var provisioner = new HostProvisioner(shell, null);
    var hostYamlPath = tempDir.resolve("etc/sail/host.yaml");

    provisioner.provision(
        "zfs", "/dev/sdb", "devpool", "incusbr0", null, hostYamlPath, tempDir.resolve("sail.db"));

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
            .onOk("needrestart")
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
                    "zfs",
                    "/dev/sdb",
                    "devpool",
                    "incusbr0",
                    null,
                    tempDir.resolve("h.yaml"),
                    tempDir.resolve("sail.db")));
    assertTrue(ex.getMessage().contains("Failed to create ZFS pool"));
  }

  @Test
  void incusInitFailureThrowsIOException() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("needrestart")
            .onOk("apt-get")
            .onOk("which incus")
            .onOk("which zpool")
            .onFail("incus admin init", "permission denied");
    var steps = new ArrayList<String>();

    var provisioner = new HostProvisioner(shell, new RecordingListener(steps));

    var ex =
        assertThrows(
            IOException.class,
            () ->
                provisioner.provision(
                    "zfs",
                    "/dev/sdb",
                    "devpool",
                    "incusbr0",
                    null,
                    tempDir.resolve("h.yaml"),
                    tempDir.resolve("sail.db")));
    assertTrue(ex.getMessage().contains("Incus init failed"));
    assertEquals(
        List.of("step:4/8:Initializing Incus...", "failed"),
        steps.subList(steps.size() - 2, steps.size()));
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
                "zfs",
                "/dev/sdb",
                "devpool",
                "incusbr0",
                null,
                tempDir.resolve("h.yaml"),
                tempDir.resolve("sail.db")));
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
                    "zfs",
                    "/dev/sdb",
                    "devpool",
                    "incusbr0",
                    null,
                    tempDir.resolve("h.yaml"),
                    tempDir.resolve("sail.db")));
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
            "zfs",
            "/dev/sdb",
            "devpool",
            "incusbr0",
            null,
            tempDir.resolve("host.yaml"),
            tempDir.resolve("sail.db"));

    assertEquals("unknown", result.incusVersion());
  }

  @Test
  void dirBackendSkipsZfsInstall() throws Exception {
    var shell = new ShellExecutor(true);
    var steps = new ArrayList<String>();
    var listener = new RecordingListener(steps);
    var provisioner = new HostProvisioner(shell, listener);

    provisioner.provision(
        "dir",
        null,
        "devpool",
        "incusbr0",
        null,
        tempDir.resolve("host.yaml"),
        tempDir.resolve("sail.db"));

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

    provisioner.provision(
        "dir",
        null,
        "devpool",
        "incusbr0",
        null,
        tempDir.resolve("host.yaml"),
        tempDir.resolve("sail.db"));

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

    provisioner.provision(
        "dir",
        null,
        "devpool",
        "incusbr0",
        null,
        tempDir.resolve("host.yaml"),
        tempDir.resolve("sail.db"));

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
                    "dir",
                    null,
                    "devpool",
                    "incusbr0",
                    null,
                    tempDir.resolve("h.yaml"),
                    tempDir.resolve("sail.db")));
    assertTrue(ex.getMessage().contains("Failed to create dir storage pool"));
  }

  @Test
  void dirBackendReturnsNullPoolDisk() throws Exception {
    var shell = new ShellExecutor(true);
    var provisioner = new HostProvisioner(shell, null);

    var result =
        provisioner.provision(
            "dir",
            null,
            "devpool",
            "incusbr0",
            null,
            tempDir.resolve("host.yaml"),
            tempDir.resolve("sail.db"));

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

    provisioner.provision(
        "dir",
        null,
        "devpool",
        "incusbr0",
        null,
        tempDir.resolve("host.yaml"),
        tempDir.resolve("sail.db"));

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

    provisioner.provision(
        "dir",
        null,
        "devpool",
        "incusbr0",
        null,
        tempDir.resolve("host.yaml"),
        tempDir.resolve("sail.db"));

    assertTrue(steps.stream().anyMatch(s -> s.contains("done:8/8:bridge rules added")));

    var cmds = shell.invocations();
    assertTrue(cmds.stream().anyMatch(c -> c.contains("ufw allow in on incusbr0")));
    assertTrue(cmds.stream().anyMatch(c -> c.contains("ufw allow out on incusbr0")));
    assertTrue(cmds.stream().anyMatch(c -> c.contains("ufw route allow in on incusbr0")));
    assertTrue(cmds.stream().anyMatch(c -> c.contains("ufw route allow out on incusbr0")));
  }

  @Test
  void ufwNotInstalledSkipsFirewallStepInsteadOfCrashing() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus version", "6.3")
            .onThrow(
                "ufw",
                new IOException("Cannot run program \"ufw\": error=2, No such file or directory"));

    var steps = new ArrayList<String>();
    var provisioner = new HostProvisioner(shell, new RecordingListener(steps));

    assertDoesNotThrow(
        () ->
            provisioner.provision(
                "dir",
                null,
                "devpool",
                "incusbr0",
                null,
                tempDir.resolve("host.yaml"),
                tempDir.resolve("sail.db")),
        "a host without ufw must skip the firewall step, not abort init");
    assertTrue(
        steps.stream().anyMatch(s -> s.contains("skipped:8/8:UFW not installed")),
        "step 8 skipped as not installed; steps=" + steps);
  }

  @Test
  void ufwInactiveSkipsFirewallStep() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus version", "6.3")
            .on("ufw status", new ShellExec.Result(0, "Status: inactive\n", ""));

    var steps = new ArrayList<String>();
    var provisioner = new HostProvisioner(shell, new RecordingListener(steps));

    provisioner.provision(
        "dir",
        null,
        "devpool",
        "incusbr0",
        null,
        tempDir.resolve("host.yaml"),
        tempDir.resolve("sail.db"));

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

    provisioner.provision(
        "dir",
        null,
        "devpool",
        "incusbr0",
        null,
        tempDir.resolve("host.yaml"),
        tempDir.resolve("sail.db"));

    assertTrue(steps.stream().anyMatch(s -> s.contains("done:8/8:some rules failed")));
  }

  @Test
  void reInitializingANodeKeepsItsSyncIdentityAndPasskeysAndRefreshesProvisioning()
      throws Exception {
    var path = tempDir.resolve("host.yaml");
    var node = new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", "ada", "box-7");
    var passkeys =
        new WebauthnConfig("sail.example.dev", "Sail", List.of("https://sail.example.dev"));
    var limits = new FileLimits(2048);
    var retention = RetentionConfig.fromMap(Map.of("messages", "30d"));
    var before =
        new HostYaml(
            "zfs",
            "oldpool",
            "/dev/sdz",
            "oldbr0",
            "old-profile",
            "ubuntu/22.04",
            "6.0",
            "10.0.0.1",
            "2026-01-01T00:00:00Z",
            passkeys,
            node,
            limits,
            retention);
    YamlUtil.dumpToFile(before.toMap(), path);

    var after = reProvision(path);

    assertEquals(after, HostYaml.fromMap(YamlUtil.parseFile(path)));
    assertEquals(node, after.sync());
    assertEquals("box-7", BoxIdentity.resolved(after.sync()).boxId());
    assertEquals(passkeys, after.webauthn());
    assertEquals(limits, after.limits());
    assertEquals(retention, after.retention());
    assertEquals(
        List.of("dir", "devpool", "incusbr0", "singlr-base", "ubuntu/24.04", "6.3", "192.0.2.4"),
        List.of(
            after.storageBackend(),
            after.pool(),
            after.bridge(),
            after.baseProfile(),
            after.image(),
            after.incusVersion(),
            after.serverIp()));
    assertNull(after.poolDisk());
    assertNotEquals(before.initializedAt(), after.initializedAt());
  }

  @Test
  void reInitializingMainKeepsItsRoleAndHandle() throws Exception {
    var path = tempDir.resolve("host.yaml");
    var main = new SyncConfig(SyncConfig.ROLE_MAIN, null, "ada", "main-box");
    YamlUtil.dumpToFile(
        HostYaml.fromMap(Map.of("storage_backend", "dir")).withSync(main).toMap(), path);

    assertEquals(main, reProvision(path).sync());
    assertEquals(main, HostYaml.fromMap(YamlUtil.parseFile(path)).sync());
  }

  @Test
  void reInitializingAStandaloneBoxLeavesSyncUnset() throws Exception {
    var path = tempDir.resolve("host.yaml");
    YamlUtil.dumpToFile(HostYaml.fromMap(Map.of("storage_backend", "dir")).toMap(), path);

    assertEquals(SyncConfig.unset(), reProvision(path).sync());
    assertFalse(Files.readString(path).contains("sync"), Files.readString(path));
  }

  @Test
  void anUnreadableHostYamlStopsReInitializingBeforeAnyStep() throws Exception {
    var path = tempDir.resolve("host.yaml");
    Files.writeString(path, "limits:\n  file_max: -1\n");
    var steps = new ArrayList<String>();
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    var refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                new HostProvisioner(shell, new RecordingListener(steps))
                    .provision(
                        "dir",
                        null,
                        "devpool",
                        "incusbr0",
                        null,
                        path,
                        path.resolveSibling("sail.db")));

    assertEquals(
        "Cannot read "
            + path
            + " to keep this box's sync identity and settings: limits.file_max must be between 1"
            + " byte and 8 GiB: a blob manifest must fit one sync frame. Fix the file, or move it"
            + " aside to provision this box as new, then rerun.",
        refused.getMessage());
    assertEquals(List.of(), steps);
    assertEquals("limits:\n  file_max: -1\n", Files.readString(path));
  }

  @Test
  void aPromotionThatFinishesWhileProvisioningIsKept() throws Exception {
    var path = tempDir.resolve("host.yaml");
    var dbPath = tempDir.resolve("sail.db");
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
    }
    var node =
        HostYaml.fromMap(Map.of("storage_backend", "dir"))
            .withSync(new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", "ada", "box-7"));
    var main = node.withSync(new SyncConfig(SyncConfig.ROLE_MAIN, null, "ada", "box-7"));
    YamlUtil.dumpToFile(node.toMap(), path);
    var promote =
        new ProvisionListener() {
          @Override
          public void onStep(int step, int total, String description) {
            if (step == 1) {
              try {
                HandleChange.write(
                    path,
                    dbPath,
                    node,
                    main,
                    target -> {
                      throw new IOException("main is not asked on a promotion");
                    },
                    OperatorCommand.PLAIN);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            }
          }

          @Override
          public void onStepDone(int step, int total, String detail) {}
        };

    var after = reProvision(path, promote);

    assertEquals(main.sync(), after.sync());
    assertEquals(main.sync(), HostYaml.fromMap(YamlUtil.parseFile(path)).sync());
  }

  private static HostYaml reProvision(Path path) throws Exception {
    return reProvision(path, null);
  }

  private static HostYaml reProvision(Path path, ProvisionListener listener) throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus version", "6.3")
            .on("ufw status", new ShellExec.Result(0, "Status: inactive\n", ""));
    return new HostProvisioner(shell, listener)
        .provision(
            "dir", null, "devpool", "incusbr0", "192.0.2.4", path, path.resolveSibling("sail.db"));
  }

  @Test
  void provisioningWritesTheNeedrestartPolicyFirstAndNeverUpgradesTheOs() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("which incus", "")
            .onFail("which zpool", "");

    provision(shell, "zfs", null);

    var policy =
        shell
            .arguments()
            .indexOf(NeedrestartPolicy.DROP_IN.installCommand(NeedrestartPolicy.DROP_IN.path()));
    assertEquals(0, policy, shell.invocations().toString());
    assertEquals(
        List.of(),
        shell.invocations().stream()
            .filter(command -> command.matches(".*\\b(upgrade|dist-upgrade|full-upgrade)\\b.*"))
            .toList());
  }

  @Test
  void aPolicyThatCannotBeWrittenStopsProvisioningBeforeAnyPackage() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("needrestart", "read-only file system");

    var error =
        assertThrows(
            IOException.class,
            () ->
                new HostProvisioner(shell, null)
                    .provision(
                        "dir",
                        null,
                        "devpool",
                        "incusbr0",
                        null,
                        tempDir.resolve("host.yaml"),
                        tempDir.resolve("sail.db")));

    assertEquals(
        "Failed to install /etc/needrestart/conf.d/50-sail.conf: read-only file system",
        error.getMessage());
    assertEquals(1, shell.invocations().size());
  }

  @Test
  void aDryRunPrintsThePolicyInstallAndWritesNothing() throws Exception {
    var out = new ByteArrayOutputStream();
    var original = System.out;
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    try {
      new HostProvisioner(new ShellExecutor(true), null)
          .provision(
              "dir",
              null,
              "devpool",
              "incusbr0",
              null,
              tempDir.resolve("host.yaml"),
              tempDir.resolve("sail.db"));
    } finally {
      System.setOut(original);
    }

    var printed =
        "[dry-run] "
            + String.join(
                " ", NeedrestartPolicy.DROP_IN.installCommand(NeedrestartPolicy.DROP_IN.path()))
            + "\n";
    assertTrue(out.toString(StandardCharsets.UTF_8).startsWith(printed), out.toString());
    assertFalse(out.toString(StandardCharsets.UTF_8).contains("upgrade"), out.toString());
    assertFalse(Files.exists(tempDir.resolve("host.yaml")));
  }

  @Test
  void theIncusVersionIsWhatIncusAnswersNowOrUnknown() throws Exception {
    assertEquals(
        "6.21",
        HostProvisioner.incusVersion(
            new ScriptedShellExecutor()
                .onOk("incus version", "Client version: 6.21\nServer version: 6.21\n")));
    assertEquals(
        "6.21",
        HostProvisioner.incusVersion(new ScriptedShellExecutor().onOk("incus version", "6.21\n")));
    assertEquals(
        "unknown", HostProvisioner.incusVersion(new ScriptedShellExecutor().onOk("incus version")));
    assertEquals(
        "unknown",
        HostProvisioner.incusVersion(
            new ScriptedShellExecutor().onFail("incus version", "cannot connect")));
    assertEquals(
        "unknown",
        HostProvisioner.incusVersion(
            new ScriptedShellExecutor()
                .onThrow("incus version", new IOException("No such file or directory"))));
  }

  private static final String UNHELD =
      """
      incus install installed
      incus-base install installed
      incus-client install installed
      incus-extra unknown not-installed
      """;

  private static final String HELD =
      """
      incus hold installed
      incus-base hold installed
      """;

  private static final List<String> DPKG_QUERY =
      List.of(
          "dpkg-query", "-W", "-f=${Package} ${db:Status-Want} ${db:Status-Status}\\n", "incus*");

  /**
   * {@code shell}, answering after its own scripts as a box whose every step but the packages is
   * done: Incus initialized, pool, profile and image made.
   */
  private static ScriptedShellExecutor bareBox(ScriptedShellExecutor shell) {
    return shell
        .onOk("needrestart")
        .onOk("curl")
        .onOk(HostPackages.ZABBLY_SOURCE)
        .on("echo $VERSION_CODENAME", new ShellExec.Result(0, "noble\n", ""))
        .on("dpkg --print-architecture", new ShellExec.Result(0, "amd64\n", ""))
        .onOk("apt-get")
        .onOk("incus admin init")
        .onOk("incus version", "6.3")
        .onOk("incus storage")
        .onOk("zpool list")
        .onOk("incus profile show")
        .onOk("incus image list");
  }

  private HostYaml provision(ShellExec shell, String backend, ProvisionListener listener)
      throws Exception {
    return new HostProvisioner(shell, listener)
        .provision(
            backend,
            "zfs".equals(backend) ? "/dev/sdb" : null,
            "devpool",
            "incusbr0",
            null,
            tempDir.resolve("host.yaml"),
            tempDir.resolve("sail.db"));
  }

  private static List<String> apt(String... args) {
    var command = new ArrayList<>(Apt.COMMAND);
    command.addAll(List.of(args));
    return command;
  }

  private static List<List<String>> packageCommands(ScriptedShellExecutor shell) {
    return shell.arguments().stream()
        .filter(
            command ->
                command.stream()
                    .anyMatch(word -> word.startsWith("apt") || word.startsWith("dpkg-query")))
        .toList();
  }

  private static void assertNoAptRanOnAClock(ScriptedShellExecutor shell) {
    assertEquals(
        shell.invocations().stream().filter(command -> command.contains("apt-get")).toList(),
        shell.unbounded());
  }

  private static int indexOfContaining(List<String> list, String substring) {
    for (var i = 0; i < list.size(); i++) {
      if (list.get(i).contains(substring)) return i;
    }
    return -1;
  }

  /** Records each step's progress, and its events as {@link RecordingListener} does. */
  private record ProgressListener(List<String> events, List<String> progress)
      implements ProvisionListener {
    @Override
    public void onStep(int step, int total, String description) {}

    @Override
    public void onStepDone(int step, int total, String detail) {
      events.add("done:" + step + "/" + total + ":" + detail);
    }

    @Override
    public void onStepProgress(String detail) {
      progress.add(detail);
    }
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

    @Override
    public void onStepFailed() {
      events.add("failed");
    }
  }
}
