/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.identity;

import ai.singlr.sail.common.Strings;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Who is acting: the one identity every write names, whichever door it came through. {@code handle}
 * is the acting FDE's handle (the {@code assignee} a spec is matched against), a run's principal on
 * the in-container lanes, or a fixed name for main and this box's machinery — on the {@link
 * Lane#MAIN} lane, the author main recorded for the version a node adopts, when main names one; it
 * is null for a machine credential that owns no FDE. {@code role} carries the capabilities. {@code
 * lane} names the way the write is made. {@code owner} is set only on the {@link Lane#AGENT} and
 * {@link Lane#ROOM} lanes: the FDE the run's principal acts for, used for attribution and policy
 * tiering, never as a separate authorization system. {@code scope} is the projects the actor may
 * see ({@link ProjectScope}): every project unless main scoped the FDE behind it, which only the
 * doors that resolve an FDE against main's roster ever say. {@code work} is set only on the {@link
 * Lane#AGENT} and {@link Lane#ROOM} lanes: what sail launched the run for ({@link Work}), which
 * only the role rule resolves.
 *
 * <p>Every entry point binds the actor it acts as with {@link #run} or {@link #call}, at its edge,
 * and the {@code ChangeLog} reads it back through {@link #current()} for every revision it records.
 * A write with nothing bound fails: there is no default identity.
 *
 * <p>Fails closed: a machine token yields a {@code null} handle that matches no assignee, and an
 * unknown role resolves (via {@link Role#fromAttribute}) to the least-privileged {@link
 * Role#VIEWER}.
 */
public record Actor(
    String handle, Role role, Lane lane, String owner, ProjectScope scope, Work work) {

  /** The author this box's own machinery writes as. */
  public static final String SYSTEM_HANDLE = "sail";

  /** The handle a node adopts main's decisions under. */
  public static final String MAIN_HANDLE = "main";

  private static final ScopedValue<Actor> CURRENT = ScopedValue.newInstance();

  private static final ScopedValue<Supplier<Actor>> READER = ScopedValue.newInstance();

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
    /**
     * The box's credential presented in a project container: the box's person, whose role its own
     * column of the permission table narrows, since whatever runs there can read it.
     */
    CONTAINER,
    /** An FDE pushing through {@code _sync} to main. */
    SYNC,
    /** A node adopting main's revisions, which main has already decided. */
    MAIN,
    /** This box's own machinery. */
    SYSTEM
  }

  /**
   * What sail launched a run for, its agent's own work: the {@code project} it is filed under, the
   * {@code spec} it works (null for none), and the room it converses in ({@link #room}), its spec's
   * room for a run that works a spec (null for none), which {@code rooms} finds only when a
   * decision asks it, so a request that decides nothing of a room reads none. A work that names
   * neither a spec nor a room is its whole project's, an ad-hoc run's.
   */
  public record Work(String project, String spec, Supplier<String> rooms) {
    public Work {
      Objects.requireNonNull(rooms, "rooms");
    }

    /** A work whose room, {@code room}, is known already. */
    public static Work of(String project, String spec, String room) {
      return new Work(project, spec, () -> room);
    }

    /** The room it converses in, found as it is asked. */
    public String room() {
      return rooms.get();
    }
  }

  public Actor {
    Objects.requireNonNull(scope, "scope");
  }

  public Actor(String handle, Role role, Lane lane) {
    this(handle, role, lane, null);
  }

  public Actor(String handle, Role role, Lane lane, String owner) {
    this(handle, role, lane, owner, ProjectScope.all(), null);
  }

  /** This actor seeing only {@code scope}. */
  public Actor withScope(ProjectScope scope) {
    return new Actor(handle, role, lane, owner, scope, work);
  }

  /** This actor working {@code work}. */
  public Actor withWork(Work work) {
    return new Actor(handle, role, lane, owner, scope, work);
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

  /** Main, as a node adopts what it decided. */
  public static Actor main() {
    return main(MAIN_HANDLE);
  }

  /**
   * Main, as a node adopts a version main recorded as made by {@code author}: the node records the
   * same author, even for a tombstone or an erasure, which carries no snapshot to name one. A null
   * author is a version main recorded with none, and the node records none either.
   */
  public static Actor main(String author) {
    return new Actor(author, Role.ADMIN, Lane.MAIN);
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

  /**
   * Who is reading: the actor bound to the current operation; else whoever {@link #readingAs} named
   * for the work around it; else, for this box's own machinery that no door bound an actor to, the
   * box itself ({@link #system}), which sees every project. A door that serves anyone else binds
   * its caller before it routes, and a read then sees no more than that caller's {@link #scope}. A
   * write never falls back like this: see {@link #current()}.
   */
  public static Actor reader() {
    if (CURRENT.isBound()) {
      return CURRENT.get();
    }
    return READER.isBound() ? READER.get().get() : system();
  }

  /**
   * Runs {@code work} with every read no actor is bound to made as whoever {@code reader} names,
   * asked at each such read: the host CLI's person, whom a command that reads nothing need not
   * resolve. Writes still need an actor bound with {@link #run} or {@link #call}.
   */
  public static <T, X extends Throwable> T readingAs(
      Supplier<Actor> reader, ScopedValue.CallableOp<T, X> work) throws X {
    return ScopedValue.where(READER, Objects.requireNonNull(reader, "reader")).call(work);
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

  /**
   * True for Sail itself: main, as a node adopts what it decided ({@link Lane#MAIN}), and this
   * box's own machinery ({@link Lane#SYSTEM}). What it does was decided already, so no rule is
   * asked of it.
   */
  public boolean isSail() {
    return lane == Lane.MAIN || lane == Lane.SYSTEM;
  }

  /** True when this actor's role grants full administrative authority. */
  public boolean isAdmin() {
    return role == Role.ADMIN;
  }

  /**
   * True when this actor was built from a run credential on an in-container lane — the agent lane
   * or its room-restricted variant. Both are refused wherever a run must not act on runs (the
   * dispatch and stop gates).
   */
  public boolean agentLane() {
    return lane == Lane.AGENT || lane == Lane.ROOM;
  }

  /**
   * The FDE this actor acts as: its own handle, or on the {@link Lane#AGENT} and {@link Lane#ROOM}
   * lanes the FDE its run acts for, never the run's principal. What a spec or room records as its
   * creator, and whom a claim assigns it to.
   */
  public String actingFde() {
    return agentLane() ? owner : handle;
  }

  /**
   * Whether this actor is {@code identity} or acts on its behalf: an agent principal carries its
   * owning FDE, so a spec assigned to that FDE is the agent's to work exactly as if the FDE edited
   * it directly. A blank identity is no one's.
   */
  public boolean actsFor(String identity) {
    return Strings.isNotBlank(identity) && (identity.equals(handle) || identity.equals(owner));
  }

  /**
   * The box a synced revision came from: the pushing FDE on main, {@code main} on a node adopting
   * its revisions, and null for a write this box made itself.
   */
  public String peer() {
    return switch (lane) {
      case SYNC -> handle;
      case MAIN -> MAIN_HANDLE;
      default -> null;
    };
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
