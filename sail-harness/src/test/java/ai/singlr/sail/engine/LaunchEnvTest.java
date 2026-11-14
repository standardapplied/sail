/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.store.LoginStore;
import ai.singlr.sail.store.RunStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run's secrets as its process reads them: one line each in its launch.env, read by the same
 * wrapper in every launch shape, which assigns each line as it is, evaluates nothing, removes the
 * file before the command starts, and starts nothing when the file is not there.
 */
class LaunchEnvTest {

  private static final String RUN_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

  @TempDir Path dir;

  private static LoginStore.Login token(String value) {
    var claude = Harnesses.of("claude-code");
    return new LoginStore.Login(
        new LoginStore.Row("claude-code", "work", "token", true, Instant.EPOCH, "0123abcd"),
        claude.loginKind("token"),
        value);
  }

  @Test
  void theFileHoldsTheCredentialAndTheVariableTheLoginsKindNames() {
    var env = new LaunchEnv("cred-1", token("sk-ant-oat01-x"));

    assertEquals(
        "SAIL_RUN_CREDENTIAL=cred-1\nCLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat01-x\n", env.lines());
    assertEquals(new RunStore.RunLogin("work", "token"), env.login());
    assertEquals("LaunchEnv[login=RunLogin[name=work, kind=token]]", env.toString());
    assertFalse(env.toString().contains("cred-1"));
    assertEquals("SAIL_RUN_CREDENTIAL=\n", new LaunchEnv(null, null).lines());
    assertEquals(RunStore.RunLogin.CONTAINER, new LaunchEnv(null, null).login());
  }

  @Test
  void aCredentialOfTwoLinesAndAPathThatIsNotOneAreRefused() {
    assertThrows(IllegalArgumentException.class, () -> new LaunchEnv("a\nb", null));
    assertThrows(IllegalArgumentException.class, () -> new LaunchEnv("a\rb", null));
    assertThrows(IllegalArgumentException.class, () -> LaunchEnv.wrap("/x; rm -rf /", "true"));
    assertThrows(IllegalArgumentException.class, () -> LaunchEnv.read("relative"));
  }

  @Test
  void theWrapperIsTheSameFixedTextInEveryShape() {
    var path = AgentUnit.forRun(RUN_ID).launchEnvPath();
    var read =
        "f=/home/dev/.sail/runs/"
            + RUN_ID
            + "/launch.env; while IFS= read -r kv; do export \"$kv\"; done < \"$f\" || exit 1;"
            + " rm -f \"$f\"";

    assertEquals("/home/dev/.sail/runs/" + RUN_ID + "/launch.env", path);
    assertEquals(read, LaunchEnv.read(path));
    assertEquals(read + "; exec claude -p x", LaunchEnv.wrap(path, "claude -p x"));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void theCommandSeesEachLineAsItIsAndTheFileIsGoneBeforeItStarts() throws Exception {
    var file = dir.resolve("launch.env");
    var marker = dir.resolve("evaluated");
    Files.writeString(
        file,
        "SAIL_RUN_CREDENTIAL=cred=1\nCLAUDE_CODE_OAUTH_TOKEN=$(touch " + marker + ")\n",
        StandardCharsets.UTF_8);

    var shown =
        run(
            LaunchEnv.wrap(
                file.toString(),
                "sh -c 'printf \"%s|%s|\" \"$SAIL_RUN_CREDENTIAL\" \"$CLAUDE_CODE_OAUTH_TOKEN\";"
                    + " test -e "
                    + file
                    + " && echo present || echo gone'"));

    assertEquals("0:cred=1|$(touch " + marker + ")|gone\n", shown);
    assertFalse(Files.exists(marker), "a line is assigned, never evaluated");
    assertFalse(Files.exists(file));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aMissingFileStartsNothing() throws Exception {
    var started = dir.resolve("started");

    var missing = run(LaunchEnv.wrap(dir.resolve("launch.env").toString(), "touch " + started));

    assertTrue(missing.startsWith("1:"), missing);
    assertFalse(Files.exists(started), "no harness starts without its credential");
  }

  private static String run(String script) throws Exception {
    return run(List.of("bash", "-c", script));
  }

  private static String run(List<String> argv) throws Exception {
    var process = new ProcessBuilder(argv).redirectErrorStream(true).start();
    var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
    var lines = out.lines().filter(line -> !line.contains("No such file")).toList();
    return process.exitValue() + ":" + String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
  }
}
