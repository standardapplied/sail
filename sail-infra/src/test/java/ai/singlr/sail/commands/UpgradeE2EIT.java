/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static ai.singlr.sail.commands.PtyTranscript.awaitText;
import static ai.singlr.sail.commands.PtyTranscript.prologue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SshIdentityProvisioner;
import ai.singlr.sail.engine.SshdKeepalive;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtyWire;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code sail upgrade} as the fleet runs it. The box is an incus system container — systemd, root,
 * sshd — provisioned by the released binary the real way, then upgraded to the candidate with
 * {@code sail upgrade --binary}: the install, the new binary's migrations, the {@code sail-api}
 * restart and the pty host's live handoff, end to end. Root's control plane under {@code
 * /var/lib/sail} is main; the user {@code mady} is a node syncing through the {@code sail} user's
 * forced command, so the gateway lane crosses the upgrade too. The candidate runs from a staged
 * path of its own, so host state that names the binary it ran from shows.
 *
 * <p>The shared script stands for a box the content migration converted: its row carries the mode
 * the old rule gave a script (0755), its history records none, and the copy an older materializer
 * wrote on disk is 0644 — on main and on the node alike.
 */
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class UpgradeE2EIT extends AbstractIncusIT {

  private static final String BOX = "sail-it-upgrade";
  private static final String INSTALLED = "/usr/local/bin/sail";
  private static final String STAGED = "/root/staged/sail";
  private static final String NOT_SAIL = "/root/staged/not-sail";
  private static final String DB = "/var/lib/sail/sail.db";
  private static final String REHEARSAL = "/root/rehearsal";
  private static final String NODE = "mady";
  private static final String NODE_DB = "/home/mady/.sail/sail.db";
  private static final String SERVER = "http://127.0.0.1:7070";
  private static final String SCRIPT_ID = "demo/deploy.sh";
  private static final String SCRIPT_ON_MAIN = "/root/.sail/projects/demo/files/deploy.sh";
  private static final String SCRIPT_ON_NODE = "/home/mady/.sail/projects/demo/files/deploy.sh";
  private static final String SESSION = "e2e";
  private static final Pattern PID = Pattern.compile("pid=(\\d+)\\r?\\n");
  private static final Duration COMMAND_DEADLINE = Duration.ofMinutes(5);
  private static final List<String> SERVICES = List.of("sail-api", "sail-pty-host");
  private static final List<String> HOST_STATE =
      List.of(
          "/home/sail/.ssh/authorized_keys",
          "/etc/systemd/system/sail-api.service",
          "/etc/systemd/system/sail-pty-host.service",
          SshIdentityProvisioner.DROP_IN,
          SshdKeepalive.DROP_IN_PATH);
  private static final String SPECS =
      "SELECT id, title, status, body_hash FROM specs WHERE id LIKE 'seed-%' ORDER BY id";
  private static final String SCRIPT_REVISIONS =
      "SELECT count(*) FROM change_log WHERE entity_type = 'file' AND entity_id = '"
          + SCRIPT_ID
          + "'";
  private static final String LEGACY_HISTORY =
      "UPDATE change_log SET snapshot = json_remove(snapshot, '$.mode')"
          + " WHERE entity_type = 'file' AND entity_id = '"
          + SCRIPT_ID
          + "'";

  private static final String PROVISION_MAIN =
      """
      set -e
      install -d -m 700 /root/.sail
      printf 'storage_backend: dir\\n' > /root/.sail/host.yaml
      sail server init
      sail host sync --as-main
      sail host config set sync-handle uday
      sail host service install
      sail host ssh-identity
      sail fde add uday --role admin --email uday@example.dev
      sail migrate --non-interactive
      """;
  private static final String PROVISION_NODE =
      """
      set -e
      install -d -m 700 ~/.sail ~/.ssh
      printf 'storage_backend: dir\\n' > ~/.sail/host.yaml
      sail server init
      ssh-keyscan -H localhost > ~/.ssh/known_hosts 2>/dev/null
      sail join localhost --handle mady --name mady --email mady@example.dev --json
      """;
  private static final String SEED =
      """
      set -e
      for n in 1 2 3; do
        printf '# Seed %%s\\n\\nKept across the upgrade.\\n' "$n" > /tmp/seed-$n.md
        sail spec create --server %s --id seed-$n --title "Seed $n" -p demo \\
          --body-file /tmp/seed-$n.md
      done
      printf '#!/bin/sh\\necho deploy\\n' > /tmp/deploy.sh
      chmod 755 /tmp/deploy.sh
      sail project files add -p demo /tmp/deploy.sh --as deploy.sh
      """
          .formatted(SERVER);

  @Test
  void theReleasedBoxUpgradesToTheCandidateKeepingItsDataServicesAndSessions() throws Exception {
    var binaries = NativeBinaries.requireOrSkip("the upgrade end to end");
    ensureIncusOrSkip();
    try {
      launchPrepared(BOX);
      root("systemctl is-system-running --wait");
      push(binaries.released(), INSTALLED);
      push(binaries.candidate(), STAGED);
      rootOk(PROVISION_MAIN);
      provisionNode();
      seed();
      var socket = ptyProxy();
      var pid = openSession(socket);
      rootOk(
          "install -d -m 700 "
              + REHEARSAL
              + " && sqlite3 "
              + DB
              + " '.backup "
              + REHEARSAL
              + "/sail.db'");
      var driver = driver();

      aFileThatIsNotSailIsRefusedBeforeAnythingChanges(driver, socket, pid);

      var specs = query(SPECS);
      var revisions = List.of(query(SCRIPT_REVISIONS), nodeQuery(SCRIPT_REVISIONS));
      var before = serviceStates();
      var upgraded = root(driver + " upgrade --binary " + STAGED);
      System.out.println(upgraded.stdout() + upgraded.stderr());
      assertEquals(0, upgraded.exitCode(), () -> upgraded.stdout() + upgraded.stderr() + journal());

      theCandidateIsInstalledAndMigratedTheDatabase(specs);
      bothServicesRestartedOnTheCandidate(before);
      theSessionLivesOnInTheSameProcessAndStillTakesInput(socket, pid);
      theDatabaseStaysSharedWithTheGateway();
      hostStateNamesTheInstalledBinary();
      theLegacyScriptSyncsWithoutAConflict(revisions);
      upgradingAgainRestartsNothing();
      aRehearsalConvergesTheCopyAndTouchesNothingElse();
    } finally {
      deleteContainerQuietly(BOX);
    }
  }

  /** The released sail drives the hop when it knows {@code --binary}, as the fleet's would. */
  private String driver() throws Exception {
    if (rootOk(INSTALLED + " upgrade --help").contains("--binary")) {
      System.out.println("sail upgrade --binary driven by the released binary: the fleet's hop");
      return INSTALLED;
    }
    System.out.println(
        "sail upgrade --binary driven by the candidate: the released sail upgrade has no --binary");
    return STAGED;
  }

  private void provisionNode() throws Exception {
    rootOk("useradd --create-home --shell /bin/bash " + NODE);
    var joined = NativeFleet.json(nodeOk(PROVISION_NODE));
    ok(
        List.of(
            "sail",
            "fde",
            "add",
            NODE,
            "--role",
            "member",
            "--email",
            NODE + "@example.dev",
            "--key",
            (String) joined.get("public_key")));
  }

  /**
   * Specs through the API, the shared script synced to the node, and then both boxes put in the
   * state the content migration leaves a legacy box in.
   */
  private void seed() throws Exception {
    awaitListening("sail-api");
    rootOk(SEED);
    nodeOk("sail sync");
    query(LEGACY_HISTORY);
    rootOk("chmod 644 " + SCRIPT_ON_MAIN);
    nodeQuery(LEGACY_HISTORY);
    ok(List.of("runuser", "-u", NODE, "--", "chmod", "644", SCRIPT_ON_NODE));
    assertEquals("493", query("SELECT mode FROM project_files WHERE id = '" + SCRIPT_ID + "'"));
    assertEquals("493", nodeQuery("SELECT mode FROM project_files WHERE id = '" + SCRIPT_ID + "'"));
  }

  /** Opens the session whose child prints its pid and blocks on {@code read}; returns the pid. */
  private String openSession(Path socket) throws Exception {
    try (var client = UserUnitFixture.connect(socket)) {
      client.create(
          SESSION,
          List.of("sh", "-c", "echo pid=$$; while read -r line; do echo got=$line; done"),
          "/tmp",
          "",
          "",
          80,
          24);
      var channel = client.attach(SESSION, false);
      prologue(channel);
      return pidOf(channel);
    }
  }

  private void aFileThatIsNotSailIsRefusedBeforeAnythingChanges(
      String driver, Path socket, String pid) throws Exception {
    rootOk("printf 'not a binary\\n' > " + NOT_SAIL + " && chmod 755 " + NOT_SAIL);
    var installed = sha(INSTALLED);
    var services = serviceStates();

    var refused = root(driver + " upgrade --binary " + NOT_SAIL);

    assertNotEquals(0, refused.exitCode(), refused::stdout);
    assertTrue(
        refused.stderr().contains(NOT_SAIL + " is not a linux-amd64 executable"), refused::stderr);
    assertEquals(installed, sha(INSTALLED), "the released binary stays in place");
    assertEquals(services, serviceStates(), "nothing restarted");
    assertSessionAlive(socket, pid);
  }

  private void theCandidateIsInstalledAndMigratedTheDatabase(String specs) throws Exception {
    assertEquals(ok(List.of(STAGED, "-V")), ok(List.of("sail", "-V")));
    assertEquals(sha(STAGED), sha(INSTALLED));
    assertEquals(
        Integer.toString(candidateSchema()), query("SELECT max(version) FROM schema_version"));
    var recorded = query("SELECT name FROM data_migrations").lines().toList();
    for (var migration : MigrateCommand.REGISTRY) {
      assertTrue(recorded.contains(migration.name()), migration.name() + " not in " + recorded);
    }
    assertEquals(specs, query(SPECS), "the seeded specs are intact");
  }

  private void bothServicesRestartedOnTheCandidate(Map<String, String> before) throws Exception {
    var after = serviceStates();
    var candidate = sha(STAGED);
    for (var unit : SERVICES) {
      assertEquals("active", ok(List.of("systemctl", "is-active", unit)).strip(), unit);
      assertNotEquals(before.get(unit), after.get(unit), unit + " was not restarted");
      var pid = ok(List.of("systemctl", "show", "-p", "MainPID", "--value", unit)).strip();
      assertEquals(candidate, sha("/proc/" + pid + "/exe"), unit + " runs the candidate");
    }
    awaitListening("sail-api");
    var listed = ok(List.of("sail", "spec", "list", "--server", SERVER, "-p", "demo"));
    for (var n = 1; n <= 3; n++) {
      assertTrue(listed.contains("seed-" + n), listed);
    }
  }

  private void theSessionLivesOnInTheSameProcessAndStillTakesInput(Path socket, String pid)
      throws Exception {
    assertSessionAlive(socket, pid);
    try (var client = UserUnitFixture.connect(socket)) {
      var channel = client.attach(SESSION, true);
      prologue(channel);
      assertEquals(pid, pidOf(channel), "the ring replays across the upgrade");
      PtyWire.write(channel, new PtyMessage.Input(1, "ping\n".getBytes(StandardCharsets.UTF_8)));
      awaitText(channel, "got=ping");
    }
  }

  private void theDatabaseStaysSharedWithTheGateway() throws Exception {
    for (var line : rootOk("stat -c '%n %U:%G %a' " + DB + "*").lines().toList()) {
      assertTrue(line.endsWith(" root:sail 660"), line);
    }
    assertEquals("root:sail 640", rootOk("stat -c '%U:%G %a' /var/lib/sail/host.yaml").strip());
  }

  private void hostStateNamesTheInstalledBinary() throws Exception {
    var keys = ok(List.of("cat", "/home/sail/.ssh/authorized_keys"));
    var forced = keys.lines().filter(line -> line.startsWith("command=")).toList();
    assertFalse(forced.isEmpty(), keys);
    for (var line : forced) {
      assertTrue(line.startsWith("command=\"" + INSTALLED + " _gateway --fde "), line);
    }
    assertTrue(
        ok(List.of("cat", "/etc/systemd/system/sail-pty-host.service"))
            .contains("ExecStart=" + INSTALLED + " _pty-host"));
    assertTrue(
        ok(List.of("cat", "/etc/systemd/system/sail-api.service"))
            .contains("ExecStart=" + INSTALLED + " server start"));
    var named = root("grep -rl " + STAGED + " /home/sail/.ssh /etc/systemd/system");
    assertEquals("", named.stdout().strip(), "host state names the staged candidate");
  }

  /**
   * The node takes its own upgrade — its binary is already the candidate, so that is the new
   * binary's migrate — then writes through the gateway. No revision of the script is recorded on
   * either box, nothing conflicts, and both copies end up with the script's mode.
   */
  private void theLegacyScriptSyncsWithoutAConflict(List<String> revisions) throws Exception {
    nodeOk("sail migrate --non-interactive");
    nodeOk(
        """
        printf 'from the node\\n' > ~/node.txt
        sail project files add -p demo ~/node.txt --as node.txt
        sail sync
        """);
    rootOk("sail project files pull -p demo");

    assertEquals(revisions, List.of(query(SCRIPT_REVISIONS), nodeQuery(SCRIPT_REVISIONS)));
    assertEquals(List.of(), NativeFleet.jsonList(nodeOk("sail conflicts --json")));
    assertEquals(List.of(), NativeFleet.jsonList(rootOk("sail conflicts --json")));
    assertEquals("1", query("SELECT count(*) FROM project_files WHERE id = 'demo/node.txt'"));
    assertEquals("755", ok(List.of("stat", "-c", "%a", SCRIPT_ON_MAIN)).strip());
    assertEquals("755", ok(List.of("stat", "-c", "%a", SCRIPT_ON_NODE)).strip());
  }

  private void upgradingAgainRestartsNothing() throws Exception {
    var services = serviceStates();
    var installed = sha(INSTALLED);

    var again = rootOk("sail upgrade --binary " + STAGED);

    assertTrue(again.contains("Already up to date"), again);
    assertEquals(installed, sha(INSTALLED));
    assertEquals(services, serviceStates(), "a repeated upgrade restarted a service");
  }

  private void aRehearsalConvergesTheCopyAndTouchesNothingElse() throws Exception {
    var hostState = mtimes();
    var services = serviceStates();

    var rehearsed = rootOk("SAIL_DATA_DIR=" + REHEARSAL + " sail migrate --non-interactive");

    assertEquals(hostState, mtimes(), "a rehearsal rewrote host state");
    assertEquals(services, serviceStates(), "a rehearsal restarted a service");
    assertTrue(rehearsed.contains("Rehearsal: migrated " + REHEARSAL + "/sail.db only"), rehearsed);
    assertEquals(
        Integer.toString(candidateSchema()),
        ok(List.of("sqlite3", REHEARSAL + "/sail.db", "SELECT max(version) FROM schema_version"))
            .strip());
  }

  private void assertSessionAlive(Path socket, String pid) throws Exception {
    try (var client = UserUnitFixture.connect(socket)) {
      var session =
          client.list().stream()
              .filter(info -> info.name().equals(SESSION))
              .findFirst()
              .orElseThrow(() -> new AssertionError("no session '" + SESSION + "'" + journal()));
      assertTrue(session.live(), () -> "the session ended" + journal());
    }
    assertTrue(
        root("kill -0 " + pid).ok(), () -> "the session's child " + pid + " is gone" + journal());
  }

  private static String pidOf(SocketChannel channel) throws IOException {
    var seen = awaitText(channel, "pid=");
    while (!PID.matcher(seen).find()) {
      seen += awaitText(channel, "\n");
    }
    var matcher = PID.matcher(seen);
    matcher.find();
    return matcher.group(1);
  }

  private static int candidateSchema() {
    try (var db = Sqlite.openMemory()) {
      var schema = new SchemaManager(db);
      schema.migrate();
      return schema.currentVersion();
    }
  }

  /**
   * The container's pty socket, offered on the runner through an incus proxy device so the test
   * speaks to the host as the box owner would.
   */
  private Path ptyProxy() throws Exception {
    var dir = Files.createDirectories(Path.of("target", "upgrade-e2e").toAbsolutePath());
    var socket = dir.resolve("pty.sock");
    Files.deleteIfExists(socket);
    var added =
        shell.exec(
            List.of(
                "incus",
                "config",
                "device",
                "add",
                BOX,
                "pty",
                "proxy",
                "listen=unix:" + socket,
                "connect=unix:/root/.sail/pty.sock",
                "bind=host",
                "uid=" + Files.getAttribute(dir, "unix:uid"),
                "gid=" + Files.getAttribute(dir, "unix:gid"),
                "mode=0600"));
    assertTrue(added.ok(), "could not proxy the pty socket: " + added.stderr());
    return socket;
  }

  /** Waits for the unit's current run to log that it is listening. */
  private void awaitListening(String unit) {
    UserUnitFixture.await(
        () -> {
          try {
            return root("journalctl -o cat --no-pager _SYSTEMD_INVOCATION_ID=$(systemctl show -p"
                    + " InvocationID --value "
                    + unit
                    + ") | grep -q listening")
                .ok();
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        },
        unit + " to log that it is listening");
  }

  /** Each service's run: a restart changes both its invocation id and its start timestamp. */
  private Map<String, String> serviceStates() throws Exception {
    var states = new LinkedHashMap<String, String>();
    for (var unit : SERVICES) {
      states.put(
          unit,
          ok(List.of("systemctl", "show", "-p", "InvocationID", "-p", "ActiveEnterTimestamp", unit))
              .strip());
    }
    return states;
  }

  private String mtimes() throws Exception {
    var command = new ArrayList<>(List.of("stat", "-c", "%n %y"));
    command.addAll(HOST_STATE);
    return ok(command);
  }

  private String sha(String path) throws Exception {
    return ok(List.of("sha256sum", path)).split(" ")[0];
  }

  private String journal() {
    try {
      return "\n--- journal ---\n"
          + root("journalctl -o short-precise --no-pager -n 200 -u sail-api -u sail-pty-host")
              .stdout();
    } catch (Exception e) {
      return "\n(journal unavailable: " + e + ")";
    }
  }

  private void push(Path binary, String path) throws Exception {
    var pushed =
        shell.exec(
            List.of(
                "incus", "file", "push", "-p", "--mode", "0755", binary.toString(), BOX + path));
    assertTrue(pushed.ok(), "could not push " + binary + ": " + pushed.stderr());
  }

  private String query(String sql) throws Exception {
    return ok(List.of("sqlite3", "-cmd", ".timeout 10000", DB, sql)).strip();
  }

  private String nodeQuery(String sql) throws Exception {
    return ok(List.of(
            "runuser", "-u", NODE, "--", "sqlite3", "-cmd", ".timeout 10000", NODE_DB, sql))
        .strip();
  }

  private String nodeOk(String script) throws Exception {
    return ok(List.of("runuser", "-l", NODE, "-c", "export SAIL_NO_UPDATE_CHECK=1\n" + script));
  }

  private ShellExec.Result root(String script) throws Exception {
    return run(List.of("bash", "-c", script));
  }

  private String rootOk(String script) throws Exception {
    return ok(List.of("bash", "-c", script));
  }

  private String ok(List<String> argv) throws Exception {
    var result = run(argv);
    assertEquals(
        0,
        result.exitCode(),
        () -> String.join(" ", argv) + "\n" + result.stdout() + result.stderr());
    return result.stdout();
  }

  private ShellExec.Result run(List<String> argv) throws Exception {
    var command =
        new ArrayList<>(List.of("incus", "exec", BOX, "--env", "SAIL_NO_UPDATE_CHECK=1", "--"));
    command.addAll(argv);
    return shell.exec(command, null, COMMAND_DEADLINE);
  }
}
