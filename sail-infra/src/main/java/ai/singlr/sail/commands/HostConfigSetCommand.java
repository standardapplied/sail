/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.WebauthnConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.DaemonSecretInstaller;
import ai.singlr.sail.engine.NetworkDetector;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import ai.singlr.sail.ssh.SshPublicKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "set",
    description = "Update a host configuration value.",
    mixinStandardHelpOptions = true)
public final class HostConfigSetCommand implements Runnable {

  private static final Set<String> SETTABLE_KEYS =
      Set.of(
          "server-ip",
          "ssh-public-key",
          "webauthn-rp-id",
          "webauthn-rp-name",
          "webauthn-origin",
          "sync-role",
          "sync-main",
          "sync-handle",
          "slack-token");
  private static final Set<String> WEBAUTHN_KEYS =
      Set.of("webauthn-rp-id", "webauthn-rp-name", "webauthn-origin");
  private static final Pattern RP_ID = Pattern.compile("[a-z0-9]([a-z0-9.-]*[a-z0-9])?");
  private static final Pattern ORIGIN = Pattern.compile("https?://\\S+[^/]");
  private static final Pattern SSH_TARGET = Pattern.compile("([a-z_][a-z0-9_-]*@)?[a-zA-Z0-9.-]+");

  @Parameters(index = "0", description = "Configuration key (e.g. 'server-ip', 'webauthn-rp-id').")
  private String key;

  @Parameters(
      index = "1",
      arity = "0..1",
      description =
          "Value to set. Omit for ssh-public-key to auto-detect it from your authorized_keys."
              + " Never pass a value for slack-token — it is read from a hidden prompt, a stdin"
              + " pipe, or --token-file.")
  private String value;

  @Option(
      names = "--token-file",
      description = "Read the slack-token value from this file instead of prompting.")
  private Path tokenFile;

  @Option(names = "--dry-run", description = "Print what would change without writing.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  ShellExec shell;
  Path userHome;
  SystemdServiceInstaller.Mode serviceMode;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    if (!SETTABLE_KEYS.contains(key)) {
      throw new IllegalArgumentException(
          "Unknown config key: '" + key + "'. Settable keys: " + String.join(", ", SETTABLE_KEYS));
    }

    if ("slack-token".equals(key)) {
      applySlackToken();
      return;
    }
    if (tokenFile != null) {
      throw new IllegalArgumentException("The --token-file option applies only to 'slack-token'.");
    }

    if (!dryRun && !ConsoleHelper.isRoot()) {
      throw new IllegalStateException(
          "Root privileges required. Run with: sudo sail host config set " + key + " " + value);
    }

    if ("ssh-public-key".equals(key)) {
      applyWorkstationKey();
      return;
    }

    if (Strings.isBlank(value)) {
      throw new IllegalArgumentException("A value is required for '" + key + "'.");
    }
    validate(key, value);

    var hostYamlPath = SailPaths.hostConfigPath();
    if (!Files.exists(hostYamlPath)) {
      throw new IllegalStateException("Server not initialized. Run 'sail host init' first.");
    }
    var hostYaml = HostYaml.fromMap(YamlUtil.parseFile(hostYamlPath));

    var updated = applyChange(hostYaml, key, value);

    if (dryRun) {
      System.out.println("[dry-run] Would update " + key + " = " + value + " in " + hostYamlPath);
      return;
    }

    YamlUtil.dumpToFile(updated.toMap(), hostYamlPath);

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("key", key);
      map.put("value", value);
      map.put("status", "updated");
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    System.out.println(Ansi.AUTO.string("  @|bold,green ✓|@ " + key + " = " + value));
    if (WEBAUTHN_KEYS.contains(key)) {
      System.out.println(
          Ansi.AUTO.string("  @|faint Restart to apply: sudo systemctl restart sail-api|@"));
    }
  }

