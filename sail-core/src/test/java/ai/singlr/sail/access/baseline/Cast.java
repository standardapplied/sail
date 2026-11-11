/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access.baseline;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Actors;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Everyone a door binds today, each named by a stable label: every role on every lane that can
 * carry it, each scope a grant can give, and each relation to the work the corpus asks about.
 * {@code ada} owns most of that work, {@code bob} is another member, {@code root} the admin; their
 * agents act for them through the runs the corpus's box holds, each on its run's work: ada's agent
 * builds {@code s-ada} and bob's {@code s-bob}, in their own rooms, and their room agents sit in
 * {@code lobby} and {@code den}, reading acme alone of what their person reads, as the role rule
 * narrows a read-only run to its own project. A run's agent is never bound above member nor a
 * room's agent above viewer ({@code RoleRule} caps them), and an admin is never scoped ({@code
 * FdeStore}), so neither appears.
 */
public final class Cast {

  public static final String OWNER = Actors.OWNER;
  public static final String OTHER = Actors.OTHER;
  public static final String ADMIN = Actors.ADMIN.handle();

  /** Ada's build run on {@code s-ada}. */
  public static final String ADA_RUN = "019fee00-0000-7000-8000-0000000ada01";

  /** Bob's build run on {@code s-bob}. */
  public static final String BOB_RUN = "019fee00-0000-7000-8000-0000000b0b01";

  /** Ada's room agent's run in her room {@code lobby}. */
  public static final String ADA_ROOM_RUN = "019fee00-0000-7000-8000-0000000ada02";

  /** Bob's room agent's run in his room {@code den}. */
  public static final String BOB_ROOM_RUN = "019fee00-0000-7000-8000-0000000b0b02";

  public static final String ADA_AGENT = Actors.agentHandle(ADA_RUN);
  public static final String BOB_AGENT = Actors.agentHandle(BOB_RUN);
  public static final String ADA_ROOM_AGENT = Actors.roomHandle(ADA_ROOM_RUN);
  public static final String BOB_ROOM_AGENT = Actors.roomHandle(BOB_ROOM_RUN);

  /** One member of the cast: the label its cells carry, and the actor a door binds. */
  public record Member(String label, Actor actor) {}

  private static final List<String> SCOPES = List.of("all", "acme", "beta");

  private Cast() {}

  /**
   * The whole cast: people on the host CLI, an HTTP token, a sync session and the box credential in
   * a project container, a machine token, run and room agents, and Sail itself as main and as this
   * box.
   */
  public static List<Member> everyone() {
    var cast = new ArrayList<Member>();
    for (var lane :
        List.of(Actor.Lane.CLI, Actor.Lane.API, Actor.Lane.SYNC, Actor.Lane.CONTAINER)) {
      cast.addAll(people(lane, SCOPES));
    }
    cast.add(member("api member machine all", new Actor(null, Role.MEMBER, Actor.Lane.API)));
    cast.addAll(agents(SCOPES));
    cast.add(member("main admin bob all", Actor.main(OTHER)));
    cast.add(member("system admin sail all", Actor.system()));
    return cast;
  }

  /** The people of {@link #everyone} who push: every sync session. */
  public static List<Member> pushers() {
    return everyone().stream().filter(member -> member.actor().lane() == Actor.Lane.SYNC).toList();
  }

  /**
   * The cast of a rule that never reads a scope: people and agents on the lanes that reach it, at
   * one scope.
   */
  public static List<Member> unscoped(Set<Actor.Lane> lanes) {
    var cast = new ArrayList<Member>();
    for (var lane : List.of(Actor.Lane.CLI, Actor.Lane.API, Actor.Lane.CONTAINER)) {
      if (lanes.contains(lane)) {
        cast.addAll(people(lane, List.of("all")));
      }
    }
    if (lanes.contains(Actor.Lane.API)) {
      cast.add(member("api member machine all", new Actor(null, Role.MEMBER, Actor.Lane.API)));
    }
    agents(List.of("all")).stream()
        .filter(member -> lanes.contains(member.actor().lane()))
        .forEach(cast::add);
    return cast;
  }

  private static List<Member> people(Actor.Lane lane, List<String> scopes) {
    var cast = new ArrayList<Member>();
    for (var role : List.of(Role.VIEWER, Role.MEMBER)) {
      for (var who : List.of(OWNER, OTHER)) {
        for (var scope : scopes) {
          cast.add(
              member(
                  label(lane, role, who, scope),
                  scoped(
                      lane == Actor.Lane.SYNC
                          ? new Actor(who, role, Actor.Lane.SYNC)
                          : new Actor(who, role, lane),
                      scope)));
        }
      }
    }
    cast.add(
        member(
            label(lane, Role.ADMIN, ADMIN, "all"),
            lane == Actor.Lane.SYNC
                ? new Actor(ADMIN, Role.ADMIN, Actor.Lane.SYNC)
                : new Actor(ADMIN, Role.ADMIN, lane)));
    return cast;
  }

  private static List<Member> agents(List<String> scopes) {
    var cast = new ArrayList<Member>();
    for (var role : List.of(Role.VIEWER, Role.MEMBER)) {
      for (var scope : scopes) {
        cast.add(agent(role, "ada-agent", ADA_AGENT, OWNER, scope, "s-ada"));
        cast.add(agent(role, "bob-agent", BOB_AGENT, OTHER, scope, "s-bob"));
      }
    }
    for (var scope : scopes) {
      cast.add(
          member(
              label(Actor.Lane.ROOM, Role.VIEWER, "ada-room", scope),
              roomAgent(ADA_ROOM_AGENT, OWNER, scope, "lobby")));
      cast.add(
          member(
              label(Actor.Lane.ROOM, Role.VIEWER, "bob-room", scope),
              roomAgent(BOB_ROOM_AGENT, OTHER, scope, "den")));
    }
    return cast;
  }

  private static Member agent(
      Role role, String who, String handle, String owner, String scope, String spec) {
    return member(
        label(Actor.Lane.AGENT, role, who, scope),
        scoped(new Actor(handle, role, Actor.Lane.AGENT, owner), scope)
            .withWork(Actor.Work.of("acme", spec, spec)));
  }

  /** A room agent of {@code owner} in {@code room} of acme, its person seeing {@code scope}. */
  private static Actor roomAgent(String handle, String owner, String scope, String room) {
    var seen = scoped(Actor.roomPrincipal(handle, owner), scope).scope();
    return Actor.roomPrincipal(handle, owner)
        .withScope(ProjectScope.of(seen.admits("acme") ? List.of("acme") : List.of()))
        .withWork(Actor.Work.of("acme", null, room));
  }

  private static Actor scoped(Actor actor, String scope) {
    return actor.withScope(
        scope.equals("all") ? ProjectScope.all() : ProjectScope.of(List.of(scope)));
  }

  private static String label(Actor.Lane lane, Role role, String who, String scope) {
    return lane.name().toLowerCase(Locale.ROOT) + " " + role.attribute() + " " + who + " " + scope;
  }

  private static Member member(String label, Actor actor) {
    return new Member(label, actor);
  }
}
