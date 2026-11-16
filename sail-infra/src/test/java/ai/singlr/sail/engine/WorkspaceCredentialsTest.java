/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The strip and the check themselves, run by a real bash, grep and git over a container's root
 * rebuilt under a temporary directory: the paths a container has are moved under it, {@code
 * runuser} and {@code mountpoint} are stand-ins that run as this account and see no mount, and
 * {@code gh} is one that says it is logged in exactly when its {@code hosts.yml} is there. What
 * each script does with the files is the production text's own.
 */
@EnabledOnOs(OS.LINUX)
class WorkspaceCredentialsTest {

  private static final String TOKEN = "ghp_builderSecret123";

  @TempDir Path root;
  private Path bin;

  @BeforeEach
  void aContainerRoot() throws IOException {
    bin = Files.createDirectories(root.resolve("sail-bin"));
    stub("runuser", "shift 3\nexec \"$@\"\n");
    stub("mountpoint", "exit 1\n");
    stub(
        "gh",
        "if [ -e \"$HOME/.config/gh/hosts.yml\" ]; then echo 'Logged in to github.com'; exit 0; fi\n"
            + "echo 'You are not logged into any GitHub hosts.' >&2\nexit 1\n");
    Files.createDirectories(root.resolve("home/dev/.ssh"));
    Files.createDirectories(root.resolve("etc"));
    Files.createDirectories(root.resolve("usr/share"));
    Files.writeString(root.resolve("usr/share/readme"), "nothing secret\n");
  }

  private void stub(String name, String body) throws IOException {
    var file = bin.resolve(name);
    Files.writeString(file, "#!/bin/sh\n" + body);
    file.toFile().setExecutable(true);
  }

  private record Ran(int exit, String err) {}

