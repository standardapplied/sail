/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The cast every rule's table is played by: {@code ada} owns the work, {@code bob} is another
 * member, {@code root} is an admin, and a credential naming no FDE is a machine's — on each lane a
 * write can arrive by.
 */
final class Actors {

  static final String OWNER = "ada";
  static final String OTHER = "bob";
  static final String RUN = "019fee00-0000-7000-8000-00000000a0a0";
  static final String OTHER_RUN = "019fee00-0000-7000-8000-00000000b0b0";
  static final String AGENT_HANDLE = "claude/" + RUN;
  static final String ROOM_HANDLE = "claude/room-" + RUN;

  static final Actor ADMIN = new Actor("root", Role.ADMIN, Actor.Lane.API);
  static final Actor OWNER_API = new Actor(OWNER, Role.MEMBER, Actor.Lane.API);
  static final Actor OWNER_CLI = new Actor(OWNER, Role.MEMBER, Actor.Lane.CLI);
  static final Actor OTHER_API = new Actor(OTHER, Role.MEMBER, Actor.Lane.API);
  static final Actor VIEWER = new Actor(OWNER, Role.VIEWER, Actor.Lane.API);
  static final Actor MACHINE = new Actor(null, Role.MEMBER, Actor.Lane.API);
  static final Actor AGENT = Actor.agentPrincipal(AGENT_HANDLE, OWNER);
  static final Actor OTHERS_AGENT = Actor.agentPrincipal("claude/" + OTHER_RUN, OTHER);
  static final Actor ROOM = Actor.roomPrincipal(ROOM_HANDLE, OWNER);
  static final Actor OWNER_SYNC = Actor.sync(OWNER, Role.MEMBER);
  static final Actor OTHER_SYNC = Actor.sync(OTHER, Role.MEMBER);
  static final Actor ADMIN_SYNC = Actor.sync(OTHER, Role.ADMIN);
  static final Actor VIEWER_SYNC = Actor.sync(OWNER, Role.VIEWER);
  static final Actor MAIN = Actor.main(OTHER);
  static final Actor SYSTEM = Actor.system();

  private Actors() {}

  /** A projection of alternating keys and values; a null value is kept. */
  static Map<String, Object> projection(Object... pairs) {
    var map = new LinkedHashMap<String, Object>();
    for (var i = 0; i < pairs.length; i += 2) {
      map.put((String) pairs[i], pairs[i + 1]);
    }
    return map;
  }

  /** {@code base} with {@code key} set to {@code value}. */
  static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
    var map = new LinkedHashMap<>(base);
    map.put(key, value);
    return map;
  }
}
