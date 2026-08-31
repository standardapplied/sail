/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SpecCliHelperTest {

  @Test
  void constructorRejectsNullShell() {
    assertThrows(NullPointerException.class, () -> new SpecCliHelper(null));
  }

  @Test
  void scriptIsBashAndDependencyFreeOverTheSocket() {
    var content = SpecCliHelper.scriptContent();
    assertTrue(content.startsWith("#!/usr/bin/env bash"), content.substring(0, 60));
    assertTrue(content.contains("--unix-socket"));
    assertTrue(content.contains(SailPaths.apiSocketContainerPath().toString()));
    assertFalse(content.contains("__SAIL_API_SOCKET__"), "the socket placeholder is resolved");
    assertTrue(content.contains("--data-urlencode"), "must url-encode via curl, never build JSON");
  }

  @Test
  void scriptDerivesProjectFromHostnameAndPresentsTheRunCredential() {
    var content = SpecCliHelper.scriptContent();
    assertTrue(content.contains("PROJECT=\"$(hostname)\""));
    assertTrue(content.contains("CREDENTIAL=\"${SAIL_RUN_CREDENTIAL:-}\""));
    assertTrue(content.contains("Authorization: Bearer $CREDENTIAL"));
    assertFalse(content.contains("SAIL_ACTOR"));
  }

  @Test
  void scriptHandlesEverySubcommand() {
    var content = SpecCliHelper.scriptContent();
    assertTrue(content.contains("box.credential"));
    assertTrue(
        content.contains("SAIL_RUN_CREDENTIAL:-"),
        "the run credential must stay the preferred identity");
    for (var sub :
        new String[] {
          "board)",
          "list)",
          "show)",
          "create)",
          "update|edit)",
          "content)",
          "archive)",
          "comment)",
          "comments)"
        }) {
      assertTrue(content.contains(sub), "missing subcommand: " + sub);
    }
  }

  @Test
  void commentAcceptsTheQuestionFlagInAnyOrder() {
    var content = SpecCliHelper.scriptContent();
    assertTrue(content.contains("--question) FIELDS+=(--data-urlencode \"question=true\")"));
    assertTrue(
        content.contains("comment accepts only --reply-to <message-id> and --question"),
        "unknown comment options must still die loudly");
    assertTrue(
        content.contains("[--reply-to <message-id>] [--question]"),
        "the usage text teaches the flag");
  }

  @Test
  void createDefaultsTheHomeRoomToTheSessionsRoomUnlessTold() throws Exception {
    var content = SpecCliHelper.scriptContent();
    assertTrue(content.contains("--room)             FIELDS+=(--data-urlencode \"room_id=$2\")"));
    assertTrue(content.contains("[--room R]"), "the usage text teaches the flag");

    var home = Files.createTempDirectory("spec-shim");
    try {
      var socket = home.resolve("api.sock");
      var script = home.resolve("spec");
      writeExecutable(
          script,
          content.replace(SailPaths.apiSocketContainerPath().toString(), socket.toString()));
      Files.writeString(home.resolve("box.credential"), "sailbox_test\n");
      var bin = home.resolve("bin");
      Files.createDirectories(bin);
      writeExecutable(
          bin.resolve("curl"),
          """
          #!/bin/sh
          printf '%s\\n' "$*" >> "$HOME/curl.log"
          exit 0
          """);
      try (var bound = bind(socket)) {
        runShim(
            home,
            bin,
            Map.of("SAIL_ROOM_ID", "design-talk"),
            "create",
            "--id",
            "a",
            "--title",
            "A");
        runShim(
            home,
            bin,
            Map.of("SAIL_ROOM_ID", "design-talk"),
            "create",
            "--id",
            "b",
            "--title",
            "B",
            "--room",
            "epic");
        runShim(home, bin, Map.of(), "create", "--id", "c", "--title", "C");
      }
      var calls = Files.readAllLines(home.resolve("curl.log"));
      assertEquals(3, calls.size(), calls.toString());
      assertTrue(
          calls.get(0).contains("room_id=design-talk"),
          "the session's room is the default: " + calls.get(0));
      assertTrue(calls.get(1).contains("room_id=epic"), "an explicit --room wins: " + calls.get(1));
      assertFalse(calls.get(1).contains("design-talk"), calls.get(1));
      assertFalse(
          calls.get(2).contains("room_id"), "outside any session nothing changes: " + calls.get(2));
      assertTrue(
          calls.get(0).contains("Bearer sailbox_test"), "the ambient credential authenticates");
    } finally {
      try (var walk = Files.walk(home)) {
        walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      }
    }
  }

  private static void runShim(Path home, Path bin, Map<String, String> extra, String... args)
      throws Exception {
    var argv =
        new java.util.ArrayList<>(List.of("/usr/bin/env", "bash", home.resolve("spec").toString()));
    argv.addAll(List.of(args));
    var pb = new ProcessBuilder(argv);
    var env = pb.environment();
    env.keySet().removeIf(key -> key.startsWith("SAIL_"));
    env.put("HOME", home.toString());
    env.put("PATH", bin + ":" + env.getOrDefault("PATH", "/usr/bin:/bin"));
    env.putAll(extra);
    pb.redirectErrorStream(true);
    var process = pb.start();
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the shim must finish");
    assertEquals(0, process.exitValue(), output);
  }

  private static java.io.Closeable bind(Path socket) throws IOException {
    var channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    channel.bind(UnixDomainSocketAddress.of(socket));
    return channel;
  }

  private static void writeExecutable(Path path, String content) throws IOException {
    Files.writeString(path, content);
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  @Test
  void createPostsAndUpdateAndArchivePut() {
    var content = SpecCliHelper.scriptContent();
    assertTrue(content.contains("-X POST --data-urlencode \"project=$PROJECT\""));
    assertTrue(content.contains("-X PUT"));
    assertTrue(content.contains("--data-urlencode \"status=archived\""));
    assertTrue(content.contains("body@$2"), "spec bodies come from a file, url-encoded by curl");
  }

  @Test
  void installInvokesIncusExecAsDevUser() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    new SpecCliHelper(shell).install("light-grid");

    var cmds = shell.invocations();
    assertEquals(3, cmds.size());
    assertTrue(cmds.get(0).contains("mkdir -p /home/dev/.sail/bin"));
    assertTrue(cmds.get(1).contains("chmod 0755"));
    assertTrue(cmds.get(1).contains("/home/dev/.sail/bin/spec"));
    assertTrue(cmds.get(2).contains("/home/dev/.profile"), "puts ~/.sail/bin on the login PATH");
    assertTrue(
        cmds.get(2).contains("grep -Fqsx"),
        "the installer must test the exact export line — the same invariant the machinery"
            + " verifier probes — or a profile mentioning .sail/bin never converges");
    assertTrue(
        cmds.get(2).contains(SpecCliHelper.profileLine()),
        "presence check and append must both use the generated export line");
  }

  @Test
  void installPropagatesMkdirAndWriteFailures() {
    var mkdirFail = new ScriptedShellExecutor().onFail("mkdir", "permission denied");
    var ex1 =
        assertThrows(IOException.class, () -> new SpecCliHelper(mkdirFail).install("light-grid"));
    assertTrue(ex1.getMessage().contains("permission denied"));

    var writeFail =
        new ScriptedShellExecutor()
            .onOk("mkdir -p /home/dev/.sail/bin")
            .onFail("printf '%s'", "disk full");
    var ex2 =
        assertThrows(IOException.class, () -> new SpecCliHelper(writeFail).install("light-grid"));
    assertTrue(ex2.getMessage().contains("disk full"));

    var pathFail =
        new ScriptedShellExecutor()
            .onOk("mkdir -p /home/dev/.sail/bin")
            .onOk("printf '%s'")
            .onFail("/home/dev/.profile", "read-only file system");
    var ex3 =
        assertThrows(IOException.class, () -> new SpecCliHelper(pathFail).install("light-grid"));
    assertTrue(ex3.getMessage().contains("read-only file system"));
  }

  @Test
  void installRejectsInvalidContainerName() {
    assertThrows(
        Exception.class, () -> new SpecCliHelper(new ScriptedShellExecutor()).install("../bad"));
  }

  @Test
  void scriptPathConstantsMatch() {
    assertEquals("/home/dev/.sail/bin/spec", SpecCliHelper.SCRIPT_PATH);
    assertEquals("/home/dev/.sail/bin", SpecCliHelper.SCRIPT_DIR);
  }
}
