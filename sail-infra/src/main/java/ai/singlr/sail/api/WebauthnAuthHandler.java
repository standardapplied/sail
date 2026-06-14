/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.auth.EnrollmentTickets;
import ai.singlr.sail.auth.PasskeyCeremonies;
import ai.singlr.sail.auth.PasskeyException;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Serves the passkey ceremony endpoints under {@code /v1/auth}. An admin mints an enrollment ticket
 * ({@code POST /register/ticket}); registration ({@code /register/start|finish}) then enrolls a
 * passkey for an FDE, authorized by either that ticket (so a browser, which cannot carry an
 * operator token, can enroll) or an authenticated {@link Capability#ADMIN} caller. Login ({@code
 * /login/start|finish}) is unauthenticated by design (the caller has no credential yet) and, on
 * success, mints a session.
 *
 * <p>This handler is a thin adapter: it parses the JSON body, base64url-decodes the binary WebAuthn
 * fields, delegates to {@link PasskeyCeremonies}, and maps {@link PasskeyException} to an HTTP
 * status. When no relying party is configured ({@code ceremonies} is null) every route returns
 * {@link ErrorCode#PASSKEY_NOT_CONFIGURED} so the feature stays cleanly disabled.
 */
public final class WebauthnAuthHandler implements HttpHandler {

  private static final Base64.Decoder B64URL = Base64.getUrlDecoder();
  private static final Base64.Encoder B64URL_ENC = Base64.getUrlEncoder().withoutPadding();
  private static final String TICKET_HEADER = "X-Enrollment-Ticket";

  private final PasskeyCeremonies ceremonies;
  private final EnrollmentTickets enrollment;
  private final ApiAuth auth;
  private final String enrollOrigin;

  public WebauthnAuthHandler(
      PasskeyCeremonies ceremonies,
      EnrollmentTickets enrollment,
      ApiAuth auth,
      String enrollOrigin) {
    this.ceremonies = ceremonies;
    this.enrollment = enrollment;
    this.auth = Objects.requireNonNull(auth, "auth");
    this.enrollOrigin = enrollOrigin;
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
                  ErrorCode.INTERNAL, "Passkey request failed.", "Check sail API logs.", null, e)));
    } finally {
      exchange.close();
    }
  }

  private ApiResponse route(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      throw new ApiException(ErrorCode.METHOD_NOT_ALLOWED, "Passkey endpoints accept POST only.");
    }
    if (ceremonies == null) {
      throw new ApiException(
          ErrorCode.PASSKEY_NOT_CONFIGURED,
          "Passkey login is not configured on this server.",
          "Set the webauthn block in host.yaml or pass --origin to 'sail server start'.");
    }
    var path = exchange.getRequestURI().getPath();
    try {
      return switch (path) {
        case "/v1/auth/register/ticket" -> registerTicket(exchange);
        case "/v1/auth/register/start" -> registerStart(exchange);
        case "/v1/auth/register/finish" -> registerFinish(exchange);
        case "/v1/auth/login/start" -> loginStart(exchange);
        case "/v1/auth/login/finish" -> loginFinish(exchange);
        default -> throw new ApiException(ErrorCode.NOT_FOUND, "Unknown passkey endpoint.");
      };
    } catch (PasskeyException e) {
      throw new ApiException(mapped(e.kind()), e.getMessage());
    }
  }

  private ApiResponse registerTicket(HttpExchange exchange) throws IOException {
    requireAdmin(exchange);
    var ticket = enrollment.issue(requireString(JsonBody.readMap(exchange), "fde"));
    var result = new LinkedHashMap<String, Object>();
    result.put("ticket", ticket.ticket());
    result.put("fde", ticket.fdeHandle());
    result.put("expires_at", ticket.expiresAt());
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
    if (ticket != null) {
      if (!enrollment.consume(ticket)) {
        throw ticketDenied();
      }
    } else {
      requireAdmin(exchange);
    }
    var registration =
        ceremonies.finishRegistration(
            requireString(body, "challenge_id"),
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
   * ticket's FDE; otherwise an authenticated {@link Capability#ADMIN} caller registers the FDE
   * named in the body.
   */
  private String authorizeEnrollmentStart(HttpExchange exchange, Map<String, Object> body) {
    var ticket = enrollmentTicket(exchange);
    if (ticket != null) {
      return enrollment.authorize(ticket).orElseThrow(WebauthnAuthHandler::ticketDenied);
    }
    requireAdmin(exchange);
    return requireString(body, "fde");
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
    return ApiResponse.ok(ceremonyBody(ceremonies.startLogin()));
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
    result.put("expires_at", login.expiresAt());
    return ApiResponse.ok(result);
  }

  private void requireAdmin(HttpExchange exchange) {
    auth.require(exchange);
    Authorizer.require(exchange, Capability.ADMIN);
  }

  private static Map<String, Object> ceremonyBody(PasskeyCeremonies.Ceremony ceremony) {
    var body = new LinkedHashMap<String, Object>();
    body.put("challenge_id", ceremony.challengeId());
    body.put("public_key", ceremony.publicKey());
    return body;
  }

  private static ErrorCode mapped(PasskeyException.Kind kind) {
    return switch (kind) {
      case NOT_FOUND -> ErrorCode.NOT_FOUND;
      case BAD_REQUEST -> ErrorCode.INVALID_REQUEST;
      case UNAUTHORIZED -> ErrorCode.AUTHENTICATION_FAILED;
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
