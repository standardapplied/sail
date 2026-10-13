/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.FileLimits;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.ContentFixtures;
import ai.singlr.sail.store.EraseRequests;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncScope;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A scoped FDE's node over a real session: main sends it exactly the rows of the projects it is
 * granted, refuses whatever it offers outside them without saying what main holds, and the node
 * drops a project the moment main stops naming it. An unscoped node beside it is untouched.
 */
@ActingAs
class ScopedSyncTest {

  private static final String A = "alpha";
  private static final String B = "beta";
  private static final ProjectScope ONLY_A = ProjectScope.of(List.of(A));
  private static final SyncWire.Hello HELLO = SyncWire.Hello.of(SyncWire.UPGRADE_FLOOR, "nia");

  @TempDir Path dir;
  private SyncBox main;
  private SyncBox nia;
  private SyncBox uma;

  @BeforeEach
  void setUp() {
    main = new SyncBox(dir, "main");
    nia = new SyncBox(dir, "nia").syncsAs(scoped(ONLY_A));
    uma = new SyncBox(dir, "uma");
  }

  @AfterEach
  void tearDown() {
    uma.close();
    nia.close();
    main.close();
  }

  @Test
  void aScopedNodeHoldsEveryRowOfItsProjectsAndNoRowOfAnother() {
    seed(main, A, "nia");
    seed(main, B, "uma");

    SyncBox.quiesce(main, nia, uma);

    SyncBox.assertScopedToMain(main, nia, ONLY_A);
    SyncBox.assertEqualToMain(main, uma);
    for (var entity : SyncedEntities.all()) {
      assertEquals(
          new ChangeLog(main.db).maxSeq(entity.type()),
          nia.syncState.checkpoint("main", entity.type()),
          entity.type() + ": a scoped node's checkpoint reaches main's tip");
      assertTrue(count(nia.db, held(entity.type())) > 0, entity.type() + " of alpha arrived");
    }
    assertEquals(0, count(nia.db, "SELECT count(*) FROM specs WHERE project = 'beta'"));
    assertEquals(0, count(nia.db, "SELECT count(*) FROM rooms WHERE project = 'beta'"));
    assertEquals(0, count(nia.db, "SELECT count(*) FROM runs WHERE project = 'beta'"));
    assertEquals(0, count(nia.db, "SELECT count(*) FROM project_files WHERE project = 'beta'"));
    assertEquals(0, count(nia.db, "SELECT count(*) FROM projects WHERE name = 'beta'"));
    assertEquals(0, count(nia.db, "SELECT count(*) FROM reviews WHERE spec_id = 'beta-work'"));
    assertEquals(
        0, count(nia.db, "SELECT count(*) FROM room_messages WHERE room_id = 'beta-work'"));
    assertEquals(ONLY_A, new SyncScope(nia.db).held());
    assertEquals(ProjectScope.all(), new SyncScope(uma.db).held());
  }

  @Test
  void aPageOfNothingButForeignHeadsStillMovesTheCheckpointPastThem() throws Exception {
    for (var i = 0; i < 1000; i++) {
      main.specs.create(spec("b" + i, B, "uma"));
    }
    main.specs.create(spec("a-last", A, "nia"));
    var tip = new ChangeLog(main.db).maxSeq("spec");
    var rowsLoaded = new ArrayList<String>();

    var since = 0L;
    var pages = new ArrayList<SyncWire.Page>();
    SyncWire.Page page;
    do {
      page =
          assertInstanceOf(
              SyncWire.Page.class,
              serve(countingRowLoads(rowsLoaded), HELLO, new SyncWire.Pull("spec", since, 100))
                  .getLast());
      assertTrue(page.next() > since, "every page moves past the heads it skipped");
      since = page.next();
      pages.add(page);
    } while (!page.done());

    assertEquals(11, pages.size());
    assertEquals(
        List.of("a-last"),
        pages.stream().flatMap(p -> p.entries().stream()).map(SyncWire.Entry::id).toList());
    assertEquals(tip, since);
    assertEquals(
        List.of("a-last"),
        rowsLoaded,
        "a thousand foreign heads cost one head scan and no row load");

    SyncBox.quiesce(main, nia);
    assertEquals(
        List.of("a-last"), nia.db.query("SELECT id FROM specs ORDER BY id", row -> row.text(0)));
    assertEquals(tip, nia.syncState.checkpoint("main", "spec"));
  }

  @Test
  void aScopedSessionFetchesOnlyContentMainServedIt() throws Exception {
    seed(main, A, "nia");
    seed(main, B, "uma");
    var ours = bodyHash(main, "alpha-work");
    var theirs = bodyHash(main, "beta-work");

    var refused =
        serve(
                main.server(scoped(ONLY_A)),
                HELLO,
                new SyncWire.Pull("spec", 0, 100),
                new SyncWire.Fetch(List.of(theirs)))
            .getLast();

    assertEquals(
        new SyncWire.Failed(
            "fetch: blob " + theirs + " is not content main served this session", "refused"),
        refused);
    var served =
        serve(
            main.server(scoped(ONLY_A)),
            HELLO,
            new SyncWire.Pull("spec", 0, 100),
            new SyncWire.Fetch(List.of(ours)));
    var manifest = assertInstanceOf(SyncWire.Manifest.class, served.get(2)).manifest();
    assertEquals(ours, manifest.hash());
    var unlisted = new BlobStore(main.db).manifest(theirs).chunkHashes();
    assertEquals(
        new SyncWire.Failed(
            "fetch_chunks: chunk "
                + unlisted.getFirst()
                + " is not content main served this session",
            "refused"),
        serve(
                main.server(scoped(ONLY_A)),
                HELLO,
                new SyncWire.Pull("spec", 0, 100),
                new SyncWire.Fetch(List.of(ours)),
                new SyncWire.FetchChunks(unlisted))
            .getLast());
    assertInstanceOf(
        SyncWire.Manifest.class,
        serve(
                main.server(Actor.sync("uma", Role.MEMBER)),
                HELLO,
                new SyncWire.Fetch(List.of(theirs)))
            .get(1),
        "an unscoped session fetches by hash as it always has");
  }

