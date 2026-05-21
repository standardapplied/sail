/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.Notifications;
import ai.singlr.sail.engine.WebhookNotifier;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
          Event.WellKnownTypes.SNAPSHOT_CREATED,
          Event.WellKnownTypes.GUARDRAIL_TRIGGERED);

  private final ProjectNotificationsResolver resolver;
  private final Function<String, WebhookSender> senderFactory;
  private final ConcurrentHashMap<String, WebhookSender> senders = new ConcurrentHashMap<>();

  /** Default reactor using {@link SailYamlNotificationsResolver} and the real HTTP notifier. */
  public static WebhookReactor withDefaultResolver() {
    return new WebhookReactor(new SailYamlNotificationsResolver(), WebhookReactor::defaultSender);
  }

  public WebhookReactor(
      ProjectNotificationsResolver resolver, Function<String, WebhookSender> senderFactory) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.senderFactory = Objects.requireNonNull(senderFactory, "senderFactory");
  }

  /** Test seam: returns a real outbound-HTTPS sender backed by {@link WebhookNotifier}. */
  static WebhookSender defaultSender(String url) {
    var notifier = new WebhookNotifier(url);
    return notifier::notify;
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
    var notifications = resolver.resolve(event.project());
    if (notifications == null || notifications.url() == null) {
      return;
    }
    if (!notifications.shouldNotify(event.type())) {
      return;
    }
    var message = WebhookMessage.forEvent(event);
    var sender = senderFor(notifications.url());
    sender.send(event.type(), event.project(), message.title(), message.message());
  }

  private WebhookSender senderFor(String url) {
    return senders.computeIfAbsent(url, senderFactory);
  }
}
