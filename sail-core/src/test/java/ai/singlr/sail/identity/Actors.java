/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.identity;

import ai.singlr.sail.store.RunStore;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The cast every rule's table is played by: {@code ada} owns the work, {@code bob} is another
 * member, {@code root} is an admin, and a credential naming no FDE is a machine's — on each lane a
 * write can arrive by. Ada's agent is a build of her spec {@code auth}, born in her room {@code
 * lobby}; her room agent sits in {@code lobby}; bob's agent builds {@code theirs} in {@code den}.
 */
public final class Actors {

  public static final String OWNER = "ada";
  public static final String OTHER = "bob";
  public static final String RUN = "019fee00-0000-7000-8000-00000000a0a0";
  public static final String OTHER_RUN = "019fee00-0000-7000-8000-00000000b0b0";
  public static final String AGENT_HANDLE = agentHandle(RUN);
  public static final String ROOM_HANDLE = roomHandle(RUN);

  public static final Actor ADMIN = new Actor("root", Role.ADMIN, Actor.Lane.API);
  public static final Actor OWNER_API = new Actor(OWNER, Role.MEMBER, Actor.Lane.API);
  public static final Actor OWNER_CLI = new Actor(OWNER, Role.MEMBER, Actor.Lane.CLI);
  public static final Actor OTHER_API = new Actor(OTHER, Role.MEMBER, Actor.Lane.API);
  public static final Actor VIEWER = new Actor(OWNER, Role.VIEWER, Actor.Lane.API);
  public static final Actor MACHINE = new Actor(null, Role.MEMBER, Actor.Lane.API);
  public static final Actor AGENT =
      Actor.agentPrincipal(AGENT_HANDLE, OWNER).withWork(new Actor.Work("acme", "auth", "lobby"));
  public static final Actor OTHERS_AGENT =
      Actor.agentPrincipal("claude/" + OTHER_RUN, OTHER)
          .withWork(new Actor.Work("acme", "theirs", "den"));
  public static final Actor ROOM =
      Actor.roomPrincipal(ROOM_HANDLE, OWNER).withWork(new Actor.Work("acme", null, "lobby"));
  public static final Actor OWNER_SYNC = new Actor(OWNER, Role.MEMBER, Actor.Lane.SYNC);
  public static final Actor OTHER_SYNC = new Actor(OTHER, Role.MEMBER, Actor.Lane.SYNC);
  public static final Actor ADMIN_SYNC = new Actor(OTHER, Role.ADMIN, Actor.Lane.SYNC);
  public static final Actor VIEWER_SYNC = new Actor(OWNER, Role.VIEWER, Actor.Lane.SYNC);
  public static final Actor VIEWER_IN_CONTAINER =
      new Actor(OWNER, Role.VIEWER, Actor.Lane.CONTAINER);
  public static final Actor OWNER_IN_CONTAINER =
      new Actor(OWNER, Role.MEMBER, Actor.Lane.CONTAINER);
  public static final Actor ADMIN_IN_CONTAINER =
      new Actor("root", Role.ADMIN, Actor.Lane.CONTAINER);
  public static final Actor MAIN = Actor.main(OTHER);
  public static final Actor SYSTEM = Actor.system();

  /**
   * The actor {@code run}'s credential stands for before the role rule weighs its owner: its
   * principal, on the room lane for a read-only lane and the agent lane otherwise, working its spec
   * in the spec's own room, or the room it was launched in.
   */
  public static Actor principalOf(RunStore.RunRow run) {
    return (run.readOnlyLane()
            ? Actor.roomPrincipal(run.principal(), run.owner())
            : Actor.agentPrincipal(run.principal(), run.owner()))
        .withWork(
            new Actor.Work(
                run.project(), run.specId(), run.specId() == null ? run.roomId() : run.specId()));
  }

  private Actors() {}

  /** The handle a build of run {@code runId} acts as, as sail mints it. */
  public static String agentHandle(String runId) {
    return PrincipalHandle.mint("claude", PrincipalHandle.Marker.BUILD, runId);
  }

  /** The handle a room's wake, run {@code runId}, acts as, as sail mints it. */
  public static String roomHandle(String runId) {
    return PrincipalHandle.mint("claude", PrincipalHandle.Marker.ROOM, runId);
  }

  /** A projection of alternating keys and values; a null value is kept. */
  public static Map<String, Object> projection(Object... pairs) {
    var map = new LinkedHashMap<String, Object>();
    for (var i = 0; i < pairs.length; i += 2) {
      map.put((String) pairs[i], pairs[i + 1]);
    }
    return map;
  }

  /** {@code base} with {@code key} set to {@code value}. */
  public static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
    var map = new LinkedHashMap<>(base);
    map.put(key, value);
    return map;
  }
}
