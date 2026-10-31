/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code skill} script run for real in bash, against a socket that is bound and a {@code curl}
 * that writes down what it was asked: so what is asserted is the request each verb makes.
 */
@EnabledOnOs(OS.LINUX)
class SkillCliHelperTest {

  @TempDir Path home;
  private Path bin;
  private Path socket;

  @BeforeEach
  void setUp() throws IOException {
    socket = home.resolve("api.sock");
    bin = Files.createDirectories(home.resolve("bin"));
    writeExecutable(
        home.resolve("skill"),
        SkillCliHelper.scriptContent()
            .replace(SailPaths.apiSocketContainerPath().toString(), socket.toString()));
    Files.writeString(home.resolve("box.credential"), "sailbox_test\n");
    writeExecutable(
        bin.resolve("curl"),
        """
        #!/bin/sh
        printf '%s\\n' "$*" >> "$HOME/curl.log"
        """);
    writeExecutable(bin.resolve("hostname"), "#!/bin/sh\necho acme\n");
  }

  private record Ran(int exit, String output) {}

  private Ran skill(Path workDir, String... args) throws Exception {
    return run(workDir, Map.of(), args);
  }

  private Ran skill(String... args) throws Exception {
    return skill(home, args);
  }

  private Ran run(Path workDir, Map<String, String> extra, String... args) throws Exception {
    var argv = new ArrayList<>(List.of("/usr/bin/env", "bash", home.resolve("skill").toString()));
    argv.addAll(List.of(args));
    var builder = new ProcessBuilder(argv).directory(workDir.toFile()).redirectErrorStream(true);
    var env = builder.environment();
    env.keySet().removeIf(key -> key.startsWith("SAIL_"));
    env.put("HOME", home.toString());
    env.put("PATH", bin + ":" + env.getOrDefault("PATH", "/usr/bin:/bin"));
    env.putAll(extra);
    try (Closeable bound = bind()) {
      var process = builder.start();
      var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the script must finish");
      return new Ran(process.exitValue(), output);
    }
  }

  private List<String> calls() throws IOException {
    var log = home.resolve("curl.log");
    return Files.exists(log) ? Files.readAllLines(log) : List.of();
  }

  private Closeable bind() throws IOException {
    var channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    channel.bind(UnixDomainSocketAddress.of(socket));
    return () -> {
      channel.close();
      Files.deleteIfExists(socket);
    };
  }

