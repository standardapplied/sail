/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    assertEquals(new OperatorCommand(true), operator);
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
  void anAccountRunningItsOwnSailTypesPlainSail() throws Exception {
    assertEquals(
        OperatorCommand.PLAIN, OperatorCommand.of(false, "dev", this::homeOf, systemUnit()));
  }

  @Test
  void aSudoCallerWhoOwnsABoxInItsHomeTypesPlainSail() throws Exception {
    own("dev", "host.yaml", "storage_backend: dir\n");

    assertEquals(
        OperatorCommand.PLAIN, OperatorCommand.of(true, "dev", this::homeOf, systemUnit()));
  }

  @Test
  void aSudoCallerOnABoxWithNoSystemUnitTypesPlainSail() {
    assertEquals(
        OperatorCommand.PLAIN,
        OperatorCommand.of(true, "dev", this::homeOf, tempDir.resolve("no-unit")));
  }

  @Test
  void thisProcessReadsItsOwnAccount() {
    var expected =
        SailPaths.isRoot() && System.getenv("SUDO_USER") != null
            ? OperatorCommand.of(
                true, System.getenv("SUDO_USER"), SailPaths::accountHome, SailPaths.SYSTEM_UNIT)
            : OperatorCommand.PLAIN;

    assertEquals(expected, OperatorCommand.here());
  }
}