  @Test
  void aScopedOfferNamesOnlyContentItsSessionSentOrWasServed() throws Exception {
    seed(main, A, "nia");
    seed(main, B, "uma");
    var ours = bodyHash(main, "alpha-work");
    var secret = bodyHash(main, "beta-work");
    var rev = new ChangeLog(main.db).head("spec", "alpha-work").orElseThrow().rev();
    var forged = new LinkedHashMap<>(main.replicas().get("spec").current("alpha-work"));
    forged.put("body_hash", secret);
    var push = new SyncWire.Push("spec", List.of(new MainReplica.Offer("alpha-work", forged, rev)));

    var answered =
        serve(
            main.server(scoped(ONLY_A)),
            HELLO,
            push,
            new SyncWire.Need("spec", List.of("alpha-work")),
            new SyncWire.Fetch(List.of(secret)));

    assertEquals(
        new SyncWire.Refused(
            "alpha-work",
            "This offer names content the session neither sent nor was served; announce it first."),
        only(answered.get(1)));
    assertEquals(ours, bodyHash(main, "alpha-work"), "nothing was written");
    assertEquals(
        new SyncWire.Failed(
            "fetch: blob " + secret + " is not content main served this session", "refused"),
        answered.getLast());

    var manifest = new BlobStore(main.db).manifest(secret);
    var asked =
        serve(
            main.server(scoped(ONLY_A)),
            HELLO,
            new SyncWire.Announce(List.of(secret)),
            new SyncWire.Manifest(manifest),
            new SyncWire.Done(),
            push);
    assertEquals(
        new SyncWire.Lack(List.of(secret)),
        asked.get(1),
        "main asks a scoped session for a blob it holds, as for one it does not");
    assertEquals(
        new SyncWire.Lack(manifest.chunkHashes()),
        asked.get(2),
        "and for every chunk of it, so the bytes are the session's own");
    assertInstanceOf(SyncWire.Failed.class, asked.getLast());
    assertEquals(ours, bodyHash(main, "alpha-work"));

    assertEquals(
        new SyncWire.Lack(List.of()),
        serve(
                main.server(Actor.sync("uma", Role.MEMBER)),
                HELLO,
                new SyncWire.Announce(List.of(secret)))
            .get(1),
        "an unscoped session is asked only for what main lacks, as it always has");
    assertInstanceOf(
        SyncWire.Accepted.class,
        only(serve(main.server(Actor.sync("nia", Role.MEMBER)), HELLO, push).get(1)),
        "and names content by hash as it always has");
  }

  @Test
  void aScopedNodeSendsContentMainAlreadyHoldsAndItsOfferIsTaken() {
    seed(main, A, "nia");
    seed(main, B, "uma");
    SyncBox.quiesce(main, nia);
    var theirs = "a body only " + B + " holds";

    Acting.as("nia", () -> nia.specs.setContent("alpha-work", theirs, "the plan of " + A));
    SyncBox.quiesce(main, nia);

    assertEquals(bodyHash(main, "beta-work"), bodyHash(main, "alpha-work"));

    retitle(nia, "alpha-work");
    SyncBox.quiesce(main, nia);

    assertEquals(
        "retitled",
        main.specs.findById("alpha-work").orElseThrow().title(),
        "an edit that changes no content sends the content it names again");
    assertEquals(bodyHash(main, "beta-work"), bodyHash(main, "alpha-work"));
  }

  @Test
  void aReadOnlyScopedOfferStillReachesMainsDecision() throws Exception {
    seed(main, A, "nia");
    var rev = new ChangeLog(main.db).head("spec", "alpha-work").orElseThrow().rev();
    var offer = main.replicas().get("spec").current("alpha-work");

    var denied =
        only(
            serve(
                    main.server(Actor.sync("nia", Role.VIEWER).withScope(ONLY_A)),
                    HELLO,
                    new SyncWire.Push(
                        "spec", List.of(new MainReplica.Offer("alpha-work", offer, rev))))
                .getLast());

    assertEquals(
        "Your credential is read-only and cannot change specs.",
        assertInstanceOf(SyncWire.Denied.class, denied).reason(),
        "its announcement was answered lacking nothing, so no offer of it names content it sent");
  }

  @Test
  void aSpecMovedIntoTheScopeIsItsOwnersToEditThoughTheRoomOnItsIdIsNot() {
    seed(main, B, "nia");
    Acting.system(() -> main.specs.update(spec("beta-work", A, "nia")));
    SyncBox.quiesce(main, nia);
    assertEquals(0, count(nia.db, "SELECT count(*) FROM rooms"));

    retitle(nia, "beta-work");
    SyncBox.quiesce(main, nia);

    assertEquals("retitled", main.specs.findById("beta-work").orElseThrow().title());
    SyncBox.assertScopedToMain(main, nia, ONLY_A);
  }

  private static void retitle(SyncBox box, String id) {
    var held = box.specs.findById(id).orElseThrow();
    Acting.as(
        held.assignee(),
        () ->
            box.specs.update(
                new SpecStore.SpecRow(
                    held.id(),
                    held.project(),
                    "retitled",
                    held.status(),
                    held.assignee(),
                    held.agent(),
                    held.model(),
                    held.reasoningEffort(),
                    held.branch(),
                    held.priority(),
                    held.createdBy(),
                    held.createdAt(),
                    held.updatedAt(),
                    held.updatedBy(),
                    held.dependsOn(),
                    held.repos(),
                    held.roomId())));
  }

