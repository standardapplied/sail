/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerFilePush;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A spec run's login and run credential reach a real container's agent only through its launch.env
 * (HL1, HL3, HL4, HL6): a fake {@code codex} records the SHA-256 of {@code CODEX_API_KEY}, whether
 * {@code SAIL_RUN_CREDENTIAL} is a minted one and whether the file was still there when it started;
 * the host's process list, sampled through each launch, and the unit's recorded environment hold
 * neither value; and a spec run whose person stored no login is refused with no row and no unit.
 * Runs only under the {@code integration} profile against a real incus daemon; skips elsewhere.
 */
class RunLoginIT extends AbstractIncusIT {

  private static final String CONTAINER = "sail-it-run-login";
  private static final String HANDLE = "it-node";
  private static final String KEY = "sk-proj-it-fake-0123456789abcdef";
  private static final Actor OPERATOR = new Actor(HANDLE, Role.ADMIN, Actor.Lane.CLI);

  /** Writes what it started with, then stays up a while, as an agent does. */
  private static final String FAKE_AGENT =
      """
      #!/usr/bin/env bash
      f="$HOME/.sail/runs/$SAIL_RUN_ID/launch.env"
      {
        printf 'key=%s\\n' "$(printf %s "$CODEX_API_KEY" | sha256sum | cut -d' ' -f1)"
        case "$SAIL_RUN_CREDENTIAL" in sailrun_*) echo cred=minted ;; *) echo cred=missing ;; esac
        test -e "$f" && echo file=present || echo file=gone
      } > "$HOME/.sail/started-$SAIL_RUN_ID.tmp"
      mv "$HOME/.sail/started-$SAIL_RUN_ID.tmp" "$HOME/.sail/started-$SAIL_RUN_ID"
      sleep "${FAKE_AGENT_SECONDS:-20}"
      """;

  private Path stateDir;

  @BeforeEach
  void provision() throws Exception {
    ensureIncusOrSkip();
    launchPrepared(CONTAINER);
    var setup =
        exec(
            CONTAINER,
            List.of(
                "bash",
                "-c",
                "set -e;"
                    + " userdel -r ubuntu 2>/dev/null || true;"
                    + " id -u dev >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash dev;"
                    + " mkdir -p /home/dev/.sail /home/dev/workspace/app /home/dev/workspace/web;"
                    + " chown -R dev:dev /home/dev;"
                    + " for i in $(seq 1 30); do"
                    + " loginctl enable-linger dev 2>/dev/null && break; sleep 1; done;"
                    + " loginctl enable-linger dev;"
                    + " timeout 60 sh -c 'until test -S /run/user/1000/bus; do sleep 1; done'"));
    assertTrue(setup.ok(), "container provisioning failed: " + setup.stderr());
    ContainerFilePush.push(
        shell, CONTAINER, "/usr/local/bin/codex", FAKE_AGENT, List.of("--mode", "0755"));
    stateDir = Files.createTempDirectory("run-login-it");
  }

  @AfterEach
  void cleanup() {
    deleteContainerQuietly(CONTAINER);
    if (stateDir != null) {
      deleteRecursively(stateDir);
    }
  }

  @Test
  void eachLaunchShapeStartsTheAgentWithTheStoredKeyAndNoValueIsOnAnyCommandLine()
      throws Exception {
    try (var db = database()) {
      var fdes = new FdeStore(db);
      var id = fdes.add(HANDLE, null, null, "admin").id();
      fdes.logins().store(id, Harnesses.of("codex"), null, "api-key", KEY);
      seedSpec(new SpecStore(db), "spec-bg", "app");
      seedSpec(new SpecStore(db), "spec-fg", "web");
      var runs = new RunStore(db);
      var ops = operations(db);

      for (var shape : List.of("background", "foreground")) {
        var spec = shape.equals("background") ? "spec-bg" : "spec-fg";
        var sampled = new ConcurrentLinkedQueue<String>();
        var sampling = new AtomicBoolean(true);
        var sampler = Thread.ofVirtual().start(() -> sample(sampling, sampled));
        DispatchOperations.Dispatched dispatched;
        try {
          dispatched = dispatch(ops, spec, shape);
          awaitStarted(dispatched.runId());
        } finally {
          sampling.set(false);
          sampler.join(Duration.ofSeconds(30));
        }
        var runId = dispatched.runId();

        assertEquals(
            "key=" + sha256(KEY) + "\ncred=minted\nfile=gone\n",
            asDev(List.of("cat", "/home/dev/.sail/started-" + runId)).stdout(),
            shape + ": the agent had the key and its credential, and the file was gone");
        assertFalse(
            asDev(List.of("test", "-e", AgentUnit.forRun(runId).launchEnvPath())).ok(),
            shape + ": no launch.env is left");
        assertTrue(sampled.size() > 1, "the host's process list was sampled through the launch");
        assertTrue(
            sampled.stream().noneMatch(line -> line.contains(KEY) || line.contains("sailrun_")),
            shape + ": no host command line held the key or a run credential");
        var inContainer = asDev(List.of("ps", "-eo", "args")).stdout();
        assertFalse(inContainer.contains(KEY) || inContainer.contains("sailrun_"), inContainer);
        var run = runs.findById(runId).orElseThrow();
        assertEquals(new RunStore.RunLogin("default", "api-key"), run.login());
        assertEquals(HANDLE, run.dispatchedBy());
        if (shape.equals("background")) {
          var unit =
              asDev(
                      List.of(
                          "systemctl",
                          "--user",
                          "show",
                          run.unit() + ".service",
                          "-p",
                          "Environment"))
                  .stdout();
          assertTrue(unit.contains("SAIL_RUN_ID=" + runId), unit);
          assertFalse(unit.contains(KEY) || unit.contains("SAIL_RUN_CREDENTIAL"), unit);
        }
      }
    }
  }

