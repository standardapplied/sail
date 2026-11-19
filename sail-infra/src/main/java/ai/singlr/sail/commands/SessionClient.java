/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtyWire;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A thin, testable client of the pty session host's socket — one call per command verb. */
public final class SessionClient implements AutoCloseable {

  private final SocketChannel channel;
  private String hostBootId = "";

  private SessionClient(SocketChannel channel) {
    this.channel = channel;
  }

  /** The boot id the host welcomed this connection with — see {@link PtyMessage.Welcome}. */
  public String hostBootId() {
    return hostBootId;
  }

  public static SessionClient connect(Path socket) throws IOException {
    return connect(socket, java.util.Objects.toString(System.getenv("SAIL_TOKEN"), ""));
  }

  /** Connects, handshakes, and identifies with {@code token} — blank means the box's owner. */
  public static SessionClient connect(Path socket, String token) throws IOException {
    var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    try {
      channel.connect(UnixDomainSocketAddress.of(socket));
      PtyWire.handshake(channel, channel);
    } catch (IOException e) {
      channel.close();
      throw new IOException(
          "No session host at " + socket + ". Is the sail-pty-host service running?", e);
    }
    var client = new SessionClient(channel);
    PtyWire.write(channel, new PtyMessage.Hello(token));
    client.hostBootId = client.expectWelcome();
    return client;
  }

  private String expectWelcome() throws IOException {
    var reply = PtyWire.read(channel);
    if (reply instanceof PtyMessage.Err(var reason, var remedy, _)) {
      throw Remedied.io(reason, remedy);
    }
    if (!(reply instanceof PtyMessage.Welcome(var bootId))) {
      throw new IOException("Unexpected host reply to hello: " + reply.getClass());
    }
    return bootId;
  }

  public void create(
      String name,
      List<String> command,
      String cwd,
      String project,
      String room,
      int cols,
      int rows)
      throws IOException {
    PtyWire.write(channel, new PtyMessage.Create(name, command, cwd, project, room, cols, rows));
    expectOk("create");
  }

  /** Every session the host admits this identity to, gathered page by page in name order. */
  public List<PtyMessage.SessionInfo> list() throws IOException {
    var all = new ArrayList<PtyMessage.SessionInfo>();
    var after = "";
    while (true) {
      PtyWire.write(channel, new PtyMessage.ListSessions(after, PtyMessage.PAGE_LIMIT));
      if (!(PtyWire.read(channel) instanceof PtyMessage.Sessions(var page, var next))) {
        throw new IOException("The host did not answer the session listing.");
      }
      all.addAll(page);
      if (next.isEmpty()) {
        return List.copyOf(all);
      }
      if (next.compareTo(after) <= 0) {
        throw new IOException(
            "The host's session listing cursor went backwards; refusing to loop.");
      }
      after = next;
    }
  }

  public void kill(String name) throws IOException {
    PtyWire.write(channel, new PtyMessage.Kill(name));
    expectOk("kill");
  }

  /** Ends a live session with {@code reason} in its stream and on its ending event. */
  public void yieldSession(String name, String reason) throws IOException {
    PtyWire.write(channel, new PtyMessage.Yield(name, reason));
    expectOk("yield");
  }

  /**
   * What the host at {@code socket} answers a request for a ticket for the holder of {@code
   * credential} to take {@code action} on {@code target} over its TCP listener: a {@link
   * PtyMessage.Ticket} or a {@link PtyMessage.TicketRefused}. The API's door onto the host ({@code
   * TerminalTickets.Host}): it asks as the box's own process, a blank token, and the credential
   * rides in the request, which the host resolves itself for that decision alone.
   */
  public static PtyMessage ticket(
      Path socket, String credential, PtyMessage.TicketAction action, String target)
      throws IOException {
    return ticket(socket, credential, action, target, "");
  }

  /** As {@link #ticket(Path, String, PtyMessage.TicketAction, String)}, in {@code workspace}. */
  public static PtyMessage ticket(
      Path socket,
      String credential,
      PtyMessage.TicketAction action,
      String target,
      String workspace)
      throws IOException {
    try (var client = connect(socket, "")) {
      PtyWire.write(
          client.channel, new PtyMessage.IssueTicket(action, target, credential, workspace));
      var reply = PtyWire.read(client.channel);
      if (!(reply instanceof PtyMessage.Ticket) && !(reply instanceof PtyMessage.TicketRefused)) {
        throw new IOException("Unexpected host reply to a ticket request: " + reply.getClass());
      }
      return reply;
    }
  }

  /**
   * Every page of the sessions the holder of {@code credential} may attach to, asked on {@code
   * socket} by this box's own process as a ticket is: the {@link PtyMessage.Sessions}, or the
   * host's refusal ({@link PtyMessage.TicketRefused}, {@link PtyMessage.Err}) as it answered.
   */
  public static PtyMessage sessionsFor(Path socket, String credential) throws IOException {
    try (var client = connect(socket, "")) {
      var all = new ArrayList<PtyMessage.SessionInfo>();
      var after = "";
      while (true) {
        PtyWire.write(
            client.channel, new PtyMessage.ListSessions(after, PtyMessage.PAGE_LIMIT, credential));
        var reply = PtyWire.read(client.channel);
        if (!(reply instanceof PtyMessage.Sessions(var page, var next))) {
          return reply;
        }
        all.addAll(page);
        if (next.isEmpty()) {
          return new PtyMessage.Sessions(List.copyOf(all), "");
        }
        if (next.compareTo(after) <= 0) {
          throw new IOException(
              "The host's session listing cursor went backwards; refusing to loop.");
        }
        after = next;
      }
    }
  }

  /** Sends the attach request and confirms it; frame traffic then belongs to the caller. */
  public SocketChannel attach(String name, boolean write) throws IOException {
    PtyWire.write(channel, new PtyMessage.Attach(name, write));
    expectOk("attach");
    return channel;
  }

  private void expectOk(String verb) throws IOException {
    var reply = PtyWire.read(channel);
    if (reply instanceof PtyMessage.Err(var reason, var remedy, _)) {
      throw Remedied.io(reason, remedy);
    }
    if (!(reply instanceof PtyMessage.Ok)) {
      throw new IOException("Unexpected host reply to " + verb + ": " + reply.getClass());
    }
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