  @Test
  void aVersionMainTookInAProjectSinceRevokedIsNeitherHandedBackNorItsContentServed()
      throws Exception {
    seed(main, A, "nia");
    seed(main, B, "nia");
    SyncBox.quiesce(main, nia.syncsAs(scoped(ProjectScope.of(List.of(A, B)))));
    Acting.as("nia", () -> nia.specs.setContent("alpha-work", "alpha told", "plan"));
    Acting.as("nia", () -> nia.specs.setContent("beta-work", "beta secret", "plan"));
    SyncBox.quiesce(main, nia);
    var told = bodyHash(main, "alpha-work");
    var secret = bodyHash(main, "beta-work");
    Acting.system(
        () -> {
          main.specs.update(spec("beta-work", A, "nia"));
          main.specs.setContent("alpha-work", "alpha moved on", "plan");
          main.specs.setContent("beta-work", "alpha public", "plan");
        });
    var ids = List.of("alpha-work", "beta-work");

    var answered =
        serve(
            main.server(scoped(ONLY_A)),
            HELLO,
            new SyncWire.Need("spec", ids),
            new SyncWire.Fetch(List.of(told)),
            new SyncWire.Fetch(List.of(secret)));

    var page = assertInstanceOf(SyncWire.Page.class, answered.get(1));
    assertEquals(ids, page.entries().stream().map(SyncWire.Entry::id).toList());
    assertEquals(
        List.of("alpha-work"),
        page.accepted().stream().map(SyncWire.Entry::id).toList(),
        "what main took of beta-work was a row of beta");
    assertEquals(
        told, assertInstanceOf(SyncWire.Manifest.class, answered.get(2)).manifest().hash());
    assertEquals(
        new SyncWire.Failed(
            "fetch: blob " + secret + " is not content main served this session", "refused"),
        answered.getLast());
    assertEquals(
        ids,
        assertInstanceOf(
                SyncWire.Page.class,
                serve(
                        main.server(Actor.sync("nia", Role.MEMBER)),
                        HELLO,
                        new SyncWire.Need("spec", ids))
                    .get(1))
            .accepted()
            .stream()
            .map(SyncWire.Entry::id)
            .toList(),
        "an unscoped session is handed what main took as it always has");
  }

  @Test
  void whatWasErasedOrDeletedBeforeTheNodeFirstSyncedStillLeavesWithItsProject() {
    seed(main, A, "nia");
    seed(main, B, "uma");
    main.specs.create(spec("alpha-short-lived", A, "nia"));
    Acting.as("nia", () -> main.specs.delete("alpha-short-lived"));
    archive(main, "alpha-work");
    var erased = prune(main, "alpha-work");

    SyncBox.quiesce(main, nia.syncsAs(scoped(ProjectScope.of(List.of(A, B)))));

    for (var target : erased.entities()) {
      assertTrue(new ChangeLog(nia.db).isErased(target.type(), target.id()), target.toString());
      assertEquals(
          A,
          new ChangeLog(nia.db).headProject(target.type(), target.id()).orElse(null),
          target + ": an erasure this box only heard of is filed under the project main names");
    }
    assertEquals(A, new ChangeLog(nia.db).headProject("spec", "alpha-short-lived").orElse(null));

    SyncBox.quiesce(main, nia.syncsAs(scoped(ProjectScope.of(List.of(B)))));

    assertEquals(
        0,
        count(nia.db, "SELECT count(*) FROM change_heads WHERE project IS NOT 'beta'"),
        "nothing of alpha stays, what this box never held live included");
    assertEquals(0, count(nia.db, "SELECT count(*) FROM change_log WHERE entity_id LIKE 'alpha%'"));
    SyncBox.assertScopedToMain(main, nia, ProjectScope.of(List.of(B)));
  }

  @Test
  void aScopedNodesOwnWorkInItsProjectIsCommittedAsToday() {
    seed(main, A, "nia");
    SyncBox.quiesce(main, nia);

    nia.create(spec("mine", A, "nia"));
    Acting.as("nia", () -> nia.specs.setContent("mine", "a body nia wrote", "her plan"));
    SyncBox.quiesce(main, nia);

    assertEquals("nia", main.specs.findById("mine").orElseThrow().createdBy());
    assertEquals("a body nia wrote", main.specs.getContent("mine").orElseThrow().body());
    SyncBox.assertScopedToMain(main, nia, ONLY_A);
  }

  @Test
  void aRowPushedIntoAnotherProjectIsDeniedNamingItsIdAndNothingIsWritten() throws Exception {
    seed(main, B, "uma");
    var before = count(main.db, "SELECT count(*) FROM change_log");
    var forged = snapshot(nia, () -> nia.create(spec("forged", B, "nia")), "spec", "forged");

    var denied =
        only(
            serve(
                    main.server(scoped(ONLY_A)),
                    HELLO,
                    new SyncWire.Push(
                        "spec", List.of(new MainReplica.Offer("forged", forged, null))))
                .getLast());

    assertEquals(
        new SyncWire.Denied(
            "forged", "Spec 'forged' is in no project this FDE is granted.", null, null, true),
        denied);
    assertEquals(before, count(main.db, "SELECT count(*) FROM change_log"));
    assertTrue(main.specs.findById("forged").isEmpty());
  }

  @Test
  void aProjectRowOutsideTheScopeIsDeniedAndOneInsideIsTaken() throws Exception {
    var definition = Map.<String, Object>of("definition", "name: any\n");

    var results =
        assertInstanceOf(
                SyncWire.Results.class,
                serve(
                        main.server(scoped(ONLY_A)),
                        HELLO,
                        new SyncWire.Push(
                            "project",
                            List.of(
                                new MainReplica.Offer(B, definition, null),
                                new MainReplica.Offer(A, definition, null))))
                    .getLast())
            .results();

    assertEquals(
        new SyncWire.Denied(
            B, "Project 'beta' is in no project this FDE is granted.", null, null, true),
        results.get(0));
    assertInstanceOf(SyncWire.Accepted.class, results.get(1));
    assertTrue(new ProjectStore(main.db).findByName(B).isEmpty());
    assertTrue(new ProjectStore(main.db).findByName(A).isPresent());
  }

  @Test
  void aGrantShowsAfterTheNextRoundAndWhatWasHeldReAdoptsUnwritten() {
    seed(main, A, "nia");
    seed(main, B, "uma");
    SyncBox.quiesce(main, nia);
    var entriesOfA = count(nia.db, "SELECT count(*) FROM change_log");
    var both = ProjectScope.of(List.of(A, B));

    var granted = SyncBox.roundSettling(main, nia.syncsAs(scoped(both)));

    assertTrue(granted.settled().isEmpty(), "a grant drops nothing");
    assertTrue(
        granted.types().stream().allMatch(report -> report.failure() == null), granted.toString());
    SyncBox.assertScopedToMain(main, nia, both);
    assertEquals("the beta spec", nia.specs.findById("beta-work").orElseThrow().title());
    assertEquals(
        entriesOfA,
        count(
            nia.db,
            "SELECT count(*) FROM change_log l JOIN change_heads h ON h.entity_type ="
                + " l.entity_type AND h.entity_id = l.entity_id WHERE h.project = 'alpha'"),
        "alpha's rows were walked again and not one was rewritten");
    assertEquals(both, new SyncScope(nia.db).held());
    SyncBox.quiesce(main, nia);
  }

