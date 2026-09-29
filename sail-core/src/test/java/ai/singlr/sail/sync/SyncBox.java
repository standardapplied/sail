/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import java.io.BufferedInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
  private Actor session;
  private String handle;

  public SyncBox(Path dir, String id) {
    this(id, Sqlite.open(dir.resolve(id + ".db")));
  }

  public SyncBox(String id) {
    this(id, Sqlite.openMemory());
  }

  /**
   * The box {@code id} over the database file {@code file} a test's production wiring already
   * opened, so its rounds and convergence can be checked with this fixture.
   */
  public static SyncBox opening(Path file, String id) {
    return new SyncBox(id, Sqlite.open(file));
  }

  private SyncBox(String id, Sqlite db) {
    this.id = id;
    this.db = db;
    new SchemaManager(db).migrate();
    this.specs = new SpecStore(db);
    this.conflicts = new SyncConflicts(db);
    this.syncState = new SyncState(db);
    this.replica = new StoreReplica(id, specs, new ChangeLog(db), conflicts, syncState);
    syncsAs(Actor.sync(id, Role.MEMBER));
  }

  /**
   * This box syncing to main as {@code actor}, whose handle becomes the box's sync handle; by
   * default a member whose handle is the box's id.
   */
  public SyncBox syncsAs(Actor actor) {
    this.session = actor;
    this.handle = actor.handle();
    return this;
  }

  /** This box configured with sync handle {@code handle}, whoever main authenticates it as. */
  public SyncBox configuredAs(String handle) {
    this.handle = handle;
    return this;
  }

  /** The actor main authenticates this box's sessions as. */
  public Actor session() {
    return session;
  }

  /** This box's configured sync handle. */
  public String handle() {
    return handle;
  }

  /** Every registered type's replica over this box, for its own handle. */
  public Map<String, StoreReplica> replicas() {
    return SyncedEntities.replicas(db, id, handle());
  }

  /**
   * One full round of {@code node} against {@code main}, as a node runs it: the session begins
   * ({@link NodeRound#begin}), then every registered type reconciles in registry order, a type's
   * failure recorded against it while the rest of the round runs.
   */
  public static List<SyncSession.TypeReport> round(SyncBox main, SyncBox node) {
    try (var link = connect(main.server(node.session()), node)) {
      Actor.run(Actor.main(), () -> NodeRound.begin(link.session(), node.db, node.handle()));
      var replicas = node.replicas();
      return SyncedEntities.all().stream()
          .map(entity -> reconcileOrFail(link, entity.type(), replicas.get(entity.type())))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static SyncSession.TypeReport reconcileOrFail(
      Link link, String type, LocalReplica local) {
    try {
      return link.reconcile(type, local);
    } catch (SyncTransportException e) {
      return SyncSession.TypeReport.failed(type, e.getMessage());
    }
  }

  /**
   * Runs full rounds of every node until one more pass is clean on every box — no failure, no
   * denial, nothing moved and no open conflict — failing, with every report of the last pass, when
   * the fleet has not settled within a few passes.
   */
  public static void quiesce(SyncBox main, SyncBox... nodes) {
    var unsettled = List.<String>of();
    for (var pass = 0; pass < QUIESCE_PASSES; pass++) {
      unsettled = unsettled(main, nodes);
      if (unsettled.isEmpty()) {
        return;
      }
    }
    fail("the fleet did not settle:\n" + String.join("\n", unsettled));
  }

  private static final int QUIESCE_PASSES = 6;

  private static List<String> unsettled(SyncBox main, SyncBox... nodes) {
    var unsettled = new ArrayList<String>();
    for (var node : nodes) {
      for (var report : round(main, node)) {
        if (report.failure() != null
            || !report.denials().isEmpty()
            || report.report().total() != 0) {
          unsettled.add(node.id + " " + report);
        }
      }
      node.conflicts.pending().forEach(conflict -> unsettled.add(node.id + " " + conflict));
    }
    return unsettled;
  }

  /**
   * Asserts that {@code node} holds exactly main's version of every synced entity either box has in
   * its change log: its comparable snapshot, authorship included, its current rev, and its head's
   * kind and author. An id main never held has no live row on the node, though the history the node
   * withdrew stays. Every mismatch is reported at once.
   */
  public static void assertEqualToMain(SyncBox main, SyncBox node) {
    var mismatches = new ArrayList<String>();
    var mains = main.replicas();
    var nodes = node.replicas();
    for (var entity : SyncedEntities.all()) {
      var type = entity.type();
      var ids = new LinkedHashSet<>(loggedIds(main, type));
      ids.addAll(loggedIds(node, type));
      for (var id : ids) {
        var expected = version(main, mains.get(type), type, id);
        var actual = version(node, nodes.get(type), type, id);
        if (expected == null) {
          if (nodes.get(type).current(id) != null) {
            mismatches.add(type + " " + id + ": main holds none, " + node.id + " holds " + actual);
          }
        } else if (!expected.equals(actual)) {
          mismatches.add(
              type + " " + id + "\n   main: " + expected + "\n   " + node.id + ": " + actual);
        }
      }
    }
    if (!mismatches.isEmpty()) {
      fail(node.id + " differs from main:\n" + String.join("\n", mismatches));
    }
  }

  private static List<String> loggedIds(SyncBox box, String type) {
    return box.db.query(
        "SELECT DISTINCT entity_id FROM change_log WHERE entity_type = ? ORDER BY entity_id",
        row -> row.text(0),
        type);
  }

  /** What {@code box} holds of one entity, as convergence compares it; null when it logs none. */
  private static Map<String, Object> version(
      SyncBox box, StoreReplica replica, String type, String id) {
    var head = new ChangeLog(box.db).head(type, id).orElse(null);
    if (head == null) {
      return null;
    }
    var version = new LinkedHashMap<String, Object>();
    version.put("current", replica.current(id));
    version.put("rev", replica.currentRev(id));
    version.put("kind", head.kind());
    version.put("author", head.actor());
    return version;
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

  /**
   * Creates {@code row} as this box's FDE — the operator whose handle is the box's id — the only
   * creator main takes a node's create from.
   */
  public void create(SpecStore.SpecRow row) {
    Acting.as(id, () -> specs.create(row));
  }

  /** This box serving every registered type as main, to sessions authenticated as {@code as}. */
  public SyncRpcServer server(Actor as) {
    return SyncRpcServer.over(
        db, id, null, as, FdeRoster.EMPTY, SyncTransitionSink.NONE, SyncWire.UPGRADE_FLOOR);
  }

  /** A protocol-4 session over a pipe to {@code server}, plus the wire log and the notices. */
  public record Link(
      SyncSession session,
      ai.singlr.sail.sync.ByteStreams.Output log,
      List<String> notices,
      Thread server)
      implements AutoCloseable {

    /** One type's round as a node runs it: adopting what main decided, as {@code main}. */
    public SyncSession.TypeReport reconcile(String type, LocalReplica local) {
      return SyncBox.reconcile(session, type, local);
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

  /**
   * One round of {@code node}'s runs against {@code main} whose answer is lost on the way back:
   * main takes them, and the node never hears it.
   */
  public static void pushLosingTheAnswer(SyncBox main, SyncBox node) throws IOException {
    var link = connect(main.server(node.session()), node, SyncWire.MAX_FRAME, losingTheResults());
    Actor.run(Actor.main(), () -> NodeRound.begin(link.session(), node.db, node.handle()));
    assertThrows(RuntimeException.class, () -> link.reconcile("run", node.replicas().get("run")));
    try {
      link.close();
    } catch (RuntimeException alreadyCut) {
      assertTrue(alreadyCut.getMessage() != null);
    }
  }

  private static UnaryOperator<OutputStream> losingTheResults() {
    return out ->
        new FilterOutputStream(out) {
          @Override
          public void write(byte[] buffer, int offset, int length) throws IOException {
            if (new String(buffer, offset, length, StandardCharsets.UTF_8)
                .contains("\"op\": \"results\"")) {
              throw new IOException("the answer is lost on the way back");
            }
            out.write(buffer, offset, length);
          }
        };
  }

  /** One type's round over {@code session} as a node runs it: adopting what main decided. */
  public static SyncSession.TypeReport reconcile(
      SyncSession session, String type, LocalReplica local) {
    return Actor.call(Actor.main(), () -> session.reconcile(type, local));
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
            null,
            Actor.sync("node", Role.MEMBER),
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
