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

import ai.singlr.sail.common.Remedied;
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

  private static final PoolSource DIR = new PoolSource.Dir();
  private static final PoolSource SDB = new PoolSource.Device("/dev/sdb", false);
  private static final long MEMORY_MB = 65536;

  @TempDir Path tempDir;

  @Test
  void provisionZfsDryRunCompletesAllSteps() throws Exception {
    var shell = new ShellExecutor(true);
    var steps = new ArrayList<String>();
    var listener = new RecordingListener(steps);
    var provisioner = new HostProvisioner(shell, listener, OperatorCommand.SUDO);

    var hostYaml =
        provisioner.provision(
            SDB,
            "devpool",
            "incusbr0",
            null,
            MEMORY_MB,
            tempDir.resolve("host.yaml"),
            tempDir.resolve("sail.db"));

    assertEquals("zfs", hostYaml.storageBackend());
    assertEquals("devpool", hostYaml.pool());
    assertEquals("/dev/sdb", hostYaml.poolSource());
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
    var provisioner = new HostProvisioner(shell, listener, OperatorCommand.SUDO);

    provisioner.provision(
        SDB,
        "devpool",
        "incusbr0",
        null,
        MEMORY_MB,
        tempDir.resolve("host.yaml"),
        tempDir.resolve("sail.db"));

    assertEquals(
        List.of(
            "a library update never restarts Incus",
            "already installed",
            "already installed; ZFS cache capped at 6.4 GiB",
            "bridge: incusbr0",
            "devpool (zfs on /dev/sdb)",
            "singlr-base (already exists): containers on devpool (zfs)",
            "already cached"),
        doneDetails);
  }

  @Test
  void provisionZfsDryRunDoesNotWriteHostYaml() throws Exception {
    var shell = new ShellExecutor(true);
    var provisioner = new HostProvisioner(shell, null, OperatorCommand.SUDO);
    var hostYamlPath = tempDir.resolve("host.yaml");

    provisioner.provision(
        SDB, "devpool", "incusbr0", null, MEMORY_MB, hostYamlPath, tempDir.resolve("sail.db"));

    assertFalse(Files.exists(hostYamlPath), "Dry-run should not write host.yaml");
  }

  @Test
  void provisionZfsWithNullListenerDoesNotThrow() throws Exception {
    var shell = new ShellExecutor(true);
    var provisioner = new HostProvisioner(shell, null, OperatorCommand.SUDO);

    assertDoesNotThrow(
        () ->
            provisioner.provision(
                SDB,
                "devpool",
                "incusbr0",
                null,
                MEMORY_MB,
                tempDir.resolve("h.yaml"),
                tempDir.resolve("sail.db")));
  }

  @Test
  void provisionZfsReturnsHostYamlWithAllFields() throws Exception {
    var shell = new ShellExecutor(true);
    var provisioner = new HostProvisioner(shell, null, OperatorCommand.SUDO);

    var result =
        provisioner.provision(
            new PoolSource.Device("/dev/nvme1n1", false),
            "mypool",
            "br0",
            null,
            MEMORY_MB,
            tempDir.resolve("h.yaml"),
            tempDir.resolve("sail.db"));

    assertEquals("zfs", result.storageBackend());
    assertEquals("mypool", result.pool());
    assertEquals("/dev/nvme1n1", result.poolSource());
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
                .onOk("apt-mark"),
            "zfs");
    var steps = new ArrayList<String>();

    provision(shell, SDB, new RecordingListener(steps));

    assertEquals(
        List.of(
            APT_UPDATE,
            apt("install", "-y", "incus"),
            DPKG_QUERY,
            hold("incus", "incus-base", "incus-client"),
            apt("install", "-y", "zfsutils-linux")),
        packageCommands(shell));
    var source = indexOfContaining(shell.invocations(), HostPackages.ZABBLY_SOURCE);
    assertTrue(source >= 0 && source < shell.arguments().indexOf(APT_UPDATE));
    assertTrue(steps.contains("done:1/8:a library update never restarts Incus"), steps.toString());
    assertTrue(
        steps.contains("done:2/8:installed; incus held (sudo sail host incus upgrade moves it)"),
        steps.toString());
    assertTrue(steps.contains("done:3/8:installed; ZFS cache capped at 6.4 GiB"), steps.toString());
    assertNoAptRanOnAClock(shell);
  }

  @Test
  void aProvisionedBoxRunsNoAptAndSaysNothingOfTheHold() throws Exception {
    var shell =
        bareBox(
            new ScriptedShellExecutor()
                .onOk("which incus")
                .onOk("which zpool")
                .onOk("dpkg-query", HELD),
            "zfs");
    var steps = new ArrayList<String>();

    provision(shell, SDB, new RecordingListener(steps));

    assertEquals(List.of(DPKG_QUERY), packageCommands(shell));
    assertTrue(steps.contains("done:2/8:already installed"), steps.toString());
    assertTrue(
        steps.contains("done:3/8:already installed; ZFS cache capped at 6.4 GiB"),
        steps.toString());
  }

  @Test
  void aBoxWithIncusButNoZpoolRefreshesAptOnceThenInstallsZfs() throws Exception {
    var shell =
        bareBox(
            new ScriptedShellExecutor()
                .onOk("which incus")
                .onFail("which zpool", "")
                .onOk("dpkg-query", HELD),
            "zfs");

    provision(shell, SDB, null);

    assertEquals(
        List.of(DPKG_QUERY, APT_UPDATE, apt("install", "-y", "zfsutils-linux")),
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
                .onOk("dpkg-query", HELD),
            "dir");
    var progress = new ArrayList<String>();
    var steps = new ArrayList<String>();

    provision(shell, DIR, new ProgressListener(steps, progress));

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
                            + "/incus_6.18_amd64.deb  Connection timed out\n")),
            "dir");
    var steps = new ArrayList<String>();

    var failure =
        assertThrows(IOException.class, () -> provision(shell, DIR, new RecordingListener(steps)));

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
                    String.join(" ", APT_UPDATE),
                    new ShellExec.Result(
                        100,
                        waiting + waiting,
                        "E: Unable to acquire the dpkg frontend lock (/var/lib/dpkg/lock-frontend), is"
                            + " another process using it?\n")),
            "dir");
    var progress = new ArrayList<String>();

    var failure =
        assertThrows(
            IOException.class,
            () -> provision(shell, DIR, new ProgressListener(new ArrayList<>(), progress)));

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
        box(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onOk("incus version", "6.3"),
            "zfs");

    var provisioner = new HostProvisioner(shell, null, OperatorCommand.SUDO);
    var hostYamlPath = tempDir.resolve("etc/sail/host.yaml");

    provisioner.provision(
        SDB, "devpool", "incusbr0", null, MEMORY_MB, hostYamlPath, tempDir.resolve("sail.db"));

    assertTrue(Files.exists(hostYamlPath), "host.yaml should be written");
    var content = Files.readString(hostYamlPath);
    assertTrue(content.contains("devpool"));
    assertTrue(content.contains("/dev/sdb"));
    assertTrue(content.contains("incusbr0"));
    assertTrue(
        content.contains("storage_backend: zfs") || content.contains("storage_backend: \"zfs\""));
  }

  @Test
  void aPoolIncusCannotCreateFailsWithIncussReason() {
    var shell =
        fresh(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onFail("incus storage create", "Error: cannot open '/dev/sdb': no such device"),
            "zfs");
    var steps = new ArrayList<String>();

    var ex =
        assertThrows(IOException.class, () -> provision(shell, SDB, new RecordingListener(steps)));

    assertEquals(
        "Failed to create storage pool devpool: Error: cannot open '/dev/sdb': no such device",
        ex.getMessage());
    assertEquals(List.of("step:5/8:Creating storage pool...", "failed"), steps.subList(8, 10));
  }

  @Test
  void incusInitFailureThrowsIOException() {
    var shell =
        box(
            new ScriptedShellExecutor()
                .onOk("needrestart")
                .onOk("apt-get")
                .onOk("which incus")
                .onOk("which zpool")
                .onOk(ZfsCacheCap.PATH)
                .onFail("incus admin init", "permission denied"),
            "zfs");
    var steps = new ArrayList<String>();

    var provisioner =
        new HostProvisioner(shell, new RecordingListener(steps), OperatorCommand.SUDO);

    var ex =
        assertThrows(
            IOException.class,
            () ->
                provisioner.provision(
                    SDB,
                    "devpool",
                    "incusbr0",
                    null,
                    MEMORY_MB,
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
        box(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .on("incus admin init", new ShellExec.Result(1, "", "network already exists"))
                .onOk("incus version", "6.3"),
            "zfs");

    var provisioner = new HostProvisioner(shell, null, OperatorCommand.SUDO);

    assertDoesNotThrow(
        () ->
            provisioner.provision(
                SDB,
                "devpool",
                "incusbr0",
                null,
                MEMORY_MB,
                tempDir.resolve("h.yaml"),
                tempDir.resolve("sail.db")));
  }

  @Test
  void cacheImageFailureThrowsIOException() {
    var shell =
        box(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onOk("incus version", "6.3")
                .onFail("incus image list", "")
                .onFail("incus image copy", "network unreachable"),
            "zfs");

    var provisioner = new HostProvisioner(shell, null, OperatorCommand.SUDO);

    var ex =
        assertThrows(
            IOException.class,
            () ->
                provisioner.provision(
                    SDB,
                    "devpool",
                    "incusbr0",
                    null,
                    MEMORY_MB,
                    tempDir.resolve("h.yaml"),
                    tempDir.resolve("sail.db")));
    assertTrue(ex.getMessage().contains("Failed to cache image"));
  }

  @Test
  void incusVersionFailureReportsUnknown() throws Exception {
    var shell =
        box(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onFail("incus version", "command not found"),
            "zfs");

    var provisioner = new HostProvisioner(shell, null, OperatorCommand.SUDO);
    var result =
        provisioner.provision(
            SDB,
            "devpool",
            "incusbr0",
            null,
            MEMORY_MB,
            tempDir.resolve("host.yaml"),
            tempDir.resolve("sail.db"));

    assertEquals("unknown", result.incusVersion());
  }

  @Test
  void dirBackendSkipsZfsInstall() throws Exception {
    var shell = new ShellExecutor(true);
    var steps = new ArrayList<String>();
    var listener = new RecordingListener(steps);
    var provisioner = new HostProvisioner(shell, listener, OperatorCommand.SUDO);

    provisioner.provision(
        DIR,
        "devpool",
        "incusbr0",
        null,
        MEMORY_MB,
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
    var shell = fresh(new ScriptedShellExecutor(new ShellExec.Result(0, "", "")), "dir");
    var steps = new ArrayList<String>();

    provision(shell, DIR, new RecordingListener(steps));

    assertTrue(
        shell.arguments().contains(List.of("incus", "storage", "create", "devpool", "dir")),
        shell.invocations().toString());
    assertTrue(steps.contains("done:5/8:devpool (dir)"), steps.toString());
  }

  @Test
  void dirBackendStoragePoolAlreadyExistsIsIdempotent() throws Exception {
    var shell =
        box(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onOk("incus version", "6.3")
                .onOk("incus storage show"),
            "dir");

    var steps = new ArrayList<String>();
    var provisioner =
        new HostProvisioner(shell, new RecordingListener(steps), OperatorCommand.SUDO);

    provisioner.provision(
        DIR,
        "devpool",
        "incusbr0",
        null,
        MEMORY_MB,
        tempDir.resolve("host.yaml"),
        tempDir.resolve("sail.db"));

    assertTrue(
        steps.contains(
            "done:5/8:devpool (dir on /var/lib/incus/storage-pools/devpool, already exists)"),
        steps.toString());
  }

  @Test
  void dirBackendStoragePoolCreationFailureThrows() {
    var shell =
        fresh(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onFail("incus storage create", "permission denied"),
            "dir");

    var ex = assertThrows(IOException.class, () -> provision(shell, DIR, null));

    assertEquals("Failed to create storage pool devpool: permission denied", ex.getMessage());
  }

  @Test
  void dirBackendReturnsNullPoolDisk() throws Exception {
    var shell = new ShellExecutor(true);
    var provisioner = new HostProvisioner(shell, null, OperatorCommand.SUDO);

    var result =
        provisioner.provision(
            DIR,
            "devpool",
            "incusbr0",
            null,
            MEMORY_MB,
            tempDir.resolve("host.yaml"),
            tempDir.resolve("sail.db"));

    assertEquals("dir", result.storageBackend());
    assertEquals("devpool", result.pool());
    assertNull(result.poolSource());
  }

  @Test
  void dirBackendDryRunCompletesAllSteps() throws Exception {
    var shell = new ShellExecutor(true);
    var steps = new ArrayList<String>();
    var listener = new RecordingListener(steps);
    var provisioner = new HostProvisioner(shell, listener, OperatorCommand.SUDO);

    provisioner.provision(
        DIR,
        "devpool",
        "incusbr0",
        null,
        MEMORY_MB,
        tempDir.resolve("host.yaml"),
        tempDir.resolve("sail.db"));

    assertEquals(15, steps.size());
  }

  @Test
  void ufwActiveAddsAllBridgeRules() throws Exception {
    var shell =
        box(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onOk("incus version", "6.3")
                .on("ufw status", new ShellExec.Result(0, "Status: active\n", ""))
                .onOk("ufw allow")
                .onOk("ufw route"),
            "dir");

    var steps = new ArrayList<String>();
    var provisioner =
        new HostProvisioner(shell, new RecordingListener(steps), OperatorCommand.SUDO);

    provisioner.provision(
        DIR,
        "devpool",
        "incusbr0",
        null,
        MEMORY_MB,
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
        box(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onOk("incus version", "6.3")
                .onThrow(
                    "ufw",
                    new IOException(
                        "Cannot run program \"ufw\": error=2, No such file or directory")),
            "dir");

    var steps = new ArrayList<String>();
    var provisioner =
        new HostProvisioner(shell, new RecordingListener(steps), OperatorCommand.SUDO);

    assertDoesNotThrow(
        () ->
            provisioner.provision(
                DIR,
                "devpool",
                "incusbr0",
                null,
                MEMORY_MB,
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
        box(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onOk("incus version", "6.3")
                .on("ufw status", new ShellExec.Result(0, "Status: inactive\n", "")),
            "dir");

    var steps = new ArrayList<String>();
    var provisioner =
        new HostProvisioner(shell, new RecordingListener(steps), OperatorCommand.SUDO);

    provisioner.provision(
        DIR,
        "devpool",
        "incusbr0",
        null,
        MEMORY_MB,
        tempDir.resolve("host.yaml"),
        tempDir.resolve("sail.db"));

    assertTrue(steps.stream().anyMatch(s -> s.contains("skipped:8/8:UFW not active")));
  }

  @Test
  void ufwRuleFailureReportsWarning() throws Exception {
    var shell =
        box(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onOk("incus version", "6.3")
                .on("ufw status", new ShellExec.Result(0, "Status: active\n", ""))
                .onOk("ufw allow")
                .onFail("ufw route", "permission denied"),
            "dir");

    var steps = new ArrayList<String>();
    var provisioner =
        new HostProvisioner(shell, new RecordingListener(steps), OperatorCommand.SUDO);

    provisioner.provision(
        DIR,
        "devpool",
        "incusbr0",
        null,
        MEMORY_MB,
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
    assertEquals("/var/lib/incus/storage-pools/devpool", after.poolSource());
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
                new HostProvisioner(shell, new RecordingListener(steps), OperatorCommand.SUDO)
                    .provision(
                        DIR,
                        "devpool",
                        "incusbr0",
                        null,
                        MEMORY_MB,
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
        box(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onOk("incus version", "6.3")
                .on("ufw status", new ShellExec.Result(0, "Status: inactive\n", "")),
            "dir");
    return new HostProvisioner(shell, listener, OperatorCommand.SUDO)
        .provision(
            DIR,
            "devpool",
            "incusbr0",
            "192.0.2.4",
            MEMORY_MB,
            path,
            path.resolveSibling("sail.db"));
  }

  @Test
  void provisioningWritesTheNeedrestartPolicyFirstAndNeverUpgradesTheOs() throws Exception {
    var shell =
        box(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onFail("which incus", "")
                .onFail("which zpool", ""),
            "zfs");

    provision(shell, SDB, null);

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
                new HostProvisioner(shell, null, OperatorCommand.SUDO)
                    .provision(
                        DIR,
                        "devpool",
                        "incusbr0",
                        null,
                        MEMORY_MB,
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
      new HostProvisioner(new ShellExecutor(true), null, OperatorCommand.SUDO)
          .provision(
              DIR,
              "devpool",
              "incusbr0",
              null,
              MEMORY_MB,
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
  void aZfsPoolOnAWholeDiskIsIncussOwnAndTheProfilePutsContainersOnIt() throws Exception {
    var shell =
        fresh(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onOk("incus profile device list", ""),
            "zfs");
    var steps = new ArrayList<String>();

    var hostYaml = provision(shell, SDB, new RecordingListener(steps));

    assertTrue(
        shell
            .arguments()
            .contains(List.of("incus", "storage", "create", "devpool", "zfs", "source=/dev/sdb")),
        shell.invocations().toString());
    assertTrue(
        shell
            .arguments()
            .contains(
                List.of(
                    "incus",
                    "profile",
                    "device",
                    "add",
                    "singlr-base",
                    "root",
                    "disk",
                    "path=/",
                    "pool=devpool")),
        shell.invocations().toString());
    assertEquals(
        List.of(),
        shell.invocations().stream()
            .filter(c -> c.startsWith("zpool") || c.startsWith("zfs"))
            .filter(c -> !c.startsWith("zpool list ") && !c.startsWith("zfs list "))
            .toList(),
        "sail only reads ZFS and makes nothing in the pool itself; Incus does");
    assertTrue(steps.contains("done:5/8:devpool (zfs on /dev/sdb)"), steps.toString());
    assertTrue(
        steps.contains("done:6/8:singlr-base (already exists): containers on devpool (zfs)"),
        steps.toString());
    assertEquals(
        List.of("zfs", "devpool", "/dev/sdb"),
        List.of(hostYaml.storageBackend(), hostYaml.pool(), hostYaml.poolSource()));
  }

  @Test
  void aZfsPoolOnAPartitionIsMadeTheSameWay() throws Exception {
    var partition = "/dev/sdd1";
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onceOnOk("incus storage list --format json", "[]")
            .onOk(
                "incus storage list",
                "[{\"name\": \"devpool\", \"driver\": \"zfs\", \"config\": {\"source\":"
                    + " \"devpool\", \"volatile.initial_source\": \""
                    + partition
                    + "\"}}]")
            .onOk("incus profile device list", "root\neth0\n")
            .onOk("incus profile device get singlr-base root pool", "devpool\n")
            .onOk("realpath -e -- " + partition, partition + "\n")
            .onOk(DeviceFixtures.LSBLK, DeviceFixtures.read(DeviceFixtures.SPARES))
            .onOk("wipefs --no-act", DeviceFixtures.NOTHING_HELD);

    var hostYaml = provision(shell, new PoolSource.Device(partition, false), null);

    assertTrue(
        shell
            .arguments()
            .contains(
                List.of("incus", "storage", "create", "devpool", "zfs", "source=" + partition)),
        shell.invocations().toString());
    assertEquals(partition, hostYaml.poolSource());
  }

  @Test
  void aFileBackedPoolIsSizedAndSaysOnceThatItIsForTrials() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onceOnOk("incus storage list --format json", "[]")
            .onOk("incus storage list", pools("devpool", "zfs", "/var/lib/incus/disks/devpool.img"))
            .onOk("incus profile device list", "root\neth0\n")
            .onOk("incus profile device get singlr-base root pool", "devpool\n");
    var steps = new ArrayList<String>();

    var hostYaml = provision(shell, new PoolSource.File(51200), new RecordingListener(steps));

    assertTrue(
        shell
            .arguments()
            .contains(List.of("incus", "storage", "create", "devpool", "zfs", "size=51200MiB")),
        shell.invocations().toString());
    assertEquals(
        List.of(
            "done:5/8:devpool (zfs in a 50.0 GiB file; slower than a disk, meant for trying ZFS)"),
        steps.stream().filter(event -> event.contains("slower than a disk")).toList());
    assertEquals("/var/lib/incus/disks/devpool.img", hostYaml.poolSource());
    assertFalse(
        shell.invocations().stream()
            .anyMatch(c -> c.startsWith("realpath") || c.startsWith("lsblk")),
        "a file pool names no device to clear");
  }

  @Test
  void aDeviceNamedAgainToBeErasedIsWipedByIncusAndOnlyThen() throws Exception {
    var shell =
        fresh(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onOk("wipefs --no-act --json -- /dev/sdb", WIPEFS_EXT4),
            "zfs");

    provision(shell, new PoolSource.Device("/dev/sdb", true), null);

    assertTrue(
        shell
            .arguments()
            .contains(
                List.of(
                    "incus",
                    "storage",
                    "create",
                    "devpool",
                    "zfs",
                    "source=/dev/sdb",
                    "source.wipe=true")),
        shell.invocations().toString());
  }

  @Test
  void aDeviceThatTookASignatureSinceItWasNamedIsRefusedAndUntouched() {
    var shell =
        fresh(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onOk("wipefs --no-act --json -- /dev/sdb", WIPEFS_EXT4),
            "zfs");

    var refused = assertThrows(IllegalArgumentException.class, () -> provision(shell, SDB, null));

    assertEquals(
        "/dev/sdb holds ext4. Nothing on it is erased unless you name it again: add --force-wipe"
            + " /dev/sdb to erase it and make the pool there.",
        refused.getMessage());
    assertNoPoolCreated(shell);
  }

  @Test
  void aDeviceMountedSinceItWasNamedIsRefusedAndUntouched() {
    var shell =
        fresh(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onOk(
                    "lsblk --json --tree",
                    DeviceFixtures.sparesWithMount("/dev/sdb", "/mnt/scratch")),
            "zfs");

    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> provision(shell, new PoolSource.Device("/dev/sdb", true), null));

    assertEquals(
        "/dev/sdb is refused, even with --force-wipe: /dev/sdb is mounted at /mnt/scratch.",
        refused.getMessage());
    assertNoPoolCreated(shell);
  }

  @Test
  void aDirBoxWithContainersAddsAZfsPoolBesideItsOwnAndTouchesNoContainer() throws Exception {
    var dirPool =
        "{\"name\": \"devpool\", \"driver\": \"dir\", \"config\": {\"source\":"
            + " \"/var/lib/incus/storage-pools/devpool\"}}";
    var tank =
        "{\"name\": \"tank\", \"driver\": \"zfs\", \"config\": {\"source\":"
            + " \"/var/lib/incus/disks/tank.img\"}}";
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onceOnOk("incus storage list --format json", "[" + dirPool + "]")
            .onOk("incus storage list", "[" + dirPool + ", " + tank + "]")
            .onOk("incus profile device list", "root\neth0\n")
            .onOk("incus profile device get singlr-base root pool", "devpool\n")
            .onOk("incus list", "[{\"name\": \"acme\", \"status\": \"Running\"}]");
    var steps = new ArrayList<String>();

    var hostYaml =
        new HostProvisioner(shell, new RecordingListener(steps), OperatorCommand.SUDO)
            .provision(
                new PoolSource.File(20480),
                "tank",
                "incusbr0",
                null,
                MEMORY_MB,
                tempDir.resolve("host.yaml"),
                tempDir.resolve("sail.db"));

    assertTrue(
        shell
            .arguments()
            .contains(List.of("incus", "storage", "create", "tank", "zfs", "size=20480MiB")),
        shell.invocations().toString());
    assertEquals(
        List.of(),
        shell.invocations().stream()
            .filter(
                c ->
                    c.matches(
                        "incus (config|stop|start|restart|delete|move|rename|copy|snapshot|"
                            + "profile device (add|set|remove)|storage (delete|set)).*"))
            .toList());
    assertTrue(
        steps.contains("done:6/8:singlr-base (already exists): containers on devpool (dir)"),
        steps.toString());
    assertEquals(
        List.of("dir", "devpool", "/var/lib/incus/storage-pools/devpool"),
        List.of(hostYaml.storageBackend(), hostYaml.pool(), hostYaml.poolSource()));
  }

  @Test
  void aZfsPoolCannotTakeTheNameOfTheDirPoolTheBoxHas() {
    var shell = box(new ScriptedShellExecutor(new ShellExec.Result(0, "", "")), "dir");

    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> provision(shell, new PoolSource.File(20480), null));

    assertEquals(
        "Incus already has a dir pool named 'devpool', so no zfs pool can take that name. Name the"
            + " new pool with --pool <name>.",
        refused.getMessage());
    assertNoPoolCreated(shell);
  }

  @Test
  void theZfsCacheIsCappedBeforeTheToolsThatLoadZfsAreInstalled() throws Exception {
    var shell =
        bareBox(
            new ScriptedShellExecutor().onFail("which zpool", "").onOk("dpkg-query", HELD), "zfs");

    provision(shell, SDB, null);

    var cap = ZfsCacheCap.forMemory(MEMORY_MB).dropIn();
    var capped = shell.arguments().indexOf(cap.installCommand(cap.path()));
    var installed = shell.arguments().indexOf(apt("install", "-y", "zfsutils-linux"));
    assertTrue(0 <= capped && capped < installed, shell.invocations().toString());
  }

  @Test
  void aDirBoxWritesNoZfsCacheCap() throws Exception {
    var shell = box(new ScriptedShellExecutor(new ShellExec.Result(0, "", "")), "dir");

    provision(shell, DIR, null);

    assertFalse(
        shell.invocations().stream().anyMatch(c -> c.contains(ZfsCacheCap.PATH)),
        shell.invocations().toString());
  }

  @Test
  void anIncusThatCannotSeeZfsOnABoxWithContainersIsLeftRunningAndTheOperatorToldHow() {
    var shell =
        fresh(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onFail("incus storage create", "Error: Required tool 'zpool' is missing")
                .onOk("incus list", "[{\"name\": \"acme\", \"status\": \"Running\"}]"),
            "zfs");

    var refused = assertThrows(IOException.class, () -> provision(shell, SDB, null));

    assertEquals(
        "Incus started before the ZFS tools were installed and does not see them (Error: Required"
            + " tool 'zpool' is missing). Restart it when no terminal or agent run is open: sudo"
            + " systemctl restart incus, then run this command again.",
        OperatorCommand.SUDO.render((Remedied) refused));
    assertFalse(
        shell.invocations().stream().anyMatch(c -> c.contains("systemctl restart")),
        "nothing is restarted under a container");
  }

  @Test
  void anIncusThatCannotSeeZfsOnABoxWithNoContainersIsRestartedSayingSo() throws Exception {
    var shell =
        fresh(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onceOnFail("incus storage create", "Error: Required tool 'zpool' is missing")
                .onOk("incus list", "[]"),
            "zfs");
    var steps = new ArrayList<String>();

    provision(shell, SDB, new RecordingListener(steps));

    var restart = shell.arguments().indexOf(List.of("systemctl", "restart", "incus"));
    var creates =
        shell.invocations().stream().filter(c -> c.startsWith("incus storage create")).count();
    assertTrue(restart > 0, shell.invocations().toString());
    assertEquals(2, creates);
    assertTrue(
        steps.contains(
            "done:5/8:devpool (zfs on /dev/sdb); restarted Incus, which ran no container, to see"
                + " ZFS"),
        steps.toString());
  }

  @Test
  void aRestartThatFailsOrACreateThatStillFailsSaysSo() {
    var unrestarted =
        fresh(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onFail("incus storage create", "Error: Required tool 'zpool' is missing")
                .onOk("incus list", "[]")
                .onFail("systemctl restart incus", "Job for incus.service failed"),
            "zfs");
    var unseen =
        fresh(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onFail("incus storage create", "Error: Required tool 'zpool' is missing")
                .onOk("incus list", "[]"),
            "zfs");

    assertEquals(
        "Failed to restart Incus to see ZFS: Job for incus.service failed",
        assertThrows(IOException.class, () -> provision(unrestarted, SDB, null)).getMessage());
    assertEquals(
        "Failed to create storage pool devpool: Error: Required tool 'zpool' is missing",
        assertThrows(IOException.class, () -> provision(unseen, SDB, null)).getMessage());
  }

  @Test
  void aProfileLeftWithoutDevicesGetsItsRootDiskAndNic() throws Exception {
    var shell =
        box(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onOk("incus profile device list", ""),
            "dir");

    provision(shell, DIR, null);

    assertTrue(
        shell
            .arguments()
            .contains(
                List.of(
                    "incus",
                    "profile",
                    "device",
                    "add",
                    "singlr-base",
                    "root",
                    "disk",
                    "path=/",
                    "pool=devpool")),
        shell.invocations().toString());
    assertTrue(
        shell
            .arguments()
            .contains(
                List.of(
                    "incus",
                    "profile",
                    "device",
                    "add",
                    "singlr-base",
                    "eth0",
                    "nic",
                    "name=eth0",
                    "network=incusbr0",
                    "type=nic")),
        shell.invocations().toString());
    assertFalse(
        shell.invocations().stream().anyMatch(c -> c.startsWith("incus profile create")),
        "the profile is kept");
  }

  @Test
  void aProfileThatNamesAPoolIncusLacksIsRefused() {
    var shell =
        box(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onOk("incus profile device get", "oldpool\n"),
            "dir");

    var refused = assertThrows(IllegalStateException.class, () -> provision(shell, DIR, null));

    assertEquals(
        "singlr-base puts containers on pool 'oldpool', which Incus does not have. Point its root"
            + " disk at a pool Incus lists (incus storage list), then run this command again.",
        refused.getMessage());
  }

  @Test
  void aProfileAndDeviceThatCannotBeMadeFail() {
    var uncreated =
        fresh(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onFail("incus profile show", "not found")
                .onFail("incus profile create", "permission denied"),
            "dir");
    var unadded =
        box(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
                .onOk("incus profile device list", "root\n")
                .onFail("incus profile device add", "network not found"),
            "dir");

    assertEquals(
        "Failed to create Incus profile: permission denied",
        assertThrows(IOException.class, () -> provision(uncreated, DIR, null)).getMessage());
    assertEquals(
        "Failed to add NIC to profile: network not found",
        assertThrows(IOException.class, () -> provision(unadded, DIR, null)).getMessage());
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

  private static final String WIPEFS_EXT4 =
      "{\"signatures\": [{\"device\": \"sdb\", \"offset\": \"0x438\", \"type\": \"ext4\"}]}";

  private static final List<String> APT_UPDATE = apt("-o", "APT::Update::Error-Mode=any", "update");

  private static final List<String> DPKG_QUERY =
      List.of(
          "dpkg-query", "-W", "-f=${Package} ${db:Status-Want} ${db:Status-Status}\\n", "incus*");

  /**
   * {@code shell}, answering after its own scripts as a box whose every step but the packages is
   * done: Incus initialized, pool, profile and image made.
   */
  /**
   * {@code shell}, answering after its own scripts as a box whose Incus answers and holds pool
   * devpool with {@code driver}, the profile's root disk on it, and /dev/sdb a spare disk.
   */
  private static ScriptedShellExecutor box(ScriptedShellExecutor shell, String driver) {
    return shell
        .onOk(
            "incus storage list",
            pools(
                "devpool",
                driver,
                "zfs".equals(driver) ? "/dev/sdb" : "/var/lib/incus/storage-pools/devpool"))
        .onOk("incus profile device list singlr-base", "root\neth0\n")
        .onOk("incus profile device get singlr-base root pool", "devpool\n")
        .onOk("realpath -e -- /dev/sdb", "/dev/sdb\n")
        .onOk("realpath -e -- /dev/sdd1", "/dev/sdd1\n")
        .onOk(DeviceFixtures.LSBLK, DeviceFixtures.read(DeviceFixtures.SPARES))
        .onOk("wipefs --no-act", DeviceFixtures.NOTHING_HELD)
        .onOk("wipefs --all --no-act")
        .onOk("zpool list -vHP", "");
  }

  /** {@code shell}, on a box whose Incus holds no pool until one is created. */
  private static ScriptedShellExecutor fresh(ScriptedShellExecutor shell, String driver) {
    return box(shell.onceOnOk("incus storage list --format json", "[]"), driver);
  }

  private static ScriptedShellExecutor bareBox(ScriptedShellExecutor shell, String driver) {
    return box(shell.onOk("incus version", "6.3"), driver)
        .onOk("needrestart")
        .onOk(ZfsCacheCap.PATH)
        .onOk("curl")
        .onOk(HostPackages.ZABBLY_SOURCE)
        .on("echo $VERSION_CODENAME", new ShellExec.Result(0, "noble\n", ""))
        .on("dpkg --print-architecture", new ShellExec.Result(0, "amd64\n", ""))
        .onOk("apt-get")
        .onOk("incus admin init")
        .onOk("incus profile show")
        .onOk("incus image list");
  }

  private HostYaml provision(ShellExec shell, PoolSource source, ProvisionListener listener)
      throws Exception {
    return new HostProvisioner(shell, listener, OperatorCommand.SUDO)
        .provision(
            source,
            "devpool",
            "incusbr0",
            null,
            MEMORY_MB,
            tempDir.resolve("host.yaml"),
            tempDir.resolve("sail.db"));
  }

  /** What {@code incus storage list --format json} answers on a box holding one pool. */
  static String pools(String name, String driver, String source) {
    return "[{\"name\": \""
        + name
        + "\", \"driver\": \""
        + driver
        + "\", \"config\": {\"source\": \""
        + source
        + "\"}}]";
  }

  private static void assertNoPoolCreated(ScriptedShellExecutor shell) {
    assertEquals(
        List.of(),
        shell.invocations().stream().filter(c -> c.startsWith("incus storage create")).toList());
  }

  private static List<String> apt(String... args) {
    var command = new ArrayList<>(Apt.COMMAND);
    command.addAll(List.of(args));
    return command;
  }

  private static List<String> hold(String... packages) {
    var command = new ArrayList<>(Apt.HOLD);
    command.addAll(List.of(packages));
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
        shell.invocations().stream().filter(command -> command.contains("apt-")).toList(),
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
