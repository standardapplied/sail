/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PrerequisiteCheckerTest {

  @Test
  void allPresentWhenWhichSucceeds() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("which");
    var checker = new PrerequisiteChecker(shell);

    var result = checker.check();

    assertEquals(PrerequisiteChecker.REQUIRED.size(), result.present().size());
    assertTrue(result.missing().isEmpty());
  }

  @Test
  void detectsMissingCommands() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("which bash")
            .onOk("which apt-get")
            .onFail("which lsblk", "")
            .onFail("which curl", "")
            .onFail("which gpg", "");
    var checker = new PrerequisiteChecker(shell);

    var result = checker.check();

    assertEquals(2, result.present().size());
    assertEquals(3, result.missing().size());
    var missingNames =
        result.missing().stream().map(PrerequisiteChecker.Prerequisite::command).toList();
    assertTrue(missingNames.contains("lsblk"));
    assertTrue(missingNames.contains("curl"));
    assertTrue(missingNames.contains("gpg"));
  }

  @Test
  void installMissingRunsAptGetInstall() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("apt-get");
    var checker = new PrerequisiteChecker(shell);

    var missing =
        List.of(
            new PrerequisiteChecker.Prerequisite("curl", "curl", "Downloading signing keys"),
            new PrerequisiteChecker.Prerequisite("gpg", "gnupg", "Verifying signing keys"));

    checker.installMissing(missing);

    var cmds = shell.invocations();
    assertEquals(1, cmds.size());
    assertTrue(cmds.getFirst().contains("apt-get install -y -qq curl gnupg"));
  }

  @Test
  void installMissingSkipsPrerequisitesWithoutPackage() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("apt-get");
    var checker = new PrerequisiteChecker(shell);

    var missing = List.of(new PrerequisiteChecker.Prerequisite("bash", null, "Shell execution"));

    checker.installMissing(missing);

    assertTrue(shell.invocations().isEmpty());
  }

  @Test
  void installMissingFiltersNullPackages() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("apt-get");
    var checker = new PrerequisiteChecker(shell);

    var missing =
        List.of(
            new PrerequisiteChecker.Prerequisite("bash", null, "Shell execution"),
            new PrerequisiteChecker.Prerequisite("curl", "curl", "Downloading signing keys"));

    checker.installMissing(missing);

    var cmds = shell.invocations();
    assertEquals(1, cmds.size());
    assertTrue(cmds.getFirst().contains("apt-get install -y -qq curl"));
    assertFalse(cmds.getFirst().contains("bash"));
  }

  @Test
  void checkRecordsAllWhichInvocations() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("which");
    var checker = new PrerequisiteChecker(shell);

    checker.check();

    var cmds = shell.invocations();
    assertEquals(PrerequisiteChecker.REQUIRED.size(), cmds.size());
    for (var cmd : cmds) {
      assertTrue(cmd.startsWith("which "));
    }
  }

  @Test
  void requiredListContainsExpectedCommands() {
    var names =
        PrerequisiteChecker.REQUIRED.stream()
            .map(PrerequisiteChecker.Prerequisite::command)
            .toList();
    assertEquals(List.of("bash", "lsblk", "apt-get", "curl", "gpg"), names);
  }

  @Test
  void checkResultsAreImmutable() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("which");
    var checker = new PrerequisiteChecker(shell);

    var result = checker.check();

    assertThrows(UnsupportedOperationException.class, () -> result.present().add(null));
    assertThrows(UnsupportedOperationException.class, () -> result.missing().add(null));
  }
}
