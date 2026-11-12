/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.config.ListenConfig;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.store.ProjectStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;

/**
 * The ticket door: a caller asks for a ticket to open a terminal session in a project or to attach
 * to a session, and the terminal host answers, deciding it as it decides that open or attach and
 * issuing the ticket for the credential the caller presented here; this door answers it with the
 * address to present it at. The door reads the project an open names in the caller's scope first,
 * as every door reads the row it acts on, so a project the caller does not hold is not found here,
 * in the words the host gives; every refusal of the host's is {@code 403} in its words, and a host
 * that cannot be reached is the server's failure.
 */
public final class TerminalTickets {

  /** How the door asks the terminal host for a ticket, as the holder of a credential. */
  @FunctionalInterface
  public interface Host {

    /**
     * A ticket for the holder of {@code credential} to take {@code action} on {@code target};
     * throws a {@link Remedied} failure in the host's words when the host refuses, and any other
     * when it cannot be asked.
     */
    String ticket(String credential, PtyMessage.TicketAction action, String target)
        throws IOException;
  }

  private final ProjectStore catalog;
  private final Host host;
  private final InetSocketAddress address;

  /**
   * The door over {@code catalog}, asking {@code host}, whose tickets are presented at {@code
   * address}.
   */
  public TerminalTickets(ProjectStore catalog, Host host, InetSocketAddress address) {
    this.catalog = catalog;
    this.host = host;
    this.address = address;
  }

  /**
   * The ticket {@code request} asks for, {@code {"action": "open", "project": ...}} or {@code
   * {"action": "attach", "session": ...}}, issued to the holder of {@code credential}, the caller
   * bound to this request.
   */
  TicketResponse issue(String credential, Map<String, Object> request) {
    var action = action(request);
    var target =
        switch (action) {
          case OPEN -> {
            var project = text(request, "project");
            NameValidator.requireValidProjectName(project);
            ProjectGate.readable(catalog, project);
            yield project;
          }
          case ATTACH -> {
            var session = text(request, "session");
            NameValidator.requireValidSessionName(session);
            yield session;
          }
        };
    try {
      return new TicketResponse(
          host.ticket(credential, action, target),
          ListenConfig.hostPort(address),
          DateTimeUtils.format(DateTimeUtils.now().plus(PtyMessage.TICKET_LIFE)));
    } catch (IOException failed) {
      throw failed instanceof Remedied
          ? new ApiException(ErrorCode.FORBIDDEN, Remedied.whole(failed).plain())
          : ApiException.of(ErrorCode.INTERNAL, failed);
    }
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
