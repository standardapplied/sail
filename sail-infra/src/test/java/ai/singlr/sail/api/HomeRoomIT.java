/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.Engagement;
import ai.singlr.sail.config.Roster;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.engine.ContainerFilePush;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The home room against <strong>one real incus container</strong>: every admission the apply's
 * engage passes (the container is Running, the member's harness is on the dev user's PATH) is asked
 * of the container itself. A member whose harness is missing leaves no room; once it is installed
 * the next apply creates the room seated, a further apply changes nothing, and a room its FDE
 * disengaged stays disengaged. Fake harness binaries stand in for the agents.
 */
class HomeRoomIT extends AbstractIncusIT {

  private static final String CONTAINER = "sail-it-home-room";
  private static final String HANDLE = "ada";
  private static final String ROOM = CONTAINER + "--" + HANDLE;
  private static final Actor FDE = new Actor(HANDLE, Role.MEMBER, Actor.Lane.CLI);
  private static final String FAKE_AGENT = "#!/usr/bin/env bash\nexit 0\n";

  private Path stateDir;

  @BeforeEach
  void provision() throws Exception {
    ensureIncusOrSkip();
    launchPrepared(CONTAINER);
    var setup =
        exec(
            CONTAINER,
            List.of(
                "bash",
                "-c",
                "set -e; userdel -r ubuntu 2>/dev/null || true;"
                    + " id -u dev >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash dev"));
    assertTrue(setup.ok(), "container provisioning failed: " + setup.stderr());
    ContainerFilePush.push(
        shell, CONTAINER, "/usr/local/bin/claude", FAKE_AGENT, List.of("--mode", "0755"));
    stateDir = Files.createTempDirectory("home-room-it");
  }

  @AfterEach
  void cleanup() {
    deleteContainerQuietly(CONTAINER);
    if (stateDir != null) {
      deleteRecursively(stateDir);
    }
  }

  @Test
  void anApplyEngagesOnceAndThenTheRoomIsTheFdes() throws Exception {
    var text =
        """
        name: %s
        agent:
          type: claude-code
          install: [claude-code, codex]
          member:
            agent: codex
        """
            .formatted(CONTAINER);
    var yaml = stateDir.resolve("sail.yaml");
    Files.writeString(yaml, text);
    var definition = SailYaml.fromMap(YamlUtil.parseMap(text));
    try (var db = Sqlite.open(stateDir.resolve("it.db"))) {
      new SchemaManager(db).migrate();
      new FdeStore(db).add(HANDLE, null, null, "member");
      var rooms = new RoomStore(db);
      var ops =
          new SailOperations(
                  shell,
                  TestProjects.reading(yaml),
                  WatcherSpawner::spawnProcess,
                  null,
                  null,
                  new SpecStore(db),
                  new ReviewStore(db),
                  new RunStore(db))
              .useMessages(new MessageStore(db))
              .useRooms(rooms);

      var missing =
          assertThrows(
              ApiException.class,
              () -> Actor.call(FDE, () -> HomeRoom.ensure(ops, definition, HANDLE)));
      assertEquals(
          "Agent 'codex' is not installed in project '" + CONTAINER + "'.", missing.getMessage());
      assertTrue(rooms.findById(ROOM).isEmpty(), "a refused member leaves no room");

      ContainerFilePush.push(
          shell, CONTAINER, "/usr/local/bin/codex", FAKE_AGENT, List.of("--mode", "0755"));
      assertEquals(
          new HomeRoom.Outcome(ROOM, true, true, null),
          Actor.call(FDE, () -> HomeRoom.ensure(ops, definition, HANDLE)));
      assertEquals("codex", roster(rooms).getFirst().agent());

      var engaged = rooms.findById(ROOM).orElseThrow();
      assertEquals(
          new HomeRoom.Outcome(ROOM, false, true, null),
          Actor.call(FDE, () -> HomeRoom.ensure(ops, definition, HANDLE)));
      assertEquals(engaged, rooms.findById(ROOM).orElseThrow(), "a second apply changes nothing");

      assertTrue(Actor.call(FDE, () -> ops.removeRoomMember(ROOM, HANDLE)).isSuccess());
      assertEquals(
          new HomeRoom.Outcome(ROOM, false, false, null),
          Actor.call(FDE, () -> HomeRoom.ensure(ops, definition, HANDLE)));
      assertTrue(roster(rooms).isEmpty(), "the FDE's disengage wins over the definition");
    }
  }

  private static List<Engagement> roster(RoomStore rooms) {
    return Roster.fromJson(rooms.findById(ROOM).orElseThrow().roster()).members();
  }
}