  /**
   * Sets the daemon's Slack bot token: owner-only token file, systemd drop-in publishing {@code
   * SAIL_SLACK_TOKEN_FILE}, service restart. The token is never accepted as an argument so it
   * cannot leak into shell history or the process list. Package-private so the full flow is
   * unit-tested with an injected shell, home, and mode instead of root and a live systemd.
   */
  void applySlackToken() throws Exception {
    if (Strings.isNotBlank(value)) {
      throw new IllegalArgumentException(
          "Never pass the token as an argument — it would land in shell history and the process"
              + " list. Run without a value for a hidden prompt, pipe the token on stdin, or use"
              + " --token-file <path>.");
    }
    var secret = DaemonSecretInstaller.SLACK_TOKEN;
    var mode = resolvedServiceMode();
    var installer = new DaemonSecretInstaller(resolvedShell(), mode, resolvedUserHome());

    if (dryRun) {
      var systemctl = mode == SystemdServiceInstaller.Mode.USER ? "systemctl --user" : "systemctl";
      System.out.println(
          "[dry-run] Would write the Slack bot token to "
              + installer.secretFilePath(secret)
              + " (mode 0600)");
      System.out.println(
          "[dry-run] Would write "
              + installer.dropInPath(secret)
              + " (Environment="
              + secret.envVar()
              + "="
              + installer.secretFilePath(secret)
              + ")");
      System.out.println(
          "[dry-run] Would run: "
              + systemctl
              + " daemon-reload (only if the drop-in changed) && "
              + systemctl
              + " restart "
              + SystemdServiceInstaller.UNIT_NAME);
      return;
    }

    var token = acquireSlackToken();
    var warning = slackTokenWarning(token);
    var applied = installer.install(secret, token);

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("key", key);
      map.put("tokenFile", applied.secretFile().toString());
      map.put("dropIn", applied.dropIn().toString());
      map.put("dropInChanged", applied.dropInChanged());
      map.put("restarted", true);
      map.put("status", "updated");
      if (warning != null) {
        map.put("warning", warning);
      }
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    if (warning != null) {
      System.out.println(Ansi.AUTO.string("  @|yellow ⚠|@ " + warning));
    }
    System.out.println(
        Ansi.AUTO.string("  @|bold,green ✓|@ slack-token set (" + applied.secretFile() + ")"));
    System.out.println(
        Ansi.AUTO.string("  @|bold,green ✓|@ sail-api restarted with " + secret.envVar() + " set"));
    System.out.println(
        Ansi.AUTO.string(
            "  @|faint Invite the bot to the configured Slack channel or posts will fail.|@"));
  }

  private String acquireSlackToken() throws IOException {
    var token = Objects.toString(readRawSlackToken(), "").strip();
    if (token.isEmpty()) {
      throw new IllegalArgumentException(
          "No token provided. Enter it at the prompt, pipe it on stdin, or pass --token-file"
              + " <path>.");
    }
    return token;
  }

  private String readRawSlackToken() throws IOException {
    if (tokenFile != null) {
      if (!Files.isRegularFile(tokenFile)) {
        throw new IllegalArgumentException("Token file not found: " + tokenFile);
      }
      return Files.readString(tokenFile);
    }
    if (ConsoleHelper.hasConsole()) {
      return ConsoleHelper.readPassword("  Slack bot token (input hidden): ");
    }
    return ConsoleHelper.readLine();
  }

  static String slackTokenWarning(String token) {
    return token.startsWith("xoxb-")
        ? null
        : "Token does not start with 'xoxb-' (a Slack bot token). Setting it anyway — if it is"
            + " wrong, the first post will fail loudly in the sail-api journal.";
  }

  private ShellExec resolvedShell() {
    return Objects.requireNonNullElseGet(shell, () -> new ShellExecutor(false));
  }

  private Path resolvedUserHome() {
    return Objects.requireNonNullElseGet(userHome, () -> Path.of(System.getProperty("user.home")));
  }

