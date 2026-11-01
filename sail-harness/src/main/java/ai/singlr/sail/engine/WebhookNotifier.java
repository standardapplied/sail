/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.WebhookUrlSafety;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Sends webhook notifications for agent watch events. Auto-detects the provider from the URL and
 * formats the payload accordingly. All calls are best-effort: failures are logged to stderr but
 * never thrown.
 */
public final class WebhookNotifier {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  /** Supported webhook providers, auto-detected from URL. */
  enum Provider {
    NTFY,
    SLACK,
    DISCORD,
    GENERIC
  }

  private final String url;
  private final Provider provider;

  public WebhookNotifier(String url) {
    requireHttpScheme(url);
    this.url = url;
    this.provider = detectProvider(url);
  }

  /** Sends a notification. Best-effort — never throws. */
  public void notify(String event, String project, String title, String message) {
    try {
      var host = URI.create(url).getHost();
      if (host == null || WebhookUrlSafety.isPrivateHost(host)) {
        System.err.println(
            "  [webhook] Warning: refusing to send to "
                + redactedUrl(url)
                + " - host resolves to a private/internal address");
        return;
      }

      var payload = buildPayload(provider, event, project, title, message);
      var request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .header("Content-Type", "application/json")
              .timeout(TIMEOUT)
              .POST(HttpRequest.BodyPublishers.ofString(payload))
              .build();

      var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        System.err.println(
            "  [webhook] Warning: HTTP " + response.statusCode() + " from " + redactedUrl(url));
      }
    } catch (Exception e) {
      System.err.println(
          "  [webhook] Warning: failed to notify " + redactedUrl(url) + " - " + e.getMessage());
    }
  }

  /** Detects provider from URL hostname. Package-private for testing. */
  static Provider detectProvider(String url) {
    var lower = url.toLowerCase();
    if (lower.contains("ntfy.sh") || lower.contains("ntfy.")) {
      return Provider.NTFY;
    }
    if (lower.contains("hooks.slack.com")) {
      return Provider.SLACK;
    }
    if (lower.contains("discord.com/api/webhooks")) {
      return Provider.DISCORD;
    }
    return Provider.GENERIC;
  }

  /** Builds the JSON payload for the given provider. Package-private for testing. */
  static String buildPayload(
      Provider provider, String event, String project, String title, String message) {
    return switch (provider) {
      case NTFY -> buildNtfyPayload(project, title, message);
      case SLACK -> buildSlackPayload(title, message);
      case DISCORD -> buildDiscordPayload(title, message);
      case GENERIC -> buildGenericPayload(event, project, title, message);
    };
  }

  private static String buildNtfyPayload(String project, String title, String message) {
    var sb = new StringBuilder();
    sb.append("{");
    sb.append("\"topic\":").append(jsonString(project));
    sb.append(",\"title\":").append(jsonString(title));
    sb.append(",\"message\":").append(jsonString(message));
    sb.append(",\"priority\":4");
    sb.append(",\"tags\":[\"warning\"]");
    sb.append("}");
    return sb.toString();
  }

  private static String buildSlackPayload(String title, String message) {
    var sb = new StringBuilder();
    sb.append("{");
    sb.append("\"text\":").append(jsonString(title + "\n" + message));
    sb.append("}");
    return sb.toString();
  }

  private static String buildDiscordPayload(String title, String message) {
    var sb = new StringBuilder();
    sb.append("{");
    sb.append("\"content\":").append(jsonString(title + "\n" + message));
    sb.append("}");
    return sb.toString();
  }

  private static String buildGenericPayload(
      String event, String project, String title, String message) {
    var sb = new StringBuilder();
    sb.append("{");
    sb.append("\"event\":").append(jsonString(event));
    sb.append(",\"project\":").append(jsonString(project));
    sb.append(",\"title\":").append(jsonString(title));
    sb.append(",\"message\":").append(jsonString(message));
    sb.append(",\"timestamp\":").append(jsonString(DateTimeUtils.format(DateTimeUtils.now())));
    sb.append("}");
    return sb.toString();
  }

  private static void requireHttpScheme(String url) {
    var lower = url.toLowerCase();
    if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
      throw new IllegalArgumentException("Webhook URL must use http or https scheme, got: " + url);
    }
  }

  static String redactedUrl(String url) {
    try {
      var uri = URI.create(url);
      var host = uri.getHost();
      if (host == null || host.isBlank()) {
        return "<webhook>";
      }
      return uri.getScheme() + "://" + host + "/...";
    } catch (Exception e) {
      return "<webhook>";
    }
  }

  private static String jsonString(String value) {
    return "\""
        + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        + "\"";
  }
}
