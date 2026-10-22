/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SailPathsTest {

  @TempDir Path tempDir;

  private static final Path HOME = Path.of(System.getProperty("user.home"));

  @Test
  void sailDirIsUnderHome() {
    assertEquals(HOME.resolve(".sail"), SailPaths.sailDir());
  }

  @Test
  void projectDirIsUnderSingDir() {
    assertEquals(HOME.resolve(".sail/projects/acme-health"), SailPaths.projectDir("acme-health"));
  }

  @Test
  void provisionStateIsInsideProjectDir() {
    var stateFile = SailPaths.provisionState("test");

    assertTrue(stateFile.startsWith(SailPaths.projectDir("test")));
    assertTrue(stateFile.toString().endsWith("provision-state.yaml"));
  }

  @Test
  void projectDirVariesByName() {
    assertNotEquals(SailPaths.projectDir("alpha"), SailPaths.projectDir("beta"));
  }

  @Test
  void hostConfigPathFallsBackToHomeWhenNoSharedCopy() {
    assertEquals(HOME.resolve(".sail/host.yaml"), SailPaths.hostConfigPath(false));
  }

  @Test
  void hostConfigPathPrefersTheSharedSystemCopy() {
    assertEquals(Path.of("/var/lib/sail/host.yaml"), SailPaths.hostConfigPath(true));
  }

  @Test
  void clientConfigPathIsUnderSingDir() {
    assertEquals(HOME.resolve(".sail/config.yaml"), SailPaths.clientConfigPath());
  }

  @Test
  void updateCheckFileIsUnderSingDir() {
    assertEquals(HOME.resolve(".sail/update-check.yaml"), SailPaths.updateCheckFile());
  }

  @Test
  void dataDirEnvOverrideWinsEvenOverProvisionedSystemDir() {
    assertEquals(Path.of("/custom/dir"), SailPaths.dataDir("/custom/dir", false));
    assertEquals(Path.of("/custom/dir"), SailPaths.dataDir("/custom/dir", true));
  }

  @Test
  void dataDirAutoDetectsProvisionedSystemDir() {
    assertEquals(Path.of("/var/lib/sail"), SailPaths.dataDir(null, true));
  }

  @Test
  void dataDirFallsBackToSailDirWhenUnsetAndNoSystemDb() {
    assertEquals(SailPaths.sailDir(), SailPaths.dataDir(null, false));
    assertEquals(SailPaths.sailDir(), SailPaths.dataDir("   ", false));
  }

  @Test
  void controlPlaneDbIsSailDbUnderDataDir() {
    assertEquals("sail.db", SailPaths.controlPlaneDb().getFileName().toString());
  }

  @Test
  void onlyADataDirOtherThanTheProvisionedOneIsAnOverride() {
    assertFalse(SailPaths.dataDirOverridden(null));
    assertFalse(SailPaths.dataDirOverridden("  "));
    assertFalse(SailPaths.dataDirOverridden("/var/lib/sail"));
    assertFalse(SailPaths.dataDirOverridden("/var/lib/sail/"));
    assertFalse(SailPaths.dataDirOverridden("/var/lib/../lib/sail"));
    assertTrue(SailPaths.dataDirOverridden("/root/rehearsal"));
    assertTrue(SailPaths.dataDirOverridden("/var/lib/sail-copy"));
  }

  @Test
  void theInstalledBinaryResolvesThroughALinkToTheExecutableItNames() throws Exception {
    var binary = Files.writeString(tempDir.resolve("sail-0.46.0"), "#!/bin/sh\n");
    binary.toFile().setExecutable(true);
    var link = Files.createSymbolicLink(tempDir.resolve("sail"), binary);

    assertEquals(binary.toRealPath(), SailPaths.installedBinary(link));
  }

  @Test
  void aMissingOrNonExecutableInstalledBinaryFailsNamingTheInstaller() throws Exception {
    var missing = tempDir.resolve("absent/sail");
    var plain = Files.writeString(tempDir.resolve("plain"), "not executable");

    for (var location : List.of(missing, plain, tempDir)) {
      var failure =
          assertThrows(IllegalStateException.class, () -> SailPaths.installedBinary(location));
      assertTrue(failure.getMessage().contains(location.toString()), failure.getMessage());
      assertTrue(failure.getMessage().contains("install.sh"), failure.getMessage());
      assertTrue(SailPaths.findInstalledBinary(location).isEmpty());
    }
  }

  @Test
  void aPresentButNonExecutableBinaryIsNamedAsSuchNotAsMissing() throws Exception {
    var plain = Files.writeString(tempDir.resolve("sail"), "not executable");

    var failure = assertThrows(IllegalStateException.class, () -> SailPaths.installedBinary(plain));

    assertTrue(failure.getMessage().startsWith(plain + " is not an executable file"));
  }

  @Test
  void theInstalledBinaryIsWhereInstallShPutsIt() throws Exception {
    var installer = Files.readString(Path.of("..", "install.sh"));

    assertTrue(
        installer.contains("INSTALL_DIR=\"" + SailPaths.INSTALLED_BINARY.getParent() + "\""),
        "install.sh and SailPaths.INSTALLED_BINARY disagree on where sail lives");
  }

  @Test
  void aLinkToAnotherDirectoryOrAMissingOneIsStillAnOverride() throws Exception {
    var elsewhere = Files.createDirectory(tempDir.resolve("copy"));
    var link = Files.createSymbolicLink(tempDir.resolve("link"), elsewhere);

    assertTrue(SailPaths.dataDirOverridden(link.toString()));
    assertTrue(SailPaths.dataDirOverridden(tempDir.resolve("missing").toString()));
  }

  @Test
  void theSystemUnitIsRootsSailApi() {
    assertEquals(Path.of("/etc/systemd/system/sail-api.service"), SailPaths.SYSTEM_UNIT);
  }

  private Path ownBox(String user) throws Exception {
    var home = tempDir.resolve("srv").resolve(user);
    Files.createDirectories(home.resolve(".sail"));
    Files.writeString(home.resolve(".sail/host.yaml"), "storage_backend: dir\n");
    return home;
  }

  private Path homeOf(String user) {
    var passwd =
        new ScriptedShellExecutor()
            .onOk(
                "getent passwd -- dev",
                "dev:x:1001:1001::" + tempDir.resolve("srv/dev") + ":/bin/bash\n")
            .onOk("getent passwd -- mady", "mady:x:1002:1002::/home/mady:/bin/bash\n");
    return SailPaths.accountHome(passwd, user);
  }

  @Test
  void sudoOnABoxAnAccountOwnsInItsHomeResolvesThatAccountsIdentity() throws Exception {
    var dev = ownBox("dev");
    var root = tempDir.resolve("root");

    assertEquals(dev, SailPaths.identityHome(true, "dev", this::homeOf, root));
  }

  @Test
  void theBoxsWorkstationKeyIsTheOneFileInItsOwnersHome() throws Exception {
    var dev = ownBox("dev");

    assertEquals(
        dev.resolve(".sail").resolve("workstation_key.pub"),
        SailPaths.workstationPublicKeyPath(
            SailPaths.identityHome(true, "dev", this::homeOf, tempDir.resolve("root"))));
    assertEquals(
        SailPaths.workstationPublicKeyPath(SailPaths.identityHome()),
        SailPaths.workstationPublicKeyPath());
  }

  @Test
  void everyOtherAccountResolvesItsOwnIdentity() throws Exception {
    ownBox("dev");
    var own = tempDir.resolve("own");

    assertEquals(own, SailPaths.identityHome(false, "dev", this::homeOf, own), "not root");
    assertEquals(own, SailPaths.identityHome(true, null, this::homeOf, own), "no sudo");
    assertEquals(own, SailPaths.identityHome(true, "root", this::homeOf, own), "sudo by root");
    assertEquals(own, SailPaths.identityHome(true, "mady", this::homeOf, own), "mady owns none");
  }

  @Test
  void theInvokingHomeIsTheSudoCallersPasswdHomeElseTheProcesssOwn() {
    var passwd =
        new ScriptedShellExecutor()
            .onOk("getent passwd -- dev", "dev:x:1001:1001::/srv/dev:/bin/bash\n")
            .onOk("getent passwd -- root", "root:x:0:0:root:/root:/bin/bash\n");
    Function<String, Path> homeOf = user -> SailPaths.accountHome(passwd, user);
    var own = tempDir.resolve("own");

    assertEquals(Path.of("/srv/dev"), SailPaths.invokingHome("dev", homeOf, own));
    assertEquals(
        Path.of("/root"),
        SailPaths.invokingHome("root", homeOf, own),
        "a root shell's sudo sets SUDO_USER=root, whose home is /root, not /home/root");
    assertEquals(own, SailPaths.invokingHome(null, homeOf, own), "no sudo");
    assertEquals(own, SailPaths.invokingHome(" ", homeOf, own), "blank SUDO_USER");
  }

  @Test
  void anAccountsHomeIsWhereItsPasswdEntrySaysNotUnderHome() {
    var passwd =
        new ScriptedShellExecutor()
            .onOk("getent passwd -- dev", "dev:x:1001:1001::/srv/dev:/bin/sh\n");

    assertEquals(Path.of("/srv/dev"), SailPaths.accountHome(passwd, "dev"));
    assertEquals(List.of(List.of("getent", "passwd", "--", "dev")), passwd.arguments());
  }

  @Test
  void anAccountWithNoUsableEntryIsRefused() {
    var refusal = "Couldn't find dev's home: 'getent passwd dev' has no usable entry.";
    for (var shell :
        List.of(
            new ScriptedShellExecutor().on("getent", new ShellExec.Result(2, "", "")),
            new ScriptedShellExecutor().onOk("getent", "dev:x:1001:1001::/srv/dev\n"),
            new ScriptedShellExecutor().onOk("getent", "devx:x:1001:1001::/srv/dev:/bin/sh\n"),
            new ScriptedShellExecutor().onOk("getent", "dev:x:1001:1001:::/bin/sh\n"),
            new ScriptedShellExecutor().onOk("getent", "dev:x:1001:1001::srv/dev:/bin/sh\n"))) {
      var failure =
          assertThrows(IllegalStateException.class, () -> SailPaths.accountHome(shell, "dev"));
      assertEquals(refusal, failure.getMessage());
    }
  }

  @Test
  void aLookupThatCannotRunIsRefused() {
    var shell = new ScriptedShellExecutor().onThrow("getent", new IOException("no getent"));

    var failure =
        assertThrows(IllegalStateException.class, () -> SailPaths.accountHome(shell, "dev"));

    assertEquals(
        "Couldn't find dev's home: 'getent passwd dev' failed: java.io.IOException: no getent",
        failure.getMessage());
  }
}
