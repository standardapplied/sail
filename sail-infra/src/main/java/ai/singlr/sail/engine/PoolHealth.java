/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * A zpool's health as {@code zpool status -P} says it now: the pool's state (ONLINE, DEGRADED,
 * FAULTED, SUSPENDED, ...; {@value #NOT_IMPORTED} when zpool has no such pool, and {@value
 * #UNANSWERED} and why when zpool did not answer within {@link #BOUND} or failed, so the health
 * cannot be told) and each of its devices that is not online, by path, with its state. A device
 * absent when the pool was imported is listed by its GUID and named by the path zpool says it
 * {@code was}. Sail reads it and repairs nothing: a device is replaced with {@code zpool replace},
 * by the operator.
 *
 * @param state the pool's state, as zpool prints it
 * @param unhealthy each device that is not online, by path, and its state
 */
public record PoolHealth(String state, Map<String, String> unhealthy) {

  /** The state of a pool zpool has not imported. */
  public static final String NOT_IMPORTED = "not imported";

  /** The start of the state of a pool zpool did not answer for. */
  public static final String UNANSWERED = "did not answer";

  /** How long zpool is given to answer: a suspended pool can hang it. */
  static final Duration BOUND = Duration.ofSeconds(10);

  private static final String ONLINE = "ONLINE";
  private static final String DEGRADED = "DEGRADED";
  private static final Set<String> SOUND = Set.of(ONLINE, "AVAIL", "INUSE");
  private static final Pattern LEAF = Pattern.compile("/.*|[0-9]+");
  private static final String STATE = "state: ";
  private static final int MISSING = 127;
  private static final List<String> MISSING_WORDS =
      List.of("command not found", "No such file or directory");

  public PoolHealth {
    unhealthy = Collections.unmodifiableMap(new LinkedHashMap<>(unhealthy));
  }

  /** The command that says it, in the C locale so its words are the ones read. */
  static List<String> status(String zpool) {
    return List.of("env", "LC_ALL=C", "zpool", "status", "-P", zpool);
  }

  /**
   * What zpool says of {@code zpool} now, given {@link #BOUND} to say it: none where zpool cannot
   * be run, as where ZFS is not installed; {@value #UNANSWERED} and why where it ran and did not
   * answer in time, failed, or said no state.
   */
  public static Optional<PoolHealth> of(ShellExec shell, String zpool) throws InterruptedException {
    ShellExec.Result said;
    try {
      said = shell.exec(status(zpool), null, BOUND);
    } catch (TimeoutException e) {
      return Optional.of(unanswered("within " + BOUND.toSeconds() + " s"));
    } catch (IOException e) {
      return Optional.empty();
    }
    if (!said.ok() && said.stderr().contains("no such pool")) {
      return Optional.of(new PoolHealth(NOT_IMPORTED, Map.of()));
    }
    if (!said.ok()
        && (said.exitCode() == MISSING
            || MISSING_WORDS.stream().anyMatch(said.stderr()::contains))) {
      return Optional.empty();
    }
    if (!said.ok()) {
      return Optional.of(unanswered("(" + said.reason() + ")"));
    }
    return Optional.of(parsed(said.stdout()).orElseGet(() -> unanswered("with a state")));
  }

  private static PoolHealth unanswered(String why) {
    return new PoolHealth(UNANSWERED + " " + why, Map.of());
  }

  /** Whether zpool answered with the pool's health. */
  public boolean answered() {
    return !state.startsWith(UNANSWERED);
  }

  /**
   * The health {@code stdout}, a {@code zpool status -P} of one pool, says; none if it says none.
   * The config table's first row is the pool itself; each device row after it, to the blank line
   * that ends the table, is a leaf when it names a path or a GUID.
   */
  static Optional<PoolHealth> parsed(String stdout) {
    var lines = stdout.lines().map(String::strip).toList();
    var state =
        lines.stream()
            .filter(line -> line.startsWith(STATE))
            .findFirst()
            .map(line -> line.substring(STATE.length()).strip());
    var header =
        IntStream.range(0, lines.size())
            .filter(i -> lines.get(i).startsWith("NAME ") && lines.get(i).contains(" STATE "))
            .findFirst();
    var unhealthy = new LinkedHashMap<String, String>();
    if (header.isPresent()) {
      lines.stream()
          .skip(header.getAsInt() + 2L)
          .takeWhile(line -> !line.isEmpty())
          .map(line -> line.split("\\s+"))
          .filter(fields -> fields.length > 1 && LEAF.matcher(fields[0]).matches())
          .filter(fields -> !SOUND.contains(fields[1]))
          .forEach(fields -> unhealthy.put(device(fields), fields[1]));
    }
    return state.map(found -> new PoolHealth(found, unhealthy));
  }

  /** A leaf's device: its path, or for a device absent at import the path zpool says it was. */
  private static String device(String[] fields) {
    for (var i = 2; i < fields.length - 1; i++) {
      if (fields[i].equals("was")) {
        return fields[i + 1];
      }
    }
    return fields[0];
  }

  /** Whether workspaces and templates may be changed on the pool: online, or degraded. */
  public boolean usable() {
    return ONLINE.equals(state) || DEGRADED.equals(state);
  }

  /** Whether the pool runs without one of its devices. */
  public boolean degraded() {
    return DEGRADED.equals(state);
  }

  /** The health as one line: the state, and each device not online with its state. */
  public String said() {
    if (unhealthy.isEmpty()) {
      return state;
    }
    var devices = new ArrayList<String>();
    unhealthy.forEach((device, its) -> devices.add(device + " is " + its));
    return state + " (" + String.join(", ", devices) + ")";
  }

  /**
   * What the operator is told of a degraded pool {@code zpool}: what is missing, that the pool goes
   * on with the device left, and the {@code zpool replace} that puts a new one in its place, which
   * Sail never runs.
   */
  public String degradedNotice(String zpool) {
    return "Pool "
        + zpool
        + " is "
        + said()
        + ", as zpool status says: it runs on the device left, with no redundancy until the"
        + " missing one is replaced. Sail replaces nothing: put a new device in its place with sudo"
        + " zpool replace "
        + zpool
        + " <missing device> <new device>.";
  }
}
