/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Serves the two static passkey ceremony pages at the Relying Party origin: {@code /login} runs
 * {@code navigator.credentials.get()} and {@code /enroll} runs {@code
 * navigator.credentials.create()}. They must be same-origin as the {@code /v1/auth} API (WebAuthn
 * binds the credential to the page's origin and RP ID), which is exactly the proxy's {@code
 * https://} origin sail sits behind, so sail serves them itself rather than shipping a separate
 * frontend artifact.
 *
 * <p>The pages are stateless HTML with inline JavaScript (no bundler, no assets), so the handler
 * holds no state. On success a page hands the result back to a waiting CLI by redirecting to the
 * {@code redirect_uri} query parameter — accepted only when it is a loopback address, so a token
 * can never be redirected off the machine — echoing the opaque {@code state} nonce the CLI
 * supplied.
 */
public final class WebauthnPageHandler implements HttpHandler {

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try {
      var path = exchange.getRequestURI().getPath();
      if (!"GET".equals(exchange.getRequestMethod())) {
        send(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed");
        return;
      }
      var page =
          switch (path) {
            case "/login" -> LOGIN_PAGE;
            case "/enroll" -> ENROLL_PAGE;
            default -> null;
          };
      if (page == null) {
        send(exchange, 404, "text/plain; charset=utf-8", "Not Found");
      } else {
        send(exchange, 200, "text/html; charset=utf-8", page);
      }
    } finally {
      exchange.close();
    }
  }

  private static void send(HttpExchange exchange, int status, String contentType, String body)
      throws IOException {
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private static final String SHARED_SCRIPT =
      """
      const params = new URLSearchParams(location.search);
      const statusEl = () => document.getElementById('status');
      const setStatus = (m) => { statusEl().textContent = m; };
      const fail = (m) => { statusEl().textContent = m; statusEl().className = 'err'; };

      function b64urlToBuf(s) {
        s = s.replace(/-/g, '+').replace(/_/g, '/');
        const pad = s.length % 4 ? '='.repeat(4 - (s.length % 4)) : '';
        const bin = atob(s + pad);
        const out = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
        return out.buffer;
      }
      function bufToB64url(buf) {
        const bytes = new Uint8Array(buf);
        let bin = '';
        for (const b of bytes) bin += String.fromCharCode(b);
        return btoa(bin).replace(/\\+/g, '-').replace(/\\//g, '_').replace(/=+$/, '');
      }
      function handBack(token) {
        const ru = params.get('redirect_uri');
        if (ru && /^http:\\/\\/(127\\.0\\.0\\.1|localhost):[0-9]+(\\/|$)/.test(ru)) {
          const u = new URL(ru);
          if (token) u.searchParams.set('token', token);
          const st = params.get('state');
          if (st) u.searchParams.set('state', st);
          location.href = u.toString();
          return true;
        }
        return false;
      }
      async function postJson(path, body, headers) {
        const res = await fetch(path, {
          method: 'POST',
          headers: Object.assign({ 'Content-Type': 'application/json' }, headers || {}),
          body: body,
        });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
      }
      """;

  private static final String LOGIN_SCRIPT =
      """
      async function run() {
        try {
          setStatus('Starting sign-in…');
          const start = await postJson('/v1/auth/login/start', '{}');
          const pk = start.public_key;
          pk.challenge = b64urlToBuf(pk.challenge);
          if (pk.allowCredentials) {
            pk.allowCredentials = pk.allowCredentials.map(c =>
              Object.assign({}, c, { id: b64urlToBuf(c.id) }));
          }
          setStatus('Touch your passkey…');
          const cred = await navigator.credentials.get({ publicKey: pk });
          const r = cred.response;
          const out = await postJson('/v1/auth/login/finish', JSON.stringify({
            challenge_id: start.challenge_id,
            credential_id: bufToB64url(cred.rawId),
            client_data_json: bufToB64url(r.clientDataJSON),
            authenticator_data: bufToB64url(r.authenticatorData),
            signature: bufToB64url(r.signature),
            user_handle: r.userHandle ? bufToB64url(r.userHandle) : null,
          }));
          setStatus('Signed in as ' + out.fde + '.');
          if (!handBack(out.session_token)) {
            statusEl().textContent = 'Signed in as ' + out.fde
              + '. Session token: ' + out.session_token;
          }
        } catch (e) {
          fail('Sign-in failed: ' + e.message);
        }
      }
      window.addEventListener('load', run);
      """;

  private static final String ENROLL_SCRIPT =
      """
      async function run() {
        const ticket = params.get('ticket');
        if (!ticket) { fail('Missing enrollment ticket in the URL.'); return; }
        const headers = { 'X-Enrollment-Ticket': ticket };
        try {
          setStatus('Starting enrollment…');
          const start = await postJson('/v1/auth/register/start', '{}', headers);
          const pk = start.public_key;
          pk.challenge = b64urlToBuf(pk.challenge);
          pk.user.id = b64urlToBuf(pk.user.id);
          if (pk.excludeCredentials) {
            pk.excludeCredentials = pk.excludeCredentials.map(c =>
              Object.assign({}, c, { id: b64urlToBuf(c.id) }));
          }
          setStatus('Touch your authenticator to create a passkey…');
          const cred = await navigator.credentials.create({ publicKey: pk });
          const r = cred.response;
          const out = await postJson('/v1/auth/register/finish', JSON.stringify({
            challenge_id: start.challenge_id,
            client_data_json: bufToB64url(r.clientDataJSON),
            attestation_object: bufToB64url(r.attestationObject),
          }), headers);
          setStatus('Passkey registered for ' + out.fde + '. You can close this tab.');
          handBack('');
        } catch (e) {
          fail('Enrollment failed: ' + e.message);
        }
      }
      window.addEventListener('load', run);
      """;

  private static final String LOGIN_PAGE = page("Sign in to Sail", LOGIN_SCRIPT);
  private static final String ENROLL_PAGE = page("Enroll a Sail passkey", ENROLL_SCRIPT);

  private static String page(String title, String script) {
    return """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>%TITLE%</title>
          <style>
            body { font-family: system-ui, sans-serif; max-width: 32rem; margin: 4rem auto;
                   padding: 0 1rem; color: #1a1a1a; }
            h1 { font-size: 1.4rem; }
            #status { margin-top: 1rem; padding: 0.75rem 1rem; border-radius: 0.5rem;
                      background: #f1f5f9; }
            #status.err { background: #fee2e2; color: #991b1b; }
          </style>
        </head>
        <body>
          <h1>%TITLE%</h1>
          <p id="status">Loading…</p>
          <script>
        %SHARED%
        %SCRIPT%
          </script>
        </body>
        </html>
        """
        .replace("%TITLE%", title)
        .replace("%SHARED%", SHARED_SCRIPT)
        .replace("%SCRIPT%", script);
  }
}
