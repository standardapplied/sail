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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

  @Test
  void sudoOnABoxAnAccountOwnsInItsHomeResolvesThatAccountsIdentity() throws Exception {
    var homes = tempDir.resolve("home");
    Files.createDirectories(homes.resolve("dev/.sail"));
    Files.writeString(homes.resolve("dev/.sail/host.yaml"), "storage_backend: dir\n");
    var root = tempDir.resolve("root");

    assertEquals(homes.resolve("dev"), SailPaths.identityHome(true, "dev", homes, root));
  }

  @Test
  void everyOtherAccountResolvesItsOwnIdentity() throws Exception {
    var homes = tempDir.resolve("home");
    Files.createDirectories(homes.resolve("dev/.sail"));
    Files.writeString(homes.resolve("dev/.sail/host.yaml"), "storage_backend: dir\n");
    var own = tempDir.resolve("own");

    assertEquals(own, SailPaths.identityHome(false, "dev", homes, own), "not root");
    assertEquals(own, SailPaths.identityHome(true, null, homes, own), "no sudo");
    assertEquals(own, SailPaths.identityHome(true, "root", homes, own), "sudo by root");
    assertEquals(own, SailPaths.identityHome(true, "mady", homes, own), "mady owns none");
  }
}
