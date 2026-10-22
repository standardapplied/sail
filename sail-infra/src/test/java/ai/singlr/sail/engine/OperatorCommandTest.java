/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
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
  void aRemedyIsRenderedForTheReaderOfTheDoorThatPrintsIt() {
    var remedy =
        Remedy.of(
            "Run '",
            Remedy.sail("upgrade"),
            "', then ",
            Remedy.sail("fde update", "ada", "--scoped"),
            "; or '",
            Remedy.program("ssh-keygen", "-R", "main"),
            "'; from a Mac '",
            Remedy.client("login"),
            "', then '",
            Remedy.sail("project apply", "gamma"),
            "'.");

    assertEquals(
        "Run 'sudo sail upgrade', then sudo sail fde update ada --scoped; or 'sudo ssh-keygen -R"
            + " main'; from a Mac 'sail login', then 'sudo sail project apply gamma'.",
        OperatorCommand.SUDO.render(remedy));
    assertEquals(
        "Run 'sail upgrade', then sail fde update ada --scoped; or 'ssh-keygen -R main'; from a"
            + " Mac 'sail login', then 'sail project apply gamma'.",
        OperatorCommand.PLAIN.render(remedy));
    assertEquals(
        "Run 'sail upgrade', then sail fde update ada --scoped; or 'ssh-keygen -R main'; from a"
            + " Mac 'sail login', then 'sudo sail project apply gamma'.",
        OperatorCommand.UNPRIVILEGED.render(remedy));
  }

  @Test
  void aRemediedFailureIsItsReasonAndItsRenderedRemedy() {
    var failure =
        Remedied.state(
            "FDE 'ada' sees every project.",
            Remedy.of("Scope it first with '", Remedy.sail("fde update", "ada", "--scoped"), "'."));

    assertEquals(
        "FDE 'ada' sees every project. Scope it first with 'sudo sail fde update ada --scoped'.",
        OperatorCommand.SUDO.render((Remedied) failure));
    assertEquals(
        "FDE 'ada' sees every project. Scope it first with 'sail fde update ada --scoped'.",
        failure.getMessage());
  }

  @Test
  void rootLoggedInDirectlyNeverTypesSudo() {
    var typesSudo =
        assertThrows(IllegalArgumentException.class, () -> new OperatorCommand(true, true));

    assertEquals("Root logged in directly never types sudo.", typesSudo.getMessage());
  }

  @Test
  void thisProcessReadsItsOwnAccountAndItsPrivilege() {
    assertEquals(
        OperatorCommand.of(SailPaths.isRoot(), System.getenv("SUDO_USER")), OperatorCommand.here());
  }
}
