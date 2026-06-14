/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SshIdentityProvisionerTest {

  private List<SshIdentityProvisioner.Step> plan() {
    return SshIdentityProvisioner.plan(Path.of("/root/.sail"), "/var/lib/sail");
  }

  private String runCommand(List<SshIdentityProvisioner.Step> plan, int index) {
    return String.join(" ", ((SshIdentityProvisioner.Run) plan.get(index)).command());
  }

  @Test
  void firstStepCreatesSailUserIdempotently() {
    var first = runCommand(plan(), 0);
    assertTrue(first.contains("id -u sail"), first);
    assertTrue(first.contains("|| useradd"), first);
    assertTrue(first.contains("--system"), first);
    assertTrue(first.contains("/home/sail"), first);
  }

  @Test
  void dataDirectoryIsSetgidAndGroupShared() {
    var step =
        plan().stream()
            .filter(SshIdentityProvisioner.Run.class::isInstance)
            .map(s -> String.join(" ", ((SshIdentityProvisioner.Run) s).command()))
            .filter(c -> c.contains("/var/lib/sail") && c.contains("install -d"))
            .findFirst()
            .orElseThrow();
    assertTrue(step.contains("-m 2770"), step);
    assertTrue(step.contains("-g sail"), step);
  }

  @Test
  void databaseMoveIsGuardedAgainstOverwriteAndMissingSource() {
    var move =
        plan().stream()
            .filter(SshIdentityProvisioner.Run.class::isInstance)
            .map(s -> String.join(" ", ((SshIdentityProvisioner.Run) s).command()))
            .filter(c -> c.contains("mv "))
            .findFirst()
            .orElseThrow();
    assertTrue(move.contains("/root/.sail/$f"), move);
    assertTrue(move.contains("/var/lib/sail/$f"), move);
    assertTrue(move.contains("sail.db-wal"), move);
    assertTrue(move.contains("[ -e") && move.contains("! -e"), move);
  }

  @Test
  void hostYamlMoveIsGuardedAndGroupReadable() {
    var commands =
        plan().stream()
            .filter(SshIdentityProvisioner.Run.class::isInstance)
            .map(s -> String.join(" ", ((SshIdentityProvisioner.Run) s).command()))
            .toList();
    var move =
        commands.stream().filter(c -> c.contains("host.yaml\" \"")).findFirst().orElseThrow();
    assertTrue(move.contains("[ -e") && move.contains("! -e"), move);
    assertTrue(move.contains("/root/.sail/host.yaml"), move);
    assertTrue(move.contains("/var/lib/sail/host.yaml"), move);
    var access =
        commands.stream()
            .filter(c -> c.contains("chmod 640") && c.contains("host.yaml"))
            .findFirst()
            .orElseThrow();
    assertTrue(access.contains("chgrp sail"), access);
  }

  @Test
  void authorizedKeysIsNeverTruncatedWhenPresent() {
    var step =
        plan().stream()
            .filter(SshIdentityProvisioner.Run.class::isInstance)
            .map(s -> String.join(" ", ((SshIdentityProvisioner.Run) s).command()))
            .filter(c -> c.contains("authorized_keys"))
            .findFirst()
            .orElseThrow();
    assertTrue(step.contains("test -f"), step);
    assertTrue(step.contains("|| install"), step);
  }

  @Test
  void dropInPointsServiceAtSharedDataDirWithGroupUmask() {
    var write =
        plan().stream()
            .filter(SshIdentityProvisioner.WriteFile.class::isInstance)
            .map(SshIdentityProvisioner.WriteFile.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals(Path.of(SshIdentityProvisioner.DROP_IN), write.path());
    assertTrue(
        write.content().contains("Environment=SAIL_DATA_DIR=/var/lib/sail"), write.content());
    assertTrue(write.content().contains("UMask=0007"), write.content());
  }

  @Test
  void planStopsBeforeMovingAndStartsAfterReload() {
    var descriptions = plan().stream().map(SshIdentityProvisioner.Step::description).toList();
    var stop = indexOfContaining(descriptions, "Stop sail-api");
    var move = indexOfContaining(descriptions, "Move the control-plane database");
    var reload = indexOfContaining(descriptions, "Reload systemd");
    var start = indexOfContaining(descriptions, "Start sail-api");
    assertTrue(stop < move, "must stop the service before moving the database");
    assertTrue(move < reload && reload < start, "must reload before starting");
  }

  private static int indexOfContaining(List<String> items, String needle) {
    for (var i = 0; i < items.size(); i++) {
      if (items.get(i).contains(needle)) {
        return i;
      }
    }
    return -1;
  }
}
