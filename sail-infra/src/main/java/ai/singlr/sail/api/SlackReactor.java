/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.SlackClient;
import ai.singlr.sail.engine.SlackPoster;
import ai.singlr.sail.store.SlackThreadStore;
import ai.singlr.sail.store.SpecStore;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Bus subscriber that mirrors a spec's lifecycle into one Slack thread. The dispatch event posts a
 * root message and records its {@code thread_ts}; every later lifecycle event replies in that
 * thread. A re-dispatch posts a new root, so each attempt gets its own thread. An escalation also
 * broadcasts its reply to the channel, since it awaits a human.
 *
 * <p>Unlike the webhook reactor, the {@code notifications.events} filter does not apply here — the
 * spec-lifecycle set below is what makes a thread coherent. Everything is best-effort: post
 * failures are retried by {@link SlackClient}, logged loudly on give-up, and never thrown, so a
 * Slack outage cannot back up the bus or the dispatch pipeline.
 */
public final class SlackReactor implements EventSubscriber {

  /** Event types that start a new thread (one per dispatch attempt). */
  static final Set<String> ROOT_TYPES =
      Set.of(Event.WellKnownTypes.SPEC_DISPATCHED, Event.WellKnownTypes.SPEC_RESTARTED);

  /** Event types mirrored into Slack. */
  static final Set<String> NOTIFIABLE_TYPES =
      Set.of(
          Event.WellKnownTypes.SPEC_DISPATCHED,
          Event.WellKnownTypes.SPEC_RESTARTED,
          Event.WellKnownTypes.AGENT_SESSION_STOPPED,
          Event.WellKnownTypes.AGENT_FAILED,
          Event.WellKnownTypes.GUARDRAIL_TRIGGERED,
          "review_stage_started",
          "review_stage_passed",
          "review_stage_failed",
          "review_completed",
          "review_errored",
          "review_iteration_started",
          "review_escalated");

  private final ProjectNotificationsResolver resolver;
  private final SlackThreadStore threads;
  private final Function<String, SpecStore.SpecRow> specLookup;
  private final SlackPoster poster;

  /**
   * Default reactor: per-project sail.yaml config, spec titles from the store, and the real Slack
   * client when a token is configured ({@code SAIL_SLACK_TOKEN} or {@code SAIL_SLACK_TOKEN_FILE}).
   */
  public static SlackReactor withDefaults(SlackThreadStore threads, SpecStore specStore) {
    return new SlackReactor(
        new SailYamlNotificationsResolver(), threads, specLookup(specStore), defaultPoster());
  }

  static Function<String, SpecStore.SpecRow> specLookup(SpecStore specStore) {
    return specId -> specStore.findById(specId).orElse(null);
  }

  static SlackPoster defaultPoster() {
    var token = SlackClient.resolveToken();
    return token == null ? null : new SlackClient(token);
  }

  /**
   * @param poster the Slack client, or {@code null} when no token is configured — events are then
   *     dropped with a loud warning instead of silently
   */
  public SlackReactor(
      ProjectNotificationsResolver resolver,
      SlackThreadStore threads,
      Function<String, SpecStore.SpecRow> specLookup,
      SlackPoster poster) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.threads = Objects.requireNonNull(threads, "threads");
    this.specLookup = Objects.requireNonNull(specLookup, "specLookup");
    this.poster = poster;
  }

  @Override
  public String name() {
    return "slack-reactor";
  }

  @Override
  public Predicate<Event> filter() {
    return e -> NOTIFIABLE_TYPES.contains(e.type());
  }

  @Override
  public void onEvent(Event event) {
    try {
      handle(event);
    } catch (Exception e) {
      System.err.println(
          "  [slack] Warning: failed to process "
              + event.type()
              + " for spec "
              + event.spec()
              + ": "
              + e.getMessage());
    }
  }

  private void handle(Event event) {
    var notifications = resolver.resolve(event.project());
    var slack = notifications == null ? null : notifications.slack();
    if (slack == null) {
      return;
    }
    if (isTurnEndStop(event)) {
      return;
    }
    if (poster == null) {
      System.err.println(
          "  [slack] Warning: notifications.slack is configured for project '"
              + event.project()
              + "' but no token is available; set SAIL_SLACK_TOKEN or SAIL_SLACK_TOKEN_FILE");
      return;
    }
    if (ROOT_TYPES.contains(event.type())) {
      postRoot(event, slack.channel());
    } else {
      postReply(event, slack.channel());
    }
  }

  private void postRoot(Event event, String channel) {
    var spec = event.spec() == null ? null : specLookup.apply(event.spec());
    var text = SlackMessage.forEvent(event, spec);
    saveThread(event, poster.post(new SlackPoster.Post(channel, text, null, false)));
  }

  /**
   * Replies in the spec's recorded thread. When no root exists — the spec was dispatched before
   * Slack was configured, or the root post failed — the reply is posted standalone and adopted as
   * the thread root so the rest of the lifecycle still lands in one place.
   */
  private void postReply(Event event, String channel) {
    var text = SlackMessage.forEvent(event, null);
    var thread =
        event.spec() == null
            ? Optional.<SlackThreadStore.ThreadRef>empty()
            : threads.find(event.project(), event.spec());
    if (thread.isEmpty()) {
      saveThread(event, poster.post(new SlackPoster.Post(channel, text, null, false)));
      return;
    }
    var broadcast = "review_escalated".equals(event.type());
    poster.post(
        new SlackPoster.Post(thread.get().channel(), text, thread.get().threadTs(), broadcast));
  }

  private void saveThread(Event event, SlackPoster.Result result) {
    if (result != null && event.spec() != null) {
      threads.save(event.project(), event.spec(), result.channel(), result.ts());
    }
  }

  /**
   * The in-container agent hook fires a stop at every turn end; only the watcher's poll-derived
   * stop (which carries a {@code source}) is the real termination worth a thread reply.
   */
  private static boolean isTurnEndStop(Event event) {
    return Event.WellKnownTypes.AGENT_SESSION_STOPPED.equals(event.type())
        && event.data().get(Event.WellKnownData.SOURCE) == null;
  }
}
