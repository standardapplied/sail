/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access.baseline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.sync.ByteStreams;
import ai.singlr.sail.sync.MainReplica;
import ai.singlr.sail.sync.SyncWire;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A1's snapshot, main half: every offer a node pushes and every request to erase is answered as the
 * snapshot records it ({@link Baseline}, {@code core-pushes}). Each sync session of the {@link
 * Cast} opens a session over the wire and pushes one offer of every revision {@link Changes} names,
 * authored as the box that pushes it authors its own, then as each other author an offer could
 * name, having first fetched main's rows of the type, as a node holds what main served it before it
 * changes it; and asks main to erase each spec and project, and what is not erased on request. The
 * whole admission is asked: the scope rule a scoped pusher meets first, main's standing checks, and
 * the type's rule inside the commit. Each session is rehearsed, so main is left as it was.
 */
class PushesBaselineTest {

  /**
   * What main prints when a denial or an erasure leaves content to collect inside a rehearsal:
   * collecting needs the connection the rehearsal holds, and the rehearsal leaves nothing behind to
   * collect.
   */
  private static final String UNCOLLECTED =
      "  [sync] could not collect content a denial or erasure left; 'sail sync gc' frees it: Take"
          + " blob retention before opening a transaction, not inside one";

  /**
   * What main prints of an offer its store failed to commit, which the offer's answer carries as
   * its refusal.
   */
  private static final Pattern REFUSED =
      Pattern.compile("  \\[sync\\] (spec|room|run|review|file|project|message) \\S+: ");

  private static final List<String> AUTHORS =
      List.of(Cast.OWNER, Cast.OTHER, Actor.SYSTEM_HANDLE, Cast.ADA_AGENT, Cast.BOB_AGENT);

  @Test
  void everyOfferAndEraseRequestIsAnsweredAsTheBaselineRecords() {
    try (var world = new World()) {
      var corpus = new Corpus(world.minted());
      var offers = offers(world);
      var printed =
          World.stderrOf(
              () -> {
                for (var pusher : Cast.pushers()) {
                  for (var offer : offers) {
                    corpus.ask(
                        "push " + offer.label() + " as " + pusher.label(),
                        () -> push(world, pusher.actor(), offer.change(), offer.author()));
                  }
                  for (var headless : List.of(false, true)) {
                    for (var change : Changes.unheld(world, headless)) {
                      corpus.ask(
                          "push " + change.label() + " as " + pusher.label(),
                          () -> unheld(world, pusher.actor(), change, headless));
                    }
                  }
                  for (var change : Changes.filedElsewhere(world)) {
                    corpus.ask(
                        "push " + change.label() + " as " + pusher.label(),
                        () ->
                            answer(
                                world,
                                pusher.actor(),
                                new SyncWire.Push(
                                    change.type(),
                                    List.of(
                                        new MainReplica.Offer(
                                            change.id(),
                                            authored(change, pusher.actor().handle()),
                                            World.ADOPTED_REV))),
                                () -> world.adoptDeletion(change.type(), change.id(), "acme")));
                  }
                  for (var change : Changes.uncatalogued(world)) {
                    corpus.ask(
                        "push " + change.label() + " as " + pusher.label(),
                        () ->
                            answer(
                                world,
                                pusher.actor(),
                                new SyncWire.Push(
                                    change.type(),
                                    List.of(
                                        new MainReplica.Offer(
                                            change.id(),
                                            authored(change, pusher.actor().handle()),
                                            world.store(change.type()).latestRev(change.id())))),
                                () -> world.uncatalog("beta")));
                  }
                  for (var erase : erasures()) {
                    corpus.ask(
                        "erase "
                            + erase.getKey()
                            + " "
                            + erase.getValue()
                            + " as "
                            + pusher.label(),
                        () ->
                            answer(
                                world,
                                pusher.actor(),
                                new SyncWire.Push(
                                    erase.getKey(),
                                    List.of(MainReplica.Offer.erasure(erase.getValue())))));
                  }
                }
              });
      assertEquals(
          List.of(),
          printed
              .lines()
              .filter(line -> !line.equals(UNCOLLECTED) && !REFUSED.matcher(line).lookingAt())
              .toList(),
          "a session prints only what its answer says, and what its rehearsal leaves uncollected");
      Baseline.check("core-pushes", corpus);
    }
  }

  /** One offer: a revision, and the author it names; null names the pusher itself. */
  private record Offer(Changes.Change change, String author) {

    String label() {
      return change.label() + (author == null ? "" : " written as " + author);
    }
  }

