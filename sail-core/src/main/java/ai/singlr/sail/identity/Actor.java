/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.identity;

import java.util.concurrent.Callable;

/**
 * Who is acting: the one identity every write names, whichever door it came through. {@code handle}
 * is the acting FDE's handle (the {@code assignee} a spec is matched against), a run's principal on
 * the in-container lanes, or a fixed name for main and this box's machinery; it is null for a
 * machine credential that owns no FDE. {@code role} carries the capabilities. {@code lane} names
 * the way the write is made. {@code owner} is set only on the {@link Lane#AGENT} and {@link
 * Lane#ROOM} lanes: the FDE the run's principal acts for, used for attribution and policy tiering,
 * never as a separate authorization system.
 *
 * <p>Every entry point binds the actor it acts as with {@link #run} or {@link #call}, at its edge,
 * and the {@code ChangeLog} reads it back through {@link #current()} for every revision it records.
 * A write with nothing bound fails: there is no default identity.
 *
 * <p>Fails closed: a machine token yields a {@code null} handle that matches no assignee, and an
 * unknown role resolves (via {@link Role#fromAttribute}) to the least-privileged {@link
 * Role#VIEWER}.
 */
public record Actor(String handle, Role role, Lane lane, String owner) {

  /** The author this box's own machinery writes as. */
  public static final String SYSTEM_HANDLE = "sail";

  /** The handle a node adopts main's decisions under. */
  public static final String MAIN_HANDLE = "main";

  private static final ScopedValue<Actor> CURRENT = ScopedValue.newInstance();

  /** The ways a write can be made. */
  public enum Lane {
    /** The box's operator, root on this box. */
    CLI,
    /** An HTTP token. */
    API,
    /** A run's write-capable principal over the local socket. */
    AGENT,
    /** A read-and-converse run's principal over the local socket. */
    ROOM,
    /** An FDE pushing through {@code _sync} to main. */
    SYNC,
    /** A node adopting main's revisions, which main has already decided. */
    MAIN,
    /** This box's own machinery. */
    SYSTEM
  }

  public Actor(String handle, Role role, Lane lane) {
    this(handle, role, lane, null);
  }

  /**
   * The local operator of a main or standalone box: the box's own FDE handle with admin authority
   * (the host operator already holds the admin token).
   */
  public static Actor cliOperator(String handle) {
    return new Actor(handle, Role.ADMIN, Lane.CLI);
  }

  /**
   * A run's agent principal authenticated over the local socket: write-capable on the spec, event,
   * and content surface, never admin, and refused outright on the dispatch and stop routes.
   */
  public static Actor agentPrincipal(String handle, String owner) {
    return new Actor(handle, Role.MEMBER, Lane.AGENT, owner);
  }

  /**
   * A {@code room}-role run's principal: read-and-converse only. The viewer role fails every
   * write-capability gate, and the room lane carries the single write the duty needs, posting to
   * its own spec's room. Enforced at the API boundary, not by the prompt.
   */
  public static Actor roomPrincipal(String handle, String owner) {
    return new Actor(handle, Role.VIEWER, Lane.ROOM, owner);
  }

  /**
   * The FDE behind one {@code _sync} session on main, with the role the gateway resolved. A {@code
   * null} handle (an unauthenticated or machine session) can never own a run or a spec.
   */
  public static Actor sync(String handle, Role role) {
    return new Actor(handle, role, Lane.SYNC);
  }

  /** Main, as a node adopts what it decided. */
  public static Actor main() {
    return new Actor(MAIN_HANDLE, Role.ADMIN, Lane.MAIN);
  }

  /** This box's own machinery: reactors, sweepers, reconcilers, migrations. */
  public static Actor system() {
    return new Actor(SYSTEM_HANDLE, Role.ADMIN, Lane.SYSTEM);
  }

  /**
   * The actor bound to the current operation.
   *
   * @throws IllegalStateException when nothing is bound: the entry point that started this work
   *     never said who is acting
   */
  public static Actor current() {
    if (!CURRENT.isBound()) {
      throw new IllegalStateException(
          "No actor is bound: every write must name who is acting. Bind one with Actor.run or"
              + " Actor.call at the entry point that started this work.");
    }
    return CURRENT.get();
  }

  /** Runs {@code work} as {@code actor}. */
  public static void run(Actor actor, Runnable work) {
    ScopedValue.where(CURRENT, actor).run(work);
  }

  /** Runs {@code work} as {@code actor} and returns its result. */
  public static <T, X extends Throwable> T call(Actor actor, ScopedValue.CallableOp<T, X> work)
      throws X {
    return ScopedValue.where(CURRENT, actor).call(work);
  }

  /**
   * {@code task} bound to the actor current at this call, so work handed to an executor still acts
   * as whoever asked for it: a {@link ScopedValue} does not cross into a plain executor's thread.
   */
  public static Runnable carrying(Runnable task) {
    var actor = current();
    return () -> run(actor, task);
  }

  /** As {@link #carrying(Runnable)} for a task that returns a value. */
  public static <T> Callable<T> carrying(Callable<T> task) {
    var actor = current();
    return () -> call(actor, task::call);
  }

  /** True when this actor's role grants full administrative authority. */
  public boolean isAdmin() {
    return role == Role.ADMIN;
  }

  /** True when this actor's role grants the {@code WRITE} capability. */
  public boolean canWrite() {
    return role.allows(Capability.WRITE);
  }

  /**
   * True when this actor was built from a run credential on an in-container lane — the agent lane
   * or its room-restricted variant. Both are refused wherever a run must not act on runs (the
   * dispatch and stop gates).
   */
  public boolean agentLane() {
    return lane == Lane.AGENT || lane == Lane.ROOM;
  }

  /** True when this actor is a room-role run's principal — read-and-converse only. */
  public boolean roomLane() {
    return lane == Lane.ROOM;
  }

  /**
   * Whether this actor is {@code identity} or acts on its behalf: an agent principal carries its
   * owning FDE, so a spec assigned to that FDE is the agent's to work exactly as if the FDE edited
   * it directly.
   */
  public boolean actsFor(String identity) {
    return identity != null && (identity.equals(handle) || identity.equals(owner));
  }

  /**
   * The box a synced revision came from: the pushing FDE on main, {@code main} on a node adopting
   * its revisions, and null for a write this box made itself.
   */
  public String peer() {
    return carriesSync() ? handle : null;
  }

  /**
   * The author a revision records when it offers {@code offered} as its own. Only a synced revision
   * carries an author of its own: main committing a push records the one it offers, and a node
   * adopting main's records the one main recorded, each falling back to the actor when the revision
   * names none. Every other write is authored by the actor.
   */
  public String authorOf(String offered) {
    return carriesSync() && offered != null ? offered : handle;
  }

  private boolean carriesSync() {
    return lane == Lane.SYNC || lane == Lane.MAIN;
  }
}
