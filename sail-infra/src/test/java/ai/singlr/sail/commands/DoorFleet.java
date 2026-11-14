/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.HostToken;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.sync.SyncDatabase;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Access arc's cast at their doors, over main's database: uday is main's admin, eng a member of
 * every project, client a member of acme only, obs a viewer of acme and beta. {@code e1} is eng's
 * spec in acme, {@code r1} eng's room there, {@code in-r1} a spec born in it, {@code a2} an
 * unassigned acme spec and {@code b1} eng's spec in beta. Each person has an API token, and a box
 * of their own ({@link DoorBox}): uday's is main, the others are main's nodes.
 */
record DoorFleet(
    SyncDatabase main, Map<String, DoorBox> boxes, Map<String, String> tokens, String hostToken)
    implements AutoCloseable {

  /** Main's configuration: uday's box. */
  static final SyncConfig MAIN = new SyncConfig(SyncConfig.ROLE_MAIN, null, "uday", "main");

  static DoorFleet open(Path dir) throws Exception {
    var main = GatewayNode.main(dir.resolve("sail.db"));
    var db = main.db();
    var roster = new FdeStore(db);
    roster.add("uday", null, null, "admin");
    roster.add("eng", null, null, "member");
    roster.add("client", null, null, "member");
    roster.add("obs", null, null, "viewer");
    roster.restrict("client");
    roster.grant("client", "acme");
    roster.restrict("obs");
    roster.grant("obs", "acme");
    roster.grant("obs", "beta");
    var tokens = new HashMap<String, String>();
    for (var person : List.of("uday", "eng", "client", "obs")) {
      var fde = roster.byHandle(person).orElseThrow();
      tokens.put(person, new TokenStore(db).create(person, fde.role(), fde.id(), null).token());
    }
    var hostToken = new TokenStore(db).create(HostToken.NAME, "admin", null, null).token();
    Acting.system(
        () ->
            List.of("acme", "beta")
                .forEach(
                    project ->
                        new ProjectStore(db)
                            .upsert(
                                TestDefinitions.yaml(
                                    "name: " + project + "\nagent:\n  type: claude-code\n"))));
    var specs = new SpecStore(db);
    Acting.as(
        "eng",
        () -> {
          new RoomStore(db)
              .create(
                  new RoomStore.RoomRow(
                      "r1", "acme", "r1", "eng", null, null, "eng", null, null, "eng"));
          specs.create(spec("e1", "acme", "eng"));
          specs.create(spec("b1", "beta", "eng"));
          specs.create(spec("in-r1", "acme", null).withRoomId("r1"));
        });
    Acting.as("uday", () -> specs.create(spec("a2", "acme", null)));
    var boxes = new HashMap<String, DoorBox>();
    boxes.put("uday", DoorBox.open(db, dir, MAIN));
    for (var person : List.of("eng", "client", "obs")) {
      boxes.put(
          person,
          DoorBox.open(db, dir, new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", person, person)));
    }
    return new DoorFleet(main, boxes, tokens, hostToken);
  }

  Sqlite db() {
    return main.db();
  }

  /** A pending spec {@code id} of {@code project}, assigned to {@code assignee}, titled its id. */
  static SpecStore.SpecRow spec(String id, String project, String assignee) {
    return spec(id, project, assignee, id);
  }

  static SpecStore.SpecRow spec(String id, String project, String assignee, String title) {
    return new SpecStore.SpecRow(
        id,
        project,
        title,
        SpecStatus.PENDING,
        assignee,
        null,
        null,
        null,
        null,
        0,
        null,
        null,
        null,
        null,
        List.of(),
        List.of());
  }

  @Override
  public void close() {
    boxes.values().forEach(DoorBox::close);
    main.close();
  }
}