  private static List<Offer> offers(World world) {
    var offers = new ArrayList<Offer>();
    for (var change : Changes.all(world)) {
      offers.add(new Offer(change, null));
    }
    for (var change : Changes.all(world)) {
      if (change.what().equals("edit title")
          || change.what().equals("status completed")
          || change.what().equals("move room to den")
          || change.what().equals("name a foreign principal")
          || change.what().startsWith("create of s-")
          || change.what().equals("create")) {
        for (var author : AUTHORS) {
          offers.add(new Offer(change, author));
        }
      }
    }
    return offers;
  }

  private static List<Map.Entry<String, String>> erasures() {
    var erasures = new ArrayList<Map.Entry<String, String>>();
    for (var spec : World.SPECS) {
      erasures.add(Map.entry("spec", spec));
    }
    erasures.add(Map.entry("spec", "s-never"));
    for (var project : List.of("acme", "beta", "delta", "omega")) {
      erasures.add(Map.entry("project", project));
    }
    erasures.add(Map.entry("room", "lobby"));
    erasures.add(Map.entry("run", Cast.ADA_RUN));
    return erasures;
  }

  /**
   * Main's answer to {@code pusher} offering {@code change} at main's own latest revision of it,
   * authored as {@code author}, or as the pusher when that is null.
   */
  private static String push(World world, Actor pusher, Changes.Change change, String author) {
    var offered = authored(change, author == null ? pusher.handle() : author);
    var expected = world.store(change.type()).latestRev(change.id());
    return answer(
        world,
        pusher,
        new SyncWire.Push(
            change.type(), List.of(new MainReplica.Offer(change.id(), offered, expected))));
  }

  /**
   * Main's answer to {@code pusher} offering {@code change}, a deletion of what main holds no head
   * of, as the first revision of it: of a row main holds with no head when {@code headless}.
   */
  private static String unheld(World world, Actor pusher, Changes.Change change, boolean headless) {
    return answer(
        world,
        pusher,
        new SyncWire.Push(
            change.type(), List.of(new MainReplica.Offer(change.id(), change.next(), null))),
        () -> {
          if (headless) {
            world.dropHead(change.type(), change.id());
          }
        });
  }

  /**
   * {@code change}'s snapshot as a box writes it when {@code author} made it: under its {@code
   * _actor}, as the creator of a spec it creates, as the author of a message it posts.
   */
  private static Map<String, Object> authored(Changes.Change change, String author) {
    if (change.next() == null || Snapshots.isDeletion(change.next())) {
      return change.next();
    }
    var offered = new LinkedHashMap<>(change.next());
    if (change.type().equals("message")) {
      if (offered.get("author") == null) {
        offered.put("author", author);
      }
      return offered;
    }
    offered.put(Snapshots.ACTOR, author);
    if (change.type().equals("spec") && !offered.containsKey(Snapshots.CREATOR)) {
      offered.put(Snapshots.CREATOR, author);
    }
    return offered;
  }

  /**
   * Main's answer to one session of {@code pusher}'s that says hello, fetches every row main holds
   * of the type, and makes {@code push}.
   */
  private static String answer(World world, Actor pusher, SyncWire.Push push) {
    return answer(world, pusher, push, () -> {});
  }

  /** As {@link #answer(World, Actor, SyncWire.Push)}, once {@code first} has run in the session. */
  private static String answer(World world, Actor pusher, SyncWire.Push push, Runnable first) {
    var box = Objects.requireNonNullElse(pusher.handle(), "anonymous") + "-box";
    var held = List.copyOf(world.store(push.type()).syncEntityIds());
    var request =
        String.join(
            "\n",
            SyncWire.encode(SyncWire.Hello.of("1.0.0", box)),
            SyncWire.encode(new SyncWire.Need(push.type(), held)),
            SyncWire.encode(push),
            "");
    var replies =
        world.db.rehearse(
            () -> {
              first.run();
              var out = new ByteStreams.Output();
              try {
                world.box.server(pusher).serve(new ByteStreams.Input(request), out);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
              return out.toString().lines().toList();
            });
    return switch (SyncWire.decodeResponse(replies.getLast())) {
      case SyncWire.Results results -> result(results.results().getFirst());
      case SyncWire.Refuse refuse -> "denied HELLO " + refuse.reason().plain();
      case SyncWire.Response other -> "failed " + other;
    };
  }

  private static String result(SyncWire.Result result) {
    return switch (result) {
      case SyncWire.Accepted ignored -> Corpus.ALLOWED;
      case SyncWire.Stale ignored -> "stale";
      case SyncWire.Refused refused -> "denied REFUSED " + refused.reason().plain();
      case SyncWire.Denied denied ->
          "denied DENIED " + denied.reason() + (denied.gone() ? " (gone)" : "");
    };
  }
}