  @Test
  void aSpecRunWhosePersonStoredNoLoginIsRefusedWithNoRowAndNoUnit() throws Exception {
    try (var db = database()) {
      new FdeStore(db).add(HANDLE, null, null, "admin");
      seedSpec(new SpecStore(db), "spec-bg", "app");

      var refused =
          assertThrows(ApiException.class, () -> dispatch(operations(db), "spec-bg", "background"));

      assertEquals(ErrorCode.AGENT_NOT_CONFIGURED, refused.failure().errorCode());
      assertEquals(List.of(), new RunStore(db).listForProject(CONTAINER));
      assertEquals(
          "",
          asDev(
                  List.of(
                      "systemctl",
                      "--user",
                      "list-units",
                      "--plain",
                      "--no-legend",
                      "sail-agent-*"))
              .stdout()
              .strip());
    }
  }

  private Sqlite database() throws Exception {
    var yaml = stateDir.resolve("sail.yaml");
    Files.writeString(
        yaml,
        """
        name: %s
        repos:
          - url: https://example.invalid/app.git
            path: app
          - url: https://example.invalid/web.git
            path: web
        agent:
          type: codex
        """
            .formatted(CONTAINER));
    var db = Sqlite.open(stateDir.resolve(DateTimeUtils.newId() + ".db"));
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, CONTAINER);
    return db;
  }

  private DispatchOperations operations(Sqlite db) {
    return new DispatchOperations(
        shell,
        TestProjects.reading(stateDir.resolve("sail.yaml")),
        new SpecStore(db),
        new ReviewStore(db),
        new RunStore(db),
        new FdeStore(db),
        event -> {},
        new WatcherSpawner(refusingShell(), (command, logPath) -> 4242L),
        (project, config) -> "",
        DispatchOperations.shellLauncher(shell),
        DispatchOperations.Listener.NONE,
        SessionYield.NONE);
  }

  private static DispatchOperations.Dispatched dispatch(
      DispatchOperations ops, String spec, String mode) {
    return assertInstanceOf(
        DispatchOperations.Dispatched.class,
        Acting.by(
            OPERATOR,
            () ->
                ops.dispatch(
                    CONTAINER,
                    new DispatchOperations.Request(spec, mode, false, null, false),
                    HANDLE)));
  }

  /** The host's every command line, over and over, until {@code sampling} ends. */
  private void sample(AtomicBoolean sampling, ConcurrentLinkedQueue<String> sampled) {
    while (sampling.get()) {
      try {
        sampled.add(shell.exec(List.of("ps", "-eo", "args")).stdout());
      } catch (Exception e) {
        sampled.add("ps failed: " + e.getMessage());
      }
    }
  }

  /** Waits, in the container and bounded, for the fake agent of {@code runId} to report. */
  private void awaitStarted(String runId) throws Exception {
    var started = "/home/dev/.sail/started-" + runId;
    var waited =
        asDev(
            List.of(
                "timeout", "60", "sh", "-c", "until test -s " + started + "; do sleep 0.2; done"));
    assertTrue(
        waited.ok(),
        "run "
            + runId
            + " never started its agent; its log: "
            + asDev(List.of("cat", AgentUnit.forRun(runId).logPath())).stdout());
  }

  private ShellExec.Result asDev(List<String> argv) throws Exception {
    return shell.exec(ContainerExec.asDevUser(CONTAINER, argv));
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static void seedSpec(SpecStore store, String id, String repo) {
    Acting.as(
        HANDLE,
        () -> {
          store.create(
              new SpecStore.SpecRow(
                  id,
                  CONTAINER,
                  "Title " + id,
                  SpecStatus.PENDING,
                  HANDLE,
                  "codex",
                  null,
                  null,
                  null,
                  0,
                  HANDLE,
                  null,
                  null,
                  HANDLE,
                  List.of(),
                  List.of(repo)));
          store.setContent(id, "Do " + id, "");
        });
  }

  /** A shell that refuses every command, so the watcher falls to its fake process fallback. */
  private static ShellExec refusingShell() {
    return new ShellExec() {
      @Override
      public Result exec(List<String> command) {
        return new Result(1, "", "refused");
      }

      @Override
      public Result exec(List<String> command, Path workDir, Duration timeout) {
        return new Result(1, "", "refused");
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }
}
