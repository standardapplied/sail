/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class AutoUpgraderTest {

  private static final UnaryOperator<String> NO_ENV = key -> null;

  @Test
  void skipsDevBuilds() {
    assertTrue(AutoUpgrader.shouldSkip("dev", NO_ENV, true, new String[] {"up"}));
  }

  @Test
  void neverUpgradesInsideAnInternalLaneOrWithoutAConsole() {
    for (var lane : new String[] {"_sync", "_gateway", "_pty-host", "_pty-child"}) {
      assertTrue(
          AutoUpgrader.shouldSkip("0.43.1", NO_ENV, true, new String[] {lane, "--fde", "mady"}),
          lane + " runs unattended under a forced command or a service");
    }
    assertTrue(
        AutoUpgrader.shouldSkip("0.43.1", NO_ENV, false, new String[] {"sync"}),
        "no console means nobody can answer the sudo prompt");
    assertFalse(AutoUpgrader.shouldSkip("0.43.1", NO_ENV, true, new String[] {"sync"}));
  }

  @Test
  void skipsWhenUpdateCheckIsDisabled() {
    assertTrue(
        AutoUpgrader.shouldSkip(
            "0.13.0", env("SAIL_NO_UPDATE_CHECK", "1"), true, new String[] {"up"}));
  }

  @Test
  void skipsWhenAlreadyReExecedByAnEarlierUpgrade() {
    assertTrue(
        AutoUpgrader.shouldSkip(
            "0.13.0", env("SAIL_AUTO_UPGRADED", "1"), true, new String[] {"up"}));
  }

  @Test
  void skipsTheSelfHandlingAndInformationalSubcommands() {
    for (var arg : new String[] {"upgrade", "--version", "-V", "--help", "-h"}) {
      assertTrue(AutoUpgrader.shouldSkip("0.13.0", NO_ENV, true, new String[] {arg}), arg);
    }
  }

  @Test
  void doesNotSkipANormalReleaseCommand() {
    assertFalse(AutoUpgrader.shouldSkip("0.13.0", NO_ENV, true, new String[] {"up", "kubera"}));
  }

  @Test
  void upgradesOnlyWhenTheCurrentVersionIsStrictlyOlder() {
    assertTrue(AutoUpgrader.shouldUpgrade("0.13.0", "0.13.1"));
    assertFalse(AutoUpgrader.shouldUpgrade("0.13.1", "0.13.1"));
    assertFalse(AutoUpgrader.shouldUpgrade("0.13.2", "0.13.1"));
  }

  @Test
  void acceptsABinaryWithAMatchingChecksumAndTheExpectedExecutableFormat() {
    var binary = new byte[] {0x7f, 'E', 'L', 'F', 4, 5, 6, 7};
    assertTrue(AutoUpgrader.isAcceptable(binary, sha256(binary), "Linux"));
  }

  @Test
  void rejectsATamperedBinaryWhoseChecksumDoesNotMatch() {
    var binary = new byte[] {0x7f, 'E', 'L', 'F', 4, 5, 6, 7};
    assertFalse(AutoUpgrader.isAcceptable(binary, "00ff", "Linux"));
  }

  @Test
  void rejectsABinaryThatIsNotInTheExpectedExecutableFormat() {
    var notElf = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
    assertFalse(AutoUpgrader.isAcceptable(notElf, sha256(notElf), "Linux"));
  }

  private static UnaryOperator<String> env(String key, String value) {
    return Map.of(key, value)::get;
  }

  private static String sha256(byte[] binary) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