  @Test
  void aRevocationDropsTheProjectAtTheStartOfTheRoundUnpushedWorkIncluded() {
    seed(main, A, "nia");
    seed(main, B, "uma");
    var both = ProjectScope.of(List.of(A, B));
    SyncBox.quiesce(main, nia.syncsAs(scoped(both)));
    Acting.as("nia", () -> nia.specs.setContent("alpha-work", "an edit main never saw", "plan"));
    new EraseRequests(nia.db).request("spec", "alpha-work", "nia");
    var body = bodyHash(nia, "alpha-work");
    var mainsBody = main.specs.getContent("alpha-work").orElseThrow().body();
    var held = count(nia.db, "SELECT count(*) FROM change_heads WHERE project = 'alpha'");

    var revoked = SyncBox.roundSettling(main, nia.syncsAs(scoped(ProjectScope.of(List.of(B)))));

    assertEquals(
        List.of(
            new Settlement.Settled(
                "project",
                A,
                new Settlement.How.Dropped((int) held),
                "main no longer grants this box the project")),
        revoked.settled());
    assertEquals(
        "project alpha: dropped from this box with the "
            + held
            + " rows it held here, unpushed work included — main no longer grants this box the"
            + " project",
        revoked.settled().getFirst().describe());
    for (var table :
        List.of(
            "specs WHERE project = 'alpha'",
            "spec_content WHERE spec_id = 'alpha-work'",
            "rooms WHERE project = 'alpha'",
            "room_messages WHERE room_id = 'alpha-work'",
            "runs WHERE project = 'alpha'",
            "reviews WHERE spec_id = 'alpha-work'",
            "project_files WHERE project = 'alpha'",
            "projects WHERE name = 'alpha'",
            "change_heads WHERE project = 'alpha'",
            "change_log WHERE entity_id LIKE 'alpha%'",
            "sync_offers",
            "erase_requests",
            "sync_conflicts")) {
      assertEquals(0, count(nia.db, "SELECT count(*) FROM " + table), table);
    }
    assertFalse(new BlobStore(nia.db).has(body), "the round's collection freed alpha's content");
    assertEquals(mainsBody, main.specs.getContent("alpha-work").orElseThrow().body());
    SyncBox.assertScopedToMain(main, nia, ProjectScope.of(List.of(B)));
    SyncBox.quiesce(main, nia);
  }

  @Test
  void aRevocationTakesWhatMainFilesUnderTheProjectAndLeavesWhatAMovedSpecLeftInAnother() {
    seed(main, A, "nia");
    seed(main, B, "uma");
    Acting.system(() -> main.specs.update(spec("beta-work", A, "uma")));
    Acting.system(() -> main.specs.update(spec("alpha-work", B, "nia")));
    SyncBox.quiesce(main, nia.syncsAs(scoped(ProjectScope.of(List.of(A, B)))));
    var left =
        List.of(
            "specs WHERE id = '%s-work'",
            "rooms WHERE id = '%s-work'",
            "room_messages WHERE room_id = '%s-work'",
            "runs WHERE spec_id = '%s-work'",
            "reviews WHERE spec_id = '%s-work'");
    assertEquals(List.of(1L, 1L, 2L, 1L, 1L), held(left, "alpha"));
    assertEquals(List.of(1L, 1L, 2L, 1L, 1L), held(left, "beta"));

    SyncBox.roundSettling(main, nia.syncsAs(scoped(ProjectScope.of(List.of(B)))));

    assertEquals(
        List.of(1L, 0L, 0L, 0L, 0L),
        held(left, "alpha"),
        "the spec moved to beta stays, and what it left in alpha goes with alpha");
    assertEquals(
        List.of(0L, 1L, 2L, 1L, 1L),
        held(left, "beta"),
        "the spec moved to alpha goes, and what it left in beta stays");
    assertEquals(0, count(nia.db, "SELECT count(*) FROM change_heads WHERE project = 'alpha'"));
    SyncBox.assertScopedToMain(main, nia, ProjectScope.of(List.of(B)));
    SyncBox.quiesce(main, nia);
    SyncBox.assertScopedToMain(main, nia, ProjectScope.of(List.of(B)));
  }

  @Test
  void aReplyIsFiledWithTheMessageItAnswersSoAThreadArrivesAndLeavesWhole() {
    seed(main, A, "nia");
    seed(main, B, "nia");
    var messages = new MessageStore(main.db);
    var asked = messages.append("beta-work", "nia", "asked in beta", null);
    moveRoom(main, "beta-work", A);
    var answered = messages.append("beta-work", "nia", "answered after the move", asked.id());
    var further = messages.append("beta-work", "nia", "and again", answered.id());
    var fresh = messages.append("beta-work", "nia", "said after the move", null);
    var thread = List.of(asked.id(), answered.id(), further.id());
    var changes = new ChangeLog(main.db);
    for (var id : thread) {
      assertEquals(
          B, changes.headProject("message", id).orElseThrow(), "a thread is one project's");
    }
    assertEquals(A, changes.headProject("message", fresh.id()).orElseThrow());

    SyncBox.quiesce(main, nia);

    assertEquals(List.of(fresh.id()), movedRoomTalk(nia), "a node of alpha alone");
    SyncBox.assertScopedToMain(main, nia, ONLY_A);

    SyncBox.quiesce(main, nia.syncsAs(scoped(ProjectScope.of(List.of(A, B)))));

    assertEquals(List.of(asked.id(), answered.id(), further.id(), fresh.id()), movedRoomTalk(nia));

    SyncBox.quiesce(main, nia.syncsAs(scoped(ONLY_A)));

    assertEquals(List.of(fresh.id()), movedRoomTalk(nia), "beta revoked: the thread left with it");
    SyncBox.assertScopedToMain(main, nia, ONLY_A);

    var onlyB = ProjectScope.of(List.of(B));
    SyncBox.quiesce(main, uma.syncsAs(Actor.sync("uma", Role.MEMBER).withScope(onlyB)));

    assertEquals(thread, movedRoomTalk(uma), "a node of beta alone holds the thread and no room");
    SyncBox.assertScopedToMain(main, uma, onlyB);
  }

