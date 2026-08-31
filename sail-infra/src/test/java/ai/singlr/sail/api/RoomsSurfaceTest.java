/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.WatcherSpawner;
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
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rooms surface end-to-end through the real {@link SailOperations}: a chat-only room is
 * created, listed with activity decoration, messaged, and deleted without ever minting a spec — the
 * decouple's core promise — while a room holding specs refuses deletion.
 */
class RoomsSurfaceTest {

  private static final String HANDLE = "uday";

  @TempDir Path tempDir;
  private Sqlite db;
  private SailOperations ops;
  private SpecStore specStore;
  private RoomStore roomStore;

  @BeforeEach
  void setUp() throws Exception {
    var yaml = tempDir.resolve("sail.yaml");
    Files.writeString(yaml, "name: acme\n");
    db = Sqlite.open(tempDir.resolve("rooms.db"));
    new SchemaManager(db).migrate();
    specStore = new SpecStore(db);
    roomStore = new RoomStore(db);
    new FdeStore(db).add(HANDLE, null, null, "admin");
    ops =
        new SailOperations(
                new ShellExecutor(false),
                yaml.toString(),
                WatcherSpawner::spawnProcess,
                null,
                null,
                specStore,
                new ReviewStore(db),
                new RunStore(db))
            .useMessages(new MessageStore(db))
            .useRooms(roomStore)
            .useSessionYield(SessionYield.NONE);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private static Actor admin() {
    return Actor.cliOperator(HANDLE);
  }

  private RoomDetailResponse create(String id, String title) {
    var result =
        ops.createRoom(
            RoomCreateRequest.fromMap(Map.of("id", id, "project", "acme", "title", title))
                .withCreatedBy(HANDLE),
            admin());
    assertTrue(result instanceof Result.Success<RoomDetailResponse>, result.toString());
    return ((Result.Success<RoomDetailResponse>) result).value();
  }

  @Test
  void aChatOnlyRoomLivesWithoutASpecAndNeverTouchesTheBoard() {
    var created = create("design-room", "Design talk");
    assertEquals("design-room", created.room().id());
    assertTrue(created.room().specIds().isEmpty(), "no spec is minted beside a chat room");
    assertTrue(specStore.findById("design-room").isEmpty(), "the board never sees it");

    var posted =
        ops.postRoomMessage(
            "design-room",
            new SpecMessageRequest("hello chat-only world", null, false),
            admin(),
            HANDLE);
    assertTrue(posted instanceof Result.Success<SpecMessageResponse>, posted.toString());

    var messages = ops.roomMessages("design-room", null, null, 10);
    assertEquals(
        1,
        ((Result.Success<SpecMessagesResponse>) messages).value().messages().size(),
        "a room with no spec still holds its conversation");

    var members = ops.roomMembers("design-room");
    assertTrue(
        ((Result.Success<RoomMembersResponse>) members).value().members().isEmpty(),
        "a fresh chat room seats nobody");

    var listed = ops.rooms("acme");
    var view = ((Result.Success<RoomsListResponse>) listed).value();
    assertEquals(1, view.rooms().size());
    assertNotNull(view.latestByRoom().get("design-room"), "the message decorates the rooms list");
  }

  @Test
  void aDuplicateRoomIdIsRefusedAsConflict() {
    create("dup-room", "First");

    var second =
        ops.createRoom(
            RoomCreateRequest.fromMap(Map.of("id", "dup-room", "project", "acme", "title", "Two"))
                .withCreatedBy(HANDLE),
            admin());

    assertTrue(second instanceof Result.Failure<RoomDetailResponse>);
    assertEquals(ErrorCode.CONFLICT, ((Result.Failure<RoomDetailResponse>) second).errorCode());
  }

  @Test
  void aRoomIdAlreadyOwnedByASpecIsRefusedSoTheNewRoomIsNeverShadowed() {
    create("adas-room", "Ada's room");
    specStore.create(
        new SpecStore.SpecRow(
            "auth",
            "acme",
            "Auth",
            ai.singlr.sail.config.SpecStatus.DRAFT,
            HANDLE,
            null,
            null,
            null,
            null,
            0,
            HANDLE,
            "",
            "",
            HANDLE,
            List.of(),
            List.of(),
            "adas-room"));

    var shadowed =
        ops.createRoom(
            RoomCreateRequest.fromMap(Map.of("id", "auth", "project", "acme", "title", "Namesake"))
                .withCreatedBy(HANDLE),
            admin());

    assertTrue(shadowed instanceof Result.Failure<RoomDetailResponse>, shadowed.toString());
    var failure = (Result.Failure<RoomDetailResponse>) shadowed;
    assertEquals(ErrorCode.CONFLICT, failure.errorCode());
    assertTrue(failure.errorMessage().contains("Spec 'auth'"), failure.errorMessage());
    assertTrue(roomStore.findById("auth").isEmpty(), "no unaddressable room is left behind");
  }

  @Test
  void deleteTombstonesAChatRoomButRefusesARoomHoldingSpecs() {
    create("empty-room", "Empty");
    var deleted = ops.deleteRoom("empty-room", admin());
    assertTrue(deleted instanceof Result.Success<RoomDeletedResponse>);
    assertTrue(roomStore.findById("empty-room").isEmpty());
    assertNotNull(roomStore.latestRev("empty-room"), "the deletion tombstones for sync");

    create("busy-room", "Busy");
    var spec =
        ops.createGlobalSpec(
            SpecCreateRequest.fromMap(
                    Map.of(
                        "id", "work", "title", "Work", "project", "acme", "room_id", "busy-room"))
                .withCreatedBy(HANDLE),
            admin());
    assertTrue(spec instanceof Result.Success<GlobalSpecCreatedResponse>, spec.toString());

    var refused = ops.deleteRoom("busy-room", admin());
    assertTrue(refused instanceof Result.Failure<RoomDeletedResponse>);
    assertEquals(ErrorCode.CONFLICT, ((Result.Failure<RoomDeletedResponse>) refused).errorCode());

    var detail = ((Result.Success<RoomDetailResponse>) ops.room("busy-room")).value();
    assertEquals(java.util.List.of("work"), detail.room().specIds());
  }

  @Test
  void invalidCreatesAndMissingRoomsFailLoudly() {
    var noTitle =
        ops.createRoom(
            RoomCreateRequest.fromMap(Map.of("id", "x-room", "project", "acme"))
                .withCreatedBy(HANDLE),
            admin());
    assertEquals(
        ErrorCode.INVALID_REQUEST, ((Result.Failure<RoomDetailResponse>) noTitle).errorCode());

    var badWake =
        ops.createRoom(
            RoomCreateRequest.fromMap(
                    Map.of("id", "y-room", "project", "acme", "title", "Y", "wake", "sometimes"))
                .withCreatedBy(HANDLE),
            admin());
    assertEquals(
        ErrorCode.INVALID_REQUEST, ((Result.Failure<RoomDetailResponse>) badWake).errorCode());

    var missing = ops.room("ghost-room");
    assertEquals(
        ErrorCode.ROOM_NOT_FOUND, ((Result.Failure<RoomDetailResponse>) missing).errorCode());

    var missingPost =
        ops.postRoomMessage(
            "ghost-room", new SpecMessageRequest("hi", null, false), admin(), HANDLE);
    assertEquals(
        ErrorCode.ROOM_NOT_FOUND, ((Result.Failure<SpecMessageResponse>) missingPost).errorCode());
  }

  @Test
  void aValidWakeModeIsAcceptedAndListsAcrossAllProjects() {
    var created =
        ops.createRoom(
            RoomCreateRequest.fromMap(
                    Map.of(
                        "id", "wakey-room", "project", "acme", "title", "Wakey", "wake", "mention"))
                .withCreatedBy(HANDLE),
            admin());
    assertEquals("mention", ((Result.Success<RoomDetailResponse>) created).value().room().wake());

    var all = ops.rooms(null);
    assertEquals(1, ((Result.Success<RoomsListResponse>) all).value().rooms().size());
  }

  @Test
  void aBoxWithoutARoomAggregateRefusesTheRoomsSurfaceLoudly() throws Exception {
    var yaml = tempDir.resolve("sail-norooms.yaml");
    Files.writeString(yaml, "name: acme\n");
    var unwired =
        new SailOperations(
                new ShellExecutor(false),
                yaml.toString(),
                WatcherSpawner::spawnProcess,
                null,
                null,
                specStore,
                new ReviewStore(db),
                new RunStore(db))
            .useMessages(new MessageStore(db));

    assertEquals(
        ErrorCode.COMMAND_FAILED,
        ((Result.Failure<RoomDetailResponse>)
                unwired.createRoom(
                    RoomCreateRequest.fromMap(
                            Map.of("id", "z-room", "project", "acme", "title", "Z"))
                        .withCreatedBy(HANDLE),
                    admin()))
            .errorCode());
    assertEquals(
        ErrorCode.COMMAND_FAILED,
        ((Result.Failure<RoomsListResponse>) unwired.rooms(null)).errorCode());
    assertEquals(
        ErrorCode.COMMAND_FAILED,
        ((Result.Failure<RoomDetailResponse>) unwired.room("any")).errorCode());
    assertEquals(
        ErrorCode.COMMAND_FAILED,
        ((Result.Failure<RoomDeletedResponse>) unwired.deleteRoom("any", admin())).errorCode());
  }

  @Test
  void aBoxWithoutAMessageStoreServesRoomsUndecorated() throws Exception {
    var yaml = tempDir.resolve("sail-nomsg.yaml");
    Files.writeString(yaml, "name: acme\n");
    var quiet =
        new SailOperations(
                new ShellExecutor(false),
                yaml.toString(),
                WatcherSpawner::spawnProcess,
                null,
                null,
                specStore,
                new ReviewStore(db),
                new RunStore(db))
            .useRooms(roomStore);
    create("quiet-room", "Quiet");

    var listed = ((Result.Success<RoomsListResponse>) quiet.rooms("acme")).value();
    assertTrue(listed.latestByRoom().isEmpty(), "no message store, no decoration");
    var detail = ((Result.Success<RoomDetailResponse>) quiet.room("quiet-room")).value();
    assertEquals("quiet-room", detail.room().id());
  }

  @Test
  void roomMessageCursorsAndUnknownDeletesFailLoudly() {
    create("cursor-room", "Cursors");

    var both = ops.roomMessages("cursor-room", "a", "b", 10);
    assertEquals(ErrorCode.BAD_REQUEST, ((Result.Failure<SpecMessagesResponse>) both).errorCode());

    var badCursor = ops.roomMessages("cursor-room", null, "not-a-uuid", 10);
    assertEquals(
        ErrorCode.BAD_REQUEST, ((Result.Failure<SpecMessagesResponse>) badCursor).errorCode());

    var ghostDelete = ops.deleteRoom("ghost-room", admin());
    assertEquals(
        ErrorCode.ROOM_NOT_FOUND, ((Result.Failure<RoomDeletedResponse>) ghostDelete).errorCode());
  }

  @Test
  void blankIdentityFieldsOnCreateAreEachRefused() {
    var blankId = ops.createRoom(new RoomCreateRequest(" ", "acme", "T", null, HANDLE), admin());
    assertEquals(
        ErrorCode.INVALID_REQUEST, ((Result.Failure<RoomDetailResponse>) blankId).errorCode());

    var blankProject =
        ops.createRoom(new RoomCreateRequest("p-room", " ", "T", null, HANDLE), admin());
    assertEquals(
        ErrorCode.INVALID_REQUEST, ((Result.Failure<RoomDetailResponse>) blankProject).errorCode());
  }

  @Test
  void aBoxWithoutAnyStoresAnswersTheRoomDoorWithNotFound() throws Exception {
    var yaml = tempDir.resolve("sail-bare.yaml");
    Files.writeString(yaml, "name: acme\n");
    var bare =
        new SailOperations(
            new ShellExecutor(false),
            yaml.toString(),
            WatcherSpawner::spawnProcess,
            null,
            null,
            null,
            new ReviewStore(db),
            new RunStore(db));
    var posted =
        bare.postRoomMessage(
            "anywhere", new SpecMessageRequest("hi", null, false), admin(), HANDLE);
    assertEquals(
        ErrorCode.ROOM_NOT_FOUND, ((Result.Failure<SpecMessageResponse>) posted).errorCode());

    var specless =
        new SailOperations(
                new ShellExecutor(false),
                yaml.toString(),
                WatcherSpawner::spawnProcess,
                null,
                null,
                null,
                new ReviewStore(db),
                new RunStore(db))
            .useRooms(roomStore);
    create("no-spec-store", "NoSpecs");
    var listed = ((Result.Success<RoomsListResponse>) specless.rooms(null)).value();
    assertTrue(
        listed.rooms().stream().allMatch(view -> view.specIds().isEmpty()),
        "a box without a spec store lists rooms with empty attachments");
  }

  @Test
  void aSpecsIdentityRoomAnswersOnTheRoomDoorEvenBeforeItsRowExists() {
    specStore.create(
        new SpecStore.SpecRow(
            "legacy",
            "acme",
            "Legacy spec",
            ai.singlr.sail.config.SpecStatus.DRAFT,
            HANDLE,
            null,
            null,
            null,
            null,
            0,
            HANDLE,
            "",
            "",
            HANDLE,
            java.util.List.of(),
            java.util.List.of()));

    var posted =
        ops.postRoomMessage(
            "legacy", new SpecMessageRequest("via room door", null, false), admin(), HANDLE);

    assertTrue(
        posted instanceof Result.Success<SpecMessageResponse>,
        "a pre-decouple spec without a room row still answers on the room door");
  }

  @Test
  void deletionNeverOrphansASpecOntoATombstonedRoom() throws Exception {
    for (var i = 0; i < 150; i++) {
      var roomId = "race-room-" + i;
      var specId = "race-spec-" + i;
      create(roomId, "Race");
      var barrier = new CyclicBarrier(2);
      var deleter =
          Thread.ofVirtual()
              .start(
                  () -> {
                    align(barrier);
                    ops.deleteRoom(roomId, admin());
                  });
      var binder =
          Thread.ofVirtual()
              .start(
                  () -> {
                    align(barrier);
                    ops.createGlobalSpec(
                        SpecCreateRequest.fromMap(
                                Map.of(
                                    "id", specId, "title", "Race", "project", "acme", "room_id",
                                    roomId))
                            .withCreatedBy(HANDLE),
                        admin());
                  });
      deleter.join();
      binder.join();
      if (specStore.findById(specId).isPresent()) {
        assertTrue(
            roomStore.findById(roomId).isPresent(),
            "iteration " + i + ": spec '" + specId + "' bound to a room tombstoned underneath it");
      }
    }
  }

  private static void align(CyclicBarrier barrier) {
    try {
      barrier.await();
    } catch (Exception interrupted) {
      throw new IllegalStateException(interrupted);
    }
  }
}
