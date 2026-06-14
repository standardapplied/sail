/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SnapshotManager;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Deletes old snapshots across one or all projects. Useful for reclaiming disk when auto-snapshot
 * creates snapshots on every dispatch.
 */
@Command(
    name = "prune",
    description = "Delete snapshots by age and/or retention count.",
    mixinStandardHelpOptions = true)
public final class SnapsPruneCommand implements Runnable {

  private static final Pattern AGE_PATTERN = Pattern.compile("(\\d+)([dhm])");

  @Parameters(
      index = "0",
      arity = "0..1",
      description = "Project name. Omit to prune all projects.")
  private String name;

  @Option(
      names = "--older-than",
      description = "Delete snapshots older than this age. Examples: 7d, 24h, 30d.")
  private String olderThan;

  @Option(
      names = "--keep",
      description =
          "Keep only the N most recently created snapshots per project. Combine with"
              + " --older-than to delete only snapshots that are both old AND beyond the keep"
              + " window.")
  private Integer keep;

  @Option(names = "--dry-run", description = "Print what would be deleted without deleting.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @picocli.CommandLine.Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  private void execute() throws Exception {
    if (olderThan == null && keep == null) {
      throw new IllegalArgumentException(
          "Specify at least one of --older-than (e.g. 7d) or --keep N.");
    }
    if (keep != null && keep < 0) {
      throw new IllegalArgumentException("--keep must be 0 or greater.");
    }
    Duration maxAge = olderThan != null ? parseAge(olderThan) : null;
    Instant cutoff = maxAge != null ? DateTimeUtils.now().minus(maxAge) : null;
    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);
    var snapMgr = new SnapshotManager(shell);
    var ansi = Ansi.AUTO;

    List<String> targets;
    if (name != null) {
      targets = List.of(name);
    } else {
      targets = mgr.listAll().stream().map(ContainerManager.ContainerInfo::name).toList();
    }

    if (!json) {
      Banner.printBranding(System.out, ansi);
      System.out.println();
      var scope = name != null ? name : "all projects";
      var mode = dryRun ? " (dry run)" : "";
      System.out.println(
          ansi.string("  @|bold Pruning snapshots|@ from " + scope + describeFilters() + mode));
      System.out.println();
    }

    var totalDeleted = 0;
    var totalKept = 0;
    var projectResults = new ArrayList<LinkedHashMap<String, Object>>();

    for (var project : targets) {
      List<SnapshotManager.SnapshotInfo> snapshots;
      try {
        snapshots = snapMgr.list(project);
      } catch (Exception e) {
        if (!json) {
          System.err.println(
              Banner.errorLine(
                  "Could not list snapshots for '"
                      + project
                      + "': "
                      + e.getMessage()
                      + ". Skipping.",
                  ansi));
        }
        continue;
      }
      if (snapshots.isEmpty()) {
        continue;
      }

      var keepers = keepersByRecency(snapshots, keep);

      var deleted = 0;
      var kept = 0;
      for (var snap : snapshots) {
        if (shouldDelete(snap, cutoff, keepers)) {
          if (!json) {
            var action = dryRun ? "would delete" : "deleting";
            System.out.println(ansi.string("  [" + action + "] " + project + "/" + snap.name()));
          }
          if (!dryRun) {
            snapMgr.delete(project, snap.name());
          }
          deleted++;
        } else {
          kept++;
        }
      }

      totalDeleted += deleted;
      totalKept += kept;

      if (json && deleted > 0) {
        var result = new LinkedHashMap<String, Object>();
        result.put("project", project);
        result.put("deleted", deleted);
        result.put("kept", kept);
        projectResults.add(result);
      }
    }

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      if (olderThan != null) {
        map.put("older_than", olderThan);
      }
      if (keep != null) {
        map.put("keep", keep);
      }
      map.put("dry_run", dryRun);
      map.put("total_deleted", totalDeleted);
      map.put("total_kept", totalKept);
      map.put("projects", projectResults);
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    System.out.println();
    var verb = dryRun ? "Would delete" : "Deleted";
    System.out.println(
        ansi.string(
            "  @|bold,green ✓|@ " + verb + " " + totalDeleted + " snapshot(s), kept " + totalKept));
  }

  private String describeFilters() {
    var parts = new ArrayList<String>();
    if (olderThan != null) {
      parts.add("older than " + olderThan);
    }
    if (keep != null) {
      parts.add("keeping " + keep + " most recent");
    }
    return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
  }

  private static boolean shouldDelete(
      SnapshotManager.SnapshotInfo snap, Instant cutoff, Set<String> keepers) {
    if (cutoff != null) {
      var t = parseSnapshotTime(snap.createdAt());
      if (t == null || !t.isBefore(cutoff)) {
        return false;
      }
    }
    if (keepers != null && keepers.contains(snap.name())) {
      return false;
    }
    return cutoff != null || keepers != null;
  }

  private static Set<String> keepersByRecency(
      List<SnapshotManager.SnapshotInfo> snapshots, Integer keep) {
    if (keep == null) {
      return null;
    }
    return snapshots.stream()
        .sorted(Comparator.comparing(SnapsPruneCommand::sortKey).reversed())
        .limit(keep)
        .map(SnapshotManager.SnapshotInfo::name)
        .collect(HashSet::new, HashSet::add, HashSet::addAll);
  }

  private static Instant sortKey(SnapshotManager.SnapshotInfo s) {
    var t = parseSnapshotTime(s.createdAt());
    return t != null ? t : Instant.MIN;
  }

  static Duration parseAge(String value) {
    var matcher = AGE_PATTERN.matcher(value.strip());
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          "Invalid age format: '"
              + value
              + "'. Use a number followed by d (days), h (hours), or m (minutes)."
              + " Examples: 7d, 24h, 30d");
    }
    var amount = Long.parseLong(matcher.group(1));
    return switch (matcher.group(2)) {
      case "d" -> Duration.ofDays(amount);
      case "h" -> Duration.ofHours(amount);
      case "m" -> Duration.ofMinutes(amount);
      default -> throw new IllegalArgumentException("Unknown unit: " + matcher.group(2));
    };
  }

  static Instant parseSnapshotTime(String iso) {
    if (Strings.isBlank(iso)) {
      return null;
    }
    try {
      return OffsetDateTime.parse(iso).toInstant();
    } catch (DateTimeParseException ignored) {
      try {
        return Instant.parse(iso);
      } catch (DateTimeParseException ignored2) {
        return null;
      }
    }
  }
}