  private static void writeExecutable(Path path, String content) throws IOException {
    Files.writeString(path, content);
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  private String call(String... rest) {
    return "--silent --show-error --fail-with-body --max-time 20 --unix-socket "
        + socket
        + " -H Authorization: Bearer sailbox_test "
        + String.join(" ", rest);
  }

  @Test
  void lsAndShowReadTheProjectsSkillsOverTheSocket() throws Exception {
    assertEquals(0, skill("ls").exit());
    assertEquals(0, skill("show", "java").exit());

    assertEquals(
        List.of(
            call("http://sail/v1/projects/acme/skills"),
            call("http://sail/v1/projects/acme/skills/java")),
        calls());
  }

  @Test
  void addSendsTheFoldersAbsolutePathAndTheNameWhenGiven() throws Exception {
    var drafts = Files.createDirectories(home.resolve("drafts/java"));

    assertEquals(0, skill(home.resolve("drafts"), "add", "java").exit());
    assertEquals(0, skill("add", drafts.toString(), "--name", "standards").exit());

    assertEquals(
        List.of(
            call(
                "-X POST --data-urlencode folder=" + drafts, "http://sail/v1/projects/acme/skills"),
            call(
                "-X POST --data-urlencode folder=" + drafts,
                "--data-urlencode name=standards http://sail/v1/projects/acme/skills")),
        calls());
  }

  @Test
  void aFolderReachedThroughALinkIsSentAsTheFolderItIsWhateverCdpathSays() throws Exception {
    var real = Files.createDirectories(home.resolve("dotfiles/java"));
    var skills = Files.createDirectories(home.resolve(".claude/skills"));
    Files.createSymbolicLink(skills.resolve("java"), real);
    Files.createDirectories(home.resolve("elsewhere/.claude/skills/java"));

    assertEquals(0, skill(home, "add", ".claude/skills/java").exit());
    assertEquals(
        0,
        run(
                home,
                Map.of("CDPATH", home.resolve("elsewhere").toString()),
                "add",
                ".claude/skills/java")
            .exit());

    var resolved =
        call(
            "-X POST --data-urlencode folder=" + real.toRealPath(),
            "http://sail/v1/projects/acme/skills");
    assertEquals(List.of(resolved, resolved), calls());
  }

  @Test
  void rmRemovesTheNamedSkill() throws Exception {
    assertEquals(0, skill("rm", "java").exit());

    assertEquals(List.of(call("-X DELETE http://sail/v1/projects/acme/skills/java")), calls());
  }

  @Test
  void whatIsNotAFolderOrNotASkillNameIsRefusedBeforeAnyRequest() throws Exception {
    Files.writeString(home.resolve("loose.md"), "x");

    assertEquals(new Ran(1, "skill: Not a folder: loose.md\n"), skill("add", "loose.md"));
    assertEquals(new Ran(1, "skill: add needs a folder\n"), skill("add"));
    assertEquals(
        new Ran(
            1, "skill: '../specs' is not a skill name: lowercase letters, digits and hyphens\n"),
        skill("show", "../specs"));
    assertEquals(
        new Ran(1, "skill: 'Java' is not a skill name: lowercase letters, digits and hyphens\n"),
        skill("rm", "Java"));
    assertEquals(
        new Ran(1, "skill: 'a b' is not a skill name: lowercase letters, digits and hyphens\n"),
        skill("add", home.toString(), "--name", "a b"));
    assertEquals(
        new Ran(1, "skill: add accepts only --name <name>\n"),
        skill("add", home.toString(), "--force"));
    assertEquals(new Ran(1, "skill: show needs a skill name\n"), skill("show"));
    assertEquals(new Ran(1, "skill: rm needs a skill name\n"), skill("rm"));
    assertEquals(
        new Ran(1, "skill: --name needs a skill name\n"), skill("add", home.toString(), "--name"));
    assertEquals(
        new Ran(1, "skill: unknown command: share (try 'skill --help')\n"), skill("share", "x"));
    assertEquals(List.of(), calls());
  }

  @Test
  void helpTeachesEveryVerb() throws Exception {
    assertEquals(
        new Ran(
            0,
            """
            skill — share this project's skills (Agent Skills folders) with every agent and every box

              skill ls                        every skill an agent here can see, and where each comes from (JSON)
              skill show <name>               a skill's SKILL.md (JSON: skill_md)
              skill add <folder> [--name N]   share a folder holding a SKILL.md, for every agent of the project
              skill rm <name>                 stop sharing a skill, on every box
            """),
        skill("--help"));
  }

  @Test
  void theScriptIsWrittenFromTheSharedPreambleNamedForItself() {
    var skill = SkillCliHelper.scriptContent();
    var spec = SpecCliHelper.scriptContent();
    var head = "set -euo pipefail\n\n";

    assertEquals(
        SocketPreamble.of("skill"),
        skill.substring(skill.indexOf(head) + head.length(), skill.indexOf("BASE=")));
    assertEquals(
        SocketPreamble.of("spec"),
        spec.substring(spec.indexOf(head) + head.length(), spec.indexOf("BASE=")));
    assertEquals(SpecCliHelper.SCRIPT_DIR + "/skill", SkillCliHelper.SCRIPT_PATH);
  }

  @Test
  void installWritesTheScriptExecutableBesideSpec() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    new SkillCliHelper(shell).install("acme");

    var write = shell.arguments().getLast();
    assertEquals(SkillCliHelper.SCRIPT_PATH, write.getLast());
    assertEquals(SkillCliHelper.scriptContent(), write.get(write.size() - 2));
    assertThrows(NullPointerException.class, () -> new SkillCliHelper(null));
  }
}
