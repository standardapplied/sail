/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access.baseline;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.Snapshots;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every kind of revision a type's rule tells apart, of the {@link World}'s rows, as the snapshot
 * the journal decides: a birth in each place it may land, an edit of each class of field a rule
 * treats differently, an assignee or status change, a deletion and a restore. A deletion's snapshot
 * is null, as the journal hands it to the rule, and a rename's is the mark its deletion of the old
 * name carries.
 */
final class Changes {

  static final String NEW_SPEC = "s-new";
  static final String NEW_ROOM = "r-new";
  static final String NEW_RUN = "019fee00-0000-7000-8000-0000000c0c01";
  static final String NEW_REVIEW = "019fee00-0000-7000-8000-0000000c0c02";
  static final String NEW_MESSAGE = "019fee00-0000-7000-8000-0000000c0c03";

  /** The mark of the deletion a rename records of the old name, which blocks its resurrection. */
  private static final String RENAMED = "_blocks_resurrection";

  /** One revision: the row it writes, what it changes, and the snapshot written; null deletes. */
  record Change(String type, String id, String what, Map<String, Object> next) {

    /** The cell key of this revision, by type, row and change. */
    String label() {
      return type + " " + id + " " + what;
    }
  }

  private final World world;
  private final List<Change> changes = new ArrayList<>();

  private Changes(World world) {
    this.world = world;
  }

  /** The revisions of spec, room and project, the types whose box decision a door pre-asks. */
  static List<Change> preAsked(World world) {
    var changes = new Changes(world);
    changes.specs();
    changes.rooms();
    changes.projects();
    return changes.changes;
  }

  /** The revisions of every synced type. */
  static List<Change> all(World world) {
    var changes = new Changes(world);
    changes.changes.addAll(preAsked(world));
    changes.runs();
    changes.reviews();
    changes.files();
    changes.messages();
    return changes.changes;
  }

  private void specs() {
    for (var id : World.SPECS) {
      if (id.equals("s-gone")) {
        continue;
      }
      var held = world.snapshot("spec", id);
      edit("spec", id, "edit title", held, "title", "retitled");
      edit("spec", id, "edit content", held, "body_hash", "0".repeat(64));
      for (var assignee : new String[] {Cast.OWNER, Cast.OTHER, null}) {
        if (!Objects.equals(held.get("assignee"), assignee)) {
          edit("spec", id, "assign " + assignee, held, "assignee", assignee);
        }
      }
      for (var status : List.of("pending", "done", "cancelled", "archived")) {
        if (!held.get("status").equals(status)) {
          edit("spec", id, "status " + status, held, "status", status);
        }
      }
      add("spec", id, "delete", null);
    }
    for (var id : List.of("s-ada", "s-lobby", "s-open-bob")) {
      var held = world.snapshot("spec", id);
      edit("spec", id, "move room to den", held, "room_id", "den");
      edit("spec", id, "move to beta", held, "project", "beta");
      edit("spec", id, "move to omega", held, "project", "omega");
    }
    var gone = world.snapshot("spec", "s-gone");
    add("spec", "s-gone", "restore", gone);
    edit("spec", "s-gone", "restore assigned bob", gone, "assignee", Cast.OTHER);
    born(NEW_SPEC, "create", null, "acme", NEW_SPEC);
    born(NEW_SPEC, "create assigned ada", Cast.OWNER, "acme", NEW_SPEC);
    born(NEW_SPEC, "create assigned bob", Cast.OTHER, "acme", NEW_SPEC);
    born(NEW_SPEC, "create in beta", null, "beta", NEW_SPEC);
    born(NEW_SPEC, "create in omega", null, "omega", NEW_SPEC);
    for (var room : List.of("lobby", "den", "hall", "attic", "b-room", "ghost")) {
      born(NEW_SPEC, "create born in " + room, null, "acme", room);
    }
    born(NEW_SPEC, "create born in lobby assigned ada", Cast.OWNER, "acme", "lobby");
    born(NEW_SPEC, "create born in den assigned ada", Cast.OWNER, "acme", "den");
    for (var room : List.of("lobby", "den", "hall", "attic", "b-room")) {
      born(room, "create over the room", null, "acme", room);
      born(room, "create over the room assigned ada", Cast.OWNER, "acme", room);
    }
    var draft = born("s-draft-new", "create draft", null, "acme", "s-draft-new");
    draft.put("status", "draft");
  }