  private SystemdServiceInstaller.Mode resolvedServiceMode() {
    if (serviceMode != null) {
      return serviceMode;
    }
    return ConsoleHelper.isRoot()
        ? SystemdServiceInstaller.Mode.SYSTEM
        : SystemdServiceInstaller.Mode.USER;
  }

  private void applyWorkstationKey() throws IOException {
    var dest = SailPaths.workstationPublicKeyPath();
    var chosen = resolveWorkstationKey(value, detectWorkstationKeys(authorizedKeysPath()));
    if (dryRun) {
      System.out.println(
          "[dry-run] Would set ssh-public-key (" + chosen.fingerprint() + ") in " + dest);
      return;
    }
    writeKey(dest, chosen);
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("key", key);
      map.put("value", chosen.line());
      map.put("status", "updated");
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }
    System.out.println(
        Ansi.AUTO.string("  @|bold,green ✓|@ ssh-public-key set (" + chosen.fingerprint() + ")"));
  }

  /**
   * The box owner's workstation key: the explicit value if given, else the sole key already
   * authorized to SSH into this box (their laptop key). Fails loud when none or several are found
   * so the owner is never guessed for.
   */
  static SshPublicKey resolveWorkstationKey(String explicitValue, List<SshPublicKey> detected) {
    if (Strings.isNotBlank(explicitValue)) {
      return SshPublicKey.parse(explicitValue);
    }
    if (detected.size() == 1) {
      return detected.getFirst();
    }
    if (detected.isEmpty()) {
      throw new IllegalArgumentException(
          "No workstation public key found in your authorized_keys. Pass the one you connect"
              + " with:\n  sudo sail host config set ssh-public-key \"<your public key>\"");
    }
    var options =
        detected.stream()
            .map(
                k ->
                    "  - "
                        + k.fingerprint()
                        + (Strings.isBlank(k.comment()) ? "" : " " + k.comment()))
            .collect(Collectors.joining("\n"));
    throw new IllegalArgumentException(
        "Multiple keys are authorized on this box; pass the one you connect with:\n" + options);
  }

  /** The keys already authorized to SSH into this box (the invoking user's authorized_keys). */
  static List<SshPublicKey> detectWorkstationKeys(Path authorizedKeys) {
    if (!Files.isRegularFile(authorizedKeys)) {
      return List.of();
    }
    try {
      return Files.readAllLines(authorizedKeys).stream()
          .map(String::strip)
          .filter(line -> !line.isEmpty() && !line.startsWith("#"))
          .map(HostConfigSetCommand::parseQuietly)
          .flatMap(Optional::stream)
          .toList();
    } catch (IOException e) {
      return List.of();
    }
  }

  private static Optional<SshPublicKey> parseQuietly(String line) {
    try {
      return Optional.of(SshPublicKey.parse(line));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private static Path authorizedKeysPath() {
    return authorizedKeysPath(System.getenv("SUDO_USER"), Path.of(System.getProperty("user.home")));
  }

  /**
   * The invoking user's authorized_keys: the sudo caller's when elevated, else the process user's.
   * Root's home is {@code /root}, not {@code /home/root} — a root shell running {@code sudo} sets
   * {@code SUDO_USER=root}, and resolving that to {@code /home/root} finds nothing and made
   * auto-detection falsely report no keys.
   */
  static Path authorizedKeysPath(String sudoUser, Path userHome) {
    if (Strings.isBlank(sudoUser)) {
      return userHome.resolve(".ssh").resolve("authorized_keys");
    }
    var home = "root".equals(sudoUser) ? Path.of("/root") : Path.of("/home", sudoUser);
    return home.resolve(".ssh").resolve("authorized_keys");
  }

  /**
   * Validates and writes an explicit workstation key value, returning the parsed key.
   * Package-private so the parse/write behaviour is unit-tested without root or a real host.
   */
  static SshPublicKey writeWorkstationKey(Path dest, String value) throws IOException {
    return writeKey(dest, SshPublicKey.parse(value));
  }

  private static SshPublicKey writeKey(Path dest, SshPublicKey key) throws IOException {
    SailPaths.ensureDataDir(dest.getParent());
    Files.writeString(dest, key.line() + "\n");
    Files.setPosixFilePermissions(
        dest,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    return key;
  }

  static void validate(String key, String value) {
    switch (key) {
      case "server-ip" -> {
        if (!NetworkDetector.isValidIpv4(value)) {
          throw new IllegalArgumentException(
              "Invalid IPv4 address: '" + value + "'. Expected format: 192.168.1.100");
        }
      }
      case "webauthn-rp-id" -> {
        if (!RP_ID.matcher(value).matches()) {
          throw new IllegalArgumentException(
              "Invalid RP id: '"
                  + value
                  + "'. Expected a lowercase hostname, e.g. sail.example.dev or localhost.");
        }
      }
      case "webauthn-origin" -> {
        if (!ORIGIN.matcher(value).matches()) {
          throw new IllegalArgumentException(
              "Invalid origin: '"
                  + value
                  + "'. Expected e.g. https://sail.example.dev (no trailing slash — browsers"
                  + " send the origin without one, and the match is exact).");
        }
      }
      case "sync-role" -> {
        if (!SyncConfig.ROLE_MAIN.equals(value) && !SyncConfig.ROLE_NODE.equals(value)) {
          throw new IllegalArgumentException(
              "Invalid sync role: '" + value + "'. Expected 'main' or 'node'.");
        }
      }
      case "sync-main" -> {
        if (!SSH_TARGET.matcher(value).matches()) {
          throw new IllegalArgumentException(
              "Invalid main target: '"
                  + value
                  + "'. Expected an SSH target, e.g. sail@maindevbox.");
        }
      }
      default -> {}
    }
  }

  static HostYaml applyChange(HostYaml current, String key, String value) {
    var webauthn = current.webauthn();
    var sync = current.sync();
    return switch (key) {
      case "server-ip" -> withServerIp(current, value);
      case "webauthn-rp-id" ->
          withWebauthn(current, new WebauthnConfig(value, webauthn.rpName(), webauthn.origins()));
      case "webauthn-rp-name" ->
          withWebauthn(current, new WebauthnConfig(webauthn.rpId(), value, webauthn.origins()));
      case "webauthn-origin" ->
          withWebauthn(
              current, new WebauthnConfig(webauthn.rpId(), webauthn.rpName(), List.of(value)));
      case "sync-role" -> withSync(current, new SyncConfig(value, sync.main(), sync.handle()));
      case "sync-main" -> withSync(current, new SyncConfig(sync.role(), value, sync.handle()));
      case "sync-handle" -> withSync(current, new SyncConfig(sync.role(), sync.main(), value));
      default -> throw new IllegalArgumentException("Unhandled key: " + key);
    };
  }

  private static HostYaml withServerIp(HostYaml current, String serverIp) {
    return new HostYaml(
        current.storageBackend(),
        current.pool(),
        current.poolDisk(),
        current.bridge(),
        current.baseProfile(),
        current.image(),
        current.incusVersion(),
        serverIp,
        current.initializedAt(),
        current.webauthn(),
        current.sync());
  }

  private static HostYaml withWebauthn(HostYaml current, WebauthnConfig webauthn) {
    return new HostYaml(
        current.storageBackend(),
        current.pool(),
        current.poolDisk(),
        current.bridge(),
        current.baseProfile(),
        current.image(),
        current.incusVersion(),
        current.serverIp(),
        current.initializedAt(),
        webauthn,
        current.sync());
  }

  private static HostYaml withSync(HostYaml current, SyncConfig sync) {
    return new HostYaml(
        current.storageBackend(),
        current.pool(),
        current.poolDisk(),
        current.bridge(),
        current.baseProfile(),
        current.image(),
        current.incusVersion(),
        current.serverIp(),
        current.initializedAt(),
        current.webauthn(),
        sync);
  }
}
