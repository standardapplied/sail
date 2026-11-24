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

  private static LoginStore.Login claude(String kind, String value) {
    return new LoginStore.Login(
        new LoginStore.Row("claude-code", "work", kind, true, Instant.EPOCH, "0123abcd"),
        Harnesses.of("claude-code").loginKind(kind),
        value);
  }

  /**
   * The credentials Claude Code also takes, each set empty for a stored key: one handed over by a
   * file descriptor, and custom headers, which can replace the key's own.
   */
  private static final String OTHER_CREDENTIALS =
      "CLAUDE_CODE_API_KEY_FILE_DESCRIPTOR=\nCLAUDE_CODE_OAUTH_TOKEN_FILE_DESCRIPTOR=\n"
          + "CLAUDE_CODE_GATEWAY_TOKEN_FILE_DESCRIPTOR=\n"
          + "CLAUDE_CODE_WEBSOCKET_AUTH_FILE_DESCRIPTOR=\n"
          + "ANTHROPIC_CUSTOM_HEADERS=\n";

  /** Every switch that sends Claude Code's requests to a cloud provider's account, set empty. */
  private static final String SWITCHES =
      "CLAUDE_CODE_USE_BEDROCK=\nCLAUDE_CODE_USE_VERTEX=\nCLAUDE_CODE_USE_FOUNDRY=\n"
          + "CLAUDE_CODE_USE_MANTLE=\nCLAUDE_CODE_USE_ANTHROPIC_AWS=\n"
          + "CLAUDE_CODE_USE_ANTHROPIC_GOOGLE_CLOUD=\n";

  /**
   * L2, L3: Claude Code's key's lines in full: every other credential and cloud switch set empty,
   * then the address its key goes to and the switch that makes Claude Code take that address from
   * its environment alone, then the key under its own variable.
   */
  @Test
  void theFileHoldsTheCredentialTheKindsFixedAddressAndTheVariableTheKindNames() {
    var key = new LaunchEnv("cred-1", claude("api-key", "fake-anthropic-key"));

    assertEquals(
        "SAIL_RUN_CREDENTIAL=cred-1\nANTHROPIC_AUTH_TOKEN=\nCLAUDE_CODE_OAUTH_TOKEN=\n"
            + OTHER_CREDENTIALS
            + SWITCHES
            + "ANTHROPIC_BASE_URL=https://api.anthropic.com\nANTHROPIC_UNIX_SOCKET=\n"
            + "CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST=1\n"
            + "ANTHROPIC_API_KEY=fake-anthropic-key\n",
        key.lines(),
        "every other variable Claude Code takes a credential from is set empty");
    assertEquals(new RunStore.RunLogin("work", "api-key"), key.login());
    assertEquals(Harnesses.of("claude-code").loginKind("api-key"), key.kind());
    assertEquals("LaunchEnv[login=RunLogin[name=work, kind=api-key]]", key.toString());
    assertFalse(key.toString().contains("cred-1"));
    assertEquals("SAIL_RUN_CREDENTIAL=\n", new LaunchEnv(null, null).lines());
    assertEquals(RunStore.RunLogin.CONTAINER, new LaunchEnv(null, null).login());
    assertEquals(null, new LaunchEnv(null, null).kind());
  }

  @Test
  void aCodexRunOnItsKeySetsTheAccessTokenVariableEmpty() {
    var codex = Harnesses.of("codex");
    var key =
        new LoginStore.Login(
            new LoginStore.Row("codex", "work", "api-key", true, Instant.EPOCH, "0123abcd"),
            codex.loginKind("api-key"),
            "sk-proj-x");

    assertEquals(
        "SAIL_RUN_CREDENTIAL=cred-1\nCODEX_ACCESS_TOKEN=\nCODEX_API_KEY=sk-proj-x\n",
        new LaunchEnv("cred-1", key).lines(),
        "a container's own access token never outranks the stored key");
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
            + "/launch.env; set +x; while IFS= read -r kv; do export \"$kv\"; done < \"$f\" ||"
            + " exit 1; rm -f \"$f\"";

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
        "SAIL_RUN_CREDENTIAL=cred=1\nANTHROPIC_AUTH_TOKEN=$(touch " + marker + ")\n",
        StandardCharsets.UTF_8);

    var shown =
        run(
            LaunchEnv.wrap(
                file.toString(),
                "sh -c 'printf \"%s|%s|\" \"$SAIL_RUN_CREDENTIAL\" \"$ANTHROPIC_AUTH_TOKEN\";"
                    + " test -e "
                    + file
                    + " && echo present || echo gone'"));

    assertEquals("0:cred=1|$(touch " + marker + ")|gone\n", shown);
    assertFalse(Files.exists(marker), "a line is assigned, never evaluated");
    assertFalse(Files.exists(file));
  }

  /**
   * What a container's profile may export to send a stored key elsewhere: another address, a unix
   * socket every request would go through instead, the host flag cleared, another key and a gateway
   * token, and a cloud switch.
   */
  static final String HOSTILE_PROFILE =
      "export ANTHROPIC_BASE_URL=http://127.0.0.1:9 ANTHROPIC_UNIX_SOCKET=/tmp/elsewhere.sock"
          + " CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST= ANTHROPIC_API_KEY=fake-container-key"
          + " ANTHROPIC_AUTH_TOKEN=fake-gateway CLAUDE_CODE_USE_BEDROCK=1"
          + " CLAUDE_CODE_USE_MANTLE=1 CLAUDE_CODE_API_KEY_FILE_DESCRIPTOR=3"
          + " 'ANTHROPIC_CUSTOM_HEADERS=x-api-key: fake-container-key'; ";

  /**
   * L2 as the harness's shell sees it, in the shape every run, room and foreground launch has
   * ({@link LaunchEnv#wrap}): each of {@link #HOSTILE_PROFILE} is replaced by the file, so the
   * harness sees the stored key, its kind's own address, no socket and the switch that keeps that
   * address, whatever the container said.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void anAddressOrCredentialTheContainerExportsIsReplacedSoTheKeyGoesToItsOwnService()
      throws Exception {
    var shown =
        "sh -c 'printf \"[%s][%s][%s][%s][%s][%s%s%s%s]\" \"$ANTHROPIC_BASE_URL\""
            + " \"$ANTHROPIC_UNIX_SOCKET\" \"$CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST\""
            + " \"$ANTHROPIC_API_KEY\" \"$ANTHROPIC_AUTH_TOKEN\" \"$CLAUDE_CODE_USE_BEDROCK\""
            + " \"$CLAUDE_CODE_USE_MANTLE\" \"$CLAUDE_CODE_API_KEY_FILE_DESCRIPTOR\""
            + " \"$ANTHROPIC_CUSTOM_HEADERS\"'";

    assertEquals(
        "0:[https://api.anthropic.com][][1][fake-stored-key][][]\n",
        runOn(claude("api-key", "fake-stored-key"), HOSTILE_PROFILE, shown));
  }

  private String runOn(LoginStore.Login stored, String container, String command) throws Exception {
    var file = dir.resolve("launch.env");
    Files.writeString(file, new LaunchEnv("c", stored).lines());
    return run(container + LaunchEnv.wrap(file.toString(), command));
  }

  /** A profile's {@code set -x} never traces a value the file holds. */
  @Test
  @EnabledOnOs(OS.LINUX)
  void tracingAProfileTurnedOnPrintsNoValue() throws Exception {
    var file = dir.resolve("launch.env");
    Files.writeString(
        file, new LaunchEnv("cred-secret-9", claude("api-key", "fake-traced-key")).lines());

    var shown = run("set -x; " + LaunchEnv.wrap(file.toString(), "true"));

    assertFalse(shown.contains("cred-secret-9"), shown);
    assertFalse(shown.contains("fake-traced-key"), shown);
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
    var ran = Child.run(new ProcessBuilder(argv).redirectErrorStream(true));
    var lines = ran.stdout().lines().filter(line -> !line.contains("No such file")).toList();
    return ran.exit() + ":" + String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
  }
}