  private static List<String> movedRoomTalk(SyncBox box) {
    return box.db.query(
        "SELECT id FROM room_messages WHERE room_id = 'beta-work'"
            + " AND body NOT LIKE 'a % in beta' ORDER BY id",
        row -> row.text(0));
  }

  private static void moveRoom(SyncBox box, String id, String project) {
    var rooms = new RoomStore(box.db);
    var moved = new LinkedHashMap<>(rooms.comparableSnapshot(id));
    moved.put("project", project);
    Acting.system(
        () ->
            rooms.commitRevision(
                id,
                moved,
                new ChangeLog(box.db).head("room", id).orElseThrow().rev(),
                rooms.authority()));
  }

  @Test
  void aSpecMovedIntoTheScopeComesWithoutItsEarlierReviewAndIsNotTheNodesToErase()
      throws Exception {
    seed(main, A, "nia");
    seed(main, B, "uma");
    Acting.system(() -> main.specs.update(spec("beta-work", A, "nia")));
    archive(main, "beta-work");
    var review =
        main.db
            .queryOne("SELECT id FROM reviews WHERE spec_id = 'beta-work'", row -> row.text(0))
            .orElseThrow();
    Acting.system(() -> new ReviewStore(main.db).supersedeForSpec("beta-work"));
    assertEquals(
        Optional.of(B),
        new ChangeLog(main.db).headProject("review", review),
        "rewritten after the move, the review is still filed where it was written");

    SyncBox.quiesce(main, nia);

    assertEquals(1, count(nia.db, "SELECT count(*) FROM specs WHERE id = 'beta-work'"));
    assertEquals(0, count(nia.db, "SELECT count(*) FROM reviews WHERE spec_id = 'beta-work'"));
    SyncBox.assertScopedToMain(main, nia, ONLY_A);
    var before = count(main.db, "SELECT count(*) FROM change_log");
    var erase = new SyncWire.Push("spec", List.of(MainReplica.Offer.erasure("beta-work")));
    assertEquals(
        new SyncWire.Refused(
            "beta-work", "Pruning spec 'beta-work' would erase work outside your project scope."),
        only(serve(main.server(scoped(ONLY_A)), HELLO, erase).getLast()));
    assertEquals(before, count(main.db, "SELECT count(*) FROM change_log"));
    assertEquals(1, count(main.db, "SELECT count(*) FROM reviews WHERE id = ?", review));

    assertInstanceOf(
        SyncWire.Accepted.class,
        only(serve(main.server(Actor.sync("nia", Role.MEMBER)), HELLO, erase).getLast()),
        "the same owner, unscoped, erases the spec and all that belongs to it");
    assertTrue(new ChangeLog(main.db).isErased("review", review));
  }

  private List<Long> held(List<String> tables, String work) {
    return tables.stream()
        .map(table -> count(nia.db, "SELECT count(*) FROM " + table.formatted(work)))
        .toList();
  }

  @Test
  void aProjectWithARunUnderWayHereIsKeptUntilTheRunEnds() {
    seed(main, A, "nia");
    SyncBox.quiesce(main, nia);
    var run = DateTimeUtils.newId().toString();
    Acting.as(
        "nia",
        () ->
            new RunStore(nia.db)
                .create(
                    run,
                    A,
                    "alpha-work",
                    "nia",
                    "build",
                    "claude",
                    "b",
                    "t",
                    null,
                    null,
                    "/l",
                    "u"));

    var kept = SyncBox.roundSettling(main, nia.syncsAs(scoped(ProjectScope.none())));

    assertEquals(
        List.of(
            new Settlement.Settled(
                "project",
                A,
                new Settlement.How.Kept(run),
                "main no longer grants this box the project")),
        kept.settled().stream().filter(left -> "project".equals(left.type())).toList());
    assertEquals(
        "project alpha: kept this round, run '"
            + run
            + "' being still under way here; dropped on the first round after it ends — main no"
            + " longer grants this box the project",
        kept.settled().getFirst().describe());
    assertTrue(nia.specs.findById("alpha-work").isPresent());
    assertTrue(new RunStore(nia.db).findById(run).isPresent());

    Acting.system(() -> new RunStore(nia.db).complete(run, "completed", 0));
    var dropped = SyncBox.roundSettling(main, nia);

    assertInstanceOf(Settlement.How.Dropped.class, dropped.settled().getFirst().how());
    assertEquals(0, count(nia.db, "SELECT count(*) FROM change_log"));
    assertTrue(new RunStore(main.db).findById(run).isEmpty(), "nothing of it was offered");
  }

  @Test
  void anErasureReachesTheNodesThatHeldTheRowAndNoOther() {
    seed(main, A, "nia");
    seed(main, B, "uma");
    archive(main, "alpha-work");
    archive(main, "beta-work");
    SyncBox.quiesce(main, nia, uma);

    var ours = prune(main, "alpha-work");
    var theirs = prune(main, "beta-work");
    SyncBox.quiesce(main, nia, uma);

    for (var target : ours.entities()) {
      assertEquals(
          A,
          new ChangeLog(main.db).headProject(target.type(), target.id()).orElse(null),
          target + ": an erasure keeps the project its head carried");
      assertTrue(new ChangeLog(nia.db).isErased(target.type(), target.id()), target.toString());
    }
    for (var target : theirs.entities()) {
      assertEquals(
          0,
          count(
              nia.db,
              "SELECT count(*) FROM change_log WHERE entity_type = ? AND entity_id = ?",
              target.type(),
              target.id()),
          target + " never crossed to a node outside its project");
      assertTrue(new ChangeLog(uma.db).isErased(target.type(), target.id()), target.toString());
    }
    SyncBox.assertScopedToMain(main, nia, ONLY_A);
    SyncBox.assertEqualToMain(main, uma);
  }

