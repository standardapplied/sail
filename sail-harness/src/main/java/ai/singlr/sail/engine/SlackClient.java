/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongConsumer;
import java.util.function.UnaryOperator;

/**
 * Posts messages to Slack via {@code chat.postMessage} — incoming webhooks cannot thread, so this
 * client authenticates with a bot token and returns the message timestamp for threading. All calls
 * are best-effort with bounded retry on transport failures and 429/5xx responses; a give-up is
 * logged loudly to stderr and never thrown. The token is never logged.
 *
 * <p>The token is resolved from the {@code SAIL_SLACK_TOKEN} environment variable or the file named
 * by {@code SAIL_SLACK_TOKEN_FILE} — never from a project's definition.
 */
public final class SlackClient implements SlackPoster {

  static final String API_URL = "https://slack.com/api/chat.postMessage";
  static final int MAX_ATTEMPTS = 3;
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final long RETRY_BACKOFF_MILLIS = 500;

  @FunctionalInterface
  interface HttpPoster {
    HttpReply send(String jsonBody) throws Exception;
  }

  record HttpReply(int status, String body) {}

  private final HttpPoster http;
  private final LongConsumer sleeper;

  public SlackClient(String token) {
    this(defaultHttp(Objects.requireNonNull(token, "token")), SlackClient::sleep);
  }

  SlackClient(HttpPoster http, LongConsumer sleeper) {
    this.http = Objects.requireNonNull(http, "http");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  @Override
  public Result post(Post post) {
    var payload = buildPayload(post);
    for (var attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        var reply = http.send(payload);
        if (reply.status() != 429 && reply.status() < 500) {
          return parseReply(reply.body(), post.channel());
        }
        warn(
            "HTTP "
                + reply.status()
                + " from Slack posting to "
                + post.channel()
                + " (attempt "
                + attempt
                + "/"
                + MAX_ATTEMPTS
                + ")");
      } catch (Exception e) {
        warn(
            "could not reach Slack posting to "
                + post.channel()
                + " (attempt "
                + attempt
                + "/"
                + MAX_ATTEMPTS
                + "): "
                + e.getMessage());
      }
      if (attempt < MAX_ATTEMPTS) {
        sleeper.accept(attempt * RETRY_BACKOFF_MILLIS);
      }
    }
    warn("giving up posting to " + post.channel() + " after " + MAX_ATTEMPTS + " attempts");
    return null;
  }

  /** Builds the exact chat.postMessage JSON body. Package-private for testing. */
  static String buildPayload(Post post) {
    var sb = new StringBuilder("{");
    sb.append("\"channel\":").append(jsonString(post.channel()));
    sb.append(",\"text\":").append(jsonString(post.text()));
    if (post.threadTs() != null) {
      sb.append(",\"thread_ts\":").append(jsonString(post.threadTs()));
    }
    if (post.broadcast()) {
      sb.append(",\"reply_broadcast\":true");
    }
    sb.append(",\"unfurl_links\":false}");
    return sb.toString();
  }

  /** Parses a chat.postMessage response body. Package-private for testing. */
  static Result parseReply(String body, String channel) {
    var map = YamlUtil.parseMap(body);
    if (!Boolean.TRUE.equals(map.get("ok"))) {
      warn("Slack rejected message for " + channel + ": " + map.get("error"));
      return null;
    }
    var ts = Objects.toString(map.get("ts"), "");
    if (ts.isBlank()) {
      warn("Slack response for " + channel + " is missing ts; replies cannot thread");
      return null;
    }
    return new Result(Objects.toString(map.get("channel"), channel), ts);
  }

  /**
   * Resolves the Slack bot token: {@code SAIL_SLACK_TOKEN} env var, else the file named by {@code
   * SAIL_SLACK_TOKEN_FILE}. Returns {@code null} when neither yields a token.
   */
  public static String resolveToken() {
    return resolveToken(SlackClient::envOrProperty);
  }

  static String resolveToken(UnaryOperator<String> lookup) {
    var token = lookup.apply("SAIL_SLACK_TOKEN");
    if (Strings.isNotBlank(token)) {
      return token.strip();
    }
    var file = lookup.apply("SAIL_SLACK_TOKEN_FILE");
    if (Strings.isBlank(file)) {
      return null;
    }
    try {
      var content = Files.readString(Path.of(file)).strip();
      return content.isEmpty() ? null : content;
    } catch (Exception e) {
      warn("could not read SAIL_SLACK_TOKEN_FILE " + file + " - " + e.getMessage());
      return null;
    }
  }

  static HttpPoster defaultHttp(String token) {
    var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    return body -> {
      var request =
          HttpRequest.newBuilder()
              .uri(URI.create(API_URL))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json; charset=utf-8")
              .timeout(TIMEOUT)
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return new HttpReply(response.statusCode(), response.body());
    };
  }

  private static String envOrProperty(String name) {
    var env = System.getenv(name);
    return env != null ? env : System.getProperty(name);
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void warn(String message) {
    System.err.println("  [slack] Warning: " + message);
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
