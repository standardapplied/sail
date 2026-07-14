/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.Capability;
import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.Role;
import ai.singlr.sail.api.SailEventPublisher;
import ai.singlr.sail.api.SyncTransitionEvents;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import ai.singlr.sail.sync.FileReplica;
import ai.singlr.sail.sync.MainReplica;
import ai.singlr.sail.sync.ProjectReplica;
import ai.singlr.sail.sync.ReviewReplica;
import ai.singlr.sail.sync.RunReplica;
import ai.singlr.sail.sync.SpecReplica;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncPrincipal;
import ai.singlr.sail.sync.SyncRpcServer;
import ai.singlr.sail.sync.SyncTransitionSink;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

/**
 * Main's side of a sync session, reached only through the SSH-key gateway: a node's {@code sail
 * sync} opens {@code ssh sail@main sail _sync}, the gateway authorizes the calling FDE and re-execs
 * this with {@code SAIL_TOKEN} set, and the {@link SyncRpcServer} then exchanges {@link
 * ai.singlr.sail.sync.SyncWire} over the channel's stdio. The token resolves to a {@link
 * SyncPrincipal} — the FDE's handle plus its role's write capability: only {@code member}+ may push
 * (write), a read-only FDE can pull but its commits are refused, and the handle binds run commits
 * to the pushing node so no FDE can forge another node's execution provenance. The serving database
 * is opened through {@link SyncDatabase}, so main's schema is converged before any revision is
 * served or committed. Not meant to be run by hand.
 */
@Command(
    name = "_sync",
    description = "Internal sync RPC server for an SSH-key session.",
    hidden = true)
public final class SyncServerCommand implements Callable<Integer> {

  @Override
  public Integer call() throws Exception {
    var host = HostInfo.hostname();
    SyncDatabase mainDb;
    try {
      mainDb = SyncDatabase.converge(SailPaths.controlPlaneDb(), host);
    } catch (RuntimeException e) {
      System.err.println(SyncCommand.reason(e));
      return 1;
    }
    try (mainDb) {
      var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      var out = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
      return serve(
          mainDb, host, System.getenv("SAIL_TOKEN"), in, out, transitionBridge(mainDb.db(), host));
    }
  }

  static int serve(
      SyncDatabase converged, String mainId, String token, BufferedReader in, Writer out)
      throws IOException {
    return serve(converged, mainId, token, in, out, SyncTransitionSink.NONE);
  }

  static int serve(
      SyncDatabase converged,
      String mainId,
      String token,
      BufferedReader in,
      Writer out,
      SyncTransitionSink transitionSink)
      throws IOException {
    var db = converged.db();
    var changeLog = new ChangeLog(db);
    var conflicts = new SyncConflicts(db);
    var syncState = new SyncState(db);
    var replicas =
        Map.<String, MainReplica>of(
            "spec", new SpecReplica(mainId, new SpecStore(db), changeLog, conflicts, syncState),
            "file", new FileReplica(mainId, new FileStore(db), changeLog, conflicts, syncState),
            "project",
                new ProjectReplica(mainId, new ProjectStore(db), changeLog, conflicts, syncState),
            "run",
                new RunReplica(mainId, mainId, new RunStore(db), changeLog, conflicts, syncState),
            "review",
                new ReviewReplica(mainId, new ReviewStore(db), changeLog, conflicts, syncState));
    new SyncRpcServer(replicas, principal(db, token), () -> roster(db), transitionSink)
        .serve(in, out);
    return 0;
  }

  /**
   * The bridge from a committed transition to main's running server: maps it to today's lifecycle
   * events and posts each to the local sail-api, where main's bus (and its Slack reactor) picks it
   * up. This {@code _sync} subprocess shares main's database but not the server's in-process bus,
   * so the local-socket publisher {@code notifyBoardUpdated} already uses is the one proven path.
   * Best-effort by contract: a down sail-api costs the narration, never the sync.
   */
  static SyncTransitionSink transitionBridge(Sqlite db, String host) {
    var specs = new SpecStore(db);
    var reviews = new ReviewStore(db);
    var publisher = new SailEventPublisher[1];
    return transition -> {
      var events =
          SyncTransitionEvents.eventsFor(
              transition,
              specId -> specs.findById(specId).map(SpecStore.SpecRow::project).orElse(null),
              specId ->
                  reviews
                      .latestReviewForSpec(specId)
                      .map(ReviewStore.ReviewRow::status)
                      .orElse(null),
              host);
      for (var event : events) {
        publish(publisher, event);
      }
    };
  }

  private static void publish(SailEventPublisher[] publisher, Event event) {
    try {
      if (publisher[0] == null) {
        publisher[0] = SailEventPublisher.localDefault();
      }
      publisher[0].publish(event);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      System.err.println(
          "  [sync] could not hand "
              + event.type()
              + " to the local sail-api; the sync is unaffected: "
              + e.getMessage());
    }
  }

  static List<Map<String, Object>> roster(Sqlite db) {
    return new FdeStore(db).list().stream().map(SyncServerCommand::fdeToMap).toList();
  }

  private static Map<String, Object> fdeToMap(FdeStore.Fde fde) {
    var map = new LinkedHashMap<String, Object>();
    map.put("handle", fde.handle());
    map.put("display_name", fde.displayName());
    map.put("email", fde.email());
    map.put("role", fde.role());
    map.put("status", fde.status());
    map.put("created_at", fde.createdAt());
    return map;
  }

  private static SyncPrincipal principal(Sqlite db, String token) {
    if (Strings.isBlank(token)) {
      return SyncPrincipal.readOnly();
    }
    return new AuthSessionStore(db)
        .validate(token)
        .flatMap(session -> new FdeStore(db).byId(session.fdeId()))
        .map(SyncServerCommand::principalOf)
        .orElse(SyncPrincipal.readOnly());
  }

  private static SyncPrincipal principalOf(FdeStore.Fde fde) {
    return new SyncPrincipal(fde.handle(), Role.fromAttribute(fde.role()).allows(Capability.WRITE));
  }
}