  @Test
  void aNodesOwnPruneIsErasedInsideItsScopeAndRefusedOutsideIt() throws Exception {
    seed(main, A, "nia");
    seed(main, B, "uma");
    archive(main, "alpha-work");
    archive(main, "beta-work");
    SyncBox.quiesce(main, nia);

    new EraseRequests(nia.db).request("spec", "alpha-work", "nia");
    SyncBox.quiesce(main, nia);

    assertTrue(new ChangeLog(main.db).isErased("spec", "alpha-work"));
    assertTrue(new ChangeLog(nia.db).isErased("spec", "alpha-work"));
    assertEquals(
        new SyncWire.Refused(
            "beta-work",
            "main holds no spec 'beta-work', so it cannot tell whose it is; sync it before"
                + " pruning"),
        only(
            serve(
                    main.server(scoped(ONLY_A)),
                    HELLO,
                    new SyncWire.Push("spec", List.of(MainReplica.Offer.erasure("beta-work"))))
                .getLast()));
    assertFalse(new ChangeLog(main.db).isErased("spec", "beta-work"));
    prune(main, "beta-work");
    assertEquals(
        new SyncWire.Refused(
            "beta-work",
            "main holds no spec 'beta-work', so it cannot tell whose it is; sync it before"
                + " pruning"),
        only(
            serve(
                    main.server(scoped(ONLY_A)),
                    HELLO,
                    new SyncWire.Push("spec", List.of(MainReplica.Offer.erasure("beta-work"))))
                .getLast()),
        "an erasure outside the scope is not answered either");
  }

  @Test
  void aRowStillHeldAfterARevokeIsWithdrawnWithoutMainsVersionCrossing() throws Exception {
    seed(main, A, "nia");
    seed(main, B, "uma");
    SyncBox.quiesce(main, nia.syncsAs(scoped(ProjectScope.of(List.of(A, B)))));
    Acting.as("uma", () -> nia.specs.setContent("beta-work", "a late edit", "plan"));
    var body = bodyHash(nia, "beta-work");
    var mains = new ChangeLog(main.db).head("spec", "beta-work").orElseThrow();
    nia.syncsAs(scoped(ONLY_A));

    List<SyncSession.TypeReport> reports;
    String wire;
    try (var link = SyncBox.connect(main.server(nia.session()), nia)) {
      reports = new ArrayList<>();
      for (var entity : SyncedEntities.all()) {
        reports.add(link.reconcile(entity.type(), nia.replicas().get(entity.type())));
      }
      wire = link.log().toString();
    }

    assertEquals(
        List.of(
            new SyncSession.Denial(
                "spec", "beta-work", "Spec 'beta-work' is in no project this FDE is granted.")),
        reports.stream().flatMap(report -> report.denials().stream()).toList());
    assertTrue(wire.contains("\"op\": \"need\""), "the node asked after the row it changed");
    assertTrue(nia.specs.findById("beta-work").isEmpty(), "the node withdrew its row");
    var withdrawal = new ChangeLog(nia.db).head("spec", "beta-work").orElseThrow();
    assertEquals(ChangeLog.Kind.TOMBSTONE, withdrawal.kind());
    assertEquals(ChangeLog.Entry.DENIED, withdrawal.origin());
    assertEquals(mains, new ChangeLog(main.db).head("spec", "beta-work").orElseThrow());

    var again = SyncBox.roundSettling(main, nia);

    assertTrue(
        again.types().stream().allMatch(report -> report.denials().isEmpty()),
        "a withdrawal is never offered again");
    assertFalse(new BlobStore(nia.db).has(body), "what the withdrawal kept left with its project");
    SyncBox.assertScopedToMain(main, nia, ONLY_A);
  }

  @Test
  void anErasedIdAndAMessageOutsideTheScopeAreDeniedBeforeAnythingIsReadOfThem() throws Exception {
    seed(main, B, "uma");
    archive(main, "beta-work");
    var message =
        Map.<String, Object>of(
            "room_id", "beta-work", "author", "nia", "body", "hello", "created_at", "t");
    var messageId = DateTimeUtils.newId().toString();
    var copy = snapshot(nia, () -> nia.create(spec("beta-work", B, "nia")), "spec", "beta-work");
    prune(main, "beta-work");
    var conflicts = count(main.db, "SELECT count(*) FROM sync_conflicts");

    var spec =
        only(
            serve(
                    main.server(scoped(ONLY_A)),
                    HELLO,
                    new SyncWire.Push(
                        "spec", List.of(new MainReplica.Offer("beta-work", copy, "9-stale"))))
                .getLast());
    var post =
        only(
            serve(
                    main.server(scoped(ONLY_A)),
                    HELLO,
                    new SyncWire.Push(
                        "message", List.of(new MainReplica.Offer(messageId, message, "9-stale"))))
                .getLast());

    assertEquals(
        new SyncWire.Denied(
            "beta-work",
            "Spec 'beta-work' is in no project this FDE is granted.",
            null,
            null,
            true),
        spec);
    assertEquals(
        new SyncWire.Denied(
            messageId,
            "Message '" + messageId + "' is in no project this FDE is granted.",
            null,
            null,
            true),
        post);
    assertEquals(conflicts, count(main.db, "SELECT count(*) FROM sync_conflicts"));
  }

  @Test
  void aRowIsBornNamingOnlyWhatItsPusherMaySee() throws Exception {
    seed(main, A, "nia");
    seed(main, B, "uma");
    new RoomStore(main.db).create(room("beta-own", B, "nia"));
    main.specs.create(spec("beta-roomless", B, "uma"));
    var spec = snapshot(nia, () -> nia.create(spec("mine", A, "nia")), "spec", "mine");
    var room =
        snapshot(
            nia,
            () -> Acting.as("nia", () -> new RoomStore(nia.db).create(room("mine", A, "nia"))),
            "room",
            "mine");
    var runId = DateTimeUtils.newId().toString();
    var run =
        snapshot(
            nia,
            () ->
                new RunStore(nia.db)
                    .create(
                        runId, A, "mine", "nia", "build", "claude", "b", "t", null, null, "/l",
                        "u"),
            "run",
            runId);
    var heads = count(main.db, "SELECT count(*) FROM change_heads");

    assertEquals(
        "Room 'beta-work' was not found.",
        born("spec", "mine", with(spec, "room_id", "beta-work")),
        "a room of beta, owned by uma");
    assertEquals(
        "Room 'beta-roomless' was not found.",
        born("spec", "mine", with(spec, "room_id", "beta-roomless")),
        "a conversation of beta that is a spec's alone");
    assertEquals(
        "Spec 'beta-own' already exists.",
        born("spec", "beta-own", with(spec, "room_id", "beta-own")),
        "the pusher's own room, in beta: its id is taken and it is not adopted");
    assertEquals(
        "Room 'beta-roomless' already exists.",
        born("room", "beta-roomless", room),
        "a room over the id of beta's spec");
    assertEquals(
        "Spec 'beta-work' was not found.", born("run", runId, with(run, "spec_id", "beta-work")));
    assertEquals(
        "Room 'beta-own' was not found.",
        born("run", runId, with(with(run, "spec_id", null), "room_id", "beta-own")));
    assertEquals(heads, count(main.db, "SELECT count(*) FROM change_heads"), "nothing was written");

    assertEquals(
        "room 'nowhere', which spec 'mine' is born in, is not held here yet; it syncs in its own"
            + " page, so the next round settles this",
        born("spec", "mine", with(spec, "room_id", "nowhere")),
        "a room nobody holds is still awaited");
    assertEquals(
        null,
        born("run", runId, with(run, "spec_id", "unheard-of")),
        "a run of a spec main has not heard of is taken as it always was");
    assertEquals(null, born("room", "mine", room));
    assertEquals(null, born("spec", "mine", spec));
  }

