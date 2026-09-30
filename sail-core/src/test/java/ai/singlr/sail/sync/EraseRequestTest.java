/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Main decides a node's request to erase on main's own copy, by the erase rule every prune asks,
 * plus what only main can say: what it holds nothing of, what it already erased, and that only
 * specs and projects are erased on request.
 */
class EraseRequestTest {

  private static final Actor MADY = Actor.sync("mady", Role.MEMBER);
  private static final Actor ADMIN = Actor.sync("uday", Role.ADMIN);
  private static final SyncWire.Hello HELLO = SyncWire.Hello.of(SyncWire.UPGRADE_FLOOR, "node");

  private SyncBox main;

  @BeforeEach
  void setUp() {
    main = new SyncBox("main");
  }

  @AfterEach
  void tearDown() {
    main.close();
  }

  private SyncWire.Result ask(Actor as, String type, String id) throws Exception {
    var out = new ByteStreams.Output();
    var request =
        SyncWire.encode(HELLO)
            + "\n"
            + SyncWire.encode(new SyncWire.Push(type, List.of(MainReplica.Offer.erasure(id))))
            + "\n";
    main.server(as).serve(new ByteStreams.Input(request), out, SyncWire.MAX_FRAME);
    var results =
        assertInstanceOf(
            SyncWire.Results.class,
            SyncWire.decodeResponse(out.toString().lines().toList().getLast()));
    return results.results().getFirst();
  }

  private String refusal(Actor as, String type, String id) throws Exception {
    return assertInstanceOf(SyncWire.Refused.class, ask(as, type, id)).reason();
  }

  private void create(String id, String assignee, String creator, SpecStatus status) {
    Acting.as(
        creator,
        () ->
            main.specs.create(
                new SpecStore.SpecRow(
                    id, "acme", id, status, assignee, null, null, null, null, 0, creator, "", "",
                    creator, List.of(), List.of())));
  }

  @Test
  void theOwnerOfAnArchivedSpecErasesItAndAskingAgainAnswersTheErasure() throws Exception {
    create("mine", "mady", "uday", SpecStatus.ARCHIVED);

    var erased = assertInstanceOf(SyncWire.Accepted.class, ask(MADY, "spec", "mine"));
    var again = assertInstanceOf(SyncWire.Accepted.class, ask(MADY, "spec", "mine"));

    assertEquals(erased.rev(), again.rev());
  }

  @Test
  void anotherMembersSpecIsRefusedByTheRuleTheLocalPruneAsks() throws Exception {
    create("theirs", "uday", "uday", SpecStatus.ARCHIVED);

    assertEquals("Spec 'theirs' is assigned to 'uday', not you.", refusal(MADY, "spec", "theirs"));
    assertInstanceOf(SyncWire.Accepted.class, ask(ADMIN, "spec", "theirs"));
  }

  @Test
  void aSpecOnTheBoardOrWithARunGoingIsRefusedEvenToAnAdmin() throws Exception {
    create("live", "mady", "mady", SpecStatus.IN_PROGRESS);
    create("busy", "mady", "mady", SpecStatus.ARCHIVED);
    var run =
        Acting.system(
            () ->
                new RunStore(main.db)
                    .create(
                        "019fee00-0000-7000-8000-0000000000c9",
                        "acme",
                        "busy",
                        "mady",
                        "build",
                        "claude-code",
                        "b",
                        "t",
                        null,
                        null,
                        "/l",
                        "u"));

    assertEquals(
        "Spec 'live' is in_progress: only archived, cancelled or deleted specs are pruned.",
        refusal(ADMIN, "spec", "live"));
    assertEquals(
        "Run '" + run + "' has not finished; a prune never erases work going on.",
        refusal(ADMIN, "spec", "busy"));
  }

  @Test
  void whatMainHoldsNothingOfOrCannotEraseOnRequestIsRefused() throws Exception {
    assertEquals(
        "main holds no spec 'never', so it cannot tell whose it is; sync it before pruning",
        refusal(ADMIN, "spec", "never"));
    assertEquals("main holds no project 'acme'", refusal(ADMIN, "project", "acme"));
    assertEquals(
        "only specs and projects are pruned on request, not a message",
        refusal(ADMIN, "message", "m"));
  }

  @Test
  void aWholeProjectIsAnAdminsAndNothingAViewers() throws Exception {
    create("in-acme", "mady", "mady", SpecStatus.DONE);

    assertEquals(
        "Pruning by policy or a whole project is admin-only.", refusal(MADY, "project", "acme"));
    assertEquals(
        "Your role is read-only: it cannot prune.",
        refusal(Actor.sync("mady", Role.VIEWER), "spec", "in-acme"));
    assertInstanceOf(SyncWire.Accepted.class, ask(ADMIN, "project", "acme"));
    assertEquals(
        List.of(),
        main.specs.list(SpecStore.SpecFilter.all()).stream().map(SpecStore.SpecRow::id).toList());
    assertEquals(true, new Erasure(main.db).isErased(new Erasure.Target("project", "acme")));
  }
}
