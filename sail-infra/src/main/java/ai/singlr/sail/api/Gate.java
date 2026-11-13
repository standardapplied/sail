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
 * How a route is decided (A3): every route an HTTP or socket router's table declares names one
 * gate, and a router that authenticates applies it ({@link #admit}) as the caller before the
 * route's handler runs; the pages, which authenticate no one, declare only unauthenticated routes.
 * A route either declares the action its operation decides on the row it resolves; or reads in
 * scope, every row through {@code ReadFilter}; or makes a write the journal decides by its type's
 * rule; or wakes the change feed's streams, which any caller may; or is decided by the terminal
 * host, as it decides an open or an attach; or serves nothing, answering a request as the API
 * always has; or is unauthenticated, for a reason, before any credential is asked. What no row is
 * needed to decide, the router decides itself: operating the box, and managing people.
 */
sealed interface Gate {

  /** A read of rows in the caller's scope. */
  Gate READS_IN_SCOPE = new ReadsInScope();

  /** A write the journal decides as it records it. */
  Gate JOURNAL = new Journal();

  /** Managing the org's people, tokens and pairings. */
  Gate PEOPLE = new ManagesPeople();

  /** A terminal ticket, which the terminal host decides as it decides that open or attach. */
  Gate TERMINAL = new Terminal();

  /**
   * A wake of the box's change-feed streams, which carries nothing and touches no row: any caller
   * who authenticates may send it, as every page it wakes is read as its own reader.
   */
  Gate WAKES = new Wakes();

  /** A request no operation serves, answered with the refusal the API has always given it. */
  Gate UNSERVED = new Unserved();

  /**
   * A run's own record, which its credential names: the run reaches only its own, so nothing is
   * left to decide.
   */
  Gate RUNS_OWN = new RunsOwn();

  /**
   * Operating the box whose FDE {@code boxFde} names when asked: its sync health, bus stats, sync
   * rounds and main.
   */
  static Gate box(Supplier<String> boxFde) {
    return new OperatesBox(boxFde);
  }

  /** A route whose operation decides {@code action} on the resource it resolves. */
  static Gate decides(Action action) {
    return new Decides(action);
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
   * The operation decides {@code action} on the resource it resolves, so the router admits every
   * caller and the operation refuses.
   */
  record Decides(Action action) implements Gate {
    public Decides {
      Objects.requireNonNull(action, "action");
    }
  }

  /** The route reads only what the caller's scope admits. */
  record ReadsInScope() implements Gate {}

  /** The route acts on the run its credential names, and on no other. */
  record RunsOwn() implements Gate {}

  /** The terminal host decides the route's open or attach, as it decides one presented to it. */
  record Terminal() implements Gate {}

  /** The journal decides the route's write by its type's rule. */
  record Journal() implements Gate {}

  /** The route wakes the change feed's streams, and reads and writes nothing. */
  record Wakes() implements Gate {}

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