  private Map<String, Object> born(
      String id, String what, String assignee, String project, String room) {
    var next = world.snapshot("spec", "s-open-ada");
    next.remove(Snapshots.CREATOR);
    next.put("title", id);
    next.put("assignee", assignee);
    next.put("project", project);
    next.put("room_id", room);
    add("spec", id, what, next);
    return next;
  }

  private void rooms() {
    for (var id : List.of("lobby", "den", "hall", "b-room", "s-ada")) {
      var held = world.snapshot("room", id);
      edit("room", id, "edit title", held, "title", "retitled");
      edit("room", id, "edit wake", held, "wake", "on");
      edit("room", id, "assign bob", held, "assignee", Cast.OTHER);
      add("room", id, "delete", null);
    }
    edit("room", "lobby", "move to beta", world.snapshot("room", "lobby"), "project", "beta");
    add("room", "attic", "restore", world.snapshot("room", "attic"));
    for (var creator : new String[] {Cast.OWNER, Cast.OTHER, null}) {
      room(NEW_ROOM, "create by " + creator, "acme", creator);
    }
    room(NEW_ROOM, "create in beta", "beta", Cast.OWNER);
    room(NEW_ROOM, "create in omega", "omega", Cast.OWNER);
    for (var spec : List.of("s-open-bob", "s-beta", "s-gone")) {
      room(spec, "create over the spec", "acme", Cast.OWNER);
    }
  }

  private void room(String id, String what, String project, String creator) {
    var next = world.snapshot("room", "lobby");
    next.put("title", id);
    next.put("project", project);
    next.put("assignee", creator);
    next.put("created_by", creator);
    add("room", id, what, next);
  }

  private void projects() {
    for (var id : List.of("acme", "beta", "delta")) {
      edit("project", id, "edit definition", world.snapshot("project", id), "description", "x");
      add("project", id, "delete", null);
      add("project", id, "rename", Map.of(RENAMED, true));
    }
    for (var id : List.of("p-new", "omega")) {
      var next = world.snapshot("project", "delta");
      next.put("name", id);
      add("project", id, "create", next);
    }
  }

  private void runs() {
    var ada = world.snapshot("run", Cast.ADA_RUN);
    edit("run", Cast.ADA_RUN, "status completed", ada, "status", "completed");
    var session = new LinkedHashMap<>(ada);
    session.put("session_id", "s-1");
    session.put("session_source", "startup");
    add("run", Cast.ADA_RUN, "report session", session);
    edit("run", Cast.ADA_RUN, "move to spec s-bob", ada, "spec_id", "s-bob");
    edit(
        "run",
        Cast.ADA_RUN,
        "name a foreign principal",
        ada,
        "principals",
        List.of(Cast.ADA_AGENT, Cast.BOB_AGENT));
    add("run", Cast.ADA_RUN, "delete", null);
    for (var id :
        List.of(
            Cast.BOB_RUN,
            Cast.ADA_ROOM_RUN,
            World.BETA_RUN,
            World.ADHOC_RUN,
            World.HANDED_OVER_RUN)) {
      edit("run", id, "status completed", world.snapshot("run", id), "status", "completed");
    }
    run("create of s-ada by ada", "s-ada", null, Cast.OWNER, Cast.OWNER);
    run("create of s-ada by bob", "s-ada", null, Cast.OTHER, Cast.OTHER);
    run("create of s-bob by ada", "s-bob", null, Cast.OWNER, Cast.OWNER);
    run("create of s-open-bob by ada", "s-open-bob", null, Cast.OWNER, Cast.OWNER);
    run("create ad-hoc by ada", null, null, Cast.OWNER, Cast.OWNER);
    run("create ad-hoc on ada's node for bob", null, null, Cast.OWNER, Cast.OTHER);
    run("create in lobby by ada", null, "lobby", Cast.OWNER, Cast.OWNER);
    run("create in den by ada", null, "den", Cast.OWNER, Cast.OWNER);
    run("create of s-ada in lobby by ada", "s-ada", "lobby", Cast.OWNER, Cast.OWNER);
  }

