/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlackClientTest {

  @TempDir Path tempDir;

  @Test
  void rootPayloadIsExact() {
    var payload =
        SlackClient.buildPayload(new SlackPoster.Post("#sail-activity", "hello", null, false));
    assertEquals(
        "{\"channel\":\"#sail-activity\",\"text\":\"hello\",\"unfurl_links\":false}", payload);
  }

  @Test
  void threadedPayloadIsExact() {
    var payload =
        SlackClient.buildPayload(new SlackPoster.Post("C123", "reply", "1700.0001", false));
    assertEquals(
        "{\"channel\":\"C123\",\"text\":\"reply\",\"thread_ts\":\"1700.0001\","
            + "\"unfurl_links\":false}",
        payload);
  }

  @Test
  void broadcastPayloadIsExact() {
    var payload =
        SlackClient.buildPayload(new SlackPoster.Post("C123", "escalated", "1700.0001", true));
    assertEquals(
        "{\"channel\":\"C123\",\"text\":\"escalated\",\"thread_ts\":\"1700.0001\","
            + "\"reply_broadcast\":true,\"unfurl_links\":false}",
        payload);
  }

  @Test
  void payloadEscapesTextAndChannel() {
    var payload =
        SlackClient.buildPayload(
            new SlackPoster.Post("#ops", "line1\n\"two\"\t\\end", null, false));
    assertTrue(payload.contains("\"text\":\"line1\\n\\\"two\\\"\\t\\\\end\""));
  }

  @Test
  void postRejectsBlankChannel() {
    assertThrows(
        IllegalArgumentException.class, () -> new SlackPoster.Post(" ", "text", null, false));
  }

  @Test
  void postRejectsBlankText() {
    assertThrows(
        IllegalArgumentException.class, () -> new SlackPoster.Post("#ops", " ", null, false));
  }

  @Test
  void successfulPostReturnsChannelAndTs() {
    var client =
        new SlackClient(
            body ->
                new SlackClient.HttpReply(200, "{\"ok\":true,\"channel\":\"C9\",\"ts\":\"5.5\"}"),
            millis -> {});

    var result = client.post(new SlackPoster.Post("#ops", "hi", null, false));

    assertEquals("C9", result.channel());
    assertEquals("5.5", result.ts());
  }

  @Test
  void logicalRejectionDoesNotRetry() {
    var calls = new ArrayList<String>();
    var client =
        new SlackClient(
            body -> {
              calls.add(body);
              return new SlackClient.HttpReply(
                  200, "{\"ok\":false,\"error\":\"channel_not_found\"}");
            },
            millis -> {});

    var err = captureStderr(() -> assertNull(client.post(post())));

    assertEquals(1, calls.size());
    assertTrue(err.contains("channel_not_found"));
  }

  @Test
  void serverErrorRetriesThenGivesUpLoudly() {
    var calls = new ArrayList<String>();
    var sleeps = new ArrayList<Long>();
    var client =
        new SlackClient(
            body -> {
              calls.add(body);
              return new SlackClient.HttpReply(500, "oops");
            },
            sleeps::add);

    var err = captureStderr(() -> assertNull(client.post(post())));

    assertEquals(SlackClient.MAX_ATTEMPTS, calls.size());
    assertEquals(List.of(500L, 1000L), sleeps);
    assertTrue(err.contains("giving up"));
  }

  @Test
  void rateLimitRetriesUntilSuccess() {
    var calls = new ArrayList<String>();
    var client =
        new SlackClient(
            body -> {
              calls.add(body);
              return calls.size() < 2
                  ? new SlackClient.HttpReply(429, "slow down")
                  : new SlackClient.HttpReply(
                      200, "{\"ok\":true,\"channel\":\"C1\",\"ts\":\"1.1\"}");
            },
            millis -> {});

    var result = client.post(post());

    assertEquals(2, calls.size());
    assertEquals("1.1", result.ts());
  }

  @Test
  void transportExceptionRetries() {
    var calls = new ArrayList<String>();
    var client =
        new SlackClient(
            body -> {
              calls.add(body);
              if (calls.size() == 1) {
                throw new java.io.IOException("connection reset");
              }
              return new SlackClient.HttpReply(
                  200, "{\"ok\":true,\"channel\":\"C1\",\"ts\":\"2.2\"}");
            },
            millis -> {});

    var result = client.post(post());

    assertEquals(2, calls.size());
    assertEquals("2.2", result.ts());
  }

  @Test
  void okResponseWithoutTsIsFailure() {
    var client =
        new SlackClient(
            body -> new SlackClient.HttpReply(200, "{\"ok\":true,\"channel\":\"C1\"}"),
            millis -> {});

    var err = captureStderr(() -> assertNull(client.post(post())));

    assertTrue(err.contains("missing ts"));
  }

  @Test
  void parseReplyFallsBackToRequestedChannel() {
    var result = SlackClient.parseReply("{\"ok\":true,\"ts\":\"3.3\"}", "#ops");
    assertEquals("#ops", result.channel());
  }

  @Test
  void resolveTokenPrefersEnvValue() {
    var token = SlackClient.resolveToken(name -> "SAIL_SLACK_TOKEN".equals(name) ? "xoxb-1" : null);
    assertEquals("xoxb-1", token);
  }

  @Test
  void resolveTokenStripsWhitespace() {
    var token =
        SlackClient.resolveToken(name -> "SAIL_SLACK_TOKEN".equals(name) ? " xoxb-1\n" : null);
    assertEquals("xoxb-1", token);
  }

  @Test
  void resolveTokenReadsTokenFile() throws Exception {
    var file = tempDir.resolve("slack-token");
    Files.writeString(file, "xoxb-from-file\n");

    var token = SlackClient.resolveToken(fileLookup(file.toString()));

    assertEquals("xoxb-from-file", token);
  }

  @Test
  void resolveTokenReturnsNullWhenUnset() {
    assertNull(SlackClient.resolveToken(name -> null));
  }

  @Test
  void resolveTokenReturnsNullOnEmptyTokenFile() throws Exception {
    var file = tempDir.resolve("empty-token");
    Files.writeString(file, "  \n");

    assertNull(SlackClient.resolveToken(fileLookup(file.toString())));
  }

  @Test
  void resolveTokenWarnsOnUnreadableTokenFile() {
    var missing = tempDir.resolve("nope").toString();

    var err = captureStderr(() -> assertNull(SlackClient.resolveToken(fileLookup(missing))));

    assertTrue(err.contains("SAIL_SLACK_TOKEN_FILE"));
  }

  @Test
  void resolveTokenViaSystemPropertyDefaultLookup() {
    System.setProperty("SAIL_SLACK_TOKEN", "xoxb-prop");
    try {
      assertEquals("xoxb-prop", SlackClient.resolveToken());
    } finally {
      System.clearProperty("SAIL_SLACK_TOKEN");
    }
  }

  @Test
  void productionConstructorBuildsWithoutNetwork() {
    assertNotNull(new SlackClient("xoxb-test"));
  }

  @Test
  void defaultHttpBuildsPoster() {
    assertNotNull(SlackClient.defaultHttp("xoxb-test"));
  }

  @Test
  void payloadNeverContainsToken() {
    var payload = SlackClient.buildPayload(post());
    assertTrue(!payload.contains("xoxb"));
    assertTrue(!payload.contains("Authorization"));
  }

  private static SlackPoster.Post post() {
    return new SlackPoster.Post("#ops", "hi", null, false);
  }

  private static UnaryOperator<String> fileLookup(String path) {
    return name -> "SAIL_SLACK_TOKEN_FILE".equals(name) ? path : null;
  }

  private static String captureStderr(Runnable work) {
    var original = System.err;
    var buffer = new ByteArrayOutputStream();
    System.setErr(new PrintStream(buffer));
    try {
      work.run();
    } finally {
      System.setErr(original);
    }
    return buffer.toString();
  }
}
