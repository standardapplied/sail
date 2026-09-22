/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * One box in a sync test: its own SQLite database with the full set of stores and a {@link
 * StoreReplica}. Shared by the in-process, over-the-wire, and conflict-resolution harnesses so the
 * fixture is defined once. {@link #connect} puts a server on a pipe and opens a protocol-4 session
 * against it, logging every request line so a test can assert what a round cost on the wire.
 */
public final class SyncBox implements AutoCloseable {

  public final String id;
  public final Sqlite db;
  public final SpecStore specs;
  public final SyncConflicts conflicts;
  public final SyncState syncState;
  public final StoreReplica replica;

  public SyncBox(Path dir, String id) {
    this(id, Sqlite.open(dir.resolve(id + ".db")));
  }

  public SyncBox(String id) {
    this(id, Sqlite.openMemory());
  }

  private SyncBox(String id, Sqlite db) {
    this.id = id;
    this.db = db;
    new SchemaManager(db).migrate();
    this.specs = new SpecStore(db);
    this.conflicts = new SyncConflicts(db);
    this.syncState = new SyncState(db);
    this.replica = new StoreReplica(id, specs, new ChangeLog(db), conflicts, syncState);
  }

  public static SpecStore.SpecRow spec(String id, String title, String status) {
    return new SpecStore.SpecRow(
        id,
        "proj",
        title,
        SpecStatus.fromWire(status),
        null,
        null,
        null,
        null,
        null,
        0,
        "uday",
        "",
        "",
        "uday",
        List.of(),
        List.of());
  }

  /** This box serving every registered type as main, to sessions authenticated as {@code as}. */
  public SyncRpcServer server(SyncPrincipal as) {
    return SyncRpcServer.over(
        db, id, as, FdeRoster.EMPTY, SyncTransitionSink.NONE, SyncWire.UPGRADE_FLOOR);
  }

  /** A protocol-4 session over a pipe to {@code server}, plus the wire log and the notices. */
  public record Link(
      SyncSession session,
      ai.singlr.sail.sync.ByteStreams.Output log,
      List<String> notices,
      Thread server)
      implements AutoCloseable {

    public SyncSession.TypeReport reconcile(String type, LocalReplica local) {
      return session.reconcile(type, local);
    }

    /** The {@code op} of every request this session has sent, in order. */
    public List<String> ops() {
      return log.toString()
          .lines()
          .map(line -> String.valueOf(YamlUtil.parseMap(line).get("op")))
          .toList();
    }

    public long count(String op) {
      return ops().stream().filter(op::equals).count();
    }

    @Override
    public void close() {
      try {
        session.close();
      } finally {
        try {
          server.join();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  public static Link connect(SyncRpcServer server, SyncBox box) throws IOException {
    return connect(server, box, SyncWire.MAX_FRAME, out -> out);
  }

  /**
   * Serves {@code server} on a virtual thread with the given frame bound, decorating its output
   * with {@code serverOut} so a test can cut the channel or interleave a write mid-round.
   */
  public static Link connect(
      SyncRpcServer server, SyncBox box, int frame, UnaryOperator<OutputStream> serverOut)
      throws IOException {
    return connect(server, box.db, box.id, frame, serverOut);
  }

  public static Link connect(
      SyncRpcServer server, Sqlite db, String box, int frame, UnaryOperator<OutputStream> serverOut)
      throws IOException {
    return connect(server, db, box, frame, serverOut, output -> output);
  }

  public static Link connect(
      SyncRpcServer server,
      SyncBox box,
      int frame,
      UnaryOperator<OutputStream> serverOut,
      UnaryOperator<OutputStream> nodeOut)
      throws IOException {
    return connect(server, box.db, box.id, frame, serverOut, nodeOut);
  }

  private static Link connect(
      SyncRpcServer server,
      Sqlite db,
      String box,
      int frame,
      UnaryOperator<OutputStream> serverOut,
      UnaryOperator<OutputStream> nodeOut)
      throws IOException {
    return connect(server, db, box, frame, serverOut, nodeOut, input -> input);
  }

  static Link connect(
      SyncRpcServer server,
      Sqlite db,
      String box,
      int frame,
      UnaryOperator<OutputStream> serverOut,
      UnaryOperator<OutputStream> nodeOut,
      UnaryOperator<InputStream> clientInput)
      throws IOException {
    var toServer = new PipedOutputStream();
    var serverIn = new BufferedInputStream(new PipedInputStream(toServer, 1024 * 1024));
    var toClient = new PipedOutputStream();
    var clientIn = new BufferedInputStream(new PipedInputStream(toClient, 1024 * 1024));
    var log = new ai.singlr.sail.sync.ByteStreams.Output();
    var tee =
        new OutputStream() {
          private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
          private int remaining;

          @Override
          public void write(int value) throws IOException {
            toServer.write(value);
            if (remaining > 0) {
              remaining--;
              return;
            }
            line.write(value);
            if (value == '\n') {
              var announcing = line.toString(java.nio.charset.StandardCharsets.UTF_8);
              log.write(announcing);
              var parsed = YamlUtil.parseMap(announcing);
              if ("chunk".equals(parsed.get("op")))
                remaining = ((Number) parsed.get("size")).intValue();
              line.reset();
            }
          }

          @Override
          public void write(byte[] buffer, int offset, int length) throws IOException {
            var end = offset + length;
            while (offset < end) {
              if (remaining > 0) {
                var count = Math.min(remaining, end - offset);
                toServer.write(buffer, offset, count);
                offset += count;
                remaining -= count;
              } else write(buffer[offset++] & 255);
            }
          }

          @Override
          public void flush() throws IOException {
            toServer.flush();
          }

          @Override
          public void close() throws IOException {
            toServer.close();
          }
        };
    var out = serverOut.apply(toClient);
    var thread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    server.serve(serverIn, out, frame);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  } finally {
                    try {
                      toClient.close();
                    } catch (IOException ignored) {
                      return;
                    }
                  }
                });
    var notices = new ArrayList<String>();
    var session =
        SyncSession.open(
            clientInput.apply(clientIn),
            nodeOut.apply(tee),
            SyncWire.Hello.of(SyncWire.UPGRADE_FLOOR, box),
            notices::add,
            db);
    return new Link(session, log, notices, thread);
  }

  public static SyncEngine.Report round(Sqlite main, Sqlite node, String type) {
    var server =
        SyncRpcServer.over(
            main,
            "main",
            new SyncPrincipal("node", true),
            FdeRoster.EMPTY,
            SyncTransitionSink.NONE,
            SyncWire.UPGRADE_FLOOR);
    try (var link = connect(server, node, "node", SyncWire.MAX_FRAME, out -> out)) {
      return link.reconcile(type, SyncedEntities.replicas(node, "node", "node").get(type)).report();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() {
    db.close();
  }
}
