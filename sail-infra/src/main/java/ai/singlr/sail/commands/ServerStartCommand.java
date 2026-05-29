/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.EventBus;
import ai.singlr.sail.api.SailApiOperations;
import ai.singlr.sail.api.SailApiServer;
import ai.singlr.sail.api.ServerConnectionConfig;
import ai.singlr.sail.api.SpecStoreAuditPersister;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "start",
    description = "Start the Sail control plane server.",
    mixinStandardHelpOptions = true)
public final class ServerStartCommand implements Runnable {

  @Option(names = "--host", description = "Host to bind.", defaultValue = "0.0.0.0")
  private String host;

  @Option(names = "--port", description = "Port to bind.", defaultValue = "7070")
  private int port;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    var dbPath = SailPaths.sailDir().resolve("sail.db");
    Files.createDirectories(dbPath.getParent());

    var db = Sqlite.open(dbPath);
    new SchemaManager(db).migrate();

    var tokenStore = new TokenStore(db);
    var configPath = SailPaths.clientConfigPath();
    if (tokenStore.list().isEmpty()) {
      var created = tokenStore.create("admin", "admin");
      ServerConnectionConfig.saveLocalToken(created.token(), configPath);
      System.out.println(
          Ansi.AUTO.string("  @|green ✓|@ API token created and saved to " + configPath));
      System.out.println();
    }
    var specStore = new SpecStore(db);
    var eventStore = new EventStore(db);
    var bus = new EventBus();
    var persister = new SpecStoreAuditPersister(eventStore);
    var operations =
        new SailApiOperations(
            new ShellExecutor(false), SailPaths.PROJECT_DESCRIPTOR, bus, persister, specStore);

    try (var server = new SailApiServer(host, port, operations, tokenStore, bus, persister)) {
      server.start();
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Sail server listening on http://" + host + ":" + server.port()));
      System.out.println(Ansi.AUTO.string("    @|faint Database: " + dbPath + "|@"));
      System.out.println(Ansi.AUTO.string("    @|faint Press Ctrl+C to stop.|@"));
      new CountDownLatch(1).await();
    } finally {
      db.close();
    }
  }
}
