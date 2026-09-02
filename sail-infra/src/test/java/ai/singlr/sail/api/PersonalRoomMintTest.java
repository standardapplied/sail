/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.PersonalRooms;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonalRoomMintTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private SailOperations ops;
  private RoomStore roomStore;

  @BeforeEach
  void setUp() throws Exception {
    var yaml = tempDir.resolve("sail.yaml");
    Files.writeString(yaml, "name: acme\n");
    db = Sqlite.open(tempDir.resolve("rooms.db"));
    new SchemaManager(db).migrate();
    roomStore = new RoomStore(db);
    var fdes = new FdeStore(db);
    fdes.add("uday", "Uday", null, "admin");
    fdes.add("rajesh", "Rajesh", null, "member");
    var projects = new ProjectStore(db);
    projects.upsert("acme", "name: acme\nagent:\n  type: claude-code\n", "uday");
    projects.upsert("nautilus", "name: nautilus\nagent:\n  type: codex\n", "uday");
    ops =
        new SailOperations(
                new ShellExecutor(false),
                yaml.toString(),
                null,
                null,
                new SpecStore(db),
                new ReviewStore(db),
                new RunStore(db),
                projects,
                SyncScheduler.disabled(),
                fdes,
                SessionYield.NONE)
            .useMessages(new MessageStore(db))
            .useRooms(roomStore);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private List<RoomView> rooms(String project, Actor actor) {
    var result = ops.rooms(project, actor);
    assertTrue(result instanceof Result.Success<RoomsListResponse>, result.toString());
    return ((Result.Success<RoomsListResponse>) result).value().rooms();
  }

  @Test
  void theFirstRoomsReadMintsTheReaderPersonalRoomOnceWithTheDefaultAgentSeated() {
    var first = rooms("acme", Actor.cliOperator("rajesh"));
    var second = rooms("acme", Actor.cliOperator("rajesh"));

    assertEquals(1, first.size());
    var personal = first.getFirst();
    assertEquals(PersonalRooms.idOf("rajesh", "acme"), personal.id());
    assertEquals("rajesh", personal.personalOf(), "the wire marks whose personal room it is");
    assertEquals("rajesh", personal.title());
    assertEquals("rajesh", personal.assignee());
    assertEquals("claude-code", personal.members().getFirst().agent());
    assertEquals("on", personal.effectiveWake(), "a solo roster answers a plain message");
    assertEquals(1, second.size(), "the second read mints nothing");
  }

  @Test
  void anUnfilteredReadMintsOnePersonalRoomPerProject() {
    var all = rooms(null, Actor.cliOperator("uday"));

    assertEquals(
        List.of(PersonalRooms.idOf("uday", "acme"), PersonalRooms.idOf("uday", "nautilus")),
        all.stream().map(RoomView::id).toList());
    assertEquals("codex", all.getLast().members().getFirst().agent());
  }

  @Test
  void eachFdeGetsTheirOwnRoomAndPrincipalsGetNone() {
    rooms("acme", Actor.cliOperator("uday"));
    rooms("acme", Actor.cliOperator("rajesh"));
    rooms("acme", Actor.agentPrincipal("claude/room-1", "uday"));
    rooms("acme", new Actor(null, Role.MEMBER, Actor.Lane.API));

    assertEquals(
        List.of("fde-uday-acme", "fde-rajesh-acme"),
        roomStore.list("acme").stream().map(RoomStore.RoomRow::id).toList());
  }
}
