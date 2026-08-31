/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

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

  private SessionClient(SocketChannel channel) {
    this.channel = channel;
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
    client.expectOk("hello");
    return client;
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

  /** Sends the attach request and confirms it; frame traffic then belongs to the caller. */
  public SocketChannel attach(String name, boolean write) throws IOException {
    PtyWire.write(channel, new PtyMessage.Attach(name, write));
    expectOk("attach");
    return channel;
  }

  private void expectOk(String verb) throws IOException {
    var reply = PtyWire.read(channel);
    if (reply instanceof PtyMessage.Err(var message)) {
      throw new IOException(message);
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
