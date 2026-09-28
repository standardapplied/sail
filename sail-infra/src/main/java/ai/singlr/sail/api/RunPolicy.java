/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Ownership;
import ai.singlr.sail.store.RunStore;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resource-scoped authorization for the run aggregate: reading a run's log (buffered tail or SSE
 * stream) and stopping it. Pure and I/O-free so REST and SSE evaluate the identical verdict for the
 * identical caller from one place. A run's owners are the FDE it acts for, stamped when it was
 * launched, and the owner of the spec it works now — its assignee, or its creator while it is
 * unassigned — or, for an ad-hoc session that works no spec, the box that launched it. So work that
 * moves to another owner can still be stopped by the FDE whose box runs it. An admin may reach any
 * run.
 *
 * <p>Fails closed: a run with no owner (none stamped, its spec gone or ownerless, an ad-hoc run
 * from a handle-less box) is an admin's alone, and a machine token's null handle owns nothing. An
 * agent principal is its own handle here, never the FDE it acts for.
 */
public final class RunPolicy {

  private RunPolicy() {}

  /**
   * Who owns {@code run}: the FDE it acts for, and the owner of the spec it works as {@code
   * specOwner} finds it — or, for an ad-hoc session, the box that launched it. Each named once.
   */
  public static List<String> owners(
      RunStore.RunRow run, Function<String, Optional<String>> specOwner) {
    var owners = new LinkedHashSet<String>();
    owners.add(Objects.toString(run.owner(), ""));
    owners.add(
        Strings.isBlank(run.specId()) ? run.node() : specOwner.apply(run.specId()).orElse(""));
    owners.removeIf(Strings::isBlank);
    return List.copyOf(owners);
  }

  /**
   * Decides whether the actor may access run {@code runId}: {@code specId} names its spec (null for
   * an ad-hoc session) and {@code owners} who owns it ({@link #owners}). The provenance guard (does
   * this run belong to this box) is a separate, earlier check; this governs identity.
   */
  public static AccessDecision access(String runId, String specId, List<String> owners) {
    var actor = Actor.current();
    if (actor.isAdmin()
        || owners.stream().anyMatch(owner -> Ownership.owns(actor.handle(), owner))) {
      return AccessDecision.allowed();
    }
    var named = owners.isEmpty() ? "its owner" : String.join(" or ", owners);
    return AccessDecision.refused(
        ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
        describeRun(runId, specId, owners),
        "Only " + named + " or an admin may access this run.");
  }

  private static String describeRun(String runId, String specId, List<String> owners) {
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
