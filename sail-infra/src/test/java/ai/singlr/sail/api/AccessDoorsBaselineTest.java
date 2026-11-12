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
import ai.singlr.sail.commands.SessionClient;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.HostAccess;
import ai.singlr.sail.engine.HostToken;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.pty.PtyAdmission;
import ai.singlr.sail.pty.PtyChildShim;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.pty.SdNotify;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.TokenStore;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A1's snapshot, door half: every check a door makes before, beside or instead of the journal
 * answers as the snapshot records it ({@link Baseline}, {@code infra-doors}). The {@link World} of
 * the core corpora is served by the operations every router calls, built as production builds them,
 * on ada's box, whose shell lists no container and runs nothing else; each member of the {@link
 * Cast} a door binds — the host CLI, the box credential in a project container, an HTTP token, a
 * run's and a room's agent — asks each door, rehearsed so the box is left as it was. A door that
 * takes the box it answers on is asked on ada's box and on bob's. A refusal is its door's code,
 * HTTP status, message and remedy; a success its response type and every id it shows, so a read's
 * audience is recorded with it ({@link #shown}). A door asked directly, rather than through the
 * operations, is asked only by whom production lets reach it ({@link Door#reaches}).
 *
 * <p>It lives beside the routers because the requests they parse are theirs alone; each is built by
 * the router's own parser.
 */
class AccessDoorsBaselineTest {

  private static final Set<Actor.Lane> DOOR_LANES =
      Set.of(
          Actor.Lane.CLI, Actor.Lane.API, Actor.Lane.CONTAINER, Actor.Lane.AGENT, Actor.Lane.ROOM);

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
  void everyDoorAnswersAsTheBaselineRecords() throws IOException {
    try (var world = new World();
        var bus = new EventBus();
        var operations =
            TestControlPlane.on(
                OperationsFactory.create(
                    world.db,
                    new ScriptedShellExecutor().onOk("incus list", "[]"),
                    bus,
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
      tickets(world, corpus);
      Baseline.check("infra-doors", corpus);
    }
  }

  private static List<Door> doors(World world) {
    var doors = new ArrayList<Door>();
    reads(world, doors);
    runs(world, doors);
    specs(world, doors);
    rooms(world, doors);
    projects(doors);
    events(world, doors);
    return doors;
  }

  /**
   * A ticket asked at the ticket door by each FDE of the roster, by a machine token and by the
   * host's own token, on main, where ada operates the box: to open a terminal in each project, and
   * to attach to ada's session and to one that does not exist. The terminal host behind the door is
   * the real one, over loopback TCP, deciding each as it decides that open or attach; its sessions
   * in a project run {@code cat}, for want of an Incus. Asked after every other door and outside a
   * rehearsal: the host answers from its own threads, and each ask mints a credential.
   */
  private void tickets(World world, Corpus corpus) throws IOException {
    var roles =
        new RoleRule(
            () -> new SyncConfig(SyncConfig.ROLE_MAIN, null, Cast.OWNER), new FdeStore(world.db));
    var pty = new HostLanes.Pty(world.db, roles, new EventStore(world.db));
    var socket = tempDir.resolve("pty.sock");
    try (var host =
        new PtySessionHost(
            socket,
            tempDir.resolve("sessions"),
            64 * 1024,
            pty::identity,
            new PtyAdmission() {
              @Override
              public void box(PtyIdentity.Person who) throws IOException {
                pty.admitBox(who);
              }

              @Override
              public void project(String project, PtyIdentity.Person who) throws IOException {
                pty.admitProject(project, who);
              }

              @Override
              public void room(String room, String project, PtyIdentity.Person who)
                  throws IOException {
                pty.admitRoom(room, project, who);
              }
            },
            PtyEvents.NONE,
            "0.0.0-test",
            new PtySessionHost.Handoff(
                SdNotify.NONE,
                Map.of(),
                exit ->
                    Stream.concat(
                            PtyChildShim.onClassPath(exit).stream(),
                            Stream.of("sh", "-c", "exec cat", "container"))
                        .toList()),
            new PtySessionHost.Network(
                Optional.of(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)),
                System::nanoTime,
                pty::holder))) {
      host.start();
      try (var unix = SessionClient.connect(socket, "")) {
        unix.create("lounge", List.of(), "/tmp", "acme", "", 80, 24);
      }
      var door =
          new TerminalTickets(
              (credential, action, target) ->
                  SessionClient.ticket(socket, credential, action, target),
              host.tcpAddress().orElseThrow());
      var askers = new LinkedHashMap<String, String>();
      for (var fde :
          List.of(Cast.OWNER, Cast.OTHER, Cast.ADMIN, World.VIEWER_FDE, World.SCOPED_FDE)) {
        askers.put("fde " + fde, session(world, fde));
      }
      askers.put("a machine token", new TokenStore(world.db).create("corpus-ci", "admin").token());
      askers.put(
          "the host token", new TokenStore(world.db).create(HostToken.NAME, "admin").token());
      for (var asker : askers.entrySet()) {
        for (var project : List.of("acme", "beta", "delta", "omega")) {
          var label = asker.getKey() + " asks a ticket to open a terminal in " + project;
          corpus.put(
              label,
              answer(
                  new Door(
                      label,
                      ops ->
                          door.issue(
                              asker.getValue(), Map.of("action", "open", "project", project))),
                  null));
        }
        for (var session : List.of("lounge", "ghost")) {
          var label = asker.getKey() + " asks a ticket to attach to " + session;
          corpus.put(
              label,
              answer(
                  new Door(
                      label,
                      ops ->
                          door.issue(
                              asker.getValue(), Map.of("action", "attach", "session", session))),
                  null));
        }
      }
    }
  }

  private static void reads(World world, List<Door> doors) {
    doors.add(new Door("list specs", ops -> ops.globalSpecs(SpecStore.SpecFilter.all())));
    doors.add(
        new Door(
            "list measures",
            ops -> measured(ops.listMeasures(SpecStore.SpecFilter.all(), null, null))));
    doors.add(new Door("list rooms", ops -> ops.rooms(null)));
    doors.add(new Door("list runs", ops -> ops.runs(null, null)));
    doors.add(new Door("list projects", Operations::projects));
    doors.add(
        new Door(
            "read changes",
            ops -> {
              var feed = ops.changes();
              var newest = feed.newest();
              var beginning = newest.substring(0, newest.indexOf('.')) + ".0.0";
              return feed.page(ChangeFeed.Ask.of(Map.of("since", beginning, "limit", "500")));
            }));
    doors.add(
        new Door(
            "wake changes",
            ops -> {
              ops.changes().wake();
              return Map.of("woken", true);
            }));
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
      doors.add(new Door("read measures of " + spec, ops -> ops.specMeasures(spec)));
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

  /** A listing of measures as the specs it lists, which name their spec by {@code spec_id}. */
  private static Object measured(Result<SpecMeasuresList> listed) {
    return listed instanceof Result.Success<SpecMeasuresList>(var listing, _)
        ? "SpecMeasuresList "
            + String.join(
                ", ", listing.specs().stream().map(SpecMeasures::specId).sorted().toList())
        : listed;
  }

  private static void runs(World world, List<Door> doors) {
    for (var box : BOXES) {
      for (var run : RUNS) {
        doors.add(
            new Door(
                "read log of " + run + " on " + box + "'s box", ops -> ops.runLog(run, 10, box)));
        doors.add(new Door("stop " + run + " on " + box + "'s box", ops -> ops.stopRun(run, box)));
      }
      var adHoc = "ada's ad-hoc run on bob's box";
      doors.add(
          new Door(
              "read log of " + adHoc + " on " + box + "'s box",
              ops -> ops.runLog(onBobsBox(world), 10, box)));
      doors.add(
          new Door(
              "stop " + adHoc + " on " + box + "'s box",
              ops -> ops.stopRun(onBobsBox(world), box)));
    }
  }

  /**
   * Ada's ad-hoc run, as bob's box ran it: it acts for ada and was executed by bob's box, which is
   * one of its owners too.
   */
  private static String onBobsBox(World world) {
    world.db.execute("UPDATE runs SET node = ? WHERE id = ?", Cast.OTHER, World.ADHOC_RUN);
    return World.ADHOC_RUN;
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
    for (var strategy : Resolution.Strategy.values()) {
      if (strategy != Resolution.Strategy.MERGE) {
        doors.add(
            new Door(
                "resolve an open conflict of spec s-ada as " + strategy,
                ops -> {
                  parkConflict(world, "spec", "s-ada");
                  return ops.resolveConflict("spec", "s-ada", new Resolution(strategy, null));
                }));
      }
    }
    doors.add(
        new Door(
            "resolve an open conflict of file acme/notes.md as MINE",
            ops -> {
              parkConflict(world, "file", "acme/notes.md");
              return ops.resolveConflict(
                  "file", "acme/notes.md", new Resolution(Resolution.Strategy.MINE, null));
            }));
    doors.add(
        new Door(
            "resolve a conflict of spec s-ada parked before conflicts kept main's revision",
            ops -> {
              parkConflict(world, "spec", "s-ada");
              world.db.execute("UPDATE sync_conflicts SET remote_rev = NULL, remote_author = NULL");
              return ops.resolveConflict(
                  "spec", "s-ada", new Resolution(Resolution.Strategy.MINE, null));
            }));
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
    seatings(world, doors);
    terminals(world, doors);
    for (var room : List.of("lobby", "den", "hall", "s-ada", "b-room", "attic")) {
      doors.add(
          new Door(
              "report a room session in " + room,
              ops -> ops.recordRoomConversation(room, "claude-code", "session-1", null, null),
              actor -> actor.lane() == Actor.Lane.CLI || actor.actingFde() == null));
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
      doors.add(
          new Door(
              "create room r-new in " + project + " with an agent seated",
              ops ->
                  ops.createRoom(
                      RoomCreateRequest.fromMap(
                          Map.of(
                              "id",
                              "r-new",
                              "project",
                              project,
                              "title",
                              "t",
                              "member",
                              Map.of("agent", "claude-code"))),
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

  /**
   * Seating and unseating the agent of a spec born in another room, on each box: the world's own
   * {@code s-lobby} and {@code s-den}, unassigned, and one assigned to the box's FDE born in a room
   * of each standing — ada's {@code lobby}, bob's {@code den}, the deleted {@code attic}, and a
   * {@code ghost} this box never held — seeded inside the rehearsal, so the box is left as it was.
   */
  private static void seatings(World world, List<Door> doors) {
    for (var box : BOXES) {
      for (var spec : List.of("s-lobby", "s-den")) {
        seating(doors, spec, box, () -> {});
      }
      for (var room : List.of("lobby", "den", "attic", "ghost")) {
        var spec = box + "-born-in-" + room;
        seating(doors, spec, box, () -> bornIn(world, spec, room, box));
      }
    }
  }

  private static void seating(List<Door> doors, String spec, String box, Runnable seed) {
    doors.add(
        new Door(
            "seat an agent in " + spec + " on " + box + "'s box",
            ops -> {
              seed.run();
              return ops.addRoomMember(
                  spec, EngageRequest.fromMap(Map.of("agent", "claude-code")), box);
            }));
    doors.add(
        new Door(
            "unseat the agent of " + spec + " on " + box + "'s box",
            ops -> {
              seed.run();
              return ops.removeRoomMember(spec, box);
            }));
  }

  /** Spec {@code id}, assigned to and created by {@code fde}, born in {@code room}. */
  private static void bornIn(World world, String id, String room, String fde) {
    Acting.system(
        () ->
            world.box.specs.create(
                new SpecStore.SpecRow(
                        id,
                        "acme",
                        "Born in " + room,
                        SpecStatus.PENDING,
                        fde,
                        null,
                        null,
                        null,
                        null,
                        0,
                        fde,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of())
                    .withRoomId(room)));
  }

  /**
   * A conflict parked on {@code type} {@code id}, seeded inside the rehearsal: this box's version
   * as it holds it, and a version of main's that differs, as a round would park it.
   */
  private static void parkConflict(World world, String type, String id) {
    Acting.system(
        () -> {
          var local = world.store(type).comparableSnapshot(id);
          var remote = new LinkedHashMap<>(local);
          remote.put("_rev", "9-main");
          new SyncConflicts(world.db)
              .record(
                  type,
                  id,
                  null,
                  YamlUtil.dumpJson(local),
                  YamlUtil.dumpJson(remote),
                  "9-main",
                  "main",
                  List.of());
        });
  }

  /** A login session of FDE {@code fde}'s, as the gateway mints one for the pty host. */
  private static String session(World world, String fde) {
    return new AuthSessionStore(world.db)
        .create(new FdeStore(world.db).byHandle(fde).orElseThrow().id(), Duration.ofHours(1))
        .token();
  }

  /**
   * Pinning a terminal session to a room ({@link HostAccess#admit}) as each FDE of the roster, on
   * main, where this box's own FDE is its operator, and on a node, where every FDE has the roster's
   * role: each FDE is the principal the pty host resolves from a session token of theirs, so the
   * door is asked once for each, by the box's operator.
   */
  private static void terminals(World world, List<Door> doors) {
    var configs =
        Map.of(
            "main",
            new SyncConfig(SyncConfig.ROLE_MAIN, null, Cast.OWNER),
            "a node",
            new SyncConfig("node", "sail@main", Cast.OWNER, "ada-box"));
    for (var box : configs.entrySet()) {
      var host = new HostAccess(world.db, new RoleRule(box::getValue, new FdeStore(world.db)));
      for (var fde :
          List.of(Cast.OWNER, Cast.OTHER, Cast.ADMIN, World.VIEWER_FDE, World.SCOPED_FDE)) {
        for (var room : List.of("lobby", "den", "hall", "s-ada", "b-room", "attic")) {
          var project = "b-room".equals(room) ? "beta" : "acme";
          doors.add(
              new Door(
                  "fde " + fde + " pins a terminal to " + room + " on " + box.getKey(),
                  ops -> {
                    host.admit(room, project, host.identity(session(world, fde)));
                    return "admitted";
                  },
                  AccessDoorsBaselineTest::operator));
        }
      }
      for (var fde : List.of(Cast.OWNER, World.VIEWER_FDE)) {
        doors.add(
            new Door(
                "fde " + fde + " pins a terminal to lobby under beta on " + box.getKey(),
                ops -> {
                  host.admit("lobby", "beta", host.identity(session(world, fde)));
                  return "admitted";
                },
                AccessDoorsBaselineTest::operator));
      }
    }
  }

  /** Whether {@code actor} is the box's operator, who asks a door that binds its own FDE. */
  private static boolean operator(Actor actor) {
    return actor.lane() == Actor.Lane.CLI && actor.role() == Role.ADMIN;
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
    } catch (IOException refused) {
      return Corpus.refused(refused);
    } catch (RuntimeException failed) {
      if (failed instanceof Remedied) {
        return Corpus.refused(failed);
      }
      throw failed;
    } catch (Exception failed) {
      throw new IllegalStateException(door.label() + " failed other than by a refusal", failed);
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
