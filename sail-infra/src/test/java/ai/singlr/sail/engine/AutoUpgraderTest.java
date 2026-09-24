/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void neverUpgradesDuringARehearsalAgainstACopy() {
    assertTrue(
        AutoUpgrader.shouldSkip(
            "0.13.0", env("SAIL_DATA_DIR", "/root/rehearsal"), true, new String[] {"migrate"}));
    assertFalse(
        AutoUpgrader.shouldSkip(
            "0.13.0", env("SAIL_DATA_DIR", "/var/lib/sail"), true, new String[] {"migrate"}));
  }

  private static UnaryOperator<String> env(String key, String value) {
    return Map.of(key, value)::get;
  }
}
