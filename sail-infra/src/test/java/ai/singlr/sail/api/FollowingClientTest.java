/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ClosesWhatItOpens;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FollowingClientTest {

  @Test
  void closingAClientAbortsTheRequestItStillWaitsOnInsteadOfWaitingForIt() throws Exception {
    var before = ClosesWhatItOpens.baseline();
    try (var silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      var client = FollowingClient.of(HttpClient.newBuilder());
      var pending =
          client
              .http()
              .sendAsync(
                  HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + silent.getLocalPort()))
                      .build(),
                  HttpResponse.BodyHandlers.discarding());

      assertTimeoutPreemptively(Duration.ofSeconds(10), client::close);

      var aborted = assertThrows(ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS));
      assertTrue(aborted.getCause() instanceof IOException, aborted.toString());
    }
    ClosesWhatItOpens.assertClosed("a closed client", before);
  }

  @Test
  void aCloseFromOneOfItsOwnNamedThreadsIsRefusedInsteadOfWaitingForItself() throws Exception {
    var client = FollowingClient.of(HttpClient.newBuilder());
    var named = new AtomicReference<String>();
    var closing =
        client.follow(
            () -> {
              named.set(Thread.currentThread().getName());
              client.close();
            });

    var refused = assertThrows(ExecutionException.class, () -> closing.get(10, TimeUnit.SECONDS));
    client.close();

    assertEquals(IllegalStateException.class, refused.getCause().getClass());
    assertEquals(
        "A followed client was closed from a thread of its own work, which would wait for"
            + " itself: close it from the thread that opened it.",
        refused.getCause().getMessage());
    assertTrue(named.get().matches("sail-follow-[0-9]+"), named.get());
  }
}
