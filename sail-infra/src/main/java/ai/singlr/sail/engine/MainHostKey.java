/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.ssh.SshPublicKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Pins main's ed25519 SSH host key into this account's {@code known_hosts} when a node joins, so
 * the sync lane's {@code ssh}, which runs in batch mode, verifies main from its first round instead
 * of failing on an unknown key. A key already pinned is kept; a different one is refused, since it
 * means main was rebuilt or something else answers for it.
 */
public final class MainHostKey {

  private static final String TYPE = "ssh-ed25519";
  private static final String REVOKED = "@revoked";

  /** How main's operator reads the fingerprint of the host key a node pins. */
  private static final String FINGERPRINT_ON_MAIN =
      "sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub";

  private final ShellExec shell;
  private final Path knownHosts;

  public MainHostKey(ShellExec shell, Path knownHosts) {
    this.shell = shell;
    this.knownHosts = knownHosts;
  }

  /** This account's {@code ~/.ssh/known_hosts}: root's under sudo, the file its sail-api reads. */
  public static MainHostKey here() {
    return new MainHostKey(
        new ShellExecutor(false), Path.of(System.getProperty("user.home"), ".ssh", "known_hosts"));
  }

  /**
   * How a node recovers from main's host key no longer being the one it pinned for {@code target}:
   * drop the old pin, then join again, which pins the new one and prints it to check against main.
   */
  public static Remedy rebuiltRecovery(String target) {
    return Remedy.of(
        "If main was rebuilt from a copy of its database, remove the old key with '",
        Remedy.program("ssh-keygen", "-R", hostOf(target)),
        "', re-run '",
        Remedy.sail("init", "--main", target),
        "' and compare the fingerprint it prints with main's ('"
            + FINGERPRINT_ON_MAIN
            + "' on main); otherwise something between this box and main is answering for it.");
  }

  /** The line a join prints for the key it pinned, {@code fingerprint}, to check against main. */
  public static String pinnedLine(String fingerprint) {
    return "Pinned main's host key " + fingerprint + ". Check it on main: " + FINGERPRINT_ON_MAIN;
  }

  /** The bare host of a sync target, whose SSH host key is pinned: no {@code user@}, no port. */
  public static String hostOf(String target) {
    var trimmed = target.strip();
    var at = trimmed.indexOf('@');
    var host = at >= 0 ? trimmed.substring(at + 1) : trimmed;
    var colon = host.indexOf(':');
    return colon >= 0 ? host.substring(0, colon) : host;
  }

  /**
   * Pins the ed25519 key of {@code target}'s host on port 22 and returns its {@code SHA256:}
   * fingerprint.
   */
  public String pin(String target) throws IOException, InterruptedException, TimeoutException {
    var host = hostOf(target);
    var scan = shell.exec(List.of("ssh-keyscan", "-H", "-t", "ed25519", "-T", "5", host));
    var line =
        scan.stdout()
            .lines()
            .map(String::strip)
            .filter(scanned -> keyOf(scanned).isPresent())
            .findFirst()
            .orElseThrow(() -> unread(host, target, scan.stderr()));
    var key = keyOf(line).orElseThrow();
    var entries = entries(host);
    if (entries.stream()
        .filter(entry -> entry.startsWith(REVOKED))
        .anyMatch(entry -> sameKey(entry, key))) {
      throw Remedied.state(
          host
              + "'s SSH host key "
              + key.fingerprint()
              + " is revoked in "
              + knownHosts
              + ", so ssh would refuse it at every sync.",
          Remedy.of(
              "Give main a new host key, or remove the revocation if it was a mistake, then re-run ",
              Remedy.sail("init", "--main", target),
              "."));
    }
    var pinned =
        entries.stream()
            .filter(entry -> !entry.startsWith("@"))
            .map(MainHostKey::keyOf)
            .flatMap(Optional::stream)
            .toList();
    if (pinned.isEmpty()) {
      append(line);
      return key.fingerprint();
    }
    if (pinned.stream().noneMatch(known -> known.blob().equals(key.blob()))) {
      throw Remedied.state(
          host
              + "'s SSH host key is "
              + key.fingerprint()
              + ", but this box pinned "
              + pinned.getFirst().fingerprint()
              + ".",
          rebuiltRecovery(target));
    }
    return key.fingerprint();
  }

  /**
   * The {@code known_hosts} lines naming {@code host}, markers kept: an {@code @revoked} key is
   * never a pin, and an {@code @cert-authority} key signs host certificates rather than being one.
   */
  private List<String> entries(String host)
      throws IOException, InterruptedException, TimeoutException {
    if (!Files.exists(knownHosts)) {
      return List.of();
    }
    var found = shell.exec(List.of("ssh-keygen", "-F", host, "-f", knownHosts.toString()));
    return found
        .stdout()
        .lines()
        .map(String::strip)
        .filter(entry -> !entry.startsWith("#"))
        .toList();
  }

  private static boolean sameKey(String entry, SshPublicKey key) {
    return keyOf(entry).map(named -> named.blob().equals(key.blob())).orElse(false);
  }

  private void append(String line) throws IOException {
    var dir = knownHosts.getParent();
    if (!Files.isDirectory(dir)) {
      Files.createDirectories(dir);
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    }
    if (!Files.exists(knownHosts)) {
      Files.createFile(knownHosts);
      Files.setPosixFilePermissions(knownHosts, PosixFilePermissions.fromString("rw-------"));
    }
    var existing = Files.readString(knownHosts);
    var separator = existing.isEmpty() || existing.endsWith("\n") ? "" : "\n";
    Files.writeString(knownHosts, separator + line + "\n", StandardOpenOption.APPEND);
  }

  /** The ed25519 key a {@code known_hosts} line names, whatever its host field. */
  private static Optional<SshPublicKey> keyOf(String line) {
    if (line.startsWith("#")) {
      return Optional.empty();
    }
    var fields = Arrays.asList(line.strip().split("\\s+"));
    var type = fields.indexOf(TYPE);
    if (type < 0 || type + 1 >= fields.size()) {
      return Optional.empty();
    }
    try {
      return Optional.of(SshPublicKey.parse(TYPE + " " + fields.get(type + 1)));
    } catch (IllegalArgumentException malformed) {
      return Optional.empty();
    }
  }

  private static IllegalStateException unread(String host, String target, String stderr) {
    var reason =
        stderr
            .lines()
            .map(String::strip)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .reduce((first, second) -> second)
            .orElse("no ed25519 host key came back");
    return Remedied.state(
        "Couldn't read " + host + "'s SSH host key on port 22: " + reason + ".",
        Remedy.of(
            "Check the address and firewall, then re-run ",
            Remedy.sail("init", "--main", target),
            "."));
  }
}
