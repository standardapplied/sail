/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.EventBus;
import ai.singlr.sail.api.SailApiOperations;
import ai.singlr.sail.api.SailApiServer;
import ai.singlr.sail.api.ServerConnectionConfig;
import ai.singlr.sail.api.SessionAwareAuth;
import ai.singlr.sail.api.SpecStoreAuditPersister;
import ai.singlr.sail.api.TokenAuth;
import ai.singlr.sail.api.WebauthnAuthHandler;
import ai.singlr.sail.auth.EnrollmentService;
import ai.singlr.sail.auth.PasskeyService;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.WebauthnConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.EnrollmentTicketStore;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.ExpiredRowSweeper;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MigrationRunner;
import ai.singlr.sail.store.PendingChallengeStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.store.WebauthnCredentialStore;
import ai.singlr.sail.webauthn.RelyingParty;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Starts the control-plane server. The API is a single-trust-level surface: every issued token is a
 * full-access operator credential (there is no role separation), and it can dispatch agents — which
 * is code execution inside project containers. The security boundary is therefore the bind address,
 * which defaults to loopback. Binding a non-loopback address exposes that surface to the network
 * over plaintext HTTP and must be opted into explicitly (put it behind a TLS reverse proxy).
 */
@Command(
    name = "start",
    description = "Start the Sail control plane server.",
    mixinStandardHelpOptions = true)
public final class ServerStartCommand implements Runnable {

  @Option(
      names = "--host",
      description = "Host to bind. Defaults to loopback; use 0.0.0.0 to expose on the network.",
      defaultValue = "127.0.0.1")
  private String host;

  @Option(names = "--port", description = "Port to bind.", defaultValue = "7070")
  private int port;

  @Option(
      names = "--rp-id",
      description =
          "WebAuthn Relying Party ID for passkey login (the registrable domain the proxy serves)."
              + " Overrides the host.yaml webauthn block.")
  private String rpId;

  @Option(
      names = "--rp-name",
      description = "Human-facing Relying Party name shown during passkey enrollment.")
  private String rpName;

  @Option(
      names = "--origin",
      description =
          "Allowed passkey origin (e.g. https://sail.example.dev). Repeatable. Overrides the"
              + " host.yaml webauthn origins.")
  private List<String> origins;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    var dbPath = SailPaths.controlPlaneDb();
    SailPaths.ensureDataDir(dbPath.getParent());

    var db = Sqlite.open(dbPath);
    var migrationResult =
        MigrationRunner.applyAll(
            db, MigrateCommand.REGISTRY, DataMigration.Prompter.NON_INTERACTIVE);
    if (migrationResult.schemaAfter() > migrationResult.schemaBefore()) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Schema migrated: "
                  + migrationResult.schemaBefore()
                  + " → "
                  + migrationResult.schemaAfter()));
    }
    for (var run : migrationResult.dataRuns()) {
      if (!run.alreadyApplied()
          && (run.report().applied() > 0
              || run.report().ambiguous() > 0
              || run.report().skipped() > 0)) {
        System.out.println(
            Ansi.AUTO.string(
                "  @|green ✓|@ "
                    + run.name()
                    + ": "
                    + run.report().applied()
                    + " applied, "
                    + run.report().ambiguous()
                    + " ambiguous, "
                    + run.report().skipped()
                    + " skipped"));
      }
    }

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

    var webauthn = resolveWebauthn();
    var configured = webauthn.isConfigured();
    var passkeyService = configured ? buildPasskeyService(db, webauthn) : null;
    var enrollment =
        configured ? new EnrollmentService(new EnrollmentTicketStore(db), new FdeStore(db)) : null;
    var enrollOrigin = configured ? webauthn.origins().getFirst() : null;
    var passkeyHandler =
        new WebauthnAuthHandler(
            passkeyService, enrollment, new TokenAuth(tokenStore), enrollOrigin);
    var auth =
        new SessionAwareAuth(new AuthSessionStore(db), new FdeStore(db), new TokenAuth(tokenStore));

    try (var server =
            new SailApiServer(
                host,
                port,
                operations,
                auth,
                bus,
                persister,
                SailPaths.apiSocketPath(),
                passkeyHandler);
        var sweeper = new ExpiredRowSweeper(dbPath)) {
      server.start();
      sweeper.start();
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Sail server listening on http://" + host + ":" + server.port()));
      if (webauthn.isConfigured()) {
        System.out.println(
            Ansi.AUTO.string("    @|faint Passkey login enabled for " + webauthn.rpId() + "|@"));
      }
      if (!isLoopback(host)) {
        System.out.println(
            Ansi.AUTO.string(
                "  @|yellow ⚠|@ Bound to a non-loopback address over plaintext HTTP. Any holder"
                    + " of a token can dispatch agents (code execution in containers). Put this"
                    + " behind a TLS reverse proxy and restrict network access."));
      }
      System.out.println(Ansi.AUTO.string("    @|faint Database: " + dbPath + "|@"));
      System.out.println(Ansi.AUTO.string("    @|faint Press Ctrl+C to stop.|@"));
      new CountDownLatch(1).await();
    } finally {
      db.close();
    }
  }

  private WebauthnConfig resolveWebauthn() throws Exception {
    var hostConfigPath = SailPaths.hostConfigPath();
    var base =
        Files.exists(hostConfigPath)
            ? HostYaml.fromMap(YamlUtil.parseFile(hostConfigPath)).webauthn()
            : WebauthnConfig.disabled();
    return new WebauthnConfig(
        rpId != null ? rpId : base.rpId(),
        rpName != null ? rpName : base.rpName(),
        origins != null && !origins.isEmpty() ? origins : base.origins());
  }

  private static PasskeyService buildPasskeyService(Sqlite db, WebauthnConfig webauthn) {
    var relyingParty =
        new RelyingParty(
            webauthn.rpId(), webauthn.resolvedRpName(), Set.copyOf(webauthn.origins()));
    return new PasskeyService(
        relyingParty,
        new FdeStore(db),
        new WebauthnCredentialStore(db),
        new AuthSessionStore(db),
        new PendingChallengeStore(db));
  }

  private static boolean isLoopback(String host) {
    return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
  }
}
