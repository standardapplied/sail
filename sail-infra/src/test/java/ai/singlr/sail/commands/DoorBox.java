/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.AgentLogStreamer;
import ai.singlr.sail.api.ApiResponse;
import ai.singlr.sail.api.ApiRouter;
import ai.singlr.sail.api.EventBus;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.RateLimitGate;
import ai.singlr.sail.api.SailOperations;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SocketDoor;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.api.TestAuth;
import ai.singlr.sail.api.WebauthnAuthHandler;
import ai.singlr.sail.auth.EnrollmentService;
import ai.singlr.sail.auth.PasskeyService;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.BoxCredentialStore;
import ai.singlr.sail.store.EnrollmentTicketStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.PendingChallengeStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.WebauthnCredentialStore;
import ai.singlr.sail.webauthn.RelyingParty;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * A box as a door test asks it: its operations over {@code db}, configured as {@code config} so its
 * person is the FDE that config names, its web door with its passkey routes, and its socket. Its
 * host runs acme's container and nothing else; it never reaches main.
 */
record DoorBox(SyncConfig config, Sqlite db, SailOperations operations, HttpServer http)
    implements AutoCloseable {

  /** The relying party its passkey routes serve. */
  static final String RP_ID = "sail.acme.dev";

  /** The origin a browser signs a passkey ceremony for. */
  static final String ORIGIN = "https://sail.acme.dev";

  static DoorBox open(Sqlite db, Path dir, SyncConfig config) throws IOException {
    var operations =
        wired(
            OperationsFactory.create(
                db,
                new AcmeRuns(),
                new EventBus(),
                null,
                SyncScheduler.disabled(),
                SessionYield.NONE),
            db,
            dir,
            config);
    var auth = TestAuth.sessions(db, config);
    var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext(
        "/", new ApiRouter(operations, auth, new RateLimitGate(), new AgentLogStreamer(auth)));
    http.createContext(
        "/v1/auth",
        new WebauthnAuthHandler(
            new PasskeyService(
                new RelyingParty(RP_ID, "Sail", Set.of(ORIGIN)),
                new FdeStore(db),
                new WebauthnCredentialStore(db),
                new AuthSessionStore(db),
                new PendingChallengeStore(db),
                Duration.ofDays(30)),
            new EnrollmentService(new EnrollmentTicketStore(db), new FdeStore(db)),
            auth,
            List.of(ORIGIN)));
    http.start();
    return new DoorBox(config, db, operations, http);
  }

  /** {@code operations} on the box {@code config} configures, over {@code db}'s control plane. */
  static SailOperations wired(SailOperations operations, Sqlite db, Path dir, SyncConfig config) {
    return operations.useControlPlane(
        db,
        dir,
        new SyncOperations(
            db,
            config.boxId(),
            dir,
            () -> config,
            target -> {
              throw new IOException("main is not asked here");
            }));
  }

  String person() {
    return config.handle();
  }

  String url() {
    return "http://127.0.0.1:" + http.getAddress().getPort();
  }

  /** What this box's socket answers its box credential, {@code method} on {@code path}. */
  ApiResponse socket(String method, String path, String form) {
    return new SocketDoor(operations)
        .ask(method, path, new BoxCredentialStore(db).replace(person()), form);
  }

  @Override
  public void close() {
    http.stop(0);
    operations.close();
  }

  /** A host whose only container is acme's, running; every other command does nothing. */
  static final class AcmeRuns implements ShellExec {
    @Override
    public Result exec(List<String> command) {
      return String.join(" ", command).startsWith("incus list")
          ? new Result(0, "[{\"name\": \"acme\", \"status\": \"Running\", \"state\": {}}]", "")
          : new Result(0, "", "");
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
