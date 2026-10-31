/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.BlobStore;
import java.io.Closeable;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class PortalSkillTest {

  @TempDir Path home;
  private Path bin;
  private Path socket;

  @BeforeEach
  void setUp() throws IOException {
    socket = home.resolve("api.sock");
    bin = Files.createDirectories(home.resolve("bin"));
    writeExecutable(
        home.resolve("portal"),
        PortalSkill.script()
            .replace(SailPaths.apiSocketContainerPath().toString(), socket.toString()));
    Files.writeString(home.resolve("box.credential"), "sailbox_test\n");
    writeExecutable(
        bin.resolve("curl"), "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$HOME/curl.log\"\n");
    writeExecutable(bin.resolve("hostname"), "#!/bin/sh\necho acme\n");
  }

  private record Ran(int exit, String output) {}

  private Ran portal(String... args) throws Exception {
    var argv = new ArrayList<>(List.of("/usr/bin/env", "bash", home.resolve("portal").toString()));
    argv.addAll(List.of(args));
    var builder = new ProcessBuilder(argv).directory(home.toFile()).redirectErrorStream(true);
    var env = builder.environment();
    env.keySet().removeIf(key -> key.startsWith("SAIL_"));
    env.put("HOME", home.toString());
    env.put("PATH", bin + ":" + env.getOrDefault("PATH", "/usr/bin:/bin"));
    var channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    channel.bind(UnixDomainSocketAddress.of(socket));
    try (Closeable bound = channel) {
      var process = builder.start();
      var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the script must finish");
      return new Ran(process.exitValue(), output);
    } finally {
      Files.deleteIfExists(socket);
    }
  }

  private List<String> calls() throws IOException {
    var log = home.resolve("curl.log");
    return Files.exists(log) ? Files.readAllLines(log) : List.of();
  }

  private String call(String... rest) {
    return "--silent --show-error --fail-with-body --max-time 20 --unix-socket "
        + socket
        + " -H Authorization: Bearer sailbox_test "
        + String.join(" ", rest);
  }

  private static void writeExecutable(Path path, String content) throws IOException {
    Files.writeString(path, content);
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  @Test
  void theSkillMeetsTheStandardAndItsScriptIsExecutable() throws IOException {
    var skill = PortalSkill.skill();

    assertEquals("sail-portal", skill.name());
    assertEquals(
        List.of("SKILL.md", "scripts/portal"),
        skill.files().stream().map(StageSkill.File::path).toList());
    assertEquals(List.of(0644, 0755), skill.files().stream().map(StageSkill.File::mode).toList());
    for (var file : skill.files()) {
      var bytes = PortalSkill.content(file).readAllBytes();
      assertEquals(BlobStore.hash(bytes), file.contentHash(), file.path());
    }
    assertEquals(
        PortalSkill.skillMd(),
        new String(
            PortalSkill.content(skill.files().getFirst()).readAllBytes(), StandardCharsets.UTF_8));
    assertEquals(
        "sail-portal has no file other.",
        assertThrows(
                IllegalArgumentException.class,
                () -> PortalSkill.content(new StageSkill.File("other", "x", 1, 0644)))
            .getMessage());
  }

  @Test
  void theSkillTellsTheAgentToUseThePortalAndNeverStartAProcessByHand() {
    var md = PortalSkill.skillMd();

    assertTrue(md.contains("run `scripts/portal up`"), md);
    assertTrue(md.contains("Never start a declared process yourself"), md);
    assertTrue(md.contains("You cannot change the definition yourself"), md);
  }

  @Test
  void upPortsAndStopCallTheProjectsStackRoutes() throws Exception {
    assertEquals(0, portal("up").exit());
    assertEquals(0, portal("up", "120").exit());
    assertEquals(0, portal("ports").exit());
    assertEquals(0, portal("stop").exit());

    assertEquals(
        List.of(
            call(
                "--max-time 90 -X POST --data-urlencode timeout=60",
                "http://sail/v1/projects/acme/serve"),
            call(
                "--max-time 150 -X POST --data-urlencode timeout=120",
                "http://sail/v1/projects/acme/serve"),
            call("http://sail/v1/projects/acme/ports"),
            call("-X DELETE http://sail/v1/projects/acme/serve")),
        calls());
  }

  @Test
  void logPrintsTheEndOfAProcesssLog() throws Exception {
    var logs = Files.createDirectories(home.resolve(".sail/processes"));
    Files.writeString(logs.resolve("web.log"), "one\ntwo\nthree\n");

    assertEquals(new Ran(0, "two\nthree\n"), portal("log", "web", "2"));
    assertEquals(
        new Ran(1, "portal: process 'api' has no log yet: it has not run. Run: portal up\n"),
        portal("log", "api"));
    assertEquals(List.of(), calls(), "a log is read here, not asked of the host");
  }

  @Test
  void whatIsNotANumberOrAProcessNameIsRefusedBeforeAnyRequest() throws Exception {
    assertEquals(new Ran(1, "portal: 'soon' is not a number\n"), portal("up", "soon"));
    assertEquals(new Ran(1, "portal: '../etc' is not a process name\n"), portal("log", "../etc"));
    assertEquals(new Ran(1, "portal: log needs a process name\n"), portal("log"));
    assertEquals(new Ran(1, "portal: 'x' is not a number\n"), portal("log", "web", "x"));
    assertEquals(
        new Ran(1, "portal: unknown command: open (try 'portal --help')\n"), portal("open"));
    assertEquals(List.of(), calls());
  }

  @Test
  void helpTeachesEveryVerb() throws Exception {
    var help = portal("--help");

    assertEquals(0, help.exit());
    assertTrue(help.output().startsWith("portal — bring this project's app up"), help.output());
  }
}
