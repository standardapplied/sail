/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.Engagement;
import ai.singlr.sail.config.Roster;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The home room through the real {@link SailOperations}: the apply's room is created as the box's
 * FDE and engaged with the definition's member once, and from then on it is the FDE's.
 */
@ActingAs
class HomeRoomTest {

  private static final String CLAUDE_ONLY =
      """
      name: acme
      agent:
        type: claude-code
      """;

  private static final String CODEX_MEMBER =
      """
      name: acme
      agent:
        type: claude-code
        install: [claude-code, codex]
        member:
          agent: codex
          model: gpt-5-codex
      """;

  private static final String RUNNING_JSON =
      """
      [{"name": "acme", "status": "Running", "state": {}}]
      """;

  @TempDir Path tempDir;
  private Path yaml;
  private Sqlite db;
  private SpecStore specStore;
  private RoomStore roomStore;
  private ScriptedShellExecutor shell;

  @BeforeEach
  void setUp() throws Exception {
    yaml = tempDir.resolve("sail.yaml");
    Files.writeString(yaml, CLAUDE_ONLY);
    db = Sqlite.open(tempDir.resolve("home.db"));
    new SchemaManager(db).migrate();
    specStore = new SpecStore(db);
    roomStore = new RoomStore(db);
    var fdes = new FdeStore(db);
    fdes.add("ada", null, null, "member");
    fdes.add("mady", null, null, "member");
    fdes.add("Ada.L", null, null, "member");
    shell = container(new ScriptedShellExecutor(new ShellExec.Result(1, "", "unscripted")));
  }

