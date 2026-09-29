/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Who may write a run. Every principal a run carries names the run itself ({@link
 * RunStore#namesRun}), and its spec and room never change. On this box's lanes a revision is an
 * admin's or its owners' ({@link #owners}), the rule stop and log access admit by; a run's own
 * principal may report its own session, even on a read-only lane. On {@link Actor.Lane#SYNC} a run
 * is its executing box's: its {@code node} is the pusher before and after, it acts for the pusher
 * or for no one, and a deleted run is never brought back.
 */
public final class RunAuthority implements WriteAuthority {

  private static final Set<String> SESSION_FIELDS =
      Set.of("session_id", "session_source", "last_activity_at");

  private final SpecStore specs;
  private final RunStore runs;
  private final Attribution attribution;

  public RunAuthority(Sqlite db) {
    this.specs = new SpecStore(db);
    this.runs = new RunStore(db);
    this.attribution = new Attribution(db);
  }

  /**
   * Who owns a run: the FDE it acts for, and the owner of the spec it works as {@code specOwner}
   * finds it — or, for a session that works no spec, the box that ran it. Each named once. So work
   * that moved to another owner can still be stopped by the FDE whose box runs it.
   */
  public static List<String> owners(
      String owner, String specId, String node, Function<String, Optional<String>> specOwner) {
    var owners = new LinkedHashSet<String>();
    owners.add(Objects.toString(owner, ""));
    owners.add(Strings.isBlank(specId) ? node : specOwner.apply(specId).orElse(""));
    owners.removeIf(Strings::isBlank);
    return List.copyOf(owners);
  }

  /** As {@link #owners(String, String, String, Function)} for {@code run}. */
  public static List<String> owners(
      RunStore.RunRow run, Function<String, Optional<String>> specOwner) {
    return owners(run.owner(), run.specId(), run.node(), specOwner);
  }

  /**
   * Why {@code actor} may not act on run {@code runId}, of spec {@code specId} (null for none),
   * owned by {@code owners}: it is neither an admin nor acting for an owner.
   */
  public static Optional<Refusal> access(
      Actor actor, String runId, String specId, List<String> owners) {
    if (actor.isAdmin() || owners.stream().anyMatch(actor::actsFor)) {
      return Optional.empty();
    }
    var named = owners.isEmpty() ? "its owner" : String.join(" or ", owners);
    return Refusal.of(
        Refusal.Kind.NOT_OWNER,
        describe(runId, specId, owners),
        "Only " + named + " or an admin may access this run.");
  }

  @Override
  public Optional<Refusal> decide(
      Actor actor, String id, Map<String, Object> held, Map<String, Object> next) {
    if (WriteAuthority.decided(actor) || (held == null && next == null)) {
      return Optional.empty();
    }
    var ownSession = reportsOwnSession(actor, held, next);
    if (!actor.canWrite() && !ownSession) {
      return Refusal.readOnly("change runs");
    }
    var foreign =
        principalsOf(next).filter(principal -> !RunStore.namesRun(principal, id)).findFirst();
    if (foreign.isPresent()) {
      return Refusal.of(
          Refusal.Kind.NOT_AUTHOR,
          "Run '" + id + "' names '" + foreign.get() + "' as its principal, which is not its own.",
          "A run's principals are the ones minted for it.");
    }
    var attributed = attribution.decide(actor, held, next, null, id);
    if (attributed.isPresent()) {
      return attributed;
    }
    for (var field : List.of("spec_id", "room_id")) {
      if (held != null
          && next != null
          && !Objects.equals(Snapshots.text(held, field), Snapshots.text(next, field))) {
        return Refusal.fixed("run", id, field);
      }
    }
    if (actor.lane() == Actor.Lane.SYNC) {
      return executed(actor.handle(), id, held, next);
    }
    if (held == null || ownSession) {
      return Optional.empty();
    }
    var specId = Snapshots.text(held, "spec_id");
    return access(
        actor,
        id,
        specId,
        owners(
            Snapshots.text(held, "owner"),
            specId,
            Snapshots.text(held, "node"),
            spec -> specs.findById(spec).map(SpecStore.SpecRow::owner)));
  }

  private Optional<Refusal> executed(
      String pusher, String id, Map<String, Object> held, Map<String, Object> next) {
    if (next != null && runs.latestRev(id) != null && runs.findById(id).isEmpty()) {
      return Refusal.of(
          Refusal.Kind.NOT_OWNER,
          "Run '" + id + "' was deleted, and a deleted run cannot be brought back.",
          null);
    }
    if (!executedBy(pusher, held) || !executedBy(pusher, next)) {
      return Refusal.of(
          Refusal.Kind.NOT_OWNER,
          "Only the node that executed run '"
              + id
              + "' may change it, and this is '"
              + pusher
              + "'.",
          null);
    }
    var owner = next == null ? null : Snapshots.text(next, "owner");
    if (Strings.isNotBlank(owner) && !owner.equals(pusher)) {
      return Refusal.of(
          Refusal.Kind.NOT_AUTHOR,
          "Run '"
              + id
              + "' acts for '"
              + owner
              + "', but a box's runs act for its own FDE, '"
              + pusher
              + "'.",
          null);
    }
    return Optional.empty();
  }

  private static boolean executedBy(String pusher, Map<String, Object> run) {
    if (run == null) {
      return true;
    }
    var node = Snapshots.text(run, "node");
    return Strings.isNotBlank(node) && node.equals(pusher);
  }

  private static boolean reportsOwnSession(
      Actor actor, Map<String, Object> held, Map<String, Object> next) {
    if (held == null
        || next == null
        || principalsOf(held).noneMatch(principal -> principal.equals(actor.handle()))) {
      return false;
    }
    return Stream.concat(held.keySet().stream(), next.keySet().stream())
        .filter(key -> !key.startsWith("_") && !SESSION_FIELDS.contains(key))
        .allMatch(key -> Objects.equals(held.get(key), next.get(key)));
  }

  private static Stream<String> principalsOf(Map<String, Object> run) {
    if (run == null) {
      return Stream.empty();
    }
    return Stream.concat(
            Stream.ofNullable(Snapshots.text(run, "principal")),
            Snapshots.stringList(run, "principals").stream())
        .filter(Strings::isNotBlank);
  }

  private static String describe(String runId, String specId, List<String> owners) {
    var owned =
        owners.isEmpty()
            ? null
            : owners.stream().map(owner -> "'" + owner + "'").collect(Collectors.joining(" and "));
    if (Strings.isBlank(specId)) {
      return "Run "
          + runId
          + " is an ad-hoc session"
          + (owned != null ? " launched by " + owned + ", not you." : ".");
    }
    return "Run "
        + runId
        + " belongs to spec '"
        + specId
        + "'"
        + (owned != null ? ", owned by " + owned + ", not you." : ", which has no owner.");
  }
}
