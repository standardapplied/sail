/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.SemVer;
import ai.singlr.sail.sync.SyncWire;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A fleet of boxes running sail's native binaries. Each box is a Podman container, the pod shares
 * one loopback, and main sits behind a real sshd whose {@code authorized_keys} forces {@code sail
 * _gateway} — the production sync lane end to end, on the artifact engineers install. A box is a
 * container rather than a directory because the native binary resolves its home through the passwd
 * entry, never {@code $HOME}.
 *
 * <p>Two binaries take part: {@link #candidate()} is the build under test and {@link #released()}
 * the release the fleet upgrades from. They arrive as {@code -Dsail.it.nativeBinary} and {@code
 * -Dsail.it.releasedBinary}; without them the tests skip, except under {@code
 * -Dsail.it.requireNative=true}, where a missing binary or an unreachable Podman fails loudly.
 *
 * <p>A box's server runs only for the duration of {@link Box#serving}, and a node's only while
 * main's sshd is down ({@link #offline}): a node server syncs on its own schedule, and a round the
 * test did not ask for would race the rounds it asserts on.
 */
public final class NativeFleet implements AutoCloseable {

  private static final String IMAGE = "sail-native-fleet";
  private static final String CONTAINERFILE =
      """
      FROM docker.io/library/ubuntu:24.04
      RUN apt-get update -qq \\
          && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends \\
              libsqlite3-0 sqlite3 openssh-server openssh-client ca-certificates \\
          && mkdir -p /run/sshd
      """;
  private static final String PROVISION_MAIN =
      """
      set -e
      useradd --system --create-home --home-dir /home/sail --shell /bin/bash sail
      chmod 700 /home/sail
      install -d -m 700 -o sail -g sail /home/sail/.ssh
      install -m 600 -o sail -g sail /dev/null /home/sail/.ssh/authorized_keys
      install -d -m 2770 -o root -g sail /var/lib/sail
      echo 'storage_backend: dir' > /var/lib/sail/host.yaml
      chgrp sail /var/lib/sail/host.yaml
      chmod 640 /var/lib/sail/host.yaml
      ssh-keygen -A >/dev/null
      """;
  private static final String SHARE_DATABASE_WITH_GATEWAY =
      "cd /var/lib/sail && chgrp sail sail.db* && chmod 660 sail.db*";
  private static final String PROVISION_NODE =
      """
      set -e
      install -d -m 700 /root/.sail /root/.ssh
      echo 'storage_backend: dir' > /root/.sail/host.yaml
      """;
  private static final Map<String, String> REPLICATED =
      replicated(
          "specs", "SELECT id, project, title, status, assignee FROM specs ORDER BY id",
          "spec_content", "SELECT spec_id, body, plan FROM spec_content ORDER BY spec_id",
          "projects", "SELECT name FROM projects ORDER BY name",
          "rooms", "SELECT id FROM rooms ORDER BY id",
          "fdes", "SELECT handle, role FROM fdes ORDER BY handle");
  private static final Duration COMMAND_DEADLINE = Duration.ofMinutes(3);
  private static final Duration BUILD_DEADLINE = Duration.ofMinutes(10);
  private static final int FIRST_PORT = 7171;

  private static boolean imageBuilt;

  private final String pod;
  private final Path candidate;
  private final Path released;
  private final List<Box> boxes = new ArrayList<>();
  private Box main;
  private Daemon sshd;

  private NativeFleet(String pod, Path candidate, Path released) {
    this.pod = pod;
    this.candidate = candidate;
    this.released = released;
  }

  public record Result(int exit, String output) {}

  @FunctionalInterface
  public interface Work {
    void run() throws Exception;
  }

  public static NativeFleet openOrSkip() throws Exception {
    var binaries = NativeBinaries.requireOrSkip("the native fleet");
    NativeBinaries.requireOrSkip("the native fleet", podmanUnavailable());
    buildImage();
    var pod = "sail-fleet-" + UUID.randomUUID().toString().substring(0, 8);
    ok(run(COMMAND_DEADLINE, List.of("podman", "pod", "create", "--share", "net", "--name", pod)));
    return new NativeFleet(pod, binaries.candidate(), binaries.released());
  }

  public Path candidate() {
    return candidate;
  }

  public Path released() {
    return released;
  }

  public Box main(String handle, Path binary) throws Exception {
    if (main != null) {
      throw new IllegalStateException("Fleet already has a main box");
    }
    var box = launch(handle, true, "/var/lib/sail/sail.db");
    box.shOk(PROVISION_MAIN);
    box.install(binary);
    box.shOk(SHARE_DATABASE_WITH_GATEWAY);
    box.sailOk("host", "sync", "--as-main");
    box.sailOk("host", "config", "set", "sync-handle", handle);
    box.sailOk("server", "init");
    box.sailOk("fde", "add", handle, "--role", "admin", "--email", handle + "@example.dev");
    main = box;
    sshd = box.sshd();
    return box;
  }

  public Box node(String handle, Path binary) throws Exception {
    if (main == null) {
      throw new IllegalStateException("A node joins a main: create main first");
    }
    var box = launch(handle, false, "/root/.sail/sail.db");
    box.shOk(PROVISION_NODE);
    box.install(binary);
    box.sailOk("server", "init");
    box.shOk("ssh-keyscan -H localhost > /root/.ssh/known_hosts 2>/dev/null");
    var joined =
        json(
            box.sailOk(
                "join",
                "localhost",
                "--handle",
                handle,
                "--name",
                handle,
                "--email",
                handle + "@example.dev",
                "--json"));
    main.sailOk(
        "fde",
        "add",
        handle,
        "--role",
        "member",
        "--email",
        handle + "@example.dev",
        "--key",
        (String) joined.get("public_key"));
    return box;
  }

  /** Runs {@code work} against {@code node}'s server while main is unreachable. */
  public void offline(Box node, Work work) throws Exception {
    sshd.close();
    try {
      node.serving(work);
    } finally {
      sshd = main.sshd();
    }
  }

  public void assertConverged(Box... nodes) throws Exception {
    var expected = main.replicated();
    for (var node : nodes) {
      assertEquals(expected, node.replicated(), () -> node.name + " has not converged with main");
    }
  }

  public static boolean belowFloor(String version) {
    return SemVer.parse(version).compareTo(SemVer.parse(SyncWire.UPGRADE_FLOOR)) < 0;
  }

  public static Map<String, Object> json(String output) {
    return YamlUtil.parseMap(lastLineStarting("{", output));
  }

  public static List<Map<String, Object>> jsonList(String output) {
    return YamlUtil.parseList(lastLineStarting("[", output));
  }

  private static String lastLineStarting(String opening, String output) {
    return output
        .lines()
        .filter(line -> line.startsWith(opening))
        .reduce((first, second) -> second)
        .orElseGet(() -> fail("no JSON line opening with " + opening + " in:\n" + output));
  }

  @Override
  public void close() throws Exception {
    run(COMMAND_DEADLINE, List.of("podman", "pod", "rm", "-f", "-t", "0", pod));
  }

  private Box launch(String name, boolean isMain, String db) throws Exception {
    var container = pod + "-" + name;
    ok(
        run(
            COMMAND_DEADLINE,
            List.of(
                "podman",
                "run",
                "-d",
                "--pod",
                pod,
                "--name",
                container,
                "--hostname",
                name + "-box",
                "-e",
                "SAIL_NO_UPDATE_CHECK=1",
                IMAGE,
                "sleep",
                "infinity")));
    var box = new Box(name, container, FIRST_PORT + boxes.size(), isMain, db);
    boxes.add(box);
    return box;
  }

  private static String podmanUnavailable() throws Exception {
    try {
      var version = run(COMMAND_DEADLINE, List.of("podman", "version"));
      return version.exit() == 0 ? null : "podman version failed: " + version.output();
    } catch (IOException e) {
      return "podman is not installed: " + e.getMessage();
    }
  }

  private static synchronized void buildImage() throws Exception {
    if (imageBuilt) {
      return;
    }
    var context = Files.createTempDirectory("sail-native-fleet-image");
    Files.writeString(context.resolve("Containerfile"), CONTAINERFILE);
    ok(run(BUILD_DEADLINE, List.of("podman", "build", "-q", "-t", IMAGE, context.toString())));
    imageBuilt = true;
  }

  private static Map<String, String> replicated(String... tableThenQuery) {
    var queries = new LinkedHashMap<String, String>();
    for (var i = 0; i < tableThenQuery.length; i += 2) {
      queries.put(tableThenQuery[i], tableThenQuery[i + 1]);
    }
    return queries;
  }

  private static String ok(Result result) {
    assertEquals(0, result.exit(), result::output);
    return result.output();
  }

  private static Result run(Duration deadline, List<String> command) throws Exception {
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    process.getOutputStream().close();
    var output =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
              } catch (IOException e) {
                return "output unreadable: " + e.getMessage();
              }
            });
    if (!process.waitFor(deadline.toMillis(), TimeUnit.MILLISECONDS)) {
      process.destroyForcibly();
      fail("timed out after " + deadline + ": " + String.join(" ", command));
    }
    return new Result(process.exitValue(), output.get());
  }

  public final class Box {

    private final String name;
    private final String container;
    private final int port;
    private final boolean isMain;
    private final String db;

    private Box(String name, String container, int port, boolean isMain, String db) {
      this.name = name;
      this.container = container;
      this.port = port;
      this.isMain = isMain;
      this.db = db;
    }

    /**
     * Puts {@code binary} on the box's path and migrates, exactly what {@code sail upgrade} does.
     */
    public void install(Path binary) throws Exception {
      ok(
          run(
              COMMAND_DEADLINE,
              List.of("podman", "cp", binary.toString(), container + ":/usr/local/bin/sail")));
      shOk("chmod 755 /usr/local/bin/sail");
      sailOk("migrate");
    }

    public String version() throws Exception {
      return sailOk("-V").strip().replaceFirst("^sail ", "");
    }

    public Result sail(String... args) throws Exception {
      var command = new ArrayList<>(exec());
      command.add("sail");
      command.addAll(List.of(args));
      return run(COMMAND_DEADLINE, command);
    }

    public String sailOk(String... args) throws Exception {
      var result = sail(args);
      assertEquals(
          0,
          result.exit(),
          () -> name + ": sail " + String.join(" ", args) + "\n" + result.output());
      return result.output();
    }

    public Result sh(String script) throws Exception {
      var command = new ArrayList<>(exec());
      command.addAll(List.of("sh", "-c", script));
      return run(COMMAND_DEADLINE, command);
    }

    public String shOk(String script) throws Exception {
      var result = sh(script);
      assertEquals(0, result.exit(), () -> name + ": " + script + "\n" + result.output());
      return result.output();
    }

    /** Runs {@code work} while this box's control-plane server is up, then stops the server. */
    public void serving(Work work) throws Exception {
      try (var server =
          new Daemon(this, "sail", List.of("sail", "server", "start", "--port", "" + port))) {
        work.run();
      }
    }

    private Daemon sshd() throws Exception {
      return new Daemon(this, "sshd", List.of("/usr/sbin/sshd", "-D", "-e"));
    }

    /**
     * Creates {@code count} specs named {@code prefix-N}, each body a unicode heading over {@code
     * bodyBytes} of filler. Only callable inside {@link #serving}.
     */
    public void createSpecs(String prefix, int count, int bodyBytes) throws Exception {
      shOk(
          """
          seq 1 %d | xargs -P 8 -I{} sh -c '
            { echo "# %s {} ✓ 日本語"; yes "filler for %s {}" | head -c %d; } > /tmp/%s-{}.md
            sail spec create --server %s --id %s-{} --title "%s {} ✓" -p demo \\
              --body-file /tmp/%s-{}.md >/dev/null'
          """
              .formatted(
                  count, prefix, prefix, bodyBytes, prefix, server(), prefix, prefix, prefix));
    }

    /** Runs {@code sail} against this box's control-plane server; only inside {@link #serving}. */
    public String apiOk(String... args) throws Exception {
      var command = new ArrayList<>(List.of(args));
      command.addAll(List.of("--server", server()));
      return sailOk(command.toArray(String[]::new));
    }

    /** Runs {@code sql} on this box's database, fed on stdin so it may quote freely. */
    public void execute(String sql) throws Exception {
      shOk("sqlite3 " + db + " <<'SQL'\n" + sql + "\nSQL");
    }

    public void retitle(String spec, String title) throws Exception {
      sailOk("spec", "update", "--server", server(), spec, "--title", title);
    }

    public String title(String spec) throws Exception {
      return query("SELECT title FROM specs WHERE id = '" + spec + "'").strip();
    }

    public String query(String sql) throws Exception {
      return shOk("sqlite3 " + db + " \"" + sql + "\"");
    }

    Map<String, String> replicated() throws Exception {
      var digests = new LinkedHashMap<String, String>();
      for (var table : REPLICATED.entrySet()) {
        digests.put(
            table.getKey(),
            shOk("sqlite3 " + db + " \"" + table.getValue() + "\" | md5sum").strip());
      }
      digests.put("specs.count", query("SELECT count(*) FROM specs").strip());
      return digests;
    }

    private String server() {
      return "http://127.0.0.1:" + port;
    }

    private List<String> exec() {
      var command = new ArrayList<>(List.of("podman", "exec"));
      if (isMain) {
        command.addAll(List.of("-e", "SAIL_DATA_DIR=/var/lib/sail"));
      }
      command.addAll(List.of(container, "sh", "-c", "umask 0007; exec \"$@\"", "sh"));
      return command;
    }
  }

  /**
   * A foreground process in a box, owned by the test: up once it says it is listening, down once
   * the process has exited. Nothing daemonizes, because a box's init reaps no one and a process
   * that outlived its parent would never be seen to stop.
   */
  private static final class Daemon implements AutoCloseable {

    private final Box box;
    private final String processName;
    private final Process process;
    private final StringBuilder log = new StringBuilder();
    private final Thread drain;

    private Daemon(Box box, String processName, List<String> command) throws Exception {
      this.box = box;
      this.processName = processName;
      var exec = new ArrayList<>(box.exec());
      exec.addAll(command);
      process = new ProcessBuilder(exec).redirectErrorStream(true).start();
      process.getOutputStream().close();
      var reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
      awaitListening(reader);
      drain = Thread.ofVirtual().start(() -> reader.lines().forEach(log::append));
    }

    @Override
    public void close() throws Exception {
      box.shOk("pkill -x " + processName);
      if (!process.waitFor(COMMAND_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        fail(box.name + ": " + processName + " did not stop:\n" + log);
      }
      drain.join();
    }

    private void awaitListening(BufferedReader reader) throws IOException {
      for (var line = reader.readLine(); line != null; line = reader.readLine()) {
        log.append(line).append('\n');
        if (line.contains("listening")) {
          return;
        }
      }
      fail(box.name + ": " + processName + " exited before listening:\n" + log);
    }
  }
}
