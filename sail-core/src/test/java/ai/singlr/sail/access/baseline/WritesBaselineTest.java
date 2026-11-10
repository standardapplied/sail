/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access.baseline;

import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.RunStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * A1's differential, box half: every write a box makes of its own is decided as main's rules at
 * {@code fd091a53} decided it ({@link Baseline}, {@code core-writes}). Each member of the {@link
 * Cast} asks every revision of a spec, room and project the way a door pre-asks it, through the
 * store's own {@code decide} — the journal's decision as it records the revision — and makes every
 * write of a run, review, file and message through the store method a door calls, rehearsed so the
 * box is left as it was.
 */
class WritesBaselineTest {

  /**
   * One store write, made by the bound actor, and what it says of a write it declined to make
   * without asking the journal; null when it made it.
   */
  record Mutation(String label, Function<World, String> act) {

    /** A write that is made, or refused by the journal, and declines nothing on its own. */
    static Mutation of(String label, Consumer<World> act) {
      return new Mutation(
          label,
          world -> {
            act.accept(world);
            return null;
          });
    }
  }

  @Test
  void everyWriteOfTheBoxsOwnIsAnsweredAsTheBaselineRecords() {
    try (var world = new World()) {
      var corpus = new Corpus(world.minted());
      var changes = Changes.preAsked(world);
      var mutations = mutations(world);
      for (var member : Cast.everyone()) {
        for (var change : changes) {
          corpus.ask(
              "write " + change.label() + " as " + member.label(),
              () -> Corpus.decided(Actor.call(member.actor(), () -> preAsk(world, change))));
        }
        for (var mutation : mutations) {
          corpus.ask(
              "write " + mutation.label() + " as " + member.label(),
              () ->
                  world.rehearse(
                      member.actor(),
                      () -> {
                        var declined = mutation.act().apply(world);
                        return declined == null
                            ? Corpus.ALLOWED
                            : Corpus.ALLOWED + ", declined: " + declined;
                      }));
        }
      }
      Baseline.check("core-writes", corpus);
    }
  }

  /** The decision a door asks of the store before it writes {@code change}. */
  private static Optional<Refusal> preAsk(World world, Changes.Change change) {
    return switch (change.type()) {
      case "spec" -> world.box.specs.decide(change.id(), change.next());
      case "room" -> world.rooms.decide(change.id(), change.next());
      case "project" ->
          change.what().equals("rename")
              ? world.projects.decideRename(change.id())
              : world.projects.decide(change.id(), change.next());
      default -> throw new IllegalArgumentException("No pre-ask for " + change.type());
    };
  }

  private static List<Mutation> mutations(World world) {
    var mutations = new ArrayList<Mutation>();
    runs(mutations);
    reviews(world, mutations);
    files(mutations);
    messages(mutations);
    return mutations;
  }

  private static void runs(List<Mutation> mutations) {
    for (var box : List.of(Cast.OWNER, Cast.OTHER)) {
      for (var spec : List.of("s-ada", "s-bob", "s-open-ada", "s-beta")) {
        mutations.add(
            Mutation.of(
                "run create of " + spec + " on " + box + "'s box",
                world -> create(world, spec, box)));
      }
      mutations.add(
          Mutation.of("run create ad-hoc on " + box + "'s box", world -> create(world, null, box)));
      for (var room : List.of("lobby", "den", "hall")) {
        mutations.add(
            new Mutation(
                "run create in " + room + " on " + box + "'s box",
                world -> declined(reserve(world, room, box))));
      }
    }
    for (var run :
        List.of(
            Cast.ADA_RUN,
            Cast.BOB_RUN,
            Cast.ADA_ROOM_RUN,
            World.ADHOC_RUN,
            World.BETA_RUN,
            World.HANDED_OVER_RUN)) {
      mutations.add(
          Mutation.of(
              "run " + run + " report session",
              world -> world.runs.recordSession(run, "s-1", "startup", "/t")));
      mutations.add(
          Mutation.of(
              "run " + run + " complete", world -> world.runs.complete(run, "completed", 0)));
      mutations.add(
          Mutation.of(
              "run " + run + " stop", world -> world.runs.claimStop(run, "stopping", () -> {})));
    }
  }

  private static RunStore.Reservation reserve(World world, String room, String box) {
    return world.runs.reserveDispatch(
        Changes.NEW_RUN,
        "acme",
        null,
        room,
        box,
        "room",
        List.of(),
        "claude-code",
        "b",
        "t",
        "/l",
        "u",
        null);
  }

  /** What a reservation that reserved nothing says of why; null when it reserved the run. */
  private static String declined(RunStore.Reservation reservation) {
    return reservation instanceof RunStore.Reservation.Reserved ? null : reservation.toString();
  }

  private static void create(World world, String spec, String box) {
    world.runs.create(
        Changes.NEW_RUN,
        spec == null || !spec.equals("s-beta") ? "acme" : "beta",
        spec,
        box,
        "build",
        "claude-code",
        "b",
        "t",
        null,
        null,
        "/l",
        "u");
  }

  private static void reviews(World world, List<Mutation> mutations) {
    for (var spec : List.of("s-ada", "s-bob", "s-open-bob", "s-beta")) {
      mutations.add(Mutation.of("review create of " + spec, w -> w.reviews.createReview(spec, 2)));
    }
    for (var name : List.of("ada", "bob")) {
      var review = world.id("review-" + name);
      var finding = world.id("finding-" + name);
      mutations.add(
          Mutation.of(
              "review review-" + name + " approve",
              w -> w.reviews.approve(review, Cast.OWNER, () -> {})));
      mutations.add(
          Mutation.of(
              "review review-" + name + " dismiss finding-" + name,
              w -> w.reviews.resolveFinding(finding, Finding.Resolution.DISMISSED)));
    }
  }

  private static void files(List<Mutation> mutations) {
    for (var project : List.of("acme", "beta", "omega")) {
      mutations.add(
          Mutation.of(
              "file " + project + "/new.md create",
              world ->
                  world.files.put(
                      project,
                      "new.md",
                      new ByteArrayInputStream("new".getBytes(StandardCharsets.UTF_8)),
                      0644)));
    }
    for (var project : List.of("acme", "beta")) {
      mutations.add(
          Mutation.of(
              "file " + project + "/notes.md delete",
              world -> world.files.delete(project, "notes.md")));
    }
  }

  /**
   * Who posts "as the poster": the bound actor's own handle, or for a credential that names none an
   * author it is not, so the rule decides the post rather than the store refusing a blank one.
   */
  private static String poster() {
    return Objects.requireNonNullElse(Actor.current().handle(), "nobody");
  }

  private static void messages(List<Mutation> mutations) {
    for (var room :
        List.of("lobby", "den", "hall", "s-ada", "s-bob", "s-lobby", "b-room", "attic", "ghost")) {
      mutations.add(
          Mutation.of(
              "message post in " + room + " as the poster",
              world -> world.messages.append(room, poster(), "hi", null)));
    }
    for (var author : List.of(Cast.OWNER, Actor.SYSTEM_HANDLE, Cast.ADA_AGENT)) {
      mutations.add(
          Mutation.of(
              "message post in lobby as " + author,
              world -> world.messages.append("lobby", author, "hi", null)));
    }
  }
}