  private static ScriptedShellExecutor container(ScriptedShellExecutor shell) {
    return shell
        .onOk("incus list ^acme$", RUNNING_JSON)
        .onOk("cat " + ContainerSailSetup.STAMP_PATH, ContainerSailSetup.fingerprint())
        .onOk("command -v", "/usr/local/bin/agent\n");
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private SailOperations operations() {
    return new SailOperations(
            shell,
            TestProjects.reading(yaml),
            WatcherSpawner::spawnProcess,
            null,
            null,
            specStore,
            new ReviewStore(db),
            new RunStore(db))
        .useMessages(new MessageStore(db))
        .useRooms(roomStore)
        .useLaunchExecutor(Runnable::run);
  }

  private static SailYaml definition(String text) {
    return SailYaml.fromMap(YamlUtil.parseMap(text));
  }

  private static Actor fde(String handle) {
    return new Actor(handle, Role.MEMBER, Actor.Lane.CLI);
  }

  private HomeRoom.Outcome apply(String handle, String text) {
    var ops = operations();
    return Actor.call(fde(handle), () -> HomeRoom.ensure(ops, definition(text), handle));
  }

  private List<Engagement> roster(String id) {
    return Roster.fromJson(roomStore.findById(id).orElseThrow().roster()).members();
  }

  @Test
  void theFirstApplyCreatesTheFdesRoomEngagedWithTheProjectsMember() {
    var outcome = apply("ada", CLAUDE_ONLY);

    assertEquals(new HomeRoom.Outcome("acme--ada", true, true, null), outcome);
    var room = roomStore.findById("acme--ada").orElseThrow();
    assertEquals("acme", room.project());
    assertEquals("acme", room.title());
    assertEquals("ada", room.assignee());
    assertEquals("ada", room.createdBy());
    var member = roster("acme--ada").getFirst();
    assertEquals("claude-code", member.agent());
    assertEquals("full", member.mode());
    assertNull(member.model());
    assertEquals(1, roster("acme--ada").size());
  }

  @Test
  void aSecondApplyChangesNothing() {
    apply("ada", CLAUDE_ONLY);
    var before = roomStore.findById("acme--ada").orElseThrow();

    var outcome = apply("ada", CLAUDE_ONLY);

    assertEquals(new HomeRoom.Outcome("acme--ada", false, true, null), outcome);
    assertEquals(before, roomStore.findById("acme--ada").orElseThrow());
  }

  @Test
  void aRoomTheFdeDisengagedStaysDisengaged() {
    apply("ada", CLAUDE_ONLY);
    var ops = operations();
    var dismissed = Actor.call(fde("ada"), () -> ops.removeRoomMember("acme--ada", "ada"));
    assertTrue(dismissed.isSuccess(), dismissed.toString());

    var outcome = apply("ada", CLAUDE_ONLY);

    assertEquals(new HomeRoom.Outcome("acme--ada", false, false, null), outcome);
    assertTrue(roster("acme--ada").isEmpty());
  }

  @Test
  void theDefinitionsMemberIsSeatedWithItsModel() throws Exception {
    Files.writeString(yaml, CODEX_MEMBER);

    apply("ada", CODEX_MEMBER);

    var member = roster("acme--ada").getFirst();
    assertEquals("codex", member.agent());
    assertEquals("gpt-5-codex", member.model());
    assertEquals("full", member.mode());
  }

  @Test
  void aLaterDefinitionDoesNotRewriteAnEngagedRoster() throws Exception {
    apply("ada", CLAUDE_ONLY);
    Files.writeString(yaml, CODEX_MEMBER);

    var outcome = apply("ada", CODEX_MEMBER);

    assertEquals(new HomeRoom.Outcome("acme--ada", false, true, null), outcome);
    assertEquals("claude-code", roster("acme--ada").getFirst().agent());
  }

  @Test
  void aRefusedMemberCreatesNoRoomAndTheNextApplyCreatesItSeated() {
    shell =
        container(
            new ScriptedShellExecutor(new ShellExec.Result(1, "", "unscripted"))
                .onceOnFail("command -v -- ", "not found"));

    var refused = assertThrows(ApiException.class, () -> apply("ada", CLAUDE_ONLY));

    assertEquals("Agent 'claude-code' is not installed in project 'acme'.", refused.getMessage());
    assertEquals(
        "Add claude-code to sail.yaml's agent.install list and run 'sail project apply acme'.",
        refused.failure().action());
    assertTrue(roomStore.findById("acme--ada").isEmpty(), "a refused member leaves no room");
    assertTrue(
        new ChangeLog(db).head("room", "acme--ada").isEmpty(), "and no revision for sync to carry");

    var outcome = apply("ada", CLAUDE_ONLY);

    assertEquals(new HomeRoom.Outcome("acme--ada", true, true, null), outcome);
    assertEquals("claude-code", roster("acme--ada").getFirst().agent());
  }

  @Test
  void eachFdeGetsTheirOwnRoomAndNeitherMayChangeTheOthers() {
    apply("ada", CLAUDE_ONLY);
    apply("mady", CLAUDE_ONLY);

    assertEquals("mady", roomStore.findById("acme--mady").orElseThrow().assignee());
    var ops = operations();
    var refused = Actor.call(fde("mady"), () -> ops.removeRoomMember("acme--ada", "mady"));
    assertEquals(
        "Room 'acme--ada' belongs to 'ada', whose box serves its agents.", refused.errorMessage());
    assertEquals(1, roster("acme--ada").size());
  }

  @Test
  void aBoxThatNamesNoFdeHasNoHomeRoom() {
    var outcome = HomeRoom.ensure(operations(), definition(CLAUDE_ONLY), " ");

    assertEquals(
        HomeRoom.Outcome.skipped("this box names no FDE, so it has no home room"), outcome);
    assertTrue(roomStore.listAll().isEmpty());
  }

  @Test
  void aDefinitionWithoutAnAgentHasNoHomeRoom() {
    var outcome = apply("ada", "name: acme\n");

    assertEquals(
        HomeRoom.Outcome.skipped("the definition names no agent, so it has no home room"), outcome);
    assertTrue(roomStore.listAll().isEmpty());
  }

  @Test
  void aHandleARoomIdCannotCarryVerbatimStillGetsAHomeRoom() {
    var outcome = apply("Ada.L", CLAUDE_ONLY);

    var id = HomeRoom.idOf("acme", "Ada.L");
    assertEquals(new HomeRoom.Outcome(id, true, true, null), outcome);
    var room = roomStore.findById(id).orElseThrow();
    assertEquals("Ada.L", room.assignee());
    assertEquals("claude-code", roster(id).getFirst().agent());
    assertEquals(new HomeRoom.Outcome(id, false, true, null), apply("Ada.L", CLAUDE_ONLY));
  }

  @Test
  void theLongestProjectAndHandleStillNameARoomOfTheirOwn() {
    var project = "p".repeat(63);
    var handle = "H".repeat(63);
    var other = "H".repeat(62) + "h";

    var id = HomeRoom.idOf(project, handle);

    assertTrue(NameValidator.isValidSpecId(id), id);
    assertTrue(NameValidator.isValidSpecId(HomeRoom.idOf(project, other)));
    assertNotEquals(id, HomeRoom.idOf(project, other));
  }

  @Test
  void aHandleIsFoldedOnlyWhenTheRoomIdCannotCarryIt() {
    assertEquals("acme--ada", HomeRoom.idOf("acme", "ada"));
    assertTrue(HomeRoom.idOf("acme", "Ada.L").matches("home--[0-9a-f]{64}"));
    assertNotEquals(HomeRoom.idOf("acme", "ada_l"), HomeRoom.idOf("acme", "Ada.L"));
    assertNotEquals(HomeRoom.idOf("acme", "Ada.L"), HomeRoom.idOf("acme2", "Ada.L"));
  }

  @Test
  void aHandleThatSpellsTheSeamNeverSharesAnotherPairsId() {
    NameValidator.requireValidProjectName("acme--ada");
    NameValidator.requireValidFdeHandle("ada--bob");

    assertEquals("acme--ada--bob", HomeRoom.idOf("acme--ada", "bob"));
    assertTrue(HomeRoom.idOf("acme", "ada--bob").matches("home--[0-9a-f]{64}"));
    assertEquals("acme---ada", HomeRoom.idOf("acme-", "ada"));
  }

  @Test
  void aFoldedIdIsNeverAnotherHandlesVerbatimId() {
    var folded = HomeRoom.idOf("acme", "Ada.L");
    var literal = folded.substring("home--".length());
    var lookalike = literal.substring(0, 63);

    assertThrows(
        IllegalArgumentException.class, () -> NameValidator.requireValidFdeHandle(literal));
    NameValidator.requireValidFdeHandle(lookalike);
    assertNotEquals(folded, HomeRoom.idOf("home", lookalike));
    assertNotEquals(folded, HomeRoom.idOf("acme", "ada-l-2a8d4330"));
  }

  @Test
  void aRoomSomeoneElseTookUnderTheHomeRoomIdRefusesTheApply() {
    var ops = operations();
    var squat =
        Actor.call(
            fde("mady"),
            () -> ops.createRoom(new RoomCreateRequest("acme--ada", "acme", "Mine", null), "mady"));
    assertTrue(squat.isSuccess(), squat.toString());

    var refused = assertThrows(ApiException.class, () -> apply("ada", CLAUDE_ONLY));

    assertEquals(
        "Room 'acme--ada' belongs to 'mady', not to ada, whose home room it would be.",
        refused.getMessage());
    assertEquals(
        "Have mady or an admin delete room 'acme--ada', then apply again.",
        refused.failure().action());
    assertTrue(roster("acme--ada").isEmpty());
  }

  @Test
  void anIdTakenByASpecRefusesTheApply() {
    Acting.as(
        "ada",
        () ->
            specStore.create(
                new SpecStore.SpecRow(
                    "acme--ada",
                    "acme",
                    "Taken",
                    SpecStatus.DRAFT,
                    "ada",
                    null,
                    null,
                    null,
                    null,
                    0,
                    "ada",
                    "",
                    "",
                    null,
                    List.of(),
                    List.of())));

    var refused = assertThrows(ApiException.class, () -> apply("ada", CLAUDE_ONLY));

    assertEquals("Spec 'acme--ada' already owns that conversation id.", refused.getMessage());
    assertTrue(roomStore.findById("acme--ada").isEmpty());
  }

  @Test
  void aSpecCreatedThroughTheApiUnderTheIdRefusesTheApply() {
    var ops = operations();
    var born =
        Actor.call(
            fde("ada"),
            () ->
                ops.createGlobalSpec(
                    new SpecCreateRequest(
                        "acme--ada",
                        "acme",
                        "Taken",
                        "draft",
                        "ada",
                        null,
                        null,
                        null,
                        null,
                        0,
                        List.of(),
                        List.of(),
                        "",
                        null,
                        null)));
    assertTrue(born.isSuccess(), born.toString());
    assertTrue(roomStore.findById("acme--ada").isPresent(), "the spec's own room");

    var refused = assertThrows(ApiException.class, () -> apply("ada", CLAUDE_ONLY));

    assertEquals("Spec 'acme--ada' already owns that conversation id.", refused.getMessage());
    assertTrue(roster("acme--ada").isEmpty());
  }

  @Test
  void theFdesOwnRoomInAnotherProjectRefusesTheApply() {
    var ops = operations();
    var other =
        Actor.call(
            fde("ada"),
            () -> ops.createRoom(new RoomCreateRequest("acme--ada", "other", "Mine", null), "ada"));
    assertTrue(other.isSuccess(), other.toString());

    var refused = assertThrows(ApiException.class, () -> apply("ada", CLAUDE_ONLY));

    assertEquals(
        "Room 'acme--ada' belongs to project 'other', not 'acme', whose home room it would be.",
        refused.getMessage());
    assertEquals("Free room id 'acme--ada', then apply again.", refused.failure().action());
    assertEquals("other", roomStore.findById("acme--ada").orElseThrow().project());
  }

  @Test
  void aSpecLookupThatFailsRefusesWithItsSentence() {
    var ops =
        new TestOperations() {
          @Override
          public Result<RoomDetailResponse> room(String roomId) {
            return Result.success(
                new RoomDetailResponse(
                    new RoomView(
                        roomId, "acme", "acme", "ada", null, null, List.of(), List.of(), "ada",
                        null, null, null),
                    null,
                    null));
          }

          @Override
          public Result<GlobalSpecDetailResponse> globalSpec(String specId) {
            return Result.failure(ErrorCode.COMMAND_FAILED, "The catalog is locked.", "Retry.");
          }
        };

    var refused =
        assertThrows(
            ApiException.class,
            () ->
                Actor.call(fde("ada"), () -> HomeRoom.ensure(ops, definition(CLAUDE_ONLY), "ada")));

    assertEquals("The catalog is locked.", refused.getMessage());
  }

  @Test
  void aBoxWithoutRoomsRefusesWithTheStoresSentence() {
    var ops =
        new SailOperations(
            shell,
            TestProjects.reading(yaml),
            WatcherSpawner::spawnProcess,
            null,
            null,
            specStore,
            new ReviewStore(db),
            new RunStore(db));

    var refused =
        assertThrows(
            ApiException.class,
            () ->
                Actor.call(fde("ada"), () -> HomeRoom.ensure(ops, definition(CLAUDE_ONLY), "ada")));

    assertEquals(
        "This box keeps no room aggregate, so rooms cannot be served.", refused.getMessage());
  }

  @Test
  void aRoomThatCannotBeReadRefusesWithTheReadsSentenceAndCreatesNothing() {
    var ops =
        new TestOperations() {
          @Override
          public Result<RoomDetailResponse> room(String roomId) {
            return Result.failure(ErrorCode.COMMAND_FAILED, "The catalog is locked.", "Retry.");
          }

          @Override
          public Result<RoomDetailResponse> createRoom(
              RoomCreateRequest request, String localHandle) {
            throw new AssertionError("a room that could not be read is not created");
          }
        };

    var refused =
        assertThrows(
            ApiException.class, () -> HomeRoom.ensure(ops, definition(CLAUDE_ONLY), "ada"));

    assertEquals("The catalog is locked.", refused.getMessage());
    assertEquals("Retry.", refused.failure().action());
  }

  @Test
  void anOutcomeRendersAsTheApplysJsonField() {
    assertEquals(
        Map.of("id", "acme--ada", "created", true, "engaged", false),
        new HomeRoom.Outcome("acme--ada", true, false, null).toMap());
    assertEquals(Map.of("skipped", "no FDE"), HomeRoom.Outcome.skipped("no FDE").toMap());
  }
}
