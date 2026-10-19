/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OperatorCommandTest {

  @Test
  void aSudoCallerTypesSudoWhateverTheBoxHasInstalled() {
    var operator = OperatorCommand.of(true, "dev");

    assertEquals(OperatorCommand.SUDO, operator);
    assertEquals("sudo sail project connect web", operator.sail("project connect web"));
    assertEquals("sudo sail sync", operator.sail("sync"));
  }

  @Test
  void rootLoggedInDirectlyTypesPlainSail() {
    assertEquals(OperatorCommand.PLAIN, OperatorCommand.of(true, null));
    assertEquals(OperatorCommand.PLAIN, OperatorCommand.of(true, " "));
    assertEquals(OperatorCommand.PLAIN, OperatorCommand.of(true, "root"));
    assertEquals("sail upgrade", OperatorCommand.PLAIN.sail("upgrade"));
  }

  @Test
  void anAccountRunningItsOwnSailTypesPlainSailAndSudoOnlyForRoots() {
    var operator = OperatorCommand.of(false, "dev");

    assertEquals(OperatorCommand.UNPRIVILEGED, operator);
    assertEquals("sail project connect web", operator.sail("project connect web"));
    assertEquals("sudo sail project apply web", operator.sail("project apply web"));
  }

  @Test
  void aNonRootProcessTypesARootOnlyVerbWithSudoAndNothingElse() {
    var own = OperatorCommand.UNPRIVILEGED;

    for (var verb : OperatorCommand.ROOT_ONLY) {
      assertEquals("sudo sail " + verb + " x", own.sail(verb + " x"));
      assertEquals("sudo sail " + verb, own.sail(verb));
    }
    assertEquals("sail initialize", own.sail("initialize"));
    assertEquals("sail hosts init", own.sail("hosts init"));
    assertEquals("sail host keys", own.sail("host keys"));
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
    assertEquals("ssh-keygen -R main", OperatorCommand.PLAIN.asThisProcess(removal));
    assertEquals("ssh-keygen -R main", OperatorCommand.UNPRIVILEGED.asThisProcess(removal));
  }

  @Test
  void aProcessThatCannotSeeItsReaderAnswersTheReadmesFormAsRoot() {
    assertEquals(OperatorCommand.SUDO, OperatorCommand.unknownReader(true));
    assertEquals(OperatorCommand.UNPRIVILEGED, OperatorCommand.unknownReader(false));
    assertEquals(
        OperatorCommand.elsewhere("spec update auth"),
        OperatorCommand.unknownReader(true).sail("spec update auth"));
  }

  @Test
  void rootLoggedInDirectlyNeverTypesSudo() {
    var typesSudo =
        assertThrows(IllegalArgumentException.class, () -> new OperatorCommand(true, true));

    assertEquals("Root logged in directly never types sudo.", typesSudo.getMessage());
  }

  @Test
  void thisProcessReadsOneOfTheThreeReaders() {
    var readers =
        List.of(OperatorCommand.PLAIN, OperatorCommand.SUDO, OperatorCommand.UNPRIVILEGED);

    assertTrue(readers.contains(OperatorCommand.here()));
    assertTrue(readers.contains(OperatorCommand.unknownReader()));
    assertEquals(SailPaths.isRoot(), OperatorCommand.unknownReader().sudo());
  }
}
