/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.store.LoginStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * An agent session's login as its workspace's shell takes it, here with stub harnesses that say the
 * fingerprint of the login they were handed: the hand-over writes it as a private file of its own
 * and nothing through a name already there, the session's shell reads it and removes it before the
 * harness starts, and each harness is signed in as it takes a login.
 */
@EnabledOnOs(OS.LINUX)
class SessionLoginTest {

  private static final String TOKEN = "sk-ant-oat01-test-token-value";
  private static final String KEY = "sk-proj-test-key-value";

  @TempDir Path dir;

  private static LoginStore.Login login(String harness, String kind, String value) {
    var of = Harnesses.of(harness);
    return new LoginStore.Login(
        new LoginStore.Row(harness, "work", kind, true, Instant.EPOCH, fingerprint(value)),
        of.loginKind(kind),
        value);
  }

  private static String fingerprint(String value) {
    try {
      var digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, 8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private record Ran(int status, String output) {}

  private Ran run(List<String> command, String input) throws Exception {
    var process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
    process.environment().put("HOME", dir.resolve("home").toString());
    process.environment().put("CODEX_HOME", dir.resolve("codex").toString());
    process.environment().put("PATH", dir.resolve("bin") + ":/usr/bin:/bin");
    Files.createDirectories(dir.resolve("home"));
    var started = process.start();
    try (var stdin = started.getOutputStream()) {
      stdin.write(input.getBytes(StandardCharsets.UTF_8));
    } catch (IOException notTaken) {
      var unused = notTaken;
    }
    var output = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(started.waitFor(30, TimeUnit.SECONDS), "the script ended");
    return new Ran(started.exitValue(), output);
  }

  private Ran hand(String file, String lines) throws Exception {
    return run(List.of("sh", "-c", SessionLogin.HAND, "sail-login", file), lines);
  }

  private String file(String id) {
    return dir.resolve("sessions").resolve(id).resolve("login.env").toString();
  }

  /** A stub of {@code name} that says its arguments and the fingerprints of what it was handed. */
  private void stub(String name, String body) throws IOException {
    var bin = Files.createDirectories(dir.resolve("bin"));
    var stub = bin.resolve(name);
    Files.writeString(stub, "#!/bin/sh\n" + body);
    Files.setPosixFilePermissions(stub, PosixFilePermissions.fromString("rwx------"));
  }

  private static final String FINGERPRINT = "fp() { printf %s \"$1\" | sha256sum | cut -c1-8; }\n";

  @Test
  void theLoginIsWrittenAsAPrivateFileOfItsOwnAndNothingThroughANameAlreadyThere()
      throws Exception {
    var file = file("i-1");
    var lines = LaunchEnv.loginLines(login("claude-code", "token", TOKEN));

    assertEquals(0, hand(file, lines).status());
    assertEquals(lines, Files.readString(Path.of(file)));
    assertEquals(
        "rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(Path.of(file))));
    assertEquals(
        "rwx------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(Path.of(file).getParent())));

    assertNotEquals(0, hand(file, "OTHER=1\n").status(), "a directory there already refuses it");
    assertEquals(lines, Files.readString(Path.of(file)));

    var elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
    Files.createSymbolicLink(dir.resolve("sessions").resolve("i-2"), elsewhere);
    assertNotEquals(0, hand(file("i-2"), lines).status(), "a link there refuses it");
    try (var listed = Files.list(elsewhere)) {
      assertEquals(0, listed.count(), "nothing is written through the link");
    }
  }

  @Test
  void aHandOverRemovesWhatOneKilledBeforeItsReadLeftAMinuteAgoAndKeepsTheRest() throws Exception {
    var stale = file("i-stale");
    var fresh = file("i-fresh");
    assertEquals(0, hand(stale, "A=1\n").status());
    assertEquals(0, hand(fresh, "A=1\n").status());
    var old = FileTime.from(Instant.now().minusSeconds(180));
    Files.setLastModifiedTime(Path.of(stale), old);
    Files.setLastModifiedTime(Path.of(stale).getParent(), old);

    assertEquals(0, hand(file("i-3"), "A=1\n").status());

    assertFalse(Files.exists(Path.of(stale).getParent(), LinkOption.NOFOLLOW_LINKS));
    assertTrue(Files.exists(Path.of(fresh)));
    assertEquals(
        0, run(List.of("sh", "-c", SessionLogin.REMOVE, "sail-login", fresh), "").status());
    assertFalse(Files.exists(Path.of(fresh).getParent(), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void claudeCodeStartsWithItsPersonsLoginInItsEnvironmentAndTheFileIsGoneFirst() throws Exception {
    var file = file("i-c");
    stub(
        "claude",
        FINGERPRINT
            + "[ -e '"
            + Path.of(file).getParent()
            + "' ] && echo still-there\n"
            + "echo \"args: $*\"\n"
            + "echo \"token: $(fp \"$CLAUDE_CODE_OAUTH_TOKEN\")\"\n"
            + "echo \"api key: [${ANTHROPIC_API_KEY}]\"\n");
    var stored = login("claude-code", "token", TOKEN);
    assertEquals(0, hand(file, LaunchEnv.loginLines(stored)).status());
    var command =
        new ArrayList<>(
            SessionLogin.command(
                Harnesses.of("claude-code"), "bypassPermissions", Optional.of(stored), file));

    assertFalse(String.join(" ", command).contains(TOKEN), "the login is on no command line");
    var ran = run(command, "");

    assertEquals(0, ran.status(), ran.output());
    assertEquals(
        "args: --permission-mode bypassPermissions\ntoken: "
            + fingerprint(TOKEN)
            + "\napi key: []\n",
        ran.output());
    assertFalse(Files.exists(Path.of(file).getParent(), LinkOption.NOFOLLOW_LINKS));
    assertEquals(
        List.of("claude", "--permission-mode", "bypassPermissions"),
        SessionLogin.shown(Harnesses.of("claude-code"), "bypassPermissions"));
  }

  /**
   * Claude Code's environment, which its own processes are started from, holds the login under one
   * name for its kind and under no other: never the variable Claude Code asks about and stores a
   * fragment of. What a command it runs then sees is Claude Code's to filter.
   */
  @Test
  void claudeCodesEnvironmentHoldsItsLoginUnderOneNameForItsKindAndNoOther() throws Exception {
    stub("claude", "env > \"$HOME/claude.env\"\n");

    assertEquals(
        List.of("CLAUDE_CODE_OAUTH_TOKEN"),
        namesItsEnvironmentHolds(login("claude-code", "token", TOKEN), "i-t"));
    assertEquals(
        List.of("SAIL_ANTHROPIC_API_KEY"),
        namesItsEnvironmentHolds(login("claude-code", "api-key", KEY), "i-a"));
  }

  private List<String> namesItsEnvironmentHolds(LoginStore.Login stored, String id)
      throws Exception {
    var file = file(id);
    assertEquals(0, hand(file, LaunchEnv.loginLines(stored)).status());
    var ran =
        run(
            SessionLogin.command(
                Harnesses.of("claude-code"), "bypassPermissions", Optional.of(stored), file),
            "");
    assertEquals(0, ran.status(), ran.output());
    return Files.readAllLines(dir.resolve("home").resolve("claude.env")).stream()
        .filter(line -> line.endsWith("=" + stored.value()))
        .map(line -> line.substring(0, line.indexOf('=')))
        .toList();
  }

  /**
   * Codex takes its key from its environment through a provider of its own config, never through
   * its store: its login is never run, so nothing of the key is kept in its home.
   */
  @Test
  void codexReadsItsKeyFromItsEnvironmentAndKeepsNothingOfIt() throws Exception {
    stub(
        "codex",
        FINGERPRINT
            + "if [ \"$1\" = login ]; then echo logged-in; exit 0; fi\n"
            + "printf 'args:'; for a in \"$@\"; do printf ' [%s]' \"$a\"; done; echo\n"
            + "echo \"key: $(fp \"$CODEX_API_KEY\")\"\n"
            + "ls -A \"$HOME\"\n");
    var file = file("i-x");
    var stored = login("codex", "api-key", KEY);
    assertEquals(0, hand(file, LaunchEnv.loginLines(stored)).status());

    var ran =
        run(
            SessionLogin.command(Harnesses.of("codex"), "full-access", Optional.of(stored), file),
            "");

    assertEquals(0, ran.status(), ran.output());
    assertEquals(
        "args: [--dangerously-bypass-approvals-and-sandbox] [-c] [model_provider=sail] [-c]"
            + " [model_providers.sail={name=\"OpenAI\", base_url=\"https://api.openai.com/v1\","
            + " env_key=\"CODEX_API_KEY\", wire_api=\"responses\"}] [-c]"
            + " [shell_environment_policy.set.CODEX_API_KEY=\"\"]\nkey: "
            + fingerprint(KEY)
            + "\n",
        ran.output(),
        "the provider reads the key from the environment; its home holds nothing");
  }

  /**
   * Claude Code's API key is printed by the session's own {@code apiKeyHelper} from a variable of
   * Sail's, so the key is never in {@code ANTHROPIC_API_KEY}, which would have it ask and keep a
   * part of the key.
   */
  @Test
  void claudeCodesApiKeyComesFromItsHelperNeverFromTheVariableItAsksAbout() throws Exception {
    stub(
        "claude",
        FINGERPRINT
            + "helper=$(printf %s \"$4\" | sed -n 's/.*\"apiKeyHelper\": \"\\(.*\\)\"}/\\1/p')\n"
            + "echo \"args: $1 $2 $3\"\n"
            + "echo \"helper prints: $(fp \"$(sh -c \"$helper\")\")\"\n"
            + "echo \"api key: [${ANTHROPIC_API_KEY-unset}]\"\n");
    var file = file("i-k");
    var stored = login("claude-code", "api-key", KEY);
    assertEquals(0, hand(file, LaunchEnv.loginLines(stored)).status());

    var ran =
        run(
            SessionLogin.command(
                Harnesses.of("claude-code"), "bypassPermissions", Optional.of(stored), file),
            "");

    assertEquals(
        new Ran(
            0,
            "args: --permission-mode bypassPermissions --settings\nhelper prints: "
                + fingerprint(KEY)
                + "\napi key: [unset]\n"),
        ran);
  }

  @Test
  void aHarnessTheWorkspaceDoesNotHoldSaysSoAndEnds127WithItsLoginRemoved() throws Exception {
    Files.createDirectories(dir.resolve("bin"));
    var file = file("i-m");
    var stored = login("claude-code", "api-key", KEY);
    assertEquals(0, hand(file, LaunchEnv.loginLines(stored)).status());

    var ran =
        run(
            SessionLogin.command(Harnesses.of("claude-code"), "default", Optional.of(stored), file),
            "");

    assertEquals(
        new Ran(
            127,
            "Claude Code is not installed in this workspace: its template was built before the"
                + " project installed it.\n"),
        ran);
    assertFalse(Files.exists(Path.of(file).getParent(), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void aSessionThatSignsInInsideIsHandedNoLogin() throws Exception {
    stub("claude", "echo \"args: $*\"; echo \"token: [${CLAUDE_CODE_OAUTH_TOKEN-unset}]\"\n");
    Harness claude = Harnesses.of("claude-code");

    var command = SessionLogin.command(claude, "default", Optional.empty(), file("i-n"));

    assertFalse(command.getLast().contains("login.env"), command.getLast());
    assertEquals(new Ran(0, "args: \ntoken: [unset]\n"), run(command, ""));
  }
}
