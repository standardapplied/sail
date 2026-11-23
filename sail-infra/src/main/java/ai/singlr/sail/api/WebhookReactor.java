/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.Notifications;
import ai.singlr.sail.engine.WebhookNotifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Bus subscriber that POSTs outbound webhook notifications when project-configured event types
 * fire. Replaces the polling-driven notification dispatch in {@link
 * ai.singlr.sail.commands.AgentWatchCommand} for events flowing through the bus.
 *
 * <p>Per-project configuration ({@link Notifications}) is looked up via a {@link
 * ProjectNotificationsResolver}. {@link WebhookNotifier} instances are cached by URL so we don't
 * rebuild the provider-detection state on every event. The notifier itself is best-effort —
 * failures are logged but never thrown — so a slow webhook endpoint cannot back up the bus drain
 * thread beyond its 10-second per-request timeout.
 */
public final class WebhookReactor implements EventSubscriber {

  /** Event types this reactor considers candidates for outbound notification. */
  static final Set<String> NOTIFIABLE_TYPES =
      Set.of(
          Event.WellKnownTypes.SPEC_DISPATCHED,
          Event.WellKnownTypes.SPEC_RESTARTED,
          Event.WellKnownTypes.AGENT_SESSION_STARTED,
          Event.WellKnownTypes.AGENT_SESSION_STOPPED,
          Event.WellKnownTypes.AGENT_SESSION_COMPLETED,
          Event.WellKnownTypes.GUARDRAIL_TRIGGERED);

  private final ProjectNotificationsResolver resolver;
  private final Function<String, WebhookSender> senderFactory;
  private final Map<String, WebhookSender> senders = new HashMap<>();
  private boolean closed;

  /** The reactor sending over real HTTP what {@code resolver} says each project wants. */
  public static WebhookReactor sending(ProjectNotificationsResolver resolver) {
    return new WebhookReactor(resolver, WebhookReactor::defaultSender);
  }

  public WebhookReactor(
      ProjectNotificationsResolver resolver, Function<String, WebhookSender> senderFactory) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.senderFactory = Objects.requireNonNull(senderFactory, "senderFactory");
  }

  /** Test seam: returns a real outbound-HTTPS sender backed by {@link WebhookNotifier}. */
  static WebhookSender defaultSender(String url) {
    var notifier = new WebhookNotifier(url);
    return new WebhookSender() {
      @Override
      public void send(String event, String project, String title, String message) {
        notifier.notify(event, project, title, message);
      }

      @Override
      public void close() {
        notifier.close();
      }
    };
  }

  @Override
  public String name() {
    return "webhook-reactor";
  }

  @Override
  public Predicate<Event> filter() {
    return e -> NOTIFIABLE_TYPES.contains(e.type());
  }

  @Override
  public void onEvent(Event event) {
    if (isSyncDerived(event) || isReplayedStop(event)) {
      return;
    }
    var notifications = resolver.resolve(event.project());
    if (notifications == null || notifications.url() == null) {
      return;
    }
    if (!notifications.shouldNotify(event.type())) {
      return;
    }
    var sender = senderFor(notifications.url());
    if (sender == null) {
      return;
    }
    var message = WebhookMessage.forEvent(event);
    sender.send(event.type(), event.project(), message.title(), message.message());
  }

  /** The sender cached for {@code url}, or null once the subscription has closed. */
  private synchronized WebhookSender senderFor(String url) {
    return closed ? null : senders.computeIfAbsent(url, senderFactory);
  }

  /**
   * Closes every sender cached, so no client outlives the subscription; a notification already
   * under way is answered first, within its own timeout.
   */
  @Override
  public void closed() {
    List<WebhookSender> open;
    synchronized (this) {
      closed = true;
      open = List.copyOf(senders.values());
      senders.clear();
    }
    open.forEach(WebhookSender::close);
  }

  /**
   * A stop the reconciler replays for a run that had already ended ({@link
   * Event.WellKnownData#REPLAY}) was notified when the run ended; it is published again only to
   * move the review loop.
   */
  private static boolean isReplayedStop(Event event) {
    return Event.WellKnownTypes.AGENT_SESSION_STOPPED.equals(event.type())
        && Event.WellKnownData.replay(event.data());
  }

  /**
   * A sync-derived event replays a transition another box already executed — and, with this same
   * webhook configuration in its synced project definition, already notified. Sending it again from
   * main would double every webhook, so narration input stays Slack-only.
   */
  private static boolean isSyncDerived(Event event) {
    return Event.WellKnownData.SOURCE_SYNC.equals(event.data().get(Event.WellKnownData.SOURCE));
  }
}
