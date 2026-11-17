/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.EventBus;
import ai.singlr.sail.api.LocalApiSocket;
import ai.singlr.sail.api.ProjectReader;
import ai.singlr.sail.api.RunLauncher;
import ai.singlr.sail.api.RunReports;
import ai.singlr.sail.api.SailOperations;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SpecWorkspaces;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.api.TestControlPlane;
import ai.singlr.sail.commands.DownCommand;
import ai.singlr.sail.commands.ExecCommand;
import ai.singlr.sail.commands.ProjectDestroyCommand;
import ai.singlr.sail.commands.RestoreCommand;
import ai.singlr.sail.commands.ScopedNode;
import ai.singlr.sail.commands.ShellCommand;
import ai.singlr.sail.commands.SnapCommand;
import ai.singlr.sail.commands.SnapDeleteCommand;
import ai.singlr.sail.commands.SnapsPruneCommand;
import ai.singlr.sail.commands.UpCommand;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.harness.RunReport;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.BoxCredentialStore;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TestRuns;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Workspaces on a real Incus: a dir pool the runner's Incus starts with and its base profile uses,
 * where the project's own container runs, and a ZFS pool in a file beside it, as {@code host init
 * --storage zfs --pool <name> --pool-file-size} adds one. A real template build runs the project's
 * provisioner, which reaches the internet (apt, the runtimes' archives) that no test body may, so
 * the template is sealed here as a finished build leaves it: the prepared image with the dev user
 * lingering, sail's machinery and a repository whose origin is a bare repository inside the
 * template, stopped, snapshot {@code v1}, and the record of the definition's digest. Everything
 * after that is the production lane: the clone, the per-workspace step, the list and the lifecycle.
 * The measured times are printed into the job log as a record, not checked: CI is not a runner.
 */
class WorkspaceIT extends AbstractIncusIT {

  private static final Predicate<String> NO_RUN = spec -> false;

  private static final String POOL = "sail-it-ws";
  private static final String BASE_POOL = "default";
  private static final String PROJECT = "sail-it-ws";
  private static final String TEMPLATE = WorkspaceTemplate.nameOf(PROJECT);
  private static final String FIX = "fix-login";
  private static final String SECOND = "second";
  private static final String SPEC_ID = "sail-it-ws-probe";
  private static final String GONE = "gone";
  private static final String KEPT = "kept";
  private static final String HELD = "held";
  private static final String BACKED = "backed";
  private static final String TREE = "tree";
  private static final String NO_ADDRESS = "sail-it-noaddr";
  private static final Path CONTAINER_DIR = Path.of("/var/lib/sail/run");

  private static final String SEAL =
      """
      set -eu
      userdel -r ubuntu 2>/dev/null || true
      id -u dev >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash dev
      for _ in $(seq 1 30); do loginctl enable-linger dev 2>/dev/null && break; sleep 1; done
      loginctl enable-linger dev
      as_dev() { runuser -u dev -- env HOME=/home/dev "$@"; }
      git init --quiet --bare -b main /srv/app.git
      chown -R dev:dev /srv/app.git
      as_dev git config --global user.email it@sail.invalid
      as_dev git config --global user.name it
      as_dev git init --quiet -b main /tmp/seed
      echo seed | as_dev tee /tmp/seed/README >/dev/null
      as_dev git -C /tmp/seed add README
      as_dev git -C /tmp/seed commit --quiet -m seed
      as_dev git -C /tmp/seed push --quiet /srv/app.git main main:second
      as_dev mkdir -p /home/dev/workspace
      as_dev git clone --quiet file:///srv/app.git /home/dev/workspace/app
      """;

  private final ContainerManager containers = new ContainerManager(shell);
  private final Workspaces workspaces =
      new Workspaces(
          shell,
          shell,
          System::nanoTime,
          SailPaths.locksDir(),
          Workspaces.memoryOf(new HostDetector()),
          url -> false);

  private static ProjectDefinition definition() {
    return new ProjectDefinition(
        PROJECT,
        "Workspaces on a real Incus",
        new ProjectDefinition.Resources(2, "2GB", "10GB"),
        null,
        null,
        null,
        List.of(new ProjectDefinition.Repo("https://example.invalid/app.git", "app", null)),
        null,
        null,
        null,
        null);
  }

  private static WorkspaceTemplate.Source source() {
    return new WorkspaceTemplate.Source(
        BASE_POOL,
        ProjectProvisioner.inputs(definition(), Optional.empty()),
        name -> name.equals(PROJECT),
        (into, fresh) -> {
          throw new AssertionError("the sealed template is current, so nothing is built");
        },
        List.of());
  }

  @BeforeEach
  void aDirBoxWithAZfsPoolBesideAndASealedTemplate() throws Exception {
    ensureIncusOrSkip();
    removeEverything();
    var made = new IncusStorage(shell).create(POOL, new PoolSource.File(10240));
    assertTrue(made.ok(), "Incus could not make a file-backed zfs pool: " + made.reason());
    launchPrepared(PROJECT);
    launchPrepared(TEMPLATE, "--storage", POOL);
    var sealed = exec(TEMPLATE, List.of("bash", "-c", SEAL));
    assertTrue(sealed.ok(), "could not seal the template: " + sealed.stderr() + sealed.stdout());
    ContainerSailSetup.ensureInstalled(shell, TEMPLATE);
    containers.stop(TEMPLATE);
    var recorded =
        shell.exec(
            List.of(
                "incus",
                "config",
                "set",
                TEMPLATE,
                "user.sail.template.of=" + PROJECT,
                "user.sail.template.current=v1",
                "user.sail.template.inputs="
                    + ProjectProvisioner.inputs(definition(), Optional.empty())));
    assertTrue(recorded.ok(), recorded.reason());
    new SnapshotManager(shell).create(TEMPLATE, "v1");
  }

  @AfterEach
  void removeEverything() {
    deleteContainerQuietly(PROJECT + "-" + FIX);
    deleteContainerQuietly(PROJECT + "-" + SECOND);
    deleteContainerQuietly(PROJECT + "-" + GONE);
    deleteContainerQuietly(PROJECT + "-" + KEPT);
    deleteContainerQuietly(PROJECT + "-" + HELD);
    deleteContainerQuietly(PROJECT + "-" + BACKED);
    deleteContainerQuietly(PROJECT + "-" + TREE);
    deleteContainerQuietly(TEMPLATE);
    deleteContainerQuietly(PROJECT);
    try {
      shell.exec(List.of("incus", "network", "delete", NO_ADDRESS));
    } catch (Exception ignored) {
    }
    try {
      shell.exec(List.of("incus", "storage", "delete", POOL));
    } catch (Exception ignored) {
    }
  }

  /**
   * What {@code path} of {@code instance} holds, running or stopped, or null when it is not there.
   */
  private String pulled(String instance, String path) throws Exception {
    var pulled = shell.exec(List.of("incus", "file", "pull", instance + path, "-"));
    return pulled.ok() ? pulled.stdout() : null;
  }

  private static final String FAKE_TOKEN = "ghp_itFakeBuilderToken0042";

  /** Runs {@code script} as root in the template, running, and stops it again. */
  private void plant(String script) throws Exception {
    containers.start(TEMPLATE);
    var planted = exec(TEMPLATE, List.of("bash", "-c", "set -eu; " + script));
    assertTrue(planted.ok(), planted.stderr());
    containers.stop(TEMPLATE);
  }

  /** A rebuild of the template, as the production lane makes one, given {@link #FAKE_TOKEN}. */
  private WorkspaceTemplate.Template rebuild() throws Exception {
    return new WorkspaceTemplate(shell, shell, SailPaths.locksDir())
        .ensure(
            definition(),
            new WorkspaceTemplate.Source(
                BASE_POOL,
                "a-later-definition",
                name -> name.equals(PROJECT),
                (into, fresh) -> {},
                List.of(FAKE_TOKEN)));
  }

  /**
   * What {@code path} of snapshot {@code version} of the template holds, read from a copy of it.
   */
  private String inSnapshot(String version, String path) throws Exception {
    var probe = PROJECT + "-probe";
    deleteContainerQuietly(probe);
    var copied = shell.exec(List.of("incus", "copy", TEMPLATE + "/" + version, probe));
    assertTrue(copied.ok(), copied.reason());
    try {
      return pulled(probe, path);
    } finally {
      deleteContainerQuietly(probe);
    }
  }

  /**
   * Scenario 1 on a real Incus: a template its build left holding a fake token, gh's login, a git
   * identity and a key is rebuilt as the production lane rebuilds it, and its new snapshot holds
   * none of the four.
   */
  @Test
  void aTemplateBuiltWithItsBuildersCredentialsIsSnapshottedHoldingNone() throws Exception {
    plant(
        "install -d -o dev -g dev -m 700 /home/dev/.ssh /home/dev/.config/gh;"
            + " echo https://oauth2:"
            + FAKE_TOKEN
            + "@example.invalid > /home/dev/.git-credentials;"
            + " echo 'example.invalid: {oauth_token: "
            + FAKE_TOKEN
            + "}' > /home/dev/.config/gh/hosts.yml;"
            + " echo 'ssh-ed25519 AAAAbuilder builder@box' > /home/dev/.ssh/authorized_keys;"
            + " runuser -u dev -- env HOME=/home/dev git config --global user.name Builder;"
            + " runuser -u dev -- env HOME=/home/dev git config --global user.email b@sail.invalid");

    assertEquals("v2", rebuild().version());
    assertEquals(null, inSnapshot("v2", "/home/dev/.git-credentials"), "no token store");
    assertEquals(null, inSnapshot("v2", "/home/dev/.config/gh/hosts.yml"), "no gh login");
    assertEquals("", inSnapshot("v2", "/home/dev/.ssh/authorized_keys"), "no key");
    var gitconfig = inSnapshot("v2", "/home/dev/.gitconfig");
    assertFalse(gitconfig != null && gitconfig.contains("b@sail.invalid"), gitconfig);
    assertFalse(gitconfig != null && gitconfig.contains("Builder"), gitconfig);
  }

  /**
   * Rule 2 on a real Incus: a template that still holds a credential after the strip fails its
   * build, records no version and takes no snapshot, and v1 stays current: (a) the build's token in
   * places no list names, (b) a system git identity, (c) a key in another home.
   */
  @Test
  void aTemplateStillHoldingACredentialFailsItsBuildAndVersionOneStaysCurrent() throws Exception {
    for (var leftover :
        List.of(
            List.of(
                "echo 'machine example.invalid password " + FAKE_TOKEN + "' > /home/dev/.netrc",
                "a token this build was given in /home/dev/.netrc"),
            List.of(
                "mkdir -p /home/dev/.sail && echo " + FAKE_TOKEN + " > /home/dev/.sail/gh-token-01",
                "/home/dev/.sail/gh-token-01"),
            List.of(
                "runuser -u dev -- git -C /home/dev/workspace/app remote set-url origin"
                    + " https://oauth2:"
                    + FAKE_TOKEN
                    + "@example.invalid/app.git",
                "/home/dev/workspace/app/.git/config"),
            List.of("git config --system user.email root@sail.invalid", "a system git user.email"),
            List.of(
                "mkdir -p /root/.ssh && echo 'ssh-ed25519 AAAAroot r' > /root/.ssh/authorized_keys",
                "/root/.ssh/authorized_keys"))) {
      plant(leftover.getFirst());

      var failed = assertThrows(IOException.class, this::rebuild, leftover.getFirst());

      assertTrue(failed.getMessage().contains(leftover.getLast()), failed.getMessage());
      assertFalse(failed.getMessage().contains(FAKE_TOKEN), "the refusal never says the token");
      var template = new BoxInstances(shell).named(TEMPLATE).orElseThrow();
      assertEquals(Optional.of("v1"), template.key(WorkspaceTemplate.CURRENT_KEY));
      assertEquals(
          List.of("v1"),
          new SnapshotManager(shell)
              .list(TEMPLATE).stream().map(SnapshotManager.SnapshotInfo::name).toList());
      plant(
          "rm -f /home/dev/.netrc /home/dev/.sail/gh-token-01 /root/.ssh/authorized_keys;"
              + " git config --system --unset-all user.email || true;"
              + " runuser -u dev -- git -C /home/dev/workspace/app remote set-url origin"
              + " file:///srv/app.git");
    }
  }

  /** Rule 2 (d) on a real Incus: the check finds a gh login the dev user has. */
  @Test
  void theCheckFindsAGhLoginOnARealContainer() throws Exception {
    containers.start(TEMPLATE);
    var planted =
        exec(
            TEMPLATE,
            List.of(
                "bash",
                "-c",
                "install -d -o dev -g dev /home/dev/.config/gh && printf"
                    + " 'github.com:\\n    oauth_token: x\\n    user: it\\n' >"
                    + " /home/dev/.config/gh/hosts.yml && chown dev:dev"
                    + " /home/dev/.config/gh/hosts.yml"));
    assertTrue(planted.ok(), planted.stderr());

    var refused =
        assertThrows(
            IOException.class, () -> WorkspaceCredentials.requireNone(shell, TEMPLATE, List.of()));

    assertTrue(
        refused.getMessage().contains("a gh login")
            || refused.getMessage().contains("gh could not"),
        refused.getMessage());
  }

  /**
   * Scenarios 2 and 3 on a real Incus: two workspaces made for two people each commit as their
   * owner and hold their owner's token store alone.
   */
  @Test
  void twoWorkspacesOfTwoPeopleEachCommitAsTheirOwnerWithTheirOwnStore() throws Exception {
    var uday =
        new WorkspaceOwner(
            "uday",
            "Uday",
            "uday@sail.invalid",
            Map.of("example.invalid", "tok-uday"),
            List.of(),
            "fde_uday");
    var ana =
        new WorkspaceOwner(
            "ana",
            "Ana",
            "ana@sail.invalid",
            Map.of("example.invalid", "tok-ana"),
            List.of("ssh-ed25519 AAAAana ana@laptop"),
            "fde_ana");

    var fix = workspaces.create(definition(), FIX, source(), null, uday.handle(), h -> uday);
    var second = workspaces.create(definition(), SECOND, source(), null, ana.handle(), h -> ana);

    for (var made : List.of(Map.entry(fix, uday), Map.entry(second, ana))) {
      var instance = made.getKey().instance();
      var owner = made.getValue();
      var email =
          exec(
              instance,
              List.of("runuser", "-u", "dev", "--", "git", "config", "--global", "user.email"));
      assertEquals(owner.email(), email.stdout().strip(), instance);
      assertEquals(
          "https://oauth2:" + owner.tokens().get("example.invalid") + "@example.invalid\n",
          pulled(instance, "/home/dev/.git-credentials"),
          instance);
      assertEquals(
          owner.keys().stream().map(key -> key + "\n").reduce("", String::concat),
          pulled(instance, "/home/dev/.ssh/authorized_keys"),
          instance);
    }
  }

  private static final String PRIVATE_GIT =
      """
      set -eu
      for _ in $(seq 1 300); do ip -4 -o addr show dev eth0 | grep -q inet && break; sleep 0.1; done
      ip=$(ip -4 -o addr show dev eth0 | awk '{print $4}' | cut -d/ -f1)
      openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=sail-it -keyout /srv/tls.key \
        -out /srv/tls.pem -addext "subjectAltName=IP:$ip" 2>/dev/null
      git init --quiet --bare -b main /srv/app.git
      git init --quiet -b main /tmp/private
      git -C /tmp/private -c user.name=it -c user.email=it@sail.invalid commit --quiet \
        --allow-empty -m private
      git -C /tmp/private push --quiet /srv/app.git main
      cat > /srv/serve.py <<'PY'
      import base64, http.server, os, ssl, subprocess
      class Git(http.server.BaseHTTPRequestHandler):
          def do_GET(self): self.answer()
          def do_POST(self): self.answer()
          def answer(self):
              sent = self.headers.get("Authorization", "")
              if not sent.startswith("Basic "):
                  self.send_response(401)
                  self.send_header("WWW-Authenticate", 'Basic realm="private"')
                  self.send_header("Content-Length", "0")
                  self.end_headers()
                  return
              with open("/srv/seen", "a") as seen:
                  seen.write(base64.b64decode(sent[6:]).decode() + "\\n")
              path, _, query = self.path.partition("?")
              body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
              env = dict(os.environ, GIT_PROJECT_ROOT="/srv", GIT_HTTP_EXPORT_ALL="1",
                         PATH_INFO=path, QUERY_STRING=query, REQUEST_METHOD=self.command,
                         CONTENT_TYPE=self.headers.get("Content-Type", ""),
                         HTTP_CONTENT_ENCODING=self.headers.get("Content-Encoding", ""))
              out = subprocess.run(["git", "http-backend"], input=body, env=env,
                                   capture_output=True).stdout
              head, _, rest = out.partition(b"\\r\\n\\r\\n")
              self.send_response(200)
              for line in head.decode().split("\\r\\n"):
                  name, _, value = line.partition(": ")
                  if name and name.lower() != "status":
                      self.send_header(name, value)
              self.send_header("Content-Length", str(len(rest)))
              self.end_headers()
              self.wfile.write(rest)
      server = http.server.HTTPServer(("0.0.0.0", 8443), Git)
      tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
      tls.load_cert_chain("/srv/tls.pem", "/srv/tls.key")
      server.socket = tls.wrap_socket(server.socket, server_side=True)
      server.serve_forever()
      PY
      systemd-run --quiet --unit sail-it-git python3 /srv/serve.py
      for _ in $(seq 1 300); do (exec 3<>/dev/tcp/127.0.0.1/8443) 2>/dev/null && break; sleep 0.1; done
      (exec 3<>/dev/tcp/127.0.0.1/8443)
      printf '%s' "$ip"
      """;

  /**
   * Item 6 of round 3 on a real Incus: a workspace's first fetch, from a server beside it that
   * refuses anonymous reads, is made with its owner's token, the one the owner step wrote.
   */
  @Test
  void theFirstFetchOfARepoThatRefusesAnonymousReadsUsesTheOwnersToken() throws Exception {
    var served = exec(PROJECT, List.of("bash", "-c", PRIVATE_GIT));
    assertTrue(served.ok(), served.stderr());
    var host = served.stdout().strip() + ":8443";
    var url = "https://" + host + "/app.git";
    var anonymous =
        exec(
            PROJECT,
            List.of(
                "env",
                "GIT_TERMINAL_PROMPT=0",
                "git",
                "-c",
                "http.sslVerify=false",
                "ls-remote",
                url));
    assertFalse(anonymous.ok(), "the repo refuses anonymous reads");
    var cert = Files.createTempFile("sail-it", ".pem");
    try {
      assertTrue(
          shell
              .exec(List.of("incus", "file", "pull", PROJECT + "/srv/tls.pem", cert.toString()))
              .ok());
      assertTrue(
          shell
              .exec(
                  List.of(
                      "incus",
                      "file",
                      "push",
                      "--mode",
                      "0644",
                      cert.toString(),
                      TEMPLATE + "/usr/local/share/ca-certificates/sail-it.crt"))
              .ok());
    } finally {
      Files.deleteIfExists(cert);
    }
    plant(
        "git config --system http."
            + url.substring(0, url.indexOf("/app.git") + 1)
            + ".sslCAInfo /usr/local/share/ca-certificates/sail-it.crt; runuser -u dev -- git -C"
            + " /home/dev/workspace/app remote set-url origin "
            + url);
    rebuild();
    var config =
        new ProjectDefinition(
            PROJECT,
            "A private repo",
            new ProjectDefinition.Resources(2, "2GB", "10GB"),
            null,
            null,
            null,
            List.of(new ProjectDefinition.Repo(url, "app", null)),
            null,
            null,
            null,
            null);
    var owner =
        new WorkspaceOwner(
            "uday",
            "Uday",
            "uday@sail.invalid",
            Map.of(host, "tok-private"),
            List.of(),
            "fde_uday");

    var made =
        workspaces.create(
            config,
            FIX,
            new WorkspaceTemplate.Source(
                BASE_POOL,
                "a-later-definition",
                name -> name.equals(PROJECT),
                (into, fresh) -> {
                  throw new AssertionError("the rebuilt template is current");
                },
                List.of()),
            null,
            owner.handle(),
            h -> owner);

    var seen = pulled(PROJECT, "/srv/seen").lines().distinct().toList();
    assertEquals(List.of("oauth2:tok-private"), seen);
    var fetched =
        exec(
            made.instance(),
            List.of(
                "runuser",
                "-u",
                "dev",
                "--",
                "git",
                "-C",
                "/home/dev/workspace/app",
                "log",
                "-1",
                "--format=%s",
                "origin/main"));
    assertEquals("private", fetched.stdout().strip(), fetched.stderr());
  }

  /**
   * The fleet's ten-at-once failure, on a real Incus: with its host keys gone sshd refuses to
   * start, which is what removing them before making new ones did to an ssh.service systemd was
   * starting at that moment. Made beside the old ones and renamed over them, they are never gone:
   * sshd, checked over and over the whole time, never refuses.
   */
  @Test
  void aWorkspacesNewHostKeysNeverLeaveSshdWithNone() throws Exception {
    var fix = workspaces.create(definition(), FIX, source(), null, "uday", h -> udayWith("tok-1"));

    var swapped =
        exec(
            fix.instance(),
            List.of(
                "bash",
                "-c",
                "set -eu; mkdir -p /run/sshd; /usr/sbin/sshd -t\n"
                    + "(for _ in $(seq 1 200); do /usr/sbin/sshd -t 2>/dev/null || echo refused; done)"
                    + " & checking=$!\n"
                    + Workspaces.HOST_KEYS
                    + "wait $checking\n"));
    var gone =
        exec(
            fix.instance(),
            List.of("bash", "-c", "rm -f /etc/ssh/ssh_host_*; /usr/sbin/sshd -t 2>&1"));

    assertTrue(swapped.ok(), swapped.stderr() + swapped.stdout());
    assertFalse(swapped.stdout().contains("refused"), swapped.stdout());
    assertFalse(gone.ok(), "sshd refuses with no host keys: " + gone.stdout());
  }

  private static final String STRIPPED_TOKEN = "tok-strip-0123456789abcdef";

  private static WorkspaceOwner leaving() {
    return new WorkspaceOwner(
        "tmp",
        "Tmp One",
        "tmp@sail.invalid",
        Map.of("example.invalid", STRIPPED_TOKEN),
        List.of("ssh-ed25519 AAAAtmp tmp@laptop"),
        "fde_old_tmp");
  }

  /**
   * Plants the leftovers no give leaves on its own, and gh's login, as a cut-short change or the
   * owner's own gh would leave them: under /home/dev, never /tmp, written in the container.
   */
  private void plantLeftovers(String instance) throws Exception {
    var planted =
        exec(
            instance,
            List.of(
                "bash",
                "-c",
                "set -eu; install -d -o dev -g dev /home/dev/.sail /home/dev/.config/gh;"
                    + " for f in /home/dev/.git-credentials.sail-new /home/dev/.sail/gh-token"
                    + " /home/dev/.config/gh/hosts.yml; do echo "
                    + STRIPPED_TOKEN
                    + " > \"$f\"; chown dev:dev \"$f\"; done"));
    assertTrue(planted.ok(), planted.stderr());
  }

  /** Nothing of the owner left, by search and by what git and the key file say. */
  private void assertHoldsNoOnesCredentials(String instance) throws Exception {
    var left =
        exec(
            instance,
            List.of(
                "bash",
                "-c",
                "found=0; for f in "
                    + String.join(" ", WorkspaceCredentials.FILES)
                    + "; do [ ! -e \"$f\" ] || { echo \"file $f\"; found=1; }; done;"
                    + " runuser -u dev -- env HOME=/home/dev git config --global --get user.email"
                    + " && found=1;"
                    + " [ ! -s /home/dev/.ssh/authorized_keys ] || { echo keys; found=1; };"
                    + " grep -r -l -F "
                    + STRIPPED_TOKEN
                    + " /home/dev && found=1;"
                    + " exit $found"));
    assertTrue(left.ok(), instance + " still holds: " + left.stdout() + left.stderr());
  }

  /**
   * The proof's High 1 on a real Incus: the strip is one idempotent act in the container, the same
   * whether none, some or all of what a give writes is there, and leaves no token, gh login,
   * identity or key, found by search.
   */
  @Test
  void theStripLeavesNothingWhetherNoneSomeOrAllOfWhatAGiveWroteIsThere() throws Exception {
    var made = workspaces.create(definition(), FIX, source(), null, "tmp", h -> leaving());
    plantLeftovers(made.instance());

    WorkspaceCredentials.strip(shell, made.instance());
    assertHoldsNoOnesCredentials(made.instance());
    WorkspaceCredentials.strip(shell, made.instance());
    assertHoldsNoOnesCredentials(made.instance());
    var some =
        exec(
            made.instance(),
            List.of(
                "bash",
                "-c",
                "echo "
                    + STRIPPED_TOKEN
                    + " > /home/dev/.git-credentials; runuser -u dev -- env HOME=/home/dev git"
                    + " config --global user.email tmp@sail.invalid"));
    assertTrue(some.ok(), some.stderr());
    WorkspaceCredentials.strip(shell, made.instance());
    assertHoldsNoOnesCredentials(made.instance());
  }

  /**
   * The proof's High 1 and Medium 1 on a real Incus: a removed person's workspaces, one running and
   * one stopped, labelled with their id, are stripped in the container and made nobody's by the
   * disown the pass runs; the stopped one is started for it and left stopped.
   */
  @Test
  void aRemovedPersonsRunningAndStoppedWorkspacesAreStrippedAndMadeNobodys() throws Exception {
    var running = workspaces.create(definition(), FIX, source(), null, "tmp", h -> leaving());
    var stopped = workspaces.create(definition(), SECOND, source(), null, "tmp", h -> leaving());
    plantLeftovers(running.instance());
    plantLeftovers(stopped.instance());
    workspaces.stop(PROJECT, SECOND, NO_RUN);
    var instances = new BoxInstances(shell);
    assertEquals(
        Optional.of("fde_old_tmp"),
        instances.named(running.instance()).orElseThrow().key(BoxInstances.OWNER_ID_KEY));

    assertEquals(
        List.of(), workspaces.disown((handle, id) -> !"fde_old_tmp".equals(id), instance -> true));

    for (var made : List.of(running, stopped)) {
      var found = instances.named(made.instance()).orElseThrow();
      assertEquals(Optional.empty(), found.key(BoxInstances.OWNER_KEY), "nobody's");
      assertEquals(Optional.empty(), found.key(BoxInstances.OWNER_ID_KEY), "no owner id left");
    }
    assertFalse(
        instances.named(stopped.instance()).orElseThrow().state() instanceof ContainerState.Running,
        "a stopped workspace is left stopped");
    assertHoldsNoOnesCredentials(running.instance());
    containers.start(stopped.instance());
    assertHoldsNoOnesCredentials(stopped.instance());
  }

  /**
   * Ruling 4 of {@code sail-a-workspace-is-its-creators} on a real Incus: a spec's workspace its
   * previous owner worked in, kept for what it holds and made nobody's when that owner left, is
   * entered by the box's person's run. It holds the previous work, and none of the previous owner's
   * token, gh login, git identity or keys: only the new owner's. The work keeps its own history (a
   * reflog entry the previous owner's checkout wrote names them, as a commit of theirs would); the
   * identity a new commit takes is the new owner's alone.
   */
  @Test
  void aDisownedKeptWorkspaceEnteredByTheNextRunHoldsTheWorkAndOnlyItsNewOwnersCredentials()
      throws Exception {
    var place = (RunPlace.SpecWorkspace) RunPlace.of(PROJECT, KEPT);
    var inputs = ProjectProvisioner.inputs(definition(), Optional.empty());
    var instance =
        workspaces
            .enterSpec(
                workspaces.checkSpec(place, inputs, name -> false),
                definition(),
                "tmp",
                h -> leaving(),
                null,
                whole -> {})
            .instance();
    plantLeftovers(instance);
    var worked =
        shell.exec(
            ContainerExec.asDevUser(
                instance,
                List.of(
                    "bash",
                    "-c",
                    "cd /home/dev/workspace/app && echo by-tmp >> README && echo mine > notes")));
    assertTrue(worked.ok(), worked.stderr());
    containers.stop(instance);
    var kept =
        shell.exec(
            List.of("incus", "config", "set", instance, BoxInstances.KEPT_KEY + "=app holds work"));
    assertTrue(kept.ok(), kept.reason());
    assertEquals(
        List.of(), workspaces.disown((handle, id) -> !"fde_old_tmp".equals(id), name -> true));
    var ana =
        new WorkspaceOwner(
            "ana",
            "Ana",
            "ana@sail.invalid",
            Map.of("example.invalid", "tok-ana-0123456789abcdef"),
            List.of("ssh-ed25519 AAAAana ana@laptop"),
            "fde_ana");

    workspaces.enterSpec(
        workspaces.checkSpec(place, inputs, name -> false),
        definition(),
        "ana",
        h -> ana,
        null,
        whole -> {});

    var entered = new BoxInstances(shell).named(instance).orElseThrow();
    assertEquals(Optional.of("ana"), entered.key(BoxInstances.OWNER_KEY));
    assertEquals(Optional.of("fde_ana"), entered.key(BoxInstances.OWNER_ID_KEY));
    assertEquals(Optional.empty(), entered.key(BoxInstances.KEPT_KEY).filter(k -> !k.isBlank()));
    assertEquals("seed\nby-tmp\n", pulled(instance, "/home/dev/workspace/app/README"));
    assertEquals("mine\n", pulled(instance, "/home/dev/workspace/app/notes"));
    var held =
        exec(
            instance,
            List.of(
                "bash",
                "-c",
                "grep -r -l -F -e "
                    + STRIPPED_TOKEN
                    + " -e AAAAtmp /home/dev /root;"
                    + " runuser -u dev -- env HOME=/home/dev git config --global --get user.email;"
                    + " cat /home/dev/.ssh/authorized_keys; grep -c tok-ana /home/dev/.git-credentials"));
    assertEquals(
        "ana@sail.invalid\nssh-ed25519 AAAAana ana@laptop\n1\n",
        held.stdout(),
        "only ana's credentials: " + held.stderr());
  }

  /**
   * Round 2 of {@code sail-a-workspace-is-its-creators} on a real Incus: a workspace that gets no
   * IPv4 address within its 30 s is not made. The create fails naming the wait and what to check,
   * and nothing of the workspace is left. Staged by giving the template a network Incus serves no
   * address on ({@code ipv4.address=none}) and making that its current snapshot, so its clone's
   * {@code eth0} comes up with none.
   */
  /**
   * A workspace takes its own machine-id without ever being left with none. systemd-networkd reads
   * it while it configures {@code eth0}, and, finding none, fails the link for good: no address,
   * ever. That is the CI lane's "got no address": six packets sent, a lease dnsmasq gave that the
   * clone never took, networkd saying "Failed to configure DHCPv6 client: No such file or
   * directory". The make's own script runs in a copy of the template whose watcher, already
   * running, records every machine-id the copy has and every moment it has none.
   */
  @Test
  void aWorkspaceTakesItsOwnMachineIdWithoutEverBeingLeftWithNone() throws Exception {
    plant(
        """
        cat > /etc/systemd/system/sail-it-id-watch.service <<'UNIT'
        [Unit]
        Description=Records every machine-id this machine has, and every moment it has none
        DefaultDependencies=no
        [Service]
        ExecStart=/bin/bash -c 'seen=; while [ "$SECONDS" -lt 300 ]; do if read -r id < /etc/machine-id 2>/dev/null && [ -n "$id" ]; then [ "$id" = "$seen" ] || { echo "$id" >> /run/sail-it-ids; seen=$id; }; else echo missing >> /run/sail-it-id-gaps; fi; done'
        [Install]
        WantedBy=sysinit.target
        UNIT
        ln -sf /etc/systemd/system/sail-it-id-watch.service \
          /etc/systemd/system/sysinit.target.wants/sail-it-id-watch.service
        """);
    var template = read(TEMPLATE + "/etc/machine-id");
    var copy = PROJECT + "-" + FIX;
    assertTrue(shell.exec(List.of("incus", "copy", TEMPLATE, copy)).ok());
    containers.start(copy);
    var watching =
        exec(
            copy,
            List.of(
                "bash",
                "-c",
                "for _ in $(seq 1 300); do [ -s /run/sail-it-ids ] && exit 0; sleep 0.1; done;"
                    + " exit 1"));
    assertTrue(watching.ok(), "the watcher never started");

    var made =
        shell.exec(
            ContainerExec.asRoot(
                copy,
                List.of("bash", "-c", Workspaces.PREPARE, "sail-workspace", PROJECT, FIX, "")),
            null,
            Duration.ofMinutes(2));

    assertTrue(made.ok(), made.stderr());
    var ids = exec(copy, List.of("cat", "/run/sail-it-ids")).stdout().strip().lines().toList();
    assertEquals(2, ids.size(), "the template's machine-id, then its own: " + ids);
    assertEquals(template, ids.getFirst());
    assertNotEquals(template, ids.getLast());
    assertTrue(ids.getLast().matches("[0-9a-f]{32}"), ids.getLast());
    assertEquals(
        "",
        exec(copy, List.of("bash", "-c", "cat /run/sail-it-id-gaps 2>/dev/null")).stdout().strip(),
        "moments the workspace had no machine-id");
  }

  @Test
  void aWorkspaceThatGetsNoAddressIsNotMadeAndNothingOfItIsLeft() throws Exception {
    var made =
        shell.exec(
            List.of(
                "incus",
                "network",
                "create",
                NO_ADDRESS,
                "ipv4.address=none",
                "ipv6.address=none"));
    assertTrue(made.ok(), made.reason());
    var moved =
        shell.exec(
            List.of(
                "incus",
                "config",
                "device",
                "override",
                TEMPLATE,
                "eth0",
                "network=" + NO_ADDRESS));
    if (!moved.ok()) {
      moved =
          shell.exec(
              List.of(
                  "incus", "config", "device", "set", TEMPLATE, "eth0", "network=" + NO_ADDRESS));
    }
    assertTrue(moved.ok(), moved.reason());
    new SnapshotManager(shell).create(TEMPLATE, "v2");
    var current =
        shell.exec(List.of("incus", "config", "set", TEMPLATE, "user.sail.template.current=v2"));
    assertTrue(current.ok(), current.reason());

    var refused =
        assertThrows(
            IOException.class,
            () -> workspaces.create(definition(), FIX, source(), null, "tmp", h -> leaving()));

    assertTrue(
        refused
            .getMessage()
            .startsWith(
                "Workspace 'fix-login' could not be finished: "
                    + PROJECT
                    + "-"
                    + FIX
                    + " got no address on eth0 within 30 s (networkd says "),
        refused.getMessage());
    assertTrue(
        refused
            .getMessage()
            .contains("), so its repos cannot be fetched; check that the network Incus gives it"),
        refused.getMessage());
    assertEquals(Optional.empty(), new BoxInstances(shell).named(PROJECT + "-" + FIX));
    var kept = keptEvidenceOf(PROJECT + "-" + FIX);
    assertTrue(
        kept.contains("== incus info " + PROJECT + "-" + FIX + "\nexit 0\n"),
        "the evidence was kept while the half-made workspace still existed: " + kept);
    assertTrue(kept.contains("== incus network list-leases "), kept);
    assertTrue(kept.contains("== /proc/loadavg\n"), kept);
  }

  /** The newest evidence {@link NoAddressEvidence} kept of {@code instance}. */
  private static String keptEvidenceOf(String instance) throws IOException {
    try (var files = Files.list(Path.of("target", "incus-evidence"))) {
      var newest =
          files
              .filter(file -> file.getFileName().toString().startsWith(instance + "-"))
              .max(Comparator.comparing(Path::toString))
              .orElseThrow(() -> new AssertionError("no evidence kept of " + instance));
      return Files.readString(newest);
    }
  }

  private static WorkspaceOwner udayWith(String token) {
    return new WorkspaceOwner(
        "uday",
        "Uday",
        "uday@sail.invalid",
        Map.of("example.invalid", token),
        List.of(),
        "fde_uday");
  }

  private static final String WATCH =
      """
      touch /home/dev/.sail-it-watching
      while [ ! -e /home/dev/.sail-it-stop ]; do
        [ -e /home/dev/.git-credentials ] || { echo absent; break; }
      done
      echo watched
      """;

  /**
   * On a real Incus: a token replaced while a workspace runs is in its store at once, the one its
   * next fetch reads, and a live run watching the store the whole time never finds it absent (N5);
   * one replaced while it is stopped is in it once it starts.
   */
  @Test
  void aReplacedTokenReachesARunningWorkspaceAtOnceAndAStoppedOneWhenItStarts() throws Exception {
    var fix = workspaces.create(definition(), FIX, source(), null, "uday", h -> udayWith("tok-1"));
    var store = "/home/dev/.git-credentials";
    var watcher =
        new ProcessBuilder("incus", "exec", fix.instance(), "--", "bash", "-c", WATCH)
            .redirectErrorStream(true)
            .start();
    watcher.getOutputStream().close();
    var watching =
        exec(
            fix.instance(),
            List.of(
                "bash",
                "-c",
                "for _ in $(seq 1 300); do [ -e /home/dev/.sail-it-watching ] && exit 0; sleep 0.1;"
                    + " done; exit 1"));
    assertTrue(watching.ok(), "the live run is watching");

    assertEquals(List.of(), workspaces.refresh(udayWith("tok-2"), project -> definition()));
    assertTrue(exec(fix.instance(), List.of("touch", "/home/dev/.sail-it-stop")).ok());
    assertTrue(watcher.waitFor(60, TimeUnit.SECONDS), "the watch ends");
    assertEquals(
        "watched",
        new String(watcher.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
    assertEquals("https://oauth2:tok-2@example.invalid\n", pulled(fix.instance(), store));
    workspaces.stop(PROJECT, FIX, NO_RUN);
    workspaces.refresh(udayWith("tok-3"), project -> definition());
    assertEquals(
        "https://oauth2:tok-2@example.invalid\n",
        pulled(fix.instance(), store),
        "a stopped workspace is not reached");
    workspaces.start(definition(), FIX, handle -> udayWith("tok-3"));
    assertEquals("https://oauth2:tok-3@example.invalid\n", pulled(fix.instance(), store));
  }

  @Test
  void workspacesAreClonesOfTheTemplateEachItsOwnMachineBesideTheProjectsContainer()
      throws Exception {
    var before = bystander();
    var fix = workspaces.create(definition(), FIX, source(), null, "eng", TestOwners::of);
    var second = workspaces.create(definition(), SECOND, source(), null, "eng", TestOwners::of);
    record("create " + FIX, fix.timings());
    record("create " + SECOND, second.timings());

    assertEquals("v1", fix.template().version());
    assertEquals(false, second.template().built());
    for (var workspace : List.of(fix, second)) {
      assertEquals(
          POOL + "/containers/" + TEMPLATE + "@snapshot-v1",
          zfs("get", "-H", "-o", "value", "origin", POOL + "/containers/" + workspace.instance()),
          "a workspace is a ZFS clone of the template's snapshot");
    }
    assertEquals(
        List.of(PROJECT + " on " + BASE_POOL, TEMPLATE + " on " + POOL),
        new BoxInstances(shell)
            .all().stream()
                .filter(i -> i.name().equals(PROJECT) || i.name().equals(TEMPLATE))
                .map(i -> i.name() + " on " + i.pool())
                .sorted()
                .toList(),
        "the project's own container stays on dir; the template is on the ZFS pool");

    assertEquals(
        List.of("main running", FIX + " running", SECOND + " running"),
        workspaces.list(PROJECT, id -> true).workspaces().stream()
            .map(row -> row.name() + " " + row.state())
            .toList());

    assertEquals(FIX, inWorkspace(fix, "git -C /home/dev/workspace/app branch --show-current"));
    assertEquals(
        "origin/" + SECOND,
        inWorkspace(second, "git -C /home/dev/workspace/app rev-parse --abbrev-ref @{upstream}"),
        "a branch the remote has is the one a workspace of that name takes");
    assertEquals(PROJECT, inWorkspace(fix, "hostname"));

    var identities = new ArrayList<String>();
    identities.add(read(TEMPLATE + "/etc/machine-id"));
    identities.add(read(fix.instance() + "/etc/machine-id"));
    identities.add(read(second.instance() + "/etc/machine-id"));
    var hostKeys = new ArrayList<String>();
    hostKeys.add(read(TEMPLATE + "/etc/ssh/ssh_host_ed25519_key.pub"));
    hostKeys.add(read(fix.instance() + "/etc/ssh/ssh_host_ed25519_key.pub"));
    hostKeys.add(read(second.instance() + "/etc/ssh/ssh_host_ed25519_key.pub"));
    assertEquals(3, new HashSet<>(identities).size(), "machine-ids: " + identities);
    assertEquals(3, new HashSet<>(hostKeys).size(), "host keys: " + hostKeys);
    for (var workspace : List.of(fix, second)) {
      assertTrue(
          read(workspace.instance() + "/etc/ssh/ssh_host_ed25519_key.pub")
              .endsWith(" root@" + PROJECT),
          "a workspace's host keys are made under the project's name");
      var keys = new BoxInstances(shell).named(workspace.instance()).orElseThrow();
      assertEquals(
          List.of(),
          List.of(
                  BoxInstances.TEMPLATE_OF_KEY,
                  WorkspaceTemplate.CURRENT_KEY,
                  WorkspaceTemplate.INPUTS_KEY,
                  WorkspaceTemplate.LAST_KEY,
                  ProjectProvisioner.INPUTS_KEY,
                  Workspaces.CREATING_KEY)
              .stream()
              .filter(key -> keys.key(key).isPresent())
              .toList(),
          "a workspace carries none of its template's keys, nor the copy's mark");
      assertTrue(keys.key(BoxInstances.READY_KEY).isPresent());
    }

    var serve =
        "setsid python3 -m http.server 3000 >/dev/null 2>&1 < /dev/null &"
            + " for i in $(seq 1 100); do ss -lntH | grep -q ':3000 ' && exit 0; sleep 0.1; done;"
            + " exit 1";
    for (var workspace : List.of(fix, second)) {
      var served = exec(workspace.instance(), List.of("bash", "-c", serve));
      assertTrue(served.ok(), workspace.workspace() + " could not serve 3000: " + served.stderr());
    }
    for (var workspace : List.of(fix, second)) {
      assertTrue(
          exec(workspace.instance(), List.of("ss", "-lntH")).stdout().contains(":3000 "),
          "each workspace listens on 3000 in its own network");
    }
    var addresses =
        workspaces.list(PROJECT, id -> true).workspaces().stream()
            .filter(row -> !row.name().equals(Workspaces.DEFAULT))
            .map(Workspaces.Row::ipv4)
            .toList();
    assertEquals(2, new HashSet<>(addresses).size(), "two clones, two addresses: " + addresses);
    assertEquals(before, bystander(), "the project's own container is untouched");
  }

  @Test
  void everyProjectVerbIsRefusedOnARealWorkspaceAndTemplateAndChangesNeither() throws Exception {
    var fix = workspaces.create(definition(), FIX, source(), null, "eng", TestOwners::of);
    var ws = fix.instance();
    var verbs =
        List.<Map.Entry<Object, List<String>>>of(
            Map.entry(new ShellCommand(), List.of(ws)),
            Map.entry(new ExecCommand(), List.of(ws, "true")),
            Map.entry(new ExecCommand(), List.of(TEMPLATE, "true")),
            Map.entry(new UpCommand(), List.of(TEMPLATE)),
            Map.entry(new DownCommand(), List.of(ws)),
            Map.entry(new SnapCommand(), List.of(TEMPLATE)),
            Map.entry(new SnapCommand(), List.of(ws)),
            Map.entry(new SnapDeleteCommand(), List.of(TEMPLATE, "v1")),
            Map.entry(new RestoreCommand(), List.of(TEMPLATE, "v1")),
            Map.entry(new SnapsPruneCommand(), List.of(TEMPLATE, "--keep", "0")),
            Map.entry(new ProjectDestroyCommand(), List.of(ws, "--dry-run")));

    for (var verb : verbs) {
      var err = new ByteArrayOutputStream();
      var original = System.err;
      System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
      int exit;
      try {
        var cli = new CommandLine(verb.getKey());
        cli.setExecutionExceptionHandler((failure, line, parsed) -> 1);
        exit = cli.execute(verb.getValue().toArray(String[]::new));
      } finally {
        System.setErr(original);
      }
      var said = err.toString(StandardCharsets.UTF_8);
      var name = verb.getValue().getFirst();
      assertEquals(1, exit, verb.getValue() + ": " + said);
      assertTrue(
          said.contains("Project " + name + " cannot take instance " + name + ": it is "),
          verb.getValue() + ": " + said);
    }

    var box = new BoxInstances(shell);
    assertEquals(new ContainerState.Stopped(), box.named(TEMPLATE).orElseThrow().state());
    assertTrue(box.named(ws).orElseThrow().state() instanceof ContainerState.Running);
    assertEquals(
        List.of("v1"),
        new SnapshotManager(shell)
            .list(TEMPLATE).stream().map(SnapshotManager.SnapshotInfo::name).toList());
    assertEquals(
        List.of(),
        new SnapshotManager(shell)
            .list(ws).stream().map(SnapshotManager.SnapshotInfo::name).toList());
  }

  /**
   * Round 3's blocker on a real Incus: a workspace made and stopped on a node while the node's
   * rounds run is still there afterwards, whole and with its keys. Each round lists the box through
   * {@link #unrendering}, so it finds the workspace as Incus lists an instance it cannot render
   * mid-stop, the listing that made a node take a workspace for a project of its own name, out of
   * its scope, and delete it; every other call, the round's re-read and delete among them, reaches
   * this host's Incus, and its locks are this box's.
   */
  @Test
  void aWorkspaceMadeAndStoppedOnANodeWhileItsRoundsRunIsStillThereWithItsKeys(@TempDir Path dir)
      throws Exception {
    var instance = PROJECT + "-" + FIX;
    var stopped = new AtomicBoolean();
    var rounds = new AtomicInteger();
    var runner = Executors.newSingleThreadExecutor();
    try (var node = new ScopedNode(dir, PROJECT, unrendering(instance))) {
      var running =
          runner.submit(
              () -> {
                while (!stopped.get()) {
                  node.round();
                  rounds.incrementAndGet();
                }
                return null;
              });
      workspaces.create(definition(), FIX, source(), null, "eng", TestOwners::of);
      workspaces.stop(PROJECT, FIX, NO_RUN);
      stopped.set(true);
      running.get(5, TimeUnit.MINUTES);
      node.round();
    } finally {
      runner.shutdownNow();
    }

    assertTrue(rounds.get() > 0, "a round ran while the workspace was made and stopped");
    var found = new BoxInstances(shell).named(instance).orElseThrow();
    assertEquals(new ContainerState.Stopped(), found.state());
    assertEquals(new BoxInstances.Workspace(PROJECT, FIX, true), found.kind());
    assertEquals(Optional.of("eng"), found.key(BoxInstances.OWNER_KEY));
    assertEquals(Optional.of(TestOwners.of("eng").id()), found.key(BoxInstances.OWNER_ID_KEY));
  }

  /**
   * A destroy on a real Incus deletes a project's workspaces and template only under their locks
   * and the box's create lock, read again there: a create that holds the create lock past the wait
   * refuses it and nothing goes; a workspace made after it read what it would delete refuses it,
   * naming it; read again as it is, it deletes every workspace and the template, and no other
   * instance.
   */
  @Test
  void aDestroyActsOnlyUnderTheLocksAndOnWhatItReadsThereSoNoWorkspaceOutlivesItsProject(
      @TempDir Path dir) throws Exception {
    var before = bystander();
    var fix = workspaces.create(definition(), FIX, source(), null, "eng", TestOwners::of);
    var teardown = new ProjectTeardown(shell, dir);
    Workspaces.Destroy destroy = parts -> teardown.tearDown(PROJECT, parts);
    var outwaited =
        new Workspaces(
            shell,
            shell,
            HeldLocks.pastEveryWait(),
            SailPaths.locksDir(),
            Workspaces.memoryOf(new HostDetector()),
            url -> false);
    var busy = new AtomicReference<Exception>();

    HeldLocks.whileHeld(
        SailPaths.locksDir().resolve(Workspaces.CREATE_LOCK),
        () -> {
          busy.set(
              assertThrows(
                  IllegalStateException.class,
                  () ->
                      outwaited.whileDestroying(
                          PROJECT, List.of(fix.instance(), TEMPLATE), destroy)));
          return null;
        });
    assertTrue(
        busy.get().getMessage().contains("Project " + PROJECT + " is not destroyed: This runner"),
        busy.get().getMessage());
    assertTrue(new BoxInstances(shell).named(fix.instance()).isPresent());
    assertTrue(new BoxInstances(shell).named(TEMPLATE).isPresent());

    var second = workspaces.create(definition(), SECOND, source(), null, "eng", TestOwners::of);
    var unseen =
        assertThrows(
            IllegalStateException.class,
            () -> workspaces.whileDestroying(PROJECT, List.of(fix.instance(), TEMPLATE), destroy));
    assertTrue(
        unseen.getMessage().contains(second.instance() + " was created after"), unseen.getMessage());
    assertTrue(new BoxInstances(shell).named(second.instance()).isPresent());

    workspaces.whileDestroying(
        PROJECT, List.of(fix.instance(), second.instance(), TEMPLATE), destroy);

    var left = new BoxInstances(shell).all().stream().map(BoxInstances.Instance::name).toList();
    assertFalse(left.contains(fix.instance()), String.valueOf(left));
    assertFalse(left.contains(second.instance()), String.valueOf(left));
    assertFalse(left.contains(TEMPLATE), String.valueOf(left));
    assertEquals(before, bystander(), "the project's own container is untouched");
  }

  /**
   * This host's Incus, except that a listing of every instance shows {@code instance} as Incus
   * shows one it cannot render: its name, an Error status and no config.
   */
  private ShellExec unrendering(String instance) {
    return new ShellExec() {
      @Override
      public Result exec(List<String> command)
          throws IOException, InterruptedException, TimeoutException {
        return rendered(command, shell.exec(command));
      }

      @Override
      public Result exec(List<String> command, Path workDir, Duration timeout)
          throws IOException, InterruptedException, TimeoutException {
        return rendered(command, shell.exec(command, workDir, timeout));
      }

      @Override
      public boolean isDryRun() {
        return false;
      }

      private Result rendered(List<String> command, Result listed) {
        if (!command.equals(List.of("incus", "list", "--format", "json")) || !listed.ok()) {
          return listed;
        }
        var entries =
            YamlUtil.parseList(listed.stdout()).stream()
                .map(
                    entry ->
                        instance.equals(entry.get("name"))
                            ? Map.<String, Object>of(
                                "name",
                                instance,
                                "status",
                                "Error",
                                "status_code",
                                400,
                                "type",
                                "container")
                            : entry)
                .toList();
        return new Result(0, YamlUtil.dumpJsonValue(entries), listed.stderr());
      }
    };
  }

  @Test
  void aWorkspaceStopsAndResumesWithItsEditsAndAPauseListsAsFrozen() throws Exception {
    var fix = workspaces.create(definition(), FIX, source(), null, "eng", TestOwners::of);
    var edited = exec(fix.instance(), List.of("bash", "-c", "echo kept > /home/dev/edit"));
    assertTrue(edited.ok(), edited.stderr());

    assertEquals(Workspaces.Change.STOPPED, workspaces.stop(PROJECT, FIX, NO_RUN).change());
    assertEquals(
        new ContainerState.Stopped(),
        new BoxInstances(shell).named(fix.instance()).orElseThrow().state());
    var resumed = workspaces.start(definition(), FIX, TestOwners::of);
    record("start " + FIX, resumed.timings());

    assertEquals(Workspaces.Change.STARTED, resumed.change());
    assertEquals("kept", inWorkspace(fix, "cat /home/dev/edit"));
    assertEquals(PROJECT, inWorkspace(fix, "hostname"), "the hostname survives a restart");

    assertTrue(shell.exec(List.of("incus", "pause", fix.instance())).ok());
    assertEquals(
        List.of("main running", FIX + " frozen"),
        workspaces.list(PROJECT, id -> true).workspaces().stream()
            .map(row -> row.name() + " " + row.state())
            .toList());
    assertEquals(
        Workspaces.Change.STARTED, workspaces.start(definition(), FIX, TestOwners::of).change());
  }

  @Test
  void theInContainerSpecBoardOfAWorkspaceReadsItsProjectsSpecs() throws Exception {
    var socketDir = Files.createTempDirectory("sail-it-ws-socket");
    Files.setPosixFilePermissions(socketDir, PosixFilePermissions.fromString("rwxr-xr-x"));
    var dbPath = Files.createTempDirectory("sail-it-ws-db").resolve("sail.db");
    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, PROJECT);
      var specs = new SpecStore(db);
      Acting.as("it", () -> specs.create(probe()));
      var fdes = new FdeStore(db);
      fdes.add("it", "IT", "it@example.dev", "admin");
      var boxes = new BoxCredentialStore(db);
      BoxCredentialFile.ensure(boxes, "it", socketDir);
      var operations =
          new SailOperations(
                  new ShellExecutor(false),
                  ProjectReader.ofCatalog(new ProjectStore(db)),
                  new EventBus(),
                  specs,
                  null,
                  new RunStore(db),
                  null,
                  SyncScheduler.disabled(),
                  fdes,
                  SessionYield.NONE)
              .useMessages(new MessageStore(db))
              .useBoxCredentials(boxes)
              .useRooms(new RoomStore(db));
      TestControlPlane.on(operations, db, socketDir, new SyncConfig(null, null, "it"));
      try (var server = new LocalApiSocket(operations, socketDir.resolve("api.sock"))) {
        server.start();
        var fix = workspaces.create(definition(), FIX, source(), null, "eng", TestOwners::of);
        new IncusDeviceManager(shell).ensureEventSocket(fix.instance(), socketDir, CONTAINER_DIR);

        var board =
            shell.exec(
                ContainerExec.asDevUser(
                    fix.instance(), List.of(SpecCliHelper.SCRIPT_PATH, "board")));

        assertTrue(board.ok(), "spec board failed: " + board.stderr() + board.stdout());
        assertTrue(
            board.stdout().contains(SPEC_ID),
            "the board lists the project's spec: " + board.stdout());
      }
    }
  }

  /**
   * Scenario 5 on a real ZFS pool: a done spec's workspace whose only unpushed commit is on its
   * runs' branch has that branch pushed, and is then deleted, its dataset with it.
   */
  @Test
  void aDoneSpecsWorkspaceIsPushedThenDeletedAndItsDatasetGoes() throws Exception {
    try (var db = Sqlite.open(Files.createTempDirectory("sail-it-idle").resolve("sail.db"))) {
      var said = new ArrayList<String>();
      var clock = new AtomicReference<>(Instant.now());
      var sweeper = sweeper(db, said, clock);
      var instance =
          doneSpecsWorkspace(
              db,
              GONE,
              SpecStatus.DONE,
              "git switch -q -c agent/gone && git commit -q --allow-empty -m work");
      clock.set(Instant.now().plus(SpecWorkspaces.IDLE).plusSeconds(1));

      sweeper.sweep();

      assertEquals(Optional.empty(), new BoxInstances(shell).named(instance));
      assertFalse(
          shell
              .exec(
                  List.of(
                      "sudo",
                      "-n",
                      "zfs",
                      "list",
                      "-H",
                      "-o",
                      "name",
                      POOL + "/containers/" + instance))
              .ok(),
          "the workspace's dataset is gone");
      assertEquals(List.of(), said);
    }
  }

  /**
   * Scenario 6 and rule A on a real ZFS pool: a done spec's workspace holding a commit on a branch
   * that is not checked out, a stash and an untracked file is kept, stopped, its dataset kept, and
   * said once naming all three; a person who starts it has it stopped again once idle, never
   * deleted by the sweep.
   */
  @Test
  void aDoneSpecsWorkspaceHoldingWhatNoRemoteHasIsKeptStoppedWithItsDataset() throws Exception {
    try (var db = Sqlite.open(Files.createTempDirectory("sail-it-idle").resolve("sail.db"))) {
      var said = new ArrayList<String>();
      var clock = new AtomicReference<>(Instant.now());
      var sweeper = sweeper(db, said, clock);
      var instance =
          doneSpecsWorkspace(
              db,
              KEPT,
              SpecStatus.DONE,
              "git switch -q -c by-hand && git commit -q --allow-empty -m work"
                  + " && git checkout -q --detach HEAD^ && echo x > f && git add f"
                  + " && git stash -q && echo y > untracked && echo z >> README");
      clock.set(Instant.now().plus(SpecWorkspaces.IDLE).plusSeconds(1));

      sweeper.sweep();
      sweeper.sweep();

      var kept = new BoxInstances(shell).named(instance).orElseThrow();
      var why =
          "app holds uncommitted changes and untracked files and a stash and commits origin lacks";
      assertEquals(new ContainerState.Stopped(), kept.state());
      assertEquals(Optional.of(why), kept.key(BoxInstances.KEPT_KEY));
      assertEquals(
          POOL + "/containers/" + instance,
          zfs("list", "-H", "-o", "name", POOL + "/containers/" + instance));
      assertEquals(
          List.of(
              "Workspace kept of sail-it-ws is kept, stopped, though spec kept is done: "
                  + why
                  + ". Sail will not delete it. Take what it holds by hand (sail project workspace"
                  + " start sail-it-ws kept, then sail project workspace shell sail-it-ws kept),"
                  + " then drop it with sail project workspace delete sail-it-ws kept."),
          said);

      workspaces.start(definition(), KEPT, TestOwners::of);
      var started = new BoxInstances(shell).named(instance).orElseThrow().lastStarted();
      clock.set(started.plus(SpecWorkspaces.IDLE).minusSeconds(30));
      sweeper.sweep();
      assertTrue(
          new BoxInstances(shell).named(instance).orElseThrow().state()
              instanceof ContainerState.Running,
          "started by hand at " + started + ", it has ten minutes from Incus's start");
      clock.set(Instant.now().plus(SpecWorkspaces.IDLE).plusSeconds(1));
      sweeper.sweep();
      assertEquals(
          new ContainerState.Stopped(),
          new BoxInstances(shell).named(instance).orElseThrow().state(),
          "a kept workspace a person started stops again once idle");
      assertEquals(1, said.size(), String.join("\n", said));
    }
  }

  /**
   * The proof's {@code kh2} on a real Incus: a commit pushed to a second remote inside the
   * workspace, its local branch then deleted, is held by that remote's ref alone, which origin
   * lacks, so the done spec's workspace is kept, not deleted.
   */
  @Test
  void aCommitOnlyAnotherRemotesRefHoldsKeepsTheDoneSpecsWorkspace() throws Exception {
    try (var db = Sqlite.open(Files.createTempDirectory("sail-it-idle").resolve("sail.db"))) {
      var said = new ArrayList<String>();
      var clock = new AtomicReference<>(Instant.now());
      var sweeper = sweeper(db, said, clock);
      var instance =
          doneSpecsWorkspace(
              db,
              BACKED,
              SpecStatus.DONE,
              "git init -q --bare /home/dev/backup.git && git remote add backup"
                  + " /home/dev/backup.git && git switch -q -c side && git commit -q"
                  + " --allow-empty -m 'backed up work' && git push -q backup side"
                  + " && git checkout -q --detach HEAD^ && git branch -q -D side");
      clock.set(Instant.now().plus(SpecWorkspaces.IDLE).plusSeconds(1));

      sweeper.sweep();

      var kept = new BoxInstances(shell).named(instance).orElseThrow();
      assertEquals(new ContainerState.Stopped(), kept.state());
      assertEquals(Optional.of("app holds commits origin lacks"), kept.key(BoxInstances.KEPT_KEY));
      assertEquals(1, said.size(), String.join("\n", said));
    }
  }

  /**
   * S-B1 of the second look on a real Incus: edits in a linked worktree under a directory the repo
   * ignores, where a harness puts its worktrees, are read where git names the worktree, and keep
   * the done spec's workspace, though the repo's own worktree is clean.
   */
  @Test
  void editsInALinkedWorktreeTheRepoIgnoresKeepTheDoneSpecsWorkspace() throws Exception {
    try (var db = Sqlite.open(Files.createTempDirectory("sail-it-idle").resolve("sail.db"))) {
      var said = new ArrayList<String>();
      var clock = new AtomicReference<>(Instant.now());
      var sweeper = sweeper(db, said, clock);
      var instance =
          doneSpecsWorkspace(
              db,
              TREE,
              SpecStatus.DONE,
              "echo .claude/ >> .git/info/exclude && git worktree add -q -b wt"
                  + " .claude/worktrees/w && cd .claude/worktrees/w && echo x > f && git add f"
                  + " && echo y > new.java");
      clock.set(Instant.now().plus(SpecWorkspaces.IDLE).plusSeconds(1));

      sweeper.sweep();

      var kept = new BoxInstances(shell).named(instance).orElseThrow();
      assertEquals(new ContainerState.Stopped(), kept.state());
      assertEquals(
          Optional.of("app/.claude/worktrees/w holds uncommitted changes and untracked files"),
          kept.key(BoxInstances.KEPT_KEY));
      assertEquals(1, said.size(), String.join("\n", said));
    }
  }

  /**
   * Ruling 1 of round 2 on a real Incus: an {@code incus exec} held open in a spec's workspace, as
   * a person's shell holds one, keeps it running across a pass past its idle period; once the
   * command ends, and with it the exec, it stops ten minutes after the pass that last found it.
   */
  @Test
  void anExecHeldOpenKeepsTheWorkspaceRunningAcrossAPassAndItStopsOnceTheExecEnds()
      throws Exception {
    try (var db = Sqlite.open(Files.createTempDirectory("sail-it-idle").resolve("sail.db"))) {
      var said = new ArrayList<String>();
      var clock = new AtomicReference<>(Instant.now());
      var sweeper = sweeper(db, said, clock);
      var instance = doneSpecsWorkspace(db, HELD, SpecStatus.AWAITING_MERGE, "true");
      var box = new BoxInstances(shell);
      var held = new ProcessBuilder("incus", "exec", instance, "--", "sleep", "600").start();
      try {
        assertTrue(until(() -> box.inUse().contains(instance)), "the exec shows as running");
        clock.set(Instant.now().plus(SpecWorkspaces.IDLE).plusSeconds(1));

        sweeper.sweep();

        assertTrue(
            box.named(instance).orElseThrow().state() instanceof ContainerState.Running,
            "an exec is running on it");
      } finally {
        shell.exec(List.of("incus", "exec", instance, "--", "pkill", "-x", "sleep"));
        if (!held.waitFor(30, TimeUnit.SECONDS)) {
          held.destroyForcibly();
        }
      }
      assertTrue(until(() -> !box.inUse().contains(instance)), "the exec is gone");
      var seen = clock.get();
      clock.set(seen.plus(SpecWorkspaces.IDLE).minusSeconds(1));
      sweeper.sweep();
      assertTrue(
          box.named(instance).orElseThrow().state() instanceof ContainerState.Running,
          "ten minutes from the pass that found the exec, at " + seen + ", have not passed");
      clock.set(seen.plus(SpecWorkspaces.IDLE).plusSeconds(1));

      sweeper.sweep();

      assertEquals(new ContainerState.Stopped(), box.named(instance).orElseThrow().state());
      assertEquals(List.of(), said);
    }
  }

  /** Whether {@code condition} holds within 30 s, asked again and again until then. */
  private static boolean until(Callable<Boolean> condition) throws Exception {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (condition.call()) {
        return true;
      }
      Thread.onSpinWait();
    }
    return false;
  }

  /**
   * Spec {@code specId}, at {@code status}, with one ended run on this box whose usage is read, and
   * its workspace made as a dispatch makes it, after which {@code work} ran in its repo as the dev
   * user.
   */
  private String doneSpecsWorkspace(Sqlite db, String specId, SpecStatus status, String work)
      throws Exception {
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, PROJECT);
    var specs = new SpecStore(db);
    Acting.as("it", () -> specs.create(spec(specId, status)));
    var runs = new RunStore(db);
    var run = DateTimeUtils.newId().toString();
    Acting.system(
        () -> {
          runs.create(
              run,
              PROJECT,
              specId,
              "eng",
              "build",
              "claude-code",
              "agent/" + specId,
              "task",
              1,
              null,
              "/home/dev/.sail/runs/" + run + "/agent.log",
              "sail-agent-" + run);
          TestRuns.complete(runs, run, "completed", null);
          runs.recordRead(run, RunReport.NONE);
        });
    var place = (RunPlace.SpecWorkspace) RunPlace.of(PROJECT, specId);
    var check =
        workspaces.checkSpec(
            place, ProjectProvisioner.inputs(definition(), Optional.empty()), name -> false);
    var entered =
        workspaces.enterSpec(check, definition(), "eng", TestOwners::of, null, whole -> {});
    var committed =
        shell.exec(
            ContainerExec.asDevUser(
                entered.instance(),
                List.of("bash", "-c", "cd /home/dev/workspace/app && " + work)));
    assertTrue(committed.ok(), committed.stderr());
    return entered.instance();
  }

  /** The box's sweep over {@code db}, on {@code clock}, which a test moves past the idle period. */
  private SpecWorkspaces sweeper(Sqlite db, List<String> said, AtomicReference<Instant> clock) {
    return new SpecWorkspaces(
        workspaces,
        shell,
        SailPaths.locksDir(),
        new SpecStore(db),
        new RunStore(db),
        new ChangeLog(db),
        project -> Optional.of(definition()),
        (specId, body) -> said.add(body),
        RunReports.reading(shell, RunLauncher.Room.NONE, event -> {}),
        TestOwners::of,
        () -> "eng",
        clock::get);
  }

  private static SpecStore.SpecRow spec(String id, SpecStatus status) {
    return new SpecStore.SpecRow(
        id,
        PROJECT,
        "Spec " + id,
        status,
        "eng",
        "claude-code",
        null,
        null,
        "agent/" + id,
        0,
        "it",
        null,
        null,
        "it",
        List.of(),
        List.of("app"));
  }

  private static SpecStore.SpecRow probe() {
    return new SpecStore.SpecRow(
        SPEC_ID,
        PROJECT,
        "Workspace board probe",
        SpecStatus.PENDING,
        null,
        null,
        null,
        null,
        null,
        0,
        "it",
        null,
        null,
        "it",
        List.of(),
        List.of());
  }

  /** The project's own container as Incus holds it: its state, snapshots and configuration. */
  private String bystander() throws Exception {
    var state = containers.queryState(PROJECT);
    var snapshots = new SnapshotManager(shell).list(PROJECT).size();
    var config = shell.exec(List.of("incus", "config", "show", PROJECT));
    assertTrue(config.ok(), config.reason());
    return state + " " + snapshots + "\n" + config.stdout();
  }

  private String inWorkspace(Workspaces.Created workspace, String command) throws Exception {
    var result =
        shell.exec(ContainerExec.asDevUser(workspace.instance(), List.of("bash", "-c", command)));
    assertTrue(result.ok(), command + ": " + result.stderr());
    return result.stdout().strip();
  }

  private String read(String path) throws Exception {
    var pulled = shell.exec(List.of("incus", "file", "pull", path, "-"));
    assertTrue(pulled.ok(), path + ": " + pulled.stderr());
    assertNotEquals("", pulled.stdout().strip(), path);
    return pulled.stdout().strip();
  }

  private String zfs(String... args) throws Exception {
    var command = new ArrayList<>(List.of("sudo", "-n", "zfs"));
    command.addAll(List.of(args));
    var result = shell.exec(command);
    assertTrue(result.ok(), String.join(" ", command) + ": " + result.reason());
    return result.stdout().strip();
  }

  /** Prints what a create or start took into the job log: a record, not a gate. */
  private static void record(String what, Workspaces.Timings timings) {
    System.out.println(
        "workspace-timing: "
            + what
            + " clone_ms="
            + timings.cloneMs()
            + " start_ms="
            + timings.startMs()
            + " ready_ms="
            + timings.readyMs()
            + " total_ms="
            + timings.totalMs()
            + " ready_steps_ms="
            + timings.readySteps());
  }
}
