/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ContainerManager.ResourceLimits;
import ai.singlr.sail.store.ProjectStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Brings a provisioned container's CPU, memory, and disk into line with its project definition,
 * applied live — no recreate, no restart — so a resource change that synced from another box
 * expands or contracts the local container in place. Idempotent: a container already at the desired
 * size is left untouched, and a container that does not exist yet is skipped (provisioning will
 * size it).
 *
 * <p>CPU and memory are compared against the container's live limits. Disk is compared against its
 * current root quota; a shrink the storage backend refuses (ZFS below used space; advisory on the
 * {@code dir} backend) is reported and skipped, never fatal — the rest of the sync stands.
 */
public final class ProjectResourceReconciler {

  private final ContainerManager manager;

  public ProjectResourceReconciler(ContainerManager manager) {
    this.manager = manager;
  }

  /** What one reconcile did: whether the container was present, and which dimensions it moved. */
  public record Outcome(
      boolean present, boolean limitsApplied, boolean diskApplied, String diskSkipped) {

    public boolean changed() {
      return limitsApplied || diskApplied;
    }

    static Outcome absent() {
      return new Outcome(false, false, false, null);
    }
  }

  /** What reconciling a whole catalog moved: containers resized, and any disk changes skipped. */
  public record FleetOutcome(List<String> resized, List<String> diskSkipped) {}

  /**
   * Reconciles every catalogued project that carries resources, returning the containers it resized
   * and the disk changes it had to skip. Projects without a resources block or a provisioned
   * container are passed over. If incus becomes unreachable mid-way (a binary error rather than a
   * missing container), it stops and returns what it managed — the sync round is never failed by
   * resource reconciliation.
   */
  public FleetOutcome reconcileCatalog(List<ProjectStore.ProjectRow> projects) {
    var resized = new ArrayList<String>();
    var diskSkipped = new ArrayList<String>();
    for (var project : projects) {
      var desired = resourcesOf(project.definition());
      if (desired == null) {
        continue;
      }
      try {
        var outcome = reconcile(project.name(), desired);
        if (outcome.changed()) {
          resized.add(project.name());
        }
        if (outcome.diskSkipped() != null) {
          diskSkipped.add(project.name() + " — " + outcome.diskSkipped());
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new FleetOutcome(resized, diskSkipped);
      } catch (IOException | TimeoutException e) {
        return new FleetOutcome(resized, diskSkipped);
      }
    }
    return new FleetOutcome(resized, diskSkipped);
  }

  private static SailYaml.Resources resourcesOf(String definition) {
    try {
      return SailYaml.fromMap(YamlUtil.parseMap(definition)).resources();
    } catch (RuntimeException e) {
      return null;
    }
  }

  public Outcome reconcile(String name, SailYaml.Resources desired)
      throws IOException, InterruptedException, TimeoutException {
    if (desired == null) {
      return Outcome.absent();
    }
    var info = manager.queryInfo(name);
    if (!isProvisioned(info.state())) {
      return Outcome.absent();
    }

    var limitsApplied = false;
    if (limitsDiffer(info.limits(), desired)) {
      manager.setResourceLimits(
          name, new ResourceLimits(String.valueOf(desired.cpu()), desired.memory()));
      limitsApplied = true;
    }

    var diskApplied = false;
    String diskSkipped = null;
    var currentDisk = manager.queryDiskQuota(name).orElse(null);
    if (currentDisk != null && !sameSize(currentDisk, desired.disk())) {
      try {
        manager.setDiskQuota(name, desired.disk());
        diskApplied = true;
      } catch (IOException e) {
        diskSkipped = e.getMessage();
      }
    }
    return new Outcome(true, limitsApplied, diskApplied, diskSkipped);
  }

  private static boolean isProvisioned(ContainerState state) {
    return state instanceof ContainerState.Running || state instanceof ContainerState.Stopped;
  }

  private static boolean limitsDiffer(ResourceLimits limits, SailYaml.Resources desired) {
    if (limits == null) {
      return true;
    }
    return !Objects.equals(String.valueOf(desired.cpu()), limits.cpu())
        || !sameSize(limits.memory(), desired.memory());
  }

  private static boolean sameSize(String a, String b) {
    return normalize(a).equals(normalize(b));
  }

  private static String normalize(String value) {
    return value == null ? "" : value.strip().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
  }
}