  private void run(String what, String spec, String room, String node, String owner) {
    var next = world.snapshot("run", Cast.ADA_RUN);
    var principal = "claude/" + (room == null ? "" : "room-") + NEW_RUN;
    next.put("role", room == null ? "build" : "room");
    next.put("spec_id", spec);
    next.put("room_id", room);
    next.put("node", node);
    next.put("owner", owner);
    next.put("principal", principal);
    next.put("principals", List.of(principal));
    add("run", NEW_RUN, what, next);
  }

  private void reviews() {
    for (var name : List.of("review-ada", "review-bob")) {
      var id = world.id(name);
      var held = world.snapshot("review", id);
      edit("review", id, "status passed", held, "status", "passed");
      edit("review", id, "decide", held, "decided_by", Cast.OWNER);
      edit("review", id, "move to spec s-open-bob", held, "spec_id", "s-open-bob");
    }
    for (var spec : List.of("s-ada", "s-bob", "s-open-bob", "s-beta")) {
      var next = world.snapshot("review", world.id("review-ada"));
      next.put("spec_id", spec);
      next.put("stages", List.of());
      add("review", NEW_REVIEW, "create of " + spec, next);
    }
    var carried = world.snapshot("review", world.id("review-ada"));
    add("review", NEW_REVIEW, "create carrying review-ada's stage", carried);
  }

  private void files() {
    for (var project : List.of("acme", "beta")) {
      var id = project + "/notes.md";
      edit("file", id, "edit mode", world.snapshot("file", id), "mode", 384);
      add("file", id, "delete", null);
    }
    for (var project : List.of("acme", "beta", "omega")) {
      add("file", project + "/new.md", "create", world.snapshot("file", "acme/notes.md"));
    }
  }

  private void messages() {
    for (var room : List.of("lobby", "den", "hall", "s-ada", "s-bob", "s-den", "b-room", "ghost")) {
      message("post in " + room + " as the pusher", room, null);
    }
    message("post in lobby as ada", "lobby", Cast.OWNER);
    message("post in lobby as bob", "lobby", Cast.OTHER);
    message("post in lobby as sail", "lobby", Actor.SYSTEM_HANDLE);
    message("post in lobby as ada's agent", "lobby", Cast.ADA_AGENT);
    message("post in lobby as ada's room agent", "lobby", Cast.ADA_ROOM_AGENT);
    message("post in den as bob's room agent", "den", Cast.BOB_ROOM_AGENT);
    var said = world.id("msg(lobby by ada)");
    edit("message", said, "edit body", world.snapshot("message", said), "body", "changed");
  }

  private void message(String what, String room, String author) {
    var next = world.snapshot("message", world.id("msg(lobby by ada)"));
    next.put("room_id", room);
    next.put("author", author);
    next.put("body", what);
    add("message", NEW_MESSAGE, what, next);
  }

  private void edit(
      String type, String id, String what, Map<String, Object> held, String key, Object value) {
    var next = new LinkedHashMap<>(held);
    next.put(key, value);
    add(type, id, what, next);
  }

  private void add(String type, String id, String what, Map<String, Object> next) {
    changes.add(new Change(type, id, what, next));
  }
}
