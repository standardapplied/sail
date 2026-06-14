/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CleanupScriptsTest {

  @Test
  void containerCleanupScriptFiltersOnRestartPolicy() {
    var script = CleanupScripts.containerCleanupScript();

    assertTrue(script.contains("restart-policy="));
    assertTrue(script.contains("podman ps -a"));
    assertTrue(script.contains("podman rm -f"));
    assertTrue(script.contains("podman system prune -f"));
  }

  @Test
  void containerCleanupScriptUsesOneHourThreshold() {
    var script = CleanupScripts.containerCleanupScript();

    assertTrue(script.contains("MAX_AGE_SECONDS=3600"));
  }

  @Test
  void containerCleanupScriptIsValidBash() {
    var script = CleanupScripts.containerCleanupScript();

    assertTrue(script.startsWith("#!/bin/bash\n"));
  }

  @Test
  void agentCleanupScriptTargetsClaudeProcesses() {
    var script = CleanupScripts.agentCleanupScript();

    assertTrue(script.contains("claude"));
    assertTrue(script.contains("MAX_AGE_SECONDS=86400"));
  }

  @Test
  void agentCleanupScriptPreservesNewestProcess() {
    var script = CleanupScripts.agentCleanupScript();

    assertTrue(script.contains("newest_pid"));
    assertTrue(script.contains("Kept newest claude process"));
  }

  @Test
  void agentCleanupScriptRequiresConfirmation() {
    var script = CleanupScripts.agentCleanupScript();

    assertTrue(script.contains("read -p"));
    assertTrue(script.contains("[y/N]"));
  }

  @Test
  void cronLineInvokesCleanupScript() {
    var cron = CleanupScripts.cronLine();

    assertTrue(cron.contains(CleanupScripts.CONTAINER_CLEANUP_PATH));
    assertTrue(cron.startsWith("0 * * * *"));
    assertTrue(cron.endsWith("\n"));
  }

  @Test
  void legacyCronPatternMatchesOldPruneCommand() {
    var legacy = CleanupScripts.legacyCronPattern();
    var oldCron = "0 * * * * podman system prune -f --filter \"until=1h\" >/dev/null 2>&1";

    assertTrue(oldCron.contains(legacy));
  }

  @Test
  void containerCleanupHandlesStoppedContainers() {
    var script = CleanupScripts.containerCleanupScript();

    assertTrue(script.contains("podman ps -a"), "Must use -a flag to include stopped containers");
  }

  @Test
  void pathConstantsAreConsistent() {
    assertTrue(CleanupScripts.CONTAINER_CLEANUP_PATH.startsWith(CleanupScripts.SAIL_DIR));
    assertTrue(CleanupScripts.AGENT_CLEANUP_PATH.startsWith(CleanupScripts.WORKSPACE));
  }

  @Test
  void buildUpgradedCrontabRemovesLegacyLine() {
    var oldCron = "0 * * * * podman system prune -f --filter \"until=1h\" >/dev/null 2>&1\n";

    var result = CleanupScripts.buildUpgradedCrontab(oldCron);

    assertFalse(result.contains("podman system prune"));
    assertTrue(result.contains(CleanupScripts.CONTAINER_CLEANUP_PATH));
  }

  @Test
  void buildUpgradedCrontabPreservesOtherEntries() {
    var oldCron = "30 2 * * * /usr/local/bin/backup.sh\n0 * * * * podman system prune -f\n";

    var result = CleanupScripts.buildUpgradedCrontab(oldCron);

    assertTrue(result.contains("backup.sh"));
    assertFalse(result.contains("podman system prune"));
    assertTrue(result.contains(CleanupScripts.CONTAINER_CLEANUP_PATH));
  }

  @Test
  void buildUpgradedCrontabFromEmpty() {
    var result = CleanupScripts.buildUpgradedCrontab("");

    assertTrue(result.contains(CleanupScripts.CONTAINER_CLEANUP_PATH));
    assertTrue(result.startsWith("0 * * * *"));
  }
}
