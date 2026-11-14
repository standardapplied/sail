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
import ai.singlr.sail.engine.SailEventHelper;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.SailRoomRelay;
import ai.singlr.sail.engine.SailSessionReport;
import ai.singlr.sail.engine.SailStopGate;
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
import java.util.Map;
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

  /**
   * Notes what it started with, then fires each of Sail's installed hooks as a harness does (a tool
   * event, the session report, the room relay, the stop gate) while it samples every process's
   * command line in the container for its own run credential, then says it started and stays up a
   * while. The hooks run as copies that differ only in their socket, the stub's: the launch mounts
   * the host's socket directory over {@code /var/lib/sail/run}, hiding anything listening there.
   */
  private static final String FAKE_AGENT =
      """
      #!/usr/bin/env bash
      f="$HOME/.sail/runs/$SAIL_RUN_ID/launch.env"
      {
        printf 'key=%s\\n' "$(printf %s "$CODEX_API_KEY" | sha256sum | cut -d' ' -f1)"
        case "$SAIL_RUN_CREDENTIAL" in sailrun_*) echo cred=minted ;; *) echo cred=missing ;; esac
        printf 'access=[%s]\n' "$CODEX_ACCESS_TOKEN"
        test -e "$f" && echo file=present || echo file=gone
      } > "$HOME/.sail/started-$SAIL_RUN_ID.tmp"
      hits="$HOME/.sail/argv-hits-$SAIL_RUN_ID"
      done="$HOME/.sail/hooks-done-$SAIL_RUN_ID"
      : > "$hits"
      (
        while [ ! -e "$done" ]; do
          for p in $(printf '%s\\n' "$SAIL_RUN_CREDENTIAL" | grep -laF -f - /proc/[0-9]*/cmdline 2>/dev/null); do
            tr '\\0' ' ' < "$p" >> "$hits"
            echo >> "$hits"
          done
          sleep 0.05
        done
      ) &
      bin="$HOME/.sail/it-hooks"
      mkdir -p "$bin"
      for hook in sail-event.sh sail-session-report sail-room-relay sail-stop-gate; do
        sed 's|__API_SOCKET__|/run/sail-it-stub/api.sock|g' \\
          "$HOME/.sail/bin/$hook" > "$bin/$hook"
        chmod 0755 "$bin/$hook"
      done
      "$bin/sail-event.sh" agent_tool_started
      printf '{"session_id":"conv-1","source":"startup"}' | "$bin/sail-session-report" codex
      printf '{}' | "$bin/sail-room-relay"
      printf '{"stop_hook_active":false}' | "$bin/sail-stop-gate" > /dev/null
      : > "$done"
      wait
      mv "$HOME/.sail/started-$SAIL_RUN_ID.tmp" "$HOME/.sail/started-$SAIL_RUN_ID"
      sleep "${FAKE_AGENT_SECONDS:-20}"
      """;

  /**
   * The host API as the hooks reach it over the socket, slow enough that each curl is alive while
   * sampled: it notes each request's method, path and whether it presented a run credential, and
   * answers every read of the run's messages with one message, so the relay and the stop gate
   * acknowledge it too.
   */
  private static final String STUB_API =
      """
      import http.server, os, socketserver, time
      PATH = "/run/sail-it-stub/api.sock"
      class Handler(http.server.BaseHTTPRequestHandler):
          def answer(self):
              length = int(self.headers.get("Content-Length") or 0)
              self.rfile.read(length)
              auth = self.headers.get("Authorization") or ""
              with open("/root/stub.log", "a") as log:
                  log.write("%s %s %s\\n" % (self.command, self.path.split("?")[0],
                      "run-credential" if auth.startswith("Bearer sailrun_") else "none"))
              time.sleep(1)
              body = b'{"messages":[{"id":"m1","author":"ana","body":"hi"}]}'
              self.send_response(200)
              self.send_header("Content-Length", str(len(body)))
              self.end_headers()
              self.wfile.write(body)
          do_GET = answer
          do_POST = answer
          def log_message(self, *args):
              pass
      class Server(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
          daemon_threads = True
      if os.path.exists(PATH):
          os.remove(PATH)
      server = Server(PATH, Handler)
      os.chmod(PATH, 0o666)
      server.serve_forever()
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
                    + " mkdir -p /home/dev/.sail/bin /home/dev/.sail/runs /home/dev/workspace/app"
                    + " /home/dev/workspace/web /run/sail-it-stub;"
                    + " for i in 1 2 3 4 5 6; do mkdir -p /home/dev/workspace/r$i; done;"
                    + " echo 'export CODEX_ACCESS_TOKEN=container-token' >> /home/dev/.profile;"
                    + " chown -R dev:dev /home/dev;"
                    + " for i in $(seq 1 30); do"
                    + " loginctl enable-linger dev 2>/dev/null && break; sleep 1; done;"
                    + " loginctl enable-linger dev;"
                    + " timeout 60 sh -c 'until test -S /run/user/1000/bus; do sleep 1; done'"));
    assertTrue(setup.ok(), "container provisioning failed: " + setup.stderr());
    ContainerFilePush.push(
        shell,
        CONTAINER,
        "/usr/local/bin/codex",
        FAKE_AGENT.replace("__API_SOCKET__", SailPaths.apiSocketContainerPath().toString()),
        List.of("--mode", "0755"));
    var hooks =
        Map.of(
            SailEventHelper.SCRIPT_PATH, SailEventHelper.scriptContent(),
            SailSessionReport.SCRIPT_PATH, SailSessionReport.scriptContent(),
            SailRoomRelay.SCRIPT_PATH, SailRoomRelay.scriptContent(),
            SailStopGate.SCRIPT_PATH, SailStopGate.scriptContent());
    for (var hook : hooks.entrySet()) {
      ContainerFilePush.pushAsDev(shell, CONTAINER, hook.getKey(), hook.getValue(), "0755");
    }
    ContainerFilePush.push(shell, CONTAINER, "/root/stub.py", STUB_API, List.of("--mode", "0600"));
    var stub =
        exec(
            CONTAINER,
            List.of(
                "sh",
                "-c",
                "systemd-run --quiet --unit=sail-it-stub-api python3 /root/stub.py &&"
                    + " timeout 30 sh -c 'until test -S /run/sail-it-stub/api.sock; do sleep 0.2;"
                    + " done'"));
    assertTrue(stub.ok(), "the stub API never listened: " + stub.stderr());
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
            "key=" + sha256(KEY) + "\ncred=minted\naccess=[]\nfile=gone\n",
            asDev(List.of("cat", "/home/dev/.sail/started-" + runId)).stdout(),
            shape
                + ": the agent had the key and its credential, the container's own token was set"
                + " empty, and the file was gone");
        assertFalse(
            asDev(List.of("test", "-e", AgentUnit.forRun(runId).launchEnvPath())).ok(),
            shape + ": no launch.env is left");
        assertTrue(sampled.size() > 1, "the host's process list was sampled through the launch");
        assertTrue(
            sampled.stream().noneMatch(line -> line.contains(KEY) || line.contains("sailrun_")),
            shape + ": no host command line held the key or a run credential");
        var inContainer = asDev(List.of("ps", "-eo", "args")).stdout();
        assertFalse(inContainer.contains(KEY) || inContainer.contains("sailrun_"), inContainer);
        assertEquals(
            "",
            asDev(List.of("cat", "/home/dev/.sail/argv-hits-" + runId)).stdout(),
            shape + ": no process in the container held the run credential while every hook ran");
        var requests =
            exec(CONTAINER, List.of("sh", "-c", "cat /root/stub.log; : > /root/stub.log"))
                .stdout()
                .lines()
                .toList();
        assertTrue(
            requests.containsAll(
                List.of(
                    "POST /v1/events run-credential",
                    "POST /v1/run/session run-credential",
                    "GET /v1/run/messages run-credential",
                    "POST /v1/run/messages run-credential")),
            shape
                + ": every hook reached the API presenting the run credential: "
                + requests
                + "\n"
                + stubDiagnosis(runId));
        assertTrue(
            requests.stream().filter("GET /v1/run/messages run-credential"::equals).count() >= 2,
            shape + ": the relay and the stop gate both read the room: " + requests);
        assertTrue(
            requests.stream().allMatch(line -> line.endsWith(" run-credential")),
            requests.toString());
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

  /**
   * However wide the dev user's home, another user of the container never sees a run's {@code
   * launch.env}: through six launches, a second user samples every run directory's mode and tries
   * the file in each, and every directory it sees is {@code 0700} with no file seen, let alone
   * read. {@code incus file push} writes the file before it sets its {@code 0600}, so only the
   * directory keeps it.
   */
  @Test
  void anotherUserOfTheContainerNeverSeesALaunchFileAtAnyInstant() throws Exception {
    var opened =
        exec(
            CONTAINER,
            List.of(
                "sh",
                "-c",
                "useradd -m other && chmod 0755 /home/dev /home/dev/.sail /home/dev/.sail/runs"
                    + " && systemd-run --quiet --unit=sail-it-other-sampler --uid=other sh -c "
                    + "'while [ ! -e /tmp/sampler-stop ]; do"
                    + " for d in /home/dev/.sail/runs/*/; do"
                    + " [ -d \"$d\" ] || continue;"
                    + " echo \"dir $(stat -c %a \"$d\" 2>/dev/null) $d\";"
                    + " if [ -e \"$d/launch.env\" ]; then echo \"file $d\";"
                    + " head -c1 \"$d/launch.env\" > /dev/null 2>&1 && echo \"READ $d\"; fi;"
                    + " done >> /tmp/samples; sleep 0.005; done'"));
    assertTrue(opened.ok(), "the second user's sampler never started: " + opened.stderr());
    try (var db = database()) {
      var fdes = new FdeStore(db);
      var id = fdes.add(HANDLE, null, null, "admin").id();
      fdes.logins().store(id, Harnesses.of("codex"), null, "api-key", KEY);
      var ops = operations(db);
      var runs = new java.util.ArrayList<String>();
      for (var i = 1; i <= 6; i++) {
        seedSpec(new SpecStore(db), "spec-m" + i, "r" + i);
        var runId = dispatch(ops, "spec-m" + i, "background").runId();
        awaitStarted(runId);
        runs.add(runId);
      }
      exec(CONTAINER, List.of("sh", "-c", "touch /tmp/sampler-stop; sleep 1"));

      var samples = exec(CONTAINER, List.of("sort", "-u", "/tmp/samples")).stdout();
      for (var runId : runs) {
        assertTrue(
            samples.contains("dir 700 /home/dev/.sail/runs/" + runId + "/"),
            "the second user sampled run " + runId + "'s directory: " + samples);
      }
      assertTrue(
          samples
              .lines()
              .filter(line -> line.startsWith("dir "))
              .allMatch(line -> line.startsWith("dir 700 ")),
          "every run directory it saw was the dev user's alone: " + samples);
      assertFalse(
          samples.contains("file ") || samples.contains("READ "),
          "no launch.env was ever seen by another user: " + samples);
    }
  }

  @Test
  void aSpecRunWhosePersonStoredNoLoginIsRefusedWithNoRowAndNoUnit() throws Exception {
    try (var db = database()) {
      new FdeStore(db).add(HANDLE, null, null, "admin");
      seedSpec(new SpecStore(db), "spec-bg", "app");

      var refused =
          assertThrows(ApiException.class, () -> dispatch(operations(db), "spec-bg", "background"));

      assertEquals(ErrorCode.LOGIN_NOT_STORED, refused.failure().errorCode());
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
          - url: https://example.invalid/r1.git
            path: r1
          - url: https://example.invalid/r2.git
            path: r2
          - url: https://example.invalid/r3.git
            path: r3
          - url: https://example.invalid/r4.git
            path: r4
          - url: https://example.invalid/r5.git
            path: r5
          - url: https://example.invalid/r6.git
            path: r6
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
        TestWorkspaces.over(shell),
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

  /** What a test that saw no hook reach the stub API prints: the socket, the stub and the run. */
  private String stubDiagnosis(String runId) throws Exception {
    return exec(
            CONTAINER,
            List.of(
                "sh",
                "-c",
                "ls -l /run/sail-it-stub; systemctl status --no-pager sail-it-stub-api 2>&1 |"
                    + " tail -5; tail -20 "
                    + AgentUnit.forRun(runId).logPath()))
        .stdout();
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
