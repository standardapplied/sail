/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.Capability;
import ai.singlr.sail.api.Role;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import ai.singlr.sail.sync.FileReplica;
import ai.singlr.sail.sync.MainReplica;
import ai.singlr.sail.sync.SpecReplica;
import ai.singlr.sail.sync.SyncRpcServer;
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
 * ai.singlr.sail.sync.SyncWire} over the channel's stdio. The token resolves to the FDE's role:
 * only {@code member}+ may push (write), so a read-only FDE can pull but its commits are refused.
 * Not meant to be run by hand.
 */
@Command(
    name = "_sync",
    description = "Internal sync RPC server for an SSH-key session.",
    hidden = true)
public final class SyncServerCommand implements Callable<Integer> {

  @Override
  public Integer call() throws Exception {
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      var out = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
      return serve(db, HostInfo.hostname(), System.getenv("SAIL_TOKEN"), in, out);
    }
  }

  static int serve(Sqlite db, String mainId, String token, BufferedReader in, Writer out)
      throws IOException {
    var changeLog = new ChangeLog(db);
    var conflicts = new SyncConflicts(db);
    var syncState = new SyncState(db);
    var replicas =
        Map.<String, MainReplica>of(
            "spec", new SpecReplica(mainId, new SpecStore(db), changeLog, conflicts, syncState),
            "file", new FileReplica(mainId, new FileStore(db), changeLog, conflicts, syncState));
    new SyncRpcServer(replicas, canWrite(db, token), () -> roster(db)).serve(in, out);
    return 0;
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

  private static boolean canWrite(Sqlite db, String token) {
    if (token == null || token.isBlank()) {
      return false;
    }
    return new AuthSessionStore(db)
        .validate(token)
        .flatMap(session -> new FdeStore(db).byId(session.fdeId()))
        .map(fde -> Role.fromAttribute(fde.role()).allows(Capability.WRITE))
        .orElse(false);
  }
}
