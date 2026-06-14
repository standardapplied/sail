/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoopbackCallbackServerTest {

  private LoopbackCallbackServer callback;

  @BeforeEach
  void setUp() throws Exception {
    callback = new LoopbackCallbackServer("state-nonce");
    callback.start();
  }

  @AfterEach
  void tearDown() {
    callback.close();
  }

  private HttpResponse<String> hit(String query) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(callback.redirectUri() + "?" + query)).GET().build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void validCallbackCompletesWithToken() throws Exception {
    var response = hit("token=sess_abc&state=state-nonce");
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("Signed in"));
    assertEquals("sess_abc", callback.awaitToken(Duration.ofSeconds(5)));
  }

  @Test
  void redirectUriIsLoopback() {
    assertTrue(callback.redirectUri().startsWith("http://127.0.0.1:"));
    assertTrue(callback.redirectUri().endsWith("/callback"));
  }

  @Test
  void mismatchedStateIsRejectedButRecovers() throws Exception {
    assertEquals(400, hit("token=sess_evil&state=wrong").statusCode());
    assertEquals(200, hit("token=sess_good&state=state-nonce").statusCode());
    assertEquals("sess_good", callback.awaitToken(Duration.ofSeconds(5)));
  }

  @Test
  void missingTokenIsRejected() throws Exception {
    assertEquals(400, hit("state=state-nonce").statusCode());
  }
}
