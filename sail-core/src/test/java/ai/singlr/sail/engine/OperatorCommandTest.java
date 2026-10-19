/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperatorCommandTest {

  @TempDir Path tempDir;

  private Path systemUnit() throws Exception {
    var unit = tempDir.resolve("sail-api.service");
    Files.writeString(unit, "[Unit]\n");
    return unit;
  }

  private Path homeOf(String user) {
    return tempDir.resolve("home").resolve(user);
  }

  private void own(String user, String file, String content) throws Exception {
    var path = homeOf(user).resolve(".sail").resolve(file);
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
  }

  @Test
  void aSudoCallerOnARootBoxTypesSudo() throws Exception {
    var operator = OperatorCommand.of(true, "dev", this::homeOf, systemUnit());

    assertEquals(OperatorCommand.SUDO, operator);
    assertEquals("sudo sail project connect web", operator.sail("project connect web"));
  }

  @Test
  void rootLoggedInDirectlyTypesPlainSail() throws Exception {
    var unit = systemUnit();

    assertEquals(OperatorCommand.PLAIN, OperatorCommand.of(true, null, this::homeOf, unit));
    assertEquals(OperatorCommand.PLAIN, OperatorCommand.of(true, " ", this::homeOf, unit));
    assertEquals(OperatorCommand.PLAIN, OperatorCommand.of(true, "root", this::homeOf, unit));
    assertEquals("sail upgrade", OperatorCommand.PLAIN.sail("upgrade"));
  }

  @Test
  void anAccountRunningItsOwnSailTypesPlainSailAndSudoOnlyForRoots() throws Exception {
    var operator = OperatorCommand.of(false, "dev", this::homeOf, systemUnit());

    assertEquals(OperatorCommand.UNPRIVILEGED, operator);
    assertEquals("sail project connect web", operator.sail("project connect web"));
    assertEquals("sudo sail project apply web", operator.sail("project apply web"));
  }

  @Test
  void aSudoCallerWhoOwnsABoxInItsHomeTypesPlainSailAndSudoOnlyForRoots() throws Exception {
    own("dev", "host.yaml", "storage_backend: dir\n");

    assertEquals(
        OperatorCommand.SUDO_UNREFUSED,
        OperatorCommand.of(true, "dev", this::homeOf, systemUnit()));
  }

  @Test
  void aSudoCallerOfARootOnlyCommandTypesSudoBeforeTheBoxInstalledAnything() {
    var freshBox = OperatorCommand.of(true, "dev", this::homeOf, tempDir.resolve("no-unit"));

    assertEquals(OperatorCommand.SUDO_UNREFUSED, freshBox);
    assertEquals("sail sync", freshBox.sail("sync"));
    for (var verb : OperatorCommand.ROOT_ONLY) {
      assertEquals("sudo sail " + verb + " x", freshBox.sail(verb + " x"));
      assertEquals("sudo sail " + verb, freshBox.sail(verb));
    }
    assertEquals("sail initialize", freshBox.sail("initialize"));
    assertEquals("sail hosts init", freshBox.sail("hosts init"));
  }

  @Test
  void onlyRootLoggedInDirectlyTypesARootOnlyCommandPlain() {
    assertEquals("sail host ssh-identity", OperatorCommand.PLAIN.sail("host ssh-identity"));
    assertEquals("sail host service install", OperatorCommand.PLAIN.root("host service install"));
    assertEquals(
        "sudo sail host service install",
        OperatorCommand.UNPRIVILEGED.root("host service install"));
    assertEquals(
        "sudo sail host service install", OperatorCommand.SUDO.root("host service install"));
    assertEquals("sudo sail host ssh-identity", OperatorCommand.SUDO.sail("host ssh-identity"));
    assertEquals(
        "sudo sail project resources set gamma",
        OperatorCommand.UNPRIVILEGED.sail("project resources set gamma"));
  }

  @Test
  void aReaderWhoReachedThisProcessThroughSudoTouchesItsFilesWithSudo() {
    var removal = "ssh-keygen -R main";

    assertEquals("sudo ssh-keygen -R main", OperatorCommand.SUDO.asThisProcess(removal));
    assertEquals("sudo ssh-keygen -R main", OperatorCommand.SUDO_UNREFUSED.asThisProcess(removal));
    assertEquals("ssh-keygen -R main", OperatorCommand.PLAIN.asThisProcess(removal));
    assertEquals("ssh-keygen -R main", OperatorCommand.UNPRIVILEGED.asThisProcess(removal));
  }

  @Test
  void rootLoggedInDirectlyNeverTypesSudo() {
    var typesSudo =
        assertThrows(IllegalArgumentException.class, () -> new OperatorCommand(true, true, false));
    var cameThroughSudo =
        assertThrows(IllegalArgumentException.class, () -> new OperatorCommand(false, true, true));

    assertEquals("Root logged in directly never types sudo.", typesSudo.getMessage());
    assertEquals("Root logged in directly never types sudo.", cameThroughSudo.getMessage());
  }

  @Test
  void thisProcessReadsItsOwnAccount() {
    var expected =
        OperatorCommand.of(
            SailPaths.isRoot(),
            System.getenv("SUDO_USER"),
            SailPaths::accountHome,
            SailPaths.SYSTEM_UNIT);

    assertEquals(expected, OperatorCommand.here());
  }
}
