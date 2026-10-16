/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

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

  /** Pins {@code host}'s ed25519 key on port 22 and returns its {@code SHA256:} fingerprint. */
  public String pin(String host) throws IOException, InterruptedException, TimeoutException {
    var scan = shell.exec(List.of("ssh-keyscan", "-H", "-t", "ed25519", "-T", "5", host));
    var line =
        scan.stdout()
            .lines()
            .map(String::strip)
            .filter(scanned -> keyOf(scanned).isPresent())
            .findFirst()
            .orElseThrow(() -> unread(host, scan.stderr()));
    var key = keyOf(line).orElseThrow();
    var pinned = pinned(host);
    if (pinned.isEmpty()) {
      append(line);
      return key.fingerprint();
    }
    if (pinned.stream().noneMatch(known -> known.blob().equals(key.blob()))) {
      throw new IllegalStateException(
          host
              + "'s SSH host key is "
              + key.fingerprint()
              + ", but this box pinned "
              + pinned.getFirst().fingerprint()
              + ". If main was rebuilt, remove the old key with 'sudo ssh-keygen -R "
              + host
              + "' and re-run; otherwise something between this box and main is answering for"
              + " it.");
    }
    return key.fingerprint();
  }

  private List<SshPublicKey> pinned(String host)
      throws IOException, InterruptedException, TimeoutException {
    if (!Files.exists(knownHosts)) {
      return List.of();
    }
    var found = shell.exec(List.of("ssh-keygen", "-F", host, "-f", knownHosts.toString()));
    return found.stdout().lines().map(MainHostKey::keyOf).flatMap(Optional::stream).toList();
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

  /** The ed25519 key a {@code known_hosts} line names, whatever its host field or marker. */
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

  private static IllegalStateException unread(String host, String stderr) {
    var reason =
        stderr
            .lines()
            .map(String::strip)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .reduce((first, second) -> second)
            .orElse("no ed25519 host key came back");
    return new IllegalStateException(
        "Couldn't read "
            + host
            + "'s SSH host key on port 22: "
            + reason
            + ". Check the address and firewall, then re-run sudo sail init --main "
            + host
            + ".");
  }
}
