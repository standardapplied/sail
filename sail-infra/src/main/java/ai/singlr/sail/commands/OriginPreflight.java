/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Preflights a tunneled passkey ceremony. {@code /v1/auth/login/start} advertises the box's allowed
 * WebAuthn origins; the server later matches the ceremony origin exactly, so when the canonical
 * tunnel origin {@link SshTunnel#ORIGIN} is not among them the ceremony is doomed before the
 * browser opens. Failing here names the exact {@code host.yaml} block to add instead of leaving the
 * engineer with a bare in-browser ceremony error.
 */
final class OriginPreflight {

  private OriginPreflight() {}

  static void requireCanonicalOrigin(String box) throws IOException, InterruptedException {
    check(box, loginStartBody());
  }

  static void check(String box, Map<String, Object> body) {
    if (body.get("error") instanceof Map<?, ?> error) {
      if ("passkey_not_configured".equals(error.get("code"))) {
        throw new IllegalStateException(
            originGuidance(box, "Passkey login is not configured on " + box + "."));
      }
      throw new IllegalStateException(
          "The control plane on "
              + box
              + " refused the passkey preflight: "
              + error.get("message"));
    }
    if (body.get("origins") instanceof List<?> origins && !origins.contains(SshTunnel.ORIGIN)) {
      throw new IllegalStateException(
          originGuidance(
              box,
              "The box "
                  + box
                  + " does not allowlist the tunnel origin "
                  + SshTunnel.ORIGIN
                  + "."));
    }
  }

  private static String originGuidance(String box, String lead) {
    return """
        %s
          Tunneled ceremonies run at %s, and the server matches that origin exactly.
          Add this webauthn block to host.yaml on %s:

              webauthn:
                rp_id: localhost
                origins:
                  - http://localhost:7070

          Then restart the control plane there: sudo sail host service restart"""
        .formatted(lead, SshTunnel.ORIGIN, box);
  }

  private static Map<String, Object> loginStartBody() throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + SshTunnel.PORT + "/v1/auth/login/start"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      return YamlUtil.parseMap(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }
  }
}
