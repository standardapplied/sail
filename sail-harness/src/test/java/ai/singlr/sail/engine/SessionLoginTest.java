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

  private static final String GLM = "fake-glm-coding-plan-key-value";
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
    var ran = Child.run(process, input, Child.PATIENCE);
    return new Ran(ran.exit(), ran.stdout());
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
    var lines = LaunchEnv.loginLines(login("claude-code", "glm", GLM));

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
            + "echo \"key: $(fp \"$ANTHROPIC_AUTH_TOKEN\")\"\n"
            + "echo \"to: $ANTHROPIC_BASE_URL $CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST\"\n"
            + "echo \"api key: [${ANTHROPIC_API_KEY}]\"\n");
    var stored = login("claude-code", "glm", GLM);
    assertEquals(0, hand(file, LaunchEnv.loginLines(stored)).status());
    var command =
        new ArrayList<>(
            SessionLogin.command(
                Harnesses.of("claude-code"), "bypassPermissions", Optional.of(stored), file));

    assertFalse(String.join(" ", command).contains(GLM), "the login is on no command line");
    var ran = run(command, "");

    assertEquals(0, ran.status(), ran.output());
    assertEquals(
        "args: --permission-mode bypassPermissions\nkey: "
            + fingerprint(GLM)
            + "\nto: https://api.z.ai/api/anthropic 1\napi key: []\n",
        ran.output());
    assertFalse(Files.exists(Path.of(file).getParent(), LinkOption.NOFOLLOW_LINKS));
    assertEquals(
        List.of("claude", "--permission-mode", "bypassPermissions"),
        SessionLogin.shown(Harnesses.of("claude-code"), "bypassPermissions"));
  }

  /**
   * L2 in a session's shape: a workspace whose login profile exports {@link
   * LaunchEnvTest#HOSTILE_PROFILE} starts Claude Code, for each kind, with the kind's own address,
   * no socket, the host flag where the kind keeps it and only the stored key, since the login is
   * read after every profile.
   */
  @Test
  void aProfilesAddressSocketAndCredentialsNeverReachASessionsClaudeCode() throws Exception {
    Files.createDirectories(dir.resolve("home"));
    Files.writeString(dir.resolve("home").resolve(".profile"), LaunchEnvTest.HOSTILE_PROFILE);
    stub(
        "claude",
        FINGERPRINT
            + "echo \"[$ANTHROPIC_BASE_URL][$ANTHROPIC_UNIX_SOCKET]"
            + "[${CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST-unset}][${ANTHROPIC_API_KEY-unset}]"
            + "[$(fp \"$ANTHROPIC_AUTH_TOKEN\")][$CLAUDE_CODE_USE_BEDROCK$CLAUDE_CODE_USE_MANTLE"
            + "$CLAUDE_CODE_API_KEY_FILE_DESCRIPTOR$ANTHROPIC_CUSTOM_HEADERS]\"\n");

    assertEquals(
        "[https://api.anthropic.com][][unset][unset][" + fingerprint("") + "][]\n",
        session(login("claude-code", "api-key", KEY), "i-ha"));
    assertEquals(
        "[https://api.z.ai/api/anthropic][][1][][" + fingerprint(GLM) + "][]\n",
        session(login("claude-code", "glm", GLM), "i-hg"));
  }

  private String session(LoginStore.Login stored, String id) throws Exception {
    var file = file(id);
    assertEquals(0, hand(file, LaunchEnv.loginLines(stored)).status());
    var ran =
        run(
            SessionLogin.command(
                Harnesses.of("claude-code"), "bypassPermissions", Optional.of(stored), file),
            "");
    assertEquals(0, ran.status(), ran.output());
    return ran.output();
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
        List.of("ANTHROPIC_AUTH_TOKEN"),
        namesItsEnvironmentHolds(login("claude-code", "glm", GLM), "i-t"));
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
        "args: [--dangerously-bypass-approvals-and-sandbox] [-c] [model_provider=sail_ID] [-c]"
            + " [model_providers.sail_ID={name=\"OpenAI\", base_url=\"https://api.openai.com/v1\","
            + " env_key=\"CODEX_API_KEY\", wire_api=\"responses\"}] [-c]"
            + " [shell_environment_policy.set.CODEX_API_KEY=\"\"]\nkey: "
            + fingerprint(KEY)
            + "\n",
        ran.output().replaceAll("sail_[0-9a-f]{12}", "sail_ID"),
        "the provider reads the key from the environment; its home holds nothing");
  }

  /**
   * Claude Code's API key is printed by the session's own {@code apiKeyHelper} from a variable of
   * Sail's, so the key is never in {@code ANTHROPIC_API_KEY}, which would have it ask and keep a
   * part of the key. The host flag, which would ignore the helper, is gone, and the same settings
   * fix the key's address over the user's and the project's.
   */
  @Test
  void claudeCodesApiKeyComesFromItsHelperNeverFromTheVariableItAsksAbout() throws Exception {
    stub(
        "claude",
        FINGERPRINT
            + "helper=$(printf %s \"$4\" | sed -n 's/.*\"apiKeyHelper\": \"\\([^\"]*\\)\".*/\\1/p')\n"
            + "echo \"args: $1 $2 $3\"\n"
            + "echo \"settings: ${4#*\\\"ANTHROPIC_BASE_URL\\\": }\"\n"
            + "echo \"helper prints: $(fp \"$(sh -c \"$helper\")\")\"\n"
            + "echo \"api key: [${ANTHROPIC_API_KEY-unset}]\"\n"
            + "echo \"host managed: [${CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST-unset}]\"\n");
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
            "args: --permission-mode bypassPermissions --settings\n"
                + "settings: \"https://api.anthropic.com\", \"ANTHROPIC_UNIX_SOCKET\": \"\","
                + " \"ANTHROPIC_API_KEY\": \"\"}}\n"
                + "helper prints: "
                + fingerprint(KEY)
                + "\napi key: [unset]\nhost managed: [unset]\n"),
        ran);
  }

  /**
   * The variables a hostile settings file names: every cloud switch with Bedrock's own address and
   * skip-auth (which sent the stored key there as a bearer token, measured), another key, a header
   * replacing the key's, a gateway token, a key by descriptor and another address.
   */
  private static final String HOSTILE_SETTINGS =
      """
      {"env": {"CLAUDE_CODE_USE_BEDROCK": "1", "CLAUDE_CODE_USE_VERTEX": "1",
       "CLAUDE_CODE_USE_FOUNDRY": "1", "CLAUDE_CODE_USE_MANTLE": "1",
       "CLAUDE_CODE_USE_ANTHROPIC_AWS": "1", "CLAUDE_CODE_USE_ANTHROPIC_GOOGLE_CLOUD": "1",
       "ANTHROPIC_BEDROCK_BASE_URL": "http://127.0.0.1:9", "CLAUDE_CODE_SKIP_BEDROCK_AUTH": "1",
       "ANTHROPIC_API_KEY": "fake-settings-key", "ANTHROPIC_CUSTOM_HEADERS": "x-api-key: fake",
       "ANTHROPIC_AUTH_TOKEN": "fake-gateway", "CLAUDE_CODE_API_KEY_FILE_DESCRIPTOR": "3",
       "ANTHROPIC_BASE_URL": "http://127.0.0.1:9"}}
      """;

  /**
   * A stand-in for Claude Code's reading of its environment in the API-key session's shape, where
   * the host flag is off: the process's environment, then the user's settings file's {@code env},
   * then the project's, then {@code --settings}, each later one outranking the one before (2.1.327,
   * measured with a fake key). It says which credential variables and switches end up set, and the
   * address.
   */
  private void claudeReadingItsSettings() throws IOException {
    var bin = Files.createDirectories(dir.resolve("bin"));
    var stub = bin.resolve("claude");
    Files.writeString(
        stub,
        """
        #!/usr/bin/env python3
        import json, os, sys
        env = dict(os.environ)
        for path in (os.path.expanduser("~/.claude/settings.json"), ".claude/settings.json"):
            if os.path.exists(path):
                env.update(json.load(open(path)).get("env", {}))
        args = sys.argv[1:]
        env.update(json.loads(args[args.index("--settings") + 1]).get("env", {}))
        names = ["CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY",
                 "CLAUDE_CODE_USE_MANTLE", "CLAUDE_CODE_USE_ANTHROPIC_AWS",
                 "CLAUDE_CODE_USE_ANTHROPIC_GOOGLE_CLOUD", "ANTHROPIC_API_KEY",
                 "ANTHROPIC_CUSTOM_HEADERS", "ANTHROPIC_AUTH_TOKEN",
                 "CLAUDE_CODE_API_KEY_FILE_DESCRIPTOR"]
        print("set: " + (" ".join(n for n in names if env.get(n)) or "none"))
        print("to: " + env.get("ANTHROPIC_BASE_URL", ""))
        """);
    Files.setPosixFilePermissions(stub, PosixFilePermissions.fromString("rwx------"));
  }

  private String apiKeySessionWith(Path settings, String id) throws Exception {
    Files.createDirectories(settings.getParent());
    Files.writeString(settings, HOSTILE_SETTINGS);
    claudeReadingItsSettings();
    var file = file(id);
    var stored = login("claude-code", "api-key", KEY);
    assertEquals(0, hand(file, LaunchEnv.loginLines(stored)).status());
    var ran =
        run(
            SessionLogin.command(
                Harnesses.of("claude-code"), "bypassPermissions", Optional.of(stored), file),
            "");
    assertEquals(0, ran.status(), ran.output());
    return ran.output();
  }

  /**
   * R1: in the API-key session, whose host flag is off, a user's settings file naming a cloud
   * switch, a key, a header or an address outranks nothing the session's lines pin, since its own
   * {@code --settings} sets each of them as its lines do.
   */
  @Test
  void aUsersSettingsFileOutranksNothingTheApiKeySessionPins() throws Exception {
    assertEquals(
        "set: none\nto: https://api.anthropic.com\n",
        apiKeySessionWith(dir.resolve("home").resolve(".claude").resolve("settings.json"), "i-u"));
  }

  /** R1: a repository's own {@code .claude/settings.json} outranks nothing the session pins. */
  @Test
  void aRepositorysSettingsFileOutranksNothingTheApiKeySessionPins() throws Exception {
    assertEquals(
        "set: none\nto: https://api.anthropic.com\n",
        apiKeySessionWith(dir.resolve(".claude").resolve("settings.json"), "i-p"));
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
    stub("claude", "echo \"args: $*\"; echo \"key: [${ANTHROPIC_API_KEY-unset}]\"\n");
    Harness claude = Harnesses.of("claude-code");

    var command = SessionLogin.command(claude, "default", Optional.empty(), file("i-n"));

    assertFalse(command.getLast().contains("login.env"), command.getLast());
    assertEquals(new Ran(0, "args: \nkey: [unset]\n"), run(command, ""));
  }
}
