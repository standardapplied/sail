/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.identity;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.RunStore;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The role a credential that names an FDE acts with: one rule for every door — an API token, a
 * passkey session, the box credential, the host CLI, a run credential, a sync session, the SSH
 * gateway and the terminal. This box's operator on main or a standalone box, the FDE its sync
 * handle names, is admin, because they hold root on it. Every other FDE acts with the role main's
 * roster gives it. Either way the credential's own role caps it, so no credential acts beyond what
 * it was minted with. An FDE the roster marks disabled is refused, and so is one it does not know,
 * unless it is the operator. A null {@code roster} is a box that keeps none yet: it knows no FDE
 * but its operator. A credential that names no FDE acts with the role of the box's FDE, so no
 * credential on a box acts beyond the FDE whose box it is. {@link #principal} is the one way in.
 *
 * <p>A node's writes speak for its FDE: every revision it pushes reaches main on the box's session,
 * which main decides as that FDE's. So on a node a credential naming any other FDE, an admin's
 * included, acts with at most {@link Role#VIEWER}, and the node refuses its writes up front rather
 * than pushing what main would deny.
 */
public final class RoleRule {

  private final Supplier<SyncConfig> box;
  private final FdeStore roster;

  public RoleRule(Supplier<SyncConfig> box, FdeStore roster) {
    this.box = Objects.requireNonNull(box, "box");
    this.roster = roster;
  }

  /**
   * What a door's credential code found: whom the credential names and the lane it came in by,
   * never the role it acts with, which only {@link #principal} resolves, so a door names no role. A
   * credential that names no person (the box's own, the host token, a machine token, a run acting
   * for no FDE) acts for the box's FDE ({@link #boxFde}): it takes that FDE's role and scope, so no
   * credential on a box reaches beyond the person whose box it is.
   */
  public sealed interface Credential {

    /**
     * A person's own credential naming FDE {@code handle}, with their full role, on the API lane
     * its commands reach: the SSH gateway's key.
     */
    static Credential person(String handle) {
      return new Person(handle, Role.ADMIN, Actor.Lane.API);
    }

    /** An API token bound to FDE {@code handle}, minted with the role {@code minted} names. */
    static Credential token(String handle, String minted) {
      return new Person(handle, Role.fromAttribute(minted), Actor.Lane.API);
    }

    /** A login session the door has validated, on {@code lane}: the FDE it was opened for. */
    static Credential session(AuthSessionStore.SessionInfo session, Actor.Lane lane) {
      return new Session(Objects.requireNonNull(session, "session"), lane);
    }

    /**
     * The box's own person with their full role, on the CLI lane of its root: the host CLI, the box
     * credential in a project container, the terminal's blank token.
     */
    static Credential box() {
      return new Box(Role.ADMIN, Actor.Lane.CLI);
    }

    /** The host CLI's API token, minted with the role {@code minted} names: the box's person. */
    static Credential hostToken(String minted) {
      return new Box(Role.fromAttribute(minted), Actor.Lane.API);
    }

    /**
     * An API token bound to no FDE, minted with the role {@code minted} names: it acts as no one,
     * with the role and scope of the box's FDE, and so is never the owner of anything.
     */
    static Credential machine(String minted) {
      return new Machine(Role.fromAttribute(minted));
    }

    /** A run's credential: its principal, for the FDE the run acts for. */
    static Credential run(RunStore.RunRow run) {
      return new Run(Objects.requireNonNull(run, "run"));
    }

    /** FDE {@code handle}, whose role {@code cap} caps. */
    record Person(String handle, Role cap, Actor.Lane lane) implements Credential {}

    /** The FDE a validated login session was opened for. */
    record Session(AuthSessionStore.SessionInfo session, Actor.Lane lane) implements Credential {}

    /** The box's FDE, whose role {@code cap} caps. */
    record Box(Role cap, Actor.Lane lane) implements Credential {}

    /** No FDE, acting with the box's FDE's role, which {@code cap} caps. */
    record Machine(Role cap) implements Credential {}

    /** A run's principal. */
    record Run(RunStore.RunRow run) implements Credential {}
  }

  /**
   * The principal {@code credential} stands for: its handle, its role capped by what the credential
   * was minted with, its lane, and its scope, always the resolved person's; empty when the
   * credential is refused — an FDE disabled or unknown here, a session whose FDE is gone, or a box
   * that cannot place its own FDE.
   */
  public Optional<Actor> principal(Credential credential) {
    return switch (Objects.requireNonNull(credential, "credential")) {
      case Credential.Person(var handle, var cap, var lane) -> actorOf(handle, cap, lane);
      case Credential.Session(var session, var lane) ->
          Optional.ofNullable(roster)
              .flatMap(known -> known.byId(session.fdeId()))
              .flatMap(fde -> actorOf(fde.handle(), Role.ADMIN, lane));
      case Credential.Box(var cap, var lane) ->
          actingFor(null, boxFde().orElse(null), cap, lane, null);
      case Credential.Machine(var cap) -> actingFor(null, null, cap, Actor.Lane.API, null);
      case Credential.Run(var run) -> {
        var principal = run.principalActor();
        yield actingFor(
            run.owner(), principal.handle(), principal.role(), principal.lane(), principal.owner());
      }
    };
  }

  /**
   * An actor showing {@code handle} on {@code lane}, for {@code owner}, acting as FDE {@code
   * person} or, when that is blank, as the box's FDE: that FDE's role capped by {@code cap}, and
   * its scope.
   */
  private Optional<Actor> actingFor(
      String person, String handle, Role cap, Actor.Lane lane, String owner) {
    var unbound = Strings.isBlank(person);
    var role = unbound ? roleOfUnbound(cap) : roleOf(person, cap);
    var scope = scopeOf(unbound ? boxFde().orElse(null) : person);
    return role.map(granted -> new Actor(handle, granted, lane, owner).withScope(scope));
  }

  /**
   * The role a credential naming FDE {@code handle} and minted with {@code cap} acts with; empty
   * when the credential is refused.
   */
  private Optional<Role> roleOf(String handle, Role cap) {
    if (Strings.isBlank(handle)) {
      return Optional.empty();
    }
    var fde = roster == null ? Optional.<FdeStore.Fde>empty() : roster.byHandle(handle);
    if (fde.isPresent() && !fde.get().active()) {
      return Optional.empty();
    }
    if (isOperator(handle)) {
      return Optional.of(Role.ADMIN.cappedBy(cap));
    }
    var bound = foreignOnNode(handle) ? cap.cappedBy(Role.VIEWER) : cap;
    return fde.map(found -> Role.fromAttribute(found.role()).cappedBy(bound));
  }

  /**
   * The projects a credential naming FDE {@code handle} may see, resolved at the same doors as its
   * role: the grants of an FDE main's roster scopes, every project otherwise. A box that keeps no
   * roster scopes no one, and the operator, an admin whatever the roster says, is never scoped.
   */
  private ProjectScope scopeOf(String handle) {
    return roster == null || Strings.isBlank(handle) || isOperator(handle)
        ? ProjectScope.all()
        : roster.scopeOf(handle);
  }

  /**
   * The actor a credential naming FDE {@code handle} and minted with {@code cap} acts as on {@code
   * lane}: its role and its scope, resolved together; empty when the credential is refused.
   */
  private Optional<Actor> actorOf(String handle, Role cap, Actor.Lane lane) {
    return roleOf(handle, cap)
        .map(role -> new Actor(handle, role, lane).withScope(scopeOf(handle)));
  }

  /** Whether {@code handle} names an FDE other than this node's own. */
  private boolean foreignOnNode(String handle) {
    var config = box.get();
    return config.isNode() && !handle.equals(config.handle());
  }

  /**
   * The role a credential that names no FDE, minted with {@code cap}, acts with: the role of the
   * box's FDE ({@link #boxFde}), so on a node it is refused until the roster knows that FDE. Main
   * or a standalone box with no sync handle has no FDE to be, and the credential acts with {@code
   * cap}; a node with none syncs as no one, and is refused. Empty when the credential is refused.
   */
  private Optional<Role> roleOfUnbound(Role cap) {
    var fde = boxFde();
    if (fde.isPresent()) {
      return roleOf(fde.get(), cap);
    }
    return box.get().isNode() ? Optional.empty() : Optional.of(cap);
  }

  /** The FDE this box is, named by its sync handle; empty on a box with no sync handle. */
  public Optional<String> boxFde() {
    var handle = box.get().handle();
    return Strings.isBlank(handle) ? Optional.empty() : Optional.of(handle);
  }

  private boolean isOperator(String handle) {
    var config = box.get();
    return !config.isNode() && handle.equals(config.handle());
  }
}
