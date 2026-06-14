/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class GitHubPusherTest {

  @Test
  void buildPutBodyForNewFile() {
    var body = GitHubPusher.buildPutBody("Create file", "dGVzdA==", null, "main");

    assertTrue(body.contains("\"message\":\"Create file\""));
    assertTrue(body.contains("\"content\":\"dGVzdA==\""));
    assertTrue(body.contains("\"branch\":\"main\""));
    assertFalse(body.contains("\"sha\""));
  }

  @Test
  void buildPutBodyForExistingFile() {
    var body = GitHubPusher.buildPutBody("Update file", "dGVzdA==", "abc123def", "main");

    assertTrue(body.contains("\"message\":\"Update file\""));
    assertTrue(body.contains("\"content\":\"dGVzdA==\""));
    assertTrue(body.contains("\"sha\":\"abc123def\""));
    assertTrue(body.contains("\"branch\":\"main\""));
  }

  @Test
  void buildPutBodyEscapesSpecialCharacters() {
    var body =
        GitHubPusher.buildPutBody("Fix \"quotes\" and \\ backslash", "dGVzdA==", null, "main");

    assertTrue(body.contains("Fix \\\"quotes\\\" and \\\\ backslash"));
  }

  @Test
  void buildPutBodyWithCustomBranch() {
    var body = GitHubPusher.buildPutBody("msg", "dGVzdA==", null, "feature/my-branch");

    assertTrue(body.contains("\"branch\":\"feature/my-branch\""));
  }

  @Test
  void base64EncodingRoundTrips() {
    var original = "name: my-project\nresources:\n  cpu: 2\n";
    var encoded = Base64.getEncoder().encodeToString(original.getBytes(StandardCharsets.UTF_8));
    var decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);

    assertEquals(original, decoded);
  }
}