  /**
   * Why main denies or refuses a scoped pusher the birth of {@code state} as {@code id}, null when
   * it takes it. The session first sends the content the state names, none of it more than a
   * manifest.
   */
  private String born(String type, String id, Map<String, Object> state) throws IOException {
    var requests = new ArrayList<SyncWire.Request>(List.of(HELLO));
    var content =
        BlobStore.referenced(
            List.of(state), SyncedEntities.require(type).store(nia.db).contentFields());
    if (!content.isEmpty()) {
      requests.add(new SyncWire.Announce(List.copyOf(content)));
      content.forEach(
          hash -> requests.add(new SyncWire.Manifest(new BlobStore(nia.db).manifest(hash))));
      requests.add(new SyncWire.Done());
      requests.add(new SyncWire.Done());
    }
    requests.add(new SyncWire.Push(type, List.of(new MainReplica.Offer(id, state, null))));
    var result =
        only(
            serve(main.server(scoped(ONLY_A)), requests.toArray(SyncWire.Request[]::new))
                .getLast());
    return switch (result) {
      case SyncWire.Denied denied -> denied.reason();
      case SyncWire.Refused refused -> refused.reason();
      default -> accepted(result);
    };
  }

  private static String accepted(SyncWire.Result result) {
    assertInstanceOf(SyncWire.Accepted.class, result);
    return null;
  }

  private static Map<String, Object> with(Map<String, Object> state, String field, Object value) {
    var changed = new LinkedHashMap<>(state);
    changed.put(field, value);
    return changed;
  }

  private static RoomStore.RoomRow room(String id, String project, String owner) {
    return new RoomStore.RoomRow(
        id, project, "the " + id + " room", owner, null, null, owner, null, null, owner);
  }

  @Test
  void anIdTakenInAnotherProjectIsDeniedAsExistingAndTheNodeWithdrawsItsOwn() {
    seed(main, A, "nia");
    seed(main, B, "uma");
    SyncBox.quiesce(main, nia);
    nia.create(spec("beta-work", A, "nia"));
    var mains = new ChangeLog(main.db).head("spec", "beta-work").orElseThrow();

    var round = SyncBox.roundSettling(main, nia);

    var denial =
        round.types().stream().flatMap(report -> report.denials().stream()).toList().getFirst();
    assertEquals(
        new SyncSession.Denial("spec", "beta-work", "Spec 'beta-work' already exists."), denial);
    assertEquals(
        "spec beta-work: main denied this change — Spec 'beta-work' already exists. Yours is in"
            + " its history: sail spec history beta-work.",
        denial.describe());
    assertTrue(nia.specs.findById("beta-work").isEmpty());
    assertEquals(mains, new ChangeLog(main.db).head("spec", "beta-work").orElseThrow());
    assertEquals("the beta spec", main.specs.findById("beta-work").orElseThrow().title());
    SyncBox.quiesce(main, nia);
  }

  @Test
  void aDenialByAnotherRuleCarriesMainsVersionOnlyInsideTheScope() throws Exception {
    seed(main, A, "uma");
    seed(main, B, "uma");
    var rev = main.specs.findById("alpha-work").orElseThrow();
    var mine = snapshot(main, () -> {}, "spec", "alpha-work");
    var edited = new LinkedHashMap<>(mine);
    edited.put("title", "taken over");
    var head = new ChangeLog(main.db).head("spec", "alpha-work").orElseThrow();

    var inside =
        assertInstanceOf(
            SyncWire.Denied.class,
            only(
                serve(
                        main.server(scoped(ONLY_A)),
                        HELLO,
                        new SyncWire.Need("spec", List.of("alpha-work")),
                        new SyncWire.Push(
                            "spec",
                            List.of(new MainReplica.Offer("alpha-work", edited, head.rev()))))
                    .getLast()));

    assertEquals("Spec 'alpha-work' is assigned to 'uma', not you.", inside.reason());
    assertEquals(head.rev(), inside.rev());
    assertEquals(rev.title(), inside.snapshot().get("title"));
    var outside =
        only(
            serve(
                    main.server(scoped(ONLY_A)),
                    HELLO,
                    new SyncWire.Push(
                        "spec", List.of(new MainReplica.Offer("beta-work", edited, head.rev()))))
                .getLast());
    assertEquals(
        new SyncWire.Denied("beta-work", "Spec 'beta-work' already exists.", null, null, true),
        outside);
  }

  @Test
  void aScopedFdeWithNoGrantAndASessionWithNoFdeReceiveNothing() throws Exception {
    seed(main, A, "nia");
    seed(main, B, "uma");

    SyncBox.quiesce(main, nia.syncsAs(scoped(ProjectScope.none())));

    assertEquals(0, count(nia.db, "SELECT count(*) FROM change_log"));
    assertEquals(ProjectScope.none(), new SyncScope(nia.db).held());
    for (var entity : SyncedEntities.all()) {
      var page =
          assertInstanceOf(
              SyncWire.Page.class,
              serve(
                      main.server(Actor.sync(null, Role.VIEWER).withScope(ProjectScope.none())),
                      SyncWire.Hello.of(SyncWire.UPGRADE_FLOOR, "anon"),
                      new SyncWire.Pull(entity.type(), 0, 100))
                  .getLast());
      assertEquals(List.of(), page.entries(), entity.type());
      assertTrue(page.done());
    }
  }

