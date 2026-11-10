/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Decision;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.identity.Actor;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * How a route is decided (A3): every route an HTTP or socket router serves declares one gate, and
 * the router applies it ({@link #admit}) as the caller before the route's handler runs. A route
 * either decides an action through its operation's one seam, which holds the row the decision
 * reads; or reads in scope, every row through {@code ReadFilter}; or makes a write the journal
 * decides by its type's rule; or serves nothing, answering a request as the API always has; or is
 * unauthenticated, for a reason, before any credential is asked. What no row is needed to decide,
 * the router decides itself: operating the box, and managing people.
 */
sealed interface Gate {

  /** A read of rows in the caller's scope. */
  Gate READS_IN_SCOPE = new ReadsInScope();

  /** A write the journal decides as it records it. */
  Gate JOURNAL = new Journal();

  /** Managing the org's people, tokens and pairings. */
  Gate PEOPLE = new ManagesPeople();

  /** A request no operation serves, answered with the refusal the API has always given it. */
  Gate UNSERVED = new Unserved();

  /**
   * Operating the box whose FDE {@code boxFde} names when asked: its sync health, bus stats, sync
   * rounds and main.
   */
  static Gate box(Supplier<String> boxFde) {
    return new OperatesBox(boxFde);
  }

  /** A route whose operation decides {@code action} through {@code seam}, its one seam. */
  static Gate decides(Action action, Class<?> seam) {
    return new Decides(action, seam);
  }

  /** A route served before any credential is asked, for {@code reason}. */
  static Gate unauthenticated(String reason) {
    return new Unauthenticated(reason);
  }

  /**
   * Throws the refusal a router answers {@code actor} with before the route's handler runs; nothing
   * for a gate the operation, the read filter or the journal applies.
   */
  default void admit(Actor actor) {}

  /**
   * The operation decides {@code action} on the resource it resolves, through {@code seam}, so the
   * router admits every caller and the seam refuses.
   */
  record Decides(Action action, Class<?> seam) implements Gate {
    public Decides {
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(seam, "seam");
    }
  }

  /** The route reads only what the caller's scope admits. */
  record ReadsInScope() implements Gate {}

  /** The journal decides the route's write by its type's rule. */
  record Journal() implements Gate {}

  /** The route reaches no operation, after reading at most the project the path names. */
  record Unserved() implements Gate {}

  /** Operate the box: the box's own person, or an admin. */
  record OperatesBox(Supplier<String> boxFde) implements Gate {
    public OperatesBox {
      Objects.requireNonNull(boxFde, "boxFde");
    }

    @Override
    public void admit(Actor actor) {
      enforce(Access.decide(actor, Action.OPERATE_BOX, Resources.box(boxFde.get())));
    }
  }

  /** Manage people: an admin. */
  record ManagesPeople() implements Gate {
    @Override
    public void admit(Actor actor) {
      enforce(Access.decide(actor, Action.MANAGE_PEOPLE, Resources.people()));
    }
  }

  /** Served with no credential, for {@code reason}. */
  record Unauthenticated(String reason) implements Gate {
    public Unauthenticated {
      if (Strings.isBlank(reason)) {
        throw new IllegalArgumentException("An unauthenticated route says why it needs no caller.");
      }
    }
  }

  /** Throws {@code decision}'s refusal; a box and the org's people are never not found. */
  private static void enforce(Decision decision) {
    if (decision instanceof Decision.Refused(var refusal)) {
      throw Refusals.exception(refusal);
    }
  }
}
