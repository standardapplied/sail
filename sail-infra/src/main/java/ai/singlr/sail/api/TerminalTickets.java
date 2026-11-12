/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.ListenConfig;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.pty.PtyMessage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;

/**
 * The ticket door: a caller asks for a ticket to open a terminal session in a project or to attach
 * to a session, and the terminal host answers. The host resolves the credential the caller
 * presented here itself, decides the request as it decides that open or attach, and issues the
 * ticket; this door answers it with the address to present it at. A refusal keeps the host's words
 * and its kind: not found is {@code 404}, refused {@code 403}, a cap reached {@code 429}, and a
 * host that cannot serve the request, or cannot be reached, {@code 503}.
 */
public final class TerminalTickets {

  /** How the door asks the terminal host for a ticket, for the holder of a credential. */
  @FunctionalInterface
  public interface Host {

    /**
     * The host's answer to a ticket for the holder of {@code credential} to take {@code action} on
     * {@code target}: a {@link PtyMessage.Ticket} or a {@link PtyMessage.TicketRefused}; throws
     * when the host cannot be asked.
     */
    PtyMessage ticket(String credential, PtyMessage.TicketAction action, String target)
        throws IOException;
  }

  private final Host host;
  private final InetSocketAddress address;

  /** The door asking {@code host}, whose tickets are presented at {@code address}. */
  public TerminalTickets(Host host, InetSocketAddress address) {
    this.host = host;
    this.address = address;
  }

  /**
   * The ticket {@code request} asks for, {@code {"action": "open", "project": ...}} or {@code
   * {"action": "attach", "session": ...}}, issued to the holder of {@code credential}, the
   * credential this request was authenticated by.
   */
  TicketResponse issue(String credential, Map<String, Object> request) {
    var action = action(request);
    var target =
        switch (action) {
          case OPEN -> {
            var project = text(request, "project");
            NameValidator.requireValidProjectName(project);
            yield project;
          }
          case ATTACH -> {
            var session = text(request, "session");
            NameValidator.requireValidSessionName(session);
            yield session;
          }
        };
    PtyMessage answer;
    try {
      answer = host.ticket(credential, action, target);
    } catch (IOException unreachable) {
      throw ApiException.of(ErrorCode.TERMINAL_UNAVAILABLE, unreachable);
    }
    return switch (answer) {
      case PtyMessage.Ticket(var ticket) ->
          new TicketResponse(
              ticket,
              ListenConfig.hostPort(address),
              DateTimeUtils.format(DateTimeUtils.now().plus(PtyMessage.TICKET_LIFE)));
      case PtyMessage.TicketRefused(var kind, var detail) ->
          throw new ApiException(code(kind), detail.plain());
      default ->
          throw new ApiException(
              ErrorCode.TERMINAL_UNAVAILABLE, "The terminal host answered no ticket.");
    };
  }

  private static ErrorCode code(PtyMessage.TicketRefusal kind) {
    return switch (kind) {
      case INVALID -> ErrorCode.INVALID_REQUEST;
      case NOT_FOUND -> ErrorCode.NOT_FOUND;
      case FORBIDDEN -> ErrorCode.FORBIDDEN;
      case LIMITED -> ErrorCode.RATE_LIMITED;
      case UNAVAILABLE -> ErrorCode.TERMINAL_UNAVAILABLE;
    };
  }

  private static PtyMessage.TicketAction action(Map<String, Object> request) {
    try {
      return PtyMessage.TicketAction.of(text(request, "action"));
    } catch (IOException neither) {
      throw new IllegalArgumentException(neither.getMessage());
    }
  }

  private static String text(Map<String, Object> request, String field) {
    return request.get(field) instanceof String value ? value : null;
  }
}