  @Test
  void aWelcomeNamingNoScopeMeansEveryProjectAndTheNextRoundPagesEverything() {
    seed(main, A, "nia");
    seed(main, B, "uma");

    SyncBox.quiesce(main, nia.syncsAs(scoped(ProjectScope.none())));
    assertEquals(ProjectScope.none(), new SyncScope(nia.db).held());

    SyncBox.quiesce(main, nia.syncsAs(Actor.sync("nia", Role.MEMBER)));

    assertEquals(ProjectScope.all(), new SyncScope(nia.db).held());
    SyncBox.assertEqualToMain(main, nia);
    assertTrue(nia.specs.findById("beta-work").isPresent());
  }

  @Test
  void aScopedSessionIsServedOnlyFromMainsDatabase() {
    var server =
        new SyncRpcServer(Map.of(), scoped(ONLY_A), FdeRoster.EMPTY, SyncTransitionSink.NONE);

    var refused =
        assertThrows(
            IllegalStateException.class,
            () -> server.serve(new ByteStreams.Input(""), new ByteStreams.Output()));

    assertEquals(
        "A scoped session is decided from main's change heads: serve it over main's database"
            + " (SyncRpcServer.over).",
        refused.getMessage());
  }

  private static Actor scoped(ProjectScope scope) {
    return Actor.sync("nia", Role.MEMBER).withScope(scope);
  }

  /**
   * Everything one project holds on {@code box}, owned by {@code owner}: its catalog row, a spec
   * with content only it holds, the spec's room with a thread, a finished run, a review and a
   * shared file.
   */
  private static void seed(SyncBox box, String project, String owner) {
    var id = project + "-work";
    new ProjectStore(box.db).upsert(project, "name: " + project + "\n");
    box.specs.create(spec(id, project, owner));
    box.specs.setContent(id, "a body only " + project + " holds", "the plan of " + project);
    new RoomStore(box.db)
        .create(
            new RoomStore.RoomRow(
                id,
                project,
                "the " + project + " room",
                owner,
                null,
                null,
                owner,
                null,
                null,
                owner));
    var messages = new MessageStore(box.db);
    var first = messages.append(id, owner, "a word in " + project, null);
    messages.append(id, owner, "a reply in " + project, first.id());
    var runs = new RunStore(box.db);
    var run = DateTimeUtils.newId().toString();
    runs.create(run, project, id, owner, "build", "claude", "b", "t", null, null, "/log", "u");
    runs.complete(run, "completed", 0);
    var reviews = new ReviewStore(box.db);
    reviews.createStage(reviews.createReview(id, 1), "security", "agent");
    ContentFixtures.put(new FileStore(box.db), project, "notes.md", "shared in " + project);
  }

  private static SpecStore.SpecRow spec(String id, String project, String owner) {
    return new SpecStore.SpecRow(
        id,
        project,
        "the " + project + " spec",
        SpecStatus.PENDING,
        owner,
        null,
        null,
        null,
        null,
        0,
        owner,
        "",
        "",
        owner,
        List.of(),
        List.of());
  }

  private static void archive(SyncBox box, String id) {
    Acting.system(() -> box.specs.updateStatus(id, SpecStatus.ARCHIVED));
  }

  private static Erasure.Result prune(SyncBox box, String id) {
    var erasure = new Erasure(box.db);
    return Acting.as(
        "uday",
        () ->
            erasure.erase(erasure.closure(List.of(new Erasure.Target(Erasure.SPEC, id))), "local"));
  }

  /** The snapshot {@code box} would offer of {@code id} once {@code written} has run. */
  private static Map<String, Object> snapshot(
      SyncBox box, Runnable written, String type, String id) {
    written.run();
    return box.replicas().get(type).current(id);
  }

  private static String bodyHash(SyncBox box, String spec) {
    return box.db
        .queryOne("SELECT body_hash FROM specs WHERE id = ?", row -> row.text(0), spec)
        .orElseThrow();
  }

  private static String held(String type) {
    return "SELECT count(*) FROM change_heads WHERE entity_type = '" + type + "'";
  }

  /** Main's server for a session scoped to alpha, recording every spec row it loads. */
  private SyncRpcServer countingRowLoads(List<String> loaded) {
    var replicas =
        new LinkedHashMap<String, MainReplica>(SyncedEntities.replicas(main.db, "main", null));
    var real = replicas.get("spec");
    replicas.put(
        "spec",
        (MainReplica)
            Proxy.newProxyInstance(
                MainReplica.class.getClassLoader(),
                new Class<?>[] {MainReplica.class},
                (proxy, method, args) -> {
                  if (args != null && args.length > 0 && args[0] instanceof String id) {
                    loaded.add(id);
                  }
                  try {
                    return method.invoke(real, args);
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                }));
    return new SyncRpcServer(
            replicas,
            scoped(ONLY_A),
            FdeRoster.EMPTY,
            SyncTransitionSink.NONE,
            new ChangeLog(main.db)::headsAfter,
            SyncWire.UPGRADE_FLOOR)
        .content(main.db, FileLimits.defaults());
  }

  private static List<SyncWire.Response> serve(SyncRpcServer server, SyncWire.Request... requests)
      throws IOException {
    var lines = new ArrayList<String>();
    for (var request : requests) {
      lines.add(SyncWire.encode(request));
    }
    var out = new ByteStreams.Output();
    server.serve(new ByteStreams.Input(String.join("\n", lines) + "\n"), out);
    return out.toString().lines().map(SyncWire::decodeResponse).toList();
  }

  private static SyncWire.Result only(SyncWire.Response response) {
    var results = assertInstanceOf(SyncWire.Results.class, response).results();
    assertEquals(1, results.size());
    return results.getFirst();
  }

  private static long count(Sqlite db, String sql, Object... args) {
    return db.queryOne(sql, row -> row.integer(0), args).orElseThrow();
  }
}
