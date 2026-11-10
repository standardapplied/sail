/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.access.baseline.Baseline;
import ai.singlr.sail.access.baseline.Cast;
import ai.singlr.sail.access.baseline.Corpus;
import ai.singlr.sail.access.baseline.World;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.SpecStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A1's differential, door half: every check a door makes before, beside or instead of the journal
 * answers as main's doors at {@code fd091a53} answered it ({@link Baseline}, {@code infra-doors}).
 * The {@link World} of the core corpora is served by the operations every router calls, built as
 * production builds them, on ada's box, whose shell lists no container and runs nothing else; each
 * member of the {@link Cast} a door binds — the host CLI and box credential, an HTTP token, a run's
 * and a room's agent — asks each door, rehearsed so the box is left as it was. A door that takes
 * the box it answers on is asked on ada's box and on bob's. A refusal is its door's code, HTTP
 * status, message and remedy; a success its response type and every id it shows, so a read's
 * audience is recorded with it ({@link #shown}). A door asked directly, rather than through the
 * operations, is asked only by whom production lets reach it ({@link Door#reaches}).
 *
 * <p>It lives beside the routers because the requests they parse are theirs alone; each is built by
 * the router's own parser.
 */
class AccessDoorsBaselineTest {

  private static final Set<Actor.Lane> DOOR_LANES =
      Set.of(Actor.Lane.CLI, Actor.Lane.API, Actor.Lane.AGENT, Actor.Lane.ROOM);

  /**
   * What a prune prints when it erases inside a rehearsal: collecting what it erased needs the
   * connection the rehearsal holds, and the rehearsal leaves nothing behind to collect.
   */
  private static final String UNCOLLECTED =
      "  [prune] erased, but could not collect its content; 'sail sync gc' frees it: Take blob"
          + " retention before opening a transaction, not inside one";

  private static final List<String> BOXES = List.of(Cast.OWNER, Cast.OTHER);

  private static final List<String> RUNS =
      List.of(
          Cast.ADA_RUN,
          Cast.BOB_RUN,
          Cast.ADA_ROOM_RUN,
          World.ADHOC_RUN,
          World.BETA_RUN,
          World.HANDED_OVER_RUN,
          "019fee00-0000-7000-8000-00000000dead");

  @TempDir Path tempDir;

  /**
   * One door: what is asked, and how, and who {@code reaches} it. A door asked directly is reached
   * only with a subject its caller reads: production reads the subject in the caller's scope first,
   * or only the box's operator, who sees every project, asks it. A subject outside the caller's
   * scope never reaches it, so the corpus holds no such cell.
   */
  private record Door(String label, Ask ask, Predicate<Actor> reaches) {

    Door(String label, Ask ask) {
      this(label, ask, actor -> true);
    }
  }

  /** A door's question, which may answer, refuse, or fail. */
  @FunctionalInterface
  private interface Ask {
    Object ask(SailOperations operations) throws Exception;
  }

  @Test
  void everyDoorAnswersAsTheBaselineRecords() {
    try (var world = new World();
        var bus = new EventBus();
        var operations =
            TestControlPlane.on(
                OperationsFactory.create(
                    world.db,
                    new ScriptedShellExecutor().onOk("incus list", "[]"),
                    bus,
                    null,
                    SyncScheduler.disabled(),
                    SessionYield.NONE),
                world.db,
                tempDir,
                new SyncConfig(SyncConfig.ROLE_MAIN, null, Cast.OWNER))) {
      var corpus = new Corpus(world.minted());
      var doors = doors(world);
      var printed =
          World.stderrOf(
              () -> {
                for (var member : Cast.everyone()) {
                  if (!DOOR_LANES.contains(member.actor().lane())) {
                    continue;
                  }
                  for (var door : doors) {
                    if (!door.reaches().test(member.actor())) {
                      continue;
                    }
                    corpus.put(
                        door.label() + " as " + member.label(),
                        world.rehearse(member.actor(), () -> answer(door, operations)));
                  }
                }
              });
      assertEquals(
          List.of(),
          printed.lines().filter(line -> !line.equals(UNCOLLECTED)).toList(),
          "a door prints nothing but what its rehearsal leaves uncollected");
      Baseline.check("infra-doors", corpus);
    }
  }

  private static List<Door> doors(World world) {
    var doors = new ArrayList<Door>();
    reads(world, doors);
    runs(doors);
    specs(world, doors);
    rooms(world, doors);
    projects(doors);
    events(world, doors);
    return doors;
  }

  private static void reads(World world, List<Door> doors) {
    doors.add(new Door("list specs", ops -> ops.globalSpecs(SpecStore.SpecFilter.all())));
    doors.add(new Door("list rooms", ops -> ops.rooms(null)));
    doors.add(new Door("list runs", ops -> ops.runs(null, null)));
    doors.add(new Door("list projects", Operations::projects));
    for (var project : List.of("acme", "beta", "omega")) {
      doors.add(new Door("list specs of " + project, ops -> ops.specs(project)));
      doors.add(new Door("list rooms of " + project, ops -> ops.rooms(project)));
      doors.add(new Door("list runs of " + project, ops -> ops.runs(project, null)));
      doors.add(new Door("read project " + project, ops -> ops.project(project)));
      doors.add(new Door("read definition of " + project, ops -> ops.projectDefinition(project)));
      doors.add(new Door("list files of " + project, ops -> ops.projectFiles(project).list()));
    }
    for (var spec : List.of("s-ada", "s-beta", "s-gone", "ghost")) {
      doors.add(new Door("read spec " + spec, ops -> ops.globalSpec(spec)));
      doors.add(new Door("list reviews of " + spec, ops -> ops.reviewsForSpec(spec)));
    }
    for (var room : List.of("lobby", "b-room", "s-ada", "ghost")) {
      doors.add(new Door("read room " + room, ops -> ops.room(room)));
      doors.add(
          new Door("read messages of " + room, ops -> ops.roomMessages(room, null, null, 100)));
    }
    for (var run : RUNS) {
      doors.add(new Door("read run " + run, ops -> ops.run(run)));
    }
    var review = world.id("review-ada");
    doors.add(new Door("read review review-ada", ops -> ops.reviewDetail(review)));
  }

  private static void runs(List<Door> doors) {
    for (var box : BOXES) {
      for (var run : RUNS) {
        doors.add(
            new Door(
                "read log of " + run + " on " + box + "'s box", ops -> ops.runLog(run, 10, box)));
        doors.add(new Door("stop " + run + " on " + box + "'s box", ops -> ops.stopRun(run, box)));
      }
    }
  }

  private static void specs(World world, List<Door> doors) {
    for (var box : BOXES) {
      for (var spec : List.of("s-ada", "s-bob", "s-open-ada", "s-busy", "s-beta")) {
        var project = world.box.specs.findById(spec).orElseThrow().project();
        doors.add(
            new Door(
                "dispatch " + spec + " on " + box + "'s box",
                ops -> dispatchAdmission(world, spec, box),
                actor -> actor.scope().admits(project)));
      }
    }
    for (var spec :
        List.of(
            "s-archived",
            "s-archived-bob",
            "s-archived-busy",
            "s-ada",
            "s-beta",
            "s-gone",
            "ghost")) {
      doors.add(
          new Door(
              "prune " + spec,
              ops ->
                  ops.pruneSpecs(
                      PruneRequest.fromMap(Map.of("ids", List.of(spec), "dry_run", false)))));
    }
    doors.add(
        new Door(
            "prune by policy",
            ops ->
                ops.pruneSpecs(
                    PruneRequest.fromMap(
                        Map.of(
                            "policy",
                            Map.of("statuses", List.of("archived"), "older_than_days", 0),
                            "dry_run",
                            false)))));
    doors.add(
        new Door(
            "create spec s-new in acme",
            ops -> ops.createGlobalSpec(created("s-new", "acme", null))));
    doors.add(
        new Door(
            "create spec s-beta in acme",
            ops -> ops.createGlobalSpec(created("s-beta", "acme", null))));
    doors.add(
        new Door(
            "create spec s-new in beta",
            ops -> ops.createGlobalSpec(created("s-new", "beta", null))));
    doors.add(
        new Door(
            "create spec b-room in acme",
            ops -> ops.createGlobalSpec(created("b-room", "acme", null))));
    doors.add(
        new Door(
            "create spec s-new born in b-room",
            ops -> ops.createGlobalSpec(created("s-new", "acme", "b-room"))));
    doors.add(
        new Door(
            "create spec s-new born in den",
            ops -> ops.createGlobalSpec(created("s-new", "acme", "den"))));
    for (var spec : List.of("s-ada", "s-busy", "s-open-ada", "s-beta")) {
      doors.add(
          new Door(
              "retitle spec " + spec,
              ops -> ops.updateGlobalSpec(spec, updated(Map.of("title", "retitled")))));
      doors.add(
          new Door(
              "assign spec " + spec + " to bob",
              ops -> ops.updateGlobalSpec(spec, updated(Map.of("assignee", Cast.OTHER)))));
      doors.add(new Door("delete spec " + spec, ops -> ops.deleteGlobalSpec(spec)));
    }
    for (var spec : List.of("s-ada", "s-beta")) {
      doors.add(
          new Door(
              "resolve the conflict of spec " + spec,
              ops ->
                  ops.resolveConflict(
                      "spec", spec, new Resolution(Resolution.Strategy.MINE, null))));
    }
    var review = world.id("review-ada");
    var finding = world.id("finding-ada");
    doors.add(new Door("approve review-ada", ops -> ops.approveReview(review)));
    doors.add(new Door("dismiss finding-ada", ops -> ops.dismissFinding(review, finding)));
  }

  private static void rooms(World world, List<Door> doors) {
    for (var room : List.of("lobby", "den", "hall", "s-ada", "b-room")) {
      doors.add(
          new Door(
              "post in " + room,
              ops ->
                  ops.postRoomMessage(
                      room,
                      SpecMessageRequest.fromMap(Map.of("body", "hi")),
                      Actor.current().handle())));
      for (var box : BOXES) {
        doors.add(
            new Door(
                "seat an agent in " + room + " on " + box + "'s box",
                ops ->
                    ops.addRoomMember(
                        room, EngageRequest.fromMap(Map.of("agent", "claude-code")), box)));
        doors.add(
            new Door(
                "unseat the agent of " + room + " on " + box + "'s box",
                ops -> ops.removeRoomMember(room, box)));
      }
      doors.add(new Door("delete room " + room, ops -> ops.deleteRoom(room)));
    }
    for (var project : List.of("acme", "beta")) {
      doors.add(
          new Door(
              "create room r-new in " + project,
              ops ->
                  ops.createRoom(
                      RoomCreateRequest.fromMap(
                          Map.of("id", "r-new", "project", project, "title", "t")),
                      Cast.OWNER)));
      for (var handle : BOXES) {
        doors.add(
            new Door(
                "ensure the home room of " + handle + " in " + project,
                ops ->
                    HomeRoom.ensure(
                        ops,
                        TestDefinitions.yaml(
                            "name: " + project + "\nagent:\n  type: claude-code\n"),
                        handle),
                actor -> actor.scope().admits(project)));
      }
    }
  }

  private static void projects(List<Door> doors) {
    for (var project : List.of("acme", "beta", "delta", "omega")) {
      doors.add(
          new Door(
              "change the definition of " + project,
              ops -> ops.writeProjectSection(project, "description", "changed", null)));
      doors.add(
          new Door(
              "serve the stack of " + project,
              ops -> ops.stack().serve(project, Duration.ofSeconds(1))));
      doors.add(new Door("stop the stack of " + project, ops -> ops.stack().stop(project)));
      doors.add(new Door("read the ports of " + project, ops -> ops.stack().ports(project)));
      for (var box : BOXES) {
        doors.add(
            new Door(
                "restore a snapshot of " + project + " on " + box + "'s box",
                ops -> ops.restoreSnapshot(project, "snap", box)));
      }
      doors.add(
          new Door("delete a snapshot of " + project, ops -> ops.deleteSnapshot(project, "snap")));
    }
  }

  /** What an event names: the project it is filed under, its spec, and its data. */
  private record About(String label, String project, String spec, Map<String, Object> data) {}

  private static void events(World world, List<Door> doors) {
    var abouts =
        List.of(
            new About("spec s-ada", "acme", "s-ada", Map.of()),
            new About("spec s-bob", "acme", "s-bob", Map.of()),
            new About("spec s-beta", "beta", "s-beta", Map.of()),
            new About("spec ghost", "acme", "ghost", Map.of()),
            new About("ada's run", "acme", null, Map.of("run_id", Cast.ADA_RUN)),
            new About("bob's run", "acme", null, Map.of("run_id", Cast.BOB_RUN)),
            new About("ada's run of s-bob", "acme", null, Map.of("run_id", World.HANDED_OVER_RUN)),
            new About("ada's beta run", "beta", null, Map.of("run_id", World.BETA_RUN)),
            new About(
                "a message in lobby",
                "acme",
                "lobby",
                Map.of("message_id", world.id("msg(lobby by ada)"))),
            new About(
                "a message in b-room",
                "beta",
                "b-room",
                Map.of("message_id", world.id("msg(b-room by ada)"))));
    for (var type :
        List.of(
            "agent_session_started",
            "agent_session_stopped",
            "review_completed",
            "spec_message_posted",
            "board_updated",
            "spec_created")) {
      for (var about : abouts) {
        for (var retold : List.of(false, true)) {
          doors.add(
              new Door(
                  "publish " + type + " about " + about.label() + (retold ? " retold" : ""),
                  ops -> ops.publishEvent(event(type, about, retold))));
        }
      }
    }
  }

  private static Event event(String type, About about, boolean retold) {
    var data = new LinkedHashMap<String, Object>(about.data());
    if (retold) {
      data.put("source", "sync");
    }
    var event = new LinkedHashMap<String, Object>();
    event.put("ts", "2026-11-10T00:00:00Z");
    event.put("project", about.project());
    if (about.spec() != null) {
      event.put("spec", about.spec());
    }
    event.put("type", type);
    event.put("agent", "claude-code");
    event.put("host", "box");
    event.put("data", data);
    return Event.fromMap(event);
  }

  private static SpecCreateRequest created(String id, String project, String room) {
    var map = new LinkedHashMap<String, Object>();
    map.put("id", id);
    map.put("project", project);
    map.put("title", id);
    map.put("room_id", room);
    return SpecCreateRequest.fromMap(map);
  }

  private static SpecUpdateRequest updated(Map<String, Object> fields) {
    return SpecUpdateRequest.fromMap(fields);
  }

  /**
   * The dispatch admission a build dispatch and a room's seating ask of a spec on {@code box},
   * asked directly, since a dispatch through the operations first needs a running container: every
   * dispatch reads its spec in the caller's scope first.
   */
  private static Object dispatchAdmission(World world, String spec, String box) {
    LaunchAdmission.requireAllowed(world.box.specs.findById(spec).orElseThrow().toSpec(), box);
    return "admitted";
  }

  /** What {@code door} answers the bound actor, as one line. */
  private static String answer(Door door, SailOperations operations) {
    try {
      return switch (door.ask().ask(operations)) {
        case Result.Success<?> success -> "ok " + shown(success.value());
        case Result.Failure<?> failure ->
            Corpus.denied(
                code(failure.errorCode()), failure.fullError() + cause(failure), failure.remedy());
        case Object value -> "ok " + shown(value);
      };
    } catch (ApiException refused) {
      return Corpus.denied(
          code(refused.failure().errorCode()), refused.failure().fullError(), refused.remedy());
    } catch (RuntimeException failed) {
      return failed instanceof Remedied remedied
          ? Corpus.denied(failed.getClass().getSimpleName(), remedied.reason(), remedied.remedy())
          : Corpus.failed(failed);
    } catch (Exception failed) {
      return "failed " + failed.getClass().getSimpleName() + " " + failed.getMessage();
    }
  }

  /** What an internal failure was caused by, which its message does not say; empty otherwise. */
  private static String cause(Result.Failure<?> failure) {
    return failure.errorCode() == ErrorCode.INTERNAL && failure.cause() != null
        ? " ("
            + failure.cause().getClass().getSimpleName()
            + ": "
            + failure.cause().getMessage()
            + ")"
        : "";
  }

  private static String code(ErrorCode code) {
    return code.name() + " " + code.httpCode();
  }

  /**
   * {@code value}'s type and every id it shows, sorted: who sees what is the door's answer, while a
   * list's order follows when its rows last moved, which a fixture written within one millisecond
   * leaves to chance. A list is a list, whatever class holds it, and a home room's outcome is shown
   * whole.
   */
  private static String shown(Object value) {
    if (value instanceof HomeRoom.Outcome outcome) {
      return "Outcome " + outcome.toMap();
    }
    if (value instanceof String said) {
      return said;
    }
    var ids = new ArrayList<String>();
    collect(value instanceof Mappable mappable ? mappable.toMap() : value, ids);
    ids.sort(null);
    var type = value instanceof List<?> ? "List" : value.getClass().getSimpleName();
    return type + (ids.isEmpty() ? "" : " " + String.join(", ", ids));
  }

  private static void collect(Object value, List<String> ids) {
    switch (value) {
      case Mappable mappable -> collect(mappable.toMap(), ids);
      case Map<?, ?> map ->
          map.forEach(
              (key, field) -> {
                if (Set.of("id", "name").contains(key) && field instanceof String id) {
                  ids.add(id);
                } else {
                  collect(field, ids);
                }
              });
      case List<?> list -> list.forEach(item -> collect(item, ids));
      case null, default -> {}
    }
  }
}
