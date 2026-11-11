/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.auth.EnrollmentTickets;
import ai.singlr.sail.auth.PasskeyCeremonies;
import ai.singlr.sail.auth.PasskeyException;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Serves the passkey ceremony endpoints under {@code /v1/auth}. An admin mints an enrollment ticket
 * ({@code POST /register/ticket}); registration ({@code /register/start|finish}) then enrolls a
 * passkey for an FDE, authorized by either that ticket (so a browser, which cannot carry an
 * operator token, can enroll) or a caller who may manage people. Login ({@code
 * /login/start|finish}) is unauthenticated by design (the caller has no credential yet) and, on
 * success, mints a session. Each route declares its {@link Gate} (A3); the people gate is asked of
 * the principal the server's own authentication resolves, a passkey session's included.
 *
 * <p>This handler is a thin adapter: it parses the JSON body, base64url-decodes the binary WebAuthn
 * fields, delegates to {@link PasskeyCeremonies}, and maps {@link PasskeyException} to an HTTP
 * status. When no relying party is configured ({@code ceremonies} is null) every route returns
 * {@link ErrorCode#PASSKEY_NOT_CONFIGURED} so the feature stays cleanly disabled.
 *
 * <p>{@code /login/start} additionally advertises the allowed WebAuthn {@code origins} — they are
 * public by nature (they are the URLs a browser signs in at), and a tunneled CLI preflights them to
 * fail with configuration guidance instead of a doomed browser ceremony.
 */
public final class WebauthnAuthHandler implements HttpHandler, RouteTable {

  private static final Base64.Decoder B64URL = Base64.getUrlDecoder();
  private static final Base64.Encoder B64URL_ENC = Base64.getUrlEncoder().withoutPadding();
  private static final String TICKET_HEADER = "X-Enrollment-Ticket";
  private static final String POST = "POST";

  /** The exchange attribute {@link #admit} sets once its caller passed the people gate. */
  private static final String PEOPLE_GATE_PASSED = "passkey.admitted";

  private static final Gate ENROLLMENT =
      Gate.unauthenticated("an enrollment ticket authorizes it; without one, the people gate does");
  private static final Gate SIGN_IN =
      Gate.unauthenticated("the caller signs in with a passkey: it holds no credential yet");

  private final PasskeyCeremonies ceremonies;
  private final EnrollmentTickets enrollment;
  private final SessionAwareAuth auth;
  private final List<String> origins;
  private final String enrollOrigin;
  private final List<Route<Ceremony>> routes =
      List.of(
          new Route<>(POST, "/v1/auth/register/ticket", Gate.PEOPLE, this::registerTicket),
          new Route<>(POST, "/v1/auth/register/start", ENROLLMENT, this::registerStart),
          new Route<>(POST, "/v1/auth/register/finish", ENROLLMENT, this::registerFinish),
          new Route<>(POST, "/v1/auth/login/start", SIGN_IN, this::loginStart),
          new Route<>(POST, "/v1/auth/login/finish", SIGN_IN, this::loginFinish));

  /**
   * The passkey endpoints, authenticating a caller by {@code auth}, the server's own: an admin
   * reaches them with a passkey session as with an API token.
   */
  public WebauthnAuthHandler(
      PasskeyCeremonies ceremonies,
      EnrollmentTickets enrollment,
      SessionAwareAuth auth,
      List<String> origins) {
    this.ceremonies = ceremonies;
    this.enrollment = enrollment;
    this.auth = Objects.requireNonNull(auth, "auth");
    this.origins = origins == null ? List.of() : List.copyOf(origins);
    this.enrollOrigin = this.origins.isEmpty() ? null : this.origins.getFirst();
  }

  /** What answers one passkey route. */
  @FunctionalInterface
  private interface Ceremony {
    ApiResponse answer(HttpExchange exchange) throws IOException;
  }

  @Override
  public List<Route<?>> routes() {
    return List.copyOf(routes);
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try {
      write(exchange, route(exchange));
    } catch (ApiException e) {
      write(exchange, ApiResponse.error(e.failure()));
    } catch (IllegalArgumentException e) {
      write(
          exchange,
          ApiResponse.error(
              new Result.Failure<>(ErrorCode.INVALID_REQUEST, e.getMessage(), null, null, e)));
    } catch (Exception e) {
      write(
          exchange,
          ApiResponse.error(
              new Result.Failure<>(
                  ErrorCode.INTERNAL,
                  "Passkey request failed.",
                  Remedy.of("Check sail API logs."),
                  null,
                  e)));
    } finally {
      exchange.close();
    }
  }

  private ApiResponse route(HttpExchange exchange) throws IOException {
    var method = exchange.getRequestMethod();
    var served = RouteTable.serve(routes, method, exchange.getRequestURI().getPath());
    if (served instanceof RouteTable.Served.MethodNotAllowed<Ceremony>
        || served instanceof RouteTable.Served.NotFound<Ceremony> && !POST.equals(method)) {
      throw new ApiException(ErrorCode.METHOD_NOT_ALLOWED, "Passkey endpoints accept POST only.");
    }
    if (ceremonies == null) {
      throw new ApiException(
          ErrorCode.PASSKEY_NOT_CONFIGURED,
          "Passkey login is not configured on this server.",
          Remedy.of(
              "Set the webauthn block in host.yaml or pass --origin to '",
              Remedy.sail("server start"),
              "'."));
    }
    if (!(served instanceof RouteTable.Served.Match<Ceremony>(var route, _))) {
      throw new ApiException(ErrorCode.NOT_FOUND, "Unknown passkey endpoint.");
    }
    try {
      if (!(route.gate() instanceof Gate.Unauthenticated)) {
        admit(exchange, route.gate());
      }
      return route.handler().answer(exchange);
    } catch (PasskeyException e) {
      throw refusal(e, exchange.getAttribute(PEOPLE_GATE_PASSED) != null);
    }
  }

  private ApiResponse registerTicket(HttpExchange exchange) throws IOException {
    var ticket = enrollment.issue(requireString(JsonBody.readMap(exchange), "fde"));
    var result = new LinkedHashMap<String, Object>();
    result.put("ticket", ticket.ticket());
    result.put("fde", ticket.fdeHandle());
    result.put("expires_at", DateTimeUtils.format(ticket.expiresAt()));
    if (enrollOrigin != null) {
      result.put("enroll_url", enrollOrigin + "/enroll?ticket=" + ticket.ticket());
    }
    return ApiResponse.ok(result);
  }

  private ApiResponse registerStart(HttpExchange exchange) throws IOException {
    var body = JsonBody.readMap(exchange);
    var fdeHandle = authorizeEnrollmentStart(exchange, body);
    return ApiResponse.ok(ceremonyBody(ceremonies.startRegistration(fdeHandle)));
  }

  private ApiResponse registerFinish(HttpExchange exchange) throws IOException {
    var body = JsonBody.readMap(exchange);
    var ticket = enrollmentTicket(exchange);
    var enrolling = ticket == null ? null : spent(ticket);
    if (ticket == null) {
      admit(exchange, Gate.PEOPLE);
    }
    var registration =
        ceremonies.finishRegistration(
            requireString(body, "challenge_id"),
            enrolling,
            decode(body, "client_data_json"),
            decode(body, "attestation_object"),
            optionalString(body, "label"));
    var result = new LinkedHashMap<String, Object>();
    result.put("status", "registered");
    result.put("fde", registration.fdeHandle());
    result.put("credential_id", B64URL_ENC.encodeToString(registration.credentialId()));
    return ApiResponse.ok(result);
  }

  /**
   * Authorizes a registration start: a valid {@code X-Enrollment-Ticket} binds the ceremony to the
   * ticket's FDE; otherwise a caller who passes the people gate registers the FDE named in the
   * body.
   */
  private String authorizeEnrollmentStart(HttpExchange exchange, Map<String, Object> body) {
    var ticket = enrollmentTicket(exchange);
    if (ticket != null) {
      return enrollment.authorize(ticket).orElseThrow(WebauthnAuthHandler::ticketDenied);
    }
    admit(exchange, Gate.PEOPLE);
    return requireString(body, "fde");
  }

  /** The FDE {@code ticket} enrolls, the ticket spent on this registration; refused once spent. */
  private String spent(String ticket) {
    return enrollment
        .authorize(ticket)
        .filter(enrolls -> enrollment.consume(ticket))
        .orElseThrow(WebauthnAuthHandler::ticketDenied);
  }

  private static String enrollmentTicket(HttpExchange exchange) {
    var value = exchange.getRequestHeaders().getFirst(TICKET_HEADER);
    return Strings.isBlank(value) ? null : value;
  }

  private static ApiException ticketDenied() {
    return new ApiException(ErrorCode.FORBIDDEN, "Enrollment ticket is invalid or expired.");
  }

  private ApiResponse loginStart(HttpExchange exchange) throws IOException {
    JsonBody.readMap(exchange);
    var body = ceremonyBody(ceremonies.startLogin());
    if (!origins.isEmpty()) {
      body.put("origins", origins);
    }
    return ApiResponse.ok(body);
  }

  private ApiResponse loginFinish(HttpExchange exchange) throws IOException {
    var body = JsonBody.readMap(exchange);
    var login =
        ceremonies.finishLogin(
            requireString(body, "challenge_id"),
            decode(body, "credential_id"),
            decode(body, "client_data_json"),
            decode(body, "authenticator_data"),
            decode(body, "signature"),
            optionalDecode(body, "user_handle"));
    var result = new LinkedHashMap<String, Object>();
    result.put("session_token", login.sessionToken());
    result.put("fde", login.fdeHandle());
    result.put("expires_at", DateTimeUtils.format(login.expiresAt()));
    return ApiResponse.ok(result);
  }

  /** Authenticates {@code exchange}'s caller and admits it past {@code gate}, or refuses it. */
  private void admit(HttpExchange exchange, Gate gate) {
    auth.require(exchange);
    gate.admit(ApiRouter.actorOf(exchange));
    exchange.setAttribute(PEOPLE_GATE_PASSED, gate);
  }

  private static Map<String, Object> ceremonyBody(PasskeyCeremonies.Ceremony ceremony) {
    var body = new LinkedHashMap<String, Object>();
    body.put("challenge_id", ceremony.challengeId());
    body.put("public_key", ceremony.publicKey());
    return body;
  }

  /**
   * A ceremony's refusal as the API answers it. A disabled person is a failed authentication to
   * whoever presents their ticket or passkey, and a conflict to an admin who did authenticate,
   * minting a ticket or registering for them: no admin is told their own credential failed.
   */
  private static ApiException refusal(PasskeyException refused, boolean authenticated) {
    var message = refused.getMessage();
    return switch (refused.kind()) {
      case NOT_FOUND -> new ApiException(ErrorCode.NOT_FOUND, message);
      case BAD_REQUEST -> new ApiException(ErrorCode.INVALID_REQUEST, message);
      case UNAUTHORIZED -> new ApiException(ErrorCode.AUTHENTICATION_FAILED, message);
      case DISABLED ->
          authenticated
              ? new ApiException(ErrorCode.CONFLICT, message)
              : new ApiException(
                  ErrorCode.AUTHENTICATION_FAILED, message + " Ask an admin to re-enable it.");
    };
  }

  private static String requireString(Map<String, Object> body, String key) {
    var value = body.get(key);
    if (value == null || value.toString().isBlank()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "Missing required field: " + key);
    }
    return value.toString();
  }

  private static String optionalString(Map<String, Object> body, String key) {
    var value = body.get(key);
    return value == null ? null : value.toString();
  }

  private static byte[] decode(Map<String, Object> body, String key) {
    try {
      return B64URL.decode(requireString(body, key));
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          ErrorCode.INVALID_REQUEST, "Field '" + key + "' is not valid base64url.");
    }
  }

  private static byte[] optionalDecode(Map<String, Object> body, String key) {
    var value = optionalString(body, key);
    if (Strings.isBlank(value)) {
      return null;
    }
    try {
      return B64URL.decode(value);
    } catch (IllegalArgumentException e) {
      throw new ApiException(
          ErrorCode.INVALID_REQUEST, "Field '" + key + "' is not valid base64url.");
    }
  }

  private static void write(HttpExchange exchange, ApiResponse response) throws IOException {
    var bytes =
        YamlUtil.dumpJson(new LinkedHashMap<>(response.body())).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(response.status(), bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
