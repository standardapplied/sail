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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
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
  public record Link(SyncSession session, StringWriter log, List<String> notices, Thread server)
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

  public static Link connect(SyncRpcServer server, String box) throws IOException {
    return connect(server, box, SyncWire.MAX_FRAME, out -> out);
  }

  /**
   * Serves {@code server} on a virtual thread with the given frame bound, decorating its output
   * with {@code serverOut} so a test can cut the channel or interleave a write mid-round.
   */
  public static Link connect(
      SyncRpcServer server, String box, int frame, UnaryOperator<Writer> serverOut)
      throws IOException {
    var toServer = new PipedWriter();
    var serverIn = new BufferedReader(new PipedReader(toServer));
    var toClient = new PipedWriter();
    var clientIn = new BufferedReader(new PipedReader(toClient));
    var log = new StringWriter();
    var tee =
        new Writer() {
          @Override
          public void write(char[] buffer, int offset, int length) throws IOException {
            toServer.write(buffer, offset, length);
            log.write(buffer, offset, length);
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
            clientIn, tee, SyncWire.Hello.of(SyncWire.UPGRADE_FLOOR, box), notices::add);
    return new Link(session, log, notices, thread);
  }

  @Override
  public void close() {
    db.close();
  }
}