  private void git(String... args) throws Exception {
    var command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    var process = environment(new ProcessBuilder(command)).start();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue(), String.join(" ", command));
  }

  private ProcessBuilder environment(ProcessBuilder builder) {
    var env = builder.environment();
    env.put("PATH", bin + ":" + System.getenv("PATH"));
    env.put("HOME", root.resolve("home/dev").toString());
    env.put("GIT_CONFIG_SYSTEM", root.resolve("etc/gitconfig").toString());
    return builder;
  }

  /**
   * {@code script} as it would run in the container, with {@code args} after it and {@code stdin}
   * on its input, run over {@link #root} with the stand-ins and the moved git config.
   */
  private Ran script(String script, String stdin, String... args) throws Exception {
    var rooted =
        script
            .replace("for d in /* /.[!.]*; do", "for d in " + root + "/*; do")
            .replace("/home/*", root + "/home/*")
            .replace("/home/dev", root + "/home/dev")
            .replace("/root/", root + "/root/")
            .replace("-o dev -g dev ", "");
    var command = new ArrayList<>(List.of("bash", "-c", rooted, "sail-test"));
    command.addAll(List.of(args));
    var process =
        environment(new ProcessBuilder(command))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    try (var in = process.getOutputStream()) {
      in.write(stdin.getBytes(StandardCharsets.UTF_8));
    }
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the script ends");
    return new Ran(
        process.exitValue(),
        new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
  }

  private void builderLeftEverything() throws Exception {
    Files.writeString(
        root.resolve("home/dev/.git-credentials"), "https://oauth2:" + TOKEN + "@github.com\n");
    Files.createDirectories(root.resolve("home/dev/.config/gh"));
    Files.writeString(
        root.resolve("home/dev/.config/gh/hosts.yml"),
        "github.com:\n  oauth_token: " + TOKEN + "\n");
    Files.writeString(root.resolve("home/dev/.ssh/authorized_keys"), "ssh-ed25519 AAAAbuilder b\n");
    git("config", "--global", "user.name", "Builder");
    git("config", "--global", "user.email", "builder@sail.invalid");
  }

  /** The strip takes all four out, and the check then finds nothing. */
  @Test
  void theStripTakesTheBuildersTokenGhLoginIdentityAndKeysOutAndTheCheckFindsNone()
      throws Exception {
    builderLeftEverything();

    assertEquals(0, script(WorkspaceCredentials.STRIP, "").exit());
    var checked = script(WorkspaceCredentials.HELD, TOKEN + "\n");

    assertEquals(0, checked.exit(), checked.err());
    assertFalse(Files.exists(root.resolve("home/dev/.git-credentials")));
    assertFalse(Files.exists(root.resolve("home/dev/.config/gh/hosts.yml")));
    assertEquals("", Files.readString(root.resolve("home/dev/.ssh/authorized_keys")));
    assertFalse(Files.readString(root.resolve("home/dev/.gitconfig")).contains("email"));
  }

  /** The strip given a name and email leaves them as the identity, and nothing else of anyone. */
  @Test
  void theStripGivenAnOwnersNameAndEmailSetsThemAsTheIdentity() throws Exception {
    builderLeftEverything();

    assertEquals(0, script(WorkspaceCredentials.STRIP, "", "Ana", "ana@sail.invalid").exit());

    var gitconfig = Files.readString(root.resolve("home/dev/.gitconfig"));
    assertTrue(gitconfig.contains("ana@sail.invalid"), gitconfig);
    assertFalse(gitconfig.contains("builder@sail.invalid"), gitconfig);
  }

  /** (a) The token's bytes anywhere on the root filesystem fail the check, wherever they hide. */
  @Test
  void aTokenAnywhereFailsTheCheckWhereverItHides() throws Exception {
    for (var hidden :
        List.of(
            "home/dev/.netrc",
            "home/dev/.sail/gh-token-01a1",
            "home/dev/workspace/app/.git/config",
            "etc/environment")) {
      var file = root.resolve(hidden);
      Files.createDirectories(file.getParent());
      Files.writeString(file, "url = https://oauth2:" + TOKEN + "@github.com/acme/app.git\n");

      var checked = script(WorkspaceCredentials.HELD, TOKEN + "\n");

      assertEquals(1, checked.exit(), hidden + ": " + checked.err());
      assertTrue(checked.err().contains(hidden), checked.err());
      assertFalse(checked.err().contains(TOKEN), "the check never says the token");
      Files.delete(file);
    }
  }

  /** (b) A git identity at the system or the global level fails the check. */
  @Test
  void aGitIdentityAtTheSystemOrGlobalLevelFailsTheCheck() throws Exception {
    git("config", "--system", "user.email", "root@box");
    var system = script(WorkspaceCredentials.HELD, "");
    Files.delete(root.resolve("etc/gitconfig"));
    git("config", "--global", "user.name", "Builder");
    var global = script(WorkspaceCredentials.HELD, "");

    assertEquals(1, system.exit(), system.err());
    assertTrue(system.err().contains("system user.email"), system.err());
    assertEquals(1, global.exit(), global.err());
    assertTrue(global.err().contains("global user.name"), global.err());
  }

  /** (c) A line in any home's authorized_keys fails the check. */
  @Test
  void aKeyInAnyHomeFailsTheCheck() throws Exception {
    Files.createDirectories(root.resolve("home/ubuntu/.ssh"));
    Files.writeString(root.resolve("home/ubuntu/.ssh/authorized_keys"), "ssh-ed25519 AAAAx x\n");

    var checked = script(WorkspaceCredentials.HELD, "");

    assertEquals(1, checked.exit(), checked.err());
    assertTrue(checked.err().contains("home/ubuntu/.ssh/authorized_keys"), checked.err());
  }

  /** (d) A gh login fails the check. */
  @Test
  void aGhLoginFailsTheCheck() throws Exception {
    Files.createDirectories(root.resolve("home/dev/.config/gh"));
    Files.writeString(root.resolve("home/dev/.config/gh/hosts.yml"), "github.com: {}\n");

    var checked = script(WorkspaceCredentials.HELD, "");

    assertEquals(1, checked.exit(), checked.err());
    assertTrue(checked.err().contains("a gh login"), checked.err());
  }

  /**
   * A git config git cannot read is never "no identity": the check fails as one that cannot tell.
   */
  @Test
  void aGitConfigThatCannotBeReadFailsTheCheckAsOneThatCannotTell() throws Exception {
    Files.writeString(root.resolve("home/dev/.gitconfig"), "[user\n\temail = uday@x\n");

    var checked = script(WorkspaceCredentials.HELD, "");

    assertEquals(2, checked.exit(), checked.err());
    assertTrue(checked.err().contains("git could not read"), checked.err());
  }

  /** A clean container passes, with no token given or with the build's. */
  @Test
  void aCleanContainerPasses() throws Exception {
    assertEquals(0, script(WorkspaceCredentials.HELD, "").exit());
    assertEquals(0, script(WorkspaceCredentials.HELD, TOKEN + "\n").exit());
  }
}
