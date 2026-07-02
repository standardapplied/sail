/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Installs a secret the {@code sail-api} daemon reads from a file named by an environment variable:
 * writes the value to an owner-only ({@code 0600}) file under {@code ~/.sail}, points the unit at
 * it through a systemd drop-in, and restarts the service so the daemon picks it up. The name → env
 * var → file path mapping in {@link DaemonSecret} is the whole seam — the next daemon secret is a
 * new constant, not a new mechanism.
 *
 * <p>Idempotent: the secret file is replaced in place, the drop-in is reconciled the same way
 * {@link SystemdServiceInstaller#reconcile()} treats the unit file (rewrite plus {@code
 * daemon-reload} only on drift), and re-running leaves exactly one drop-in.
 */
public final class DaemonSecretInstaller {

  /** A secret the daemon reads from a file whose path is published via {@code envVar}. */
  public record DaemonSecret(String name, String envVar) {
    public DaemonSecret {
      if (Strings.isBlank(name) || Strings.isBlank(envVar)) {
        throw new IllegalArgumentException("name and envVar are required");
      }
    }
  }

  /** The Slack bot token the reactor resolves through {@code SAIL_SLACK_TOKEN_FILE}. */
  public static final DaemonSecret SLACK_TOKEN =
      new DaemonSecret("slack-token", "SAIL_SLACK_TOKEN_FILE");

  /** What {@link #install} changed on disk. */
  public record Applied(Path secretFile, Path dropIn, boolean dropInChanged) {}

  private static final String OWNER_ONLY = "rw-------";

  private final ShellExec shell;
  private final SystemdServiceInstaller.Mode mode;
  private final Path secretDir;
  private final Path dropInDir;

  /**
   * @param shell the shell executor (real or scripted for tests)
   * @param mode scope of the {@code sail-api} unit the drop-in targets — {@link
   *     SystemdServiceInstaller.Mode#SYSTEM} puts it under {@code /etc/systemd/system}, {@link
   *     SystemdServiceInstaller.Mode#USER} under {@code ~/.config/systemd/user}
   * @param userHome the daemon user's home directory; secrets live in its {@code .sail}
   */
  public DaemonSecretInstaller(ShellExec shell, SystemdServiceInstaller.Mode mode, Path userHome) {
    this(
        shell,
        mode,
        Objects.requireNonNull(userHome, "userHome").resolve(".sail"),
        mode == SystemdServiceInstaller.Mode.SYSTEM
            ? Path.of("/etc/systemd/system", SystemdServiceInstaller.UNIT_NAME + ".d")
            : userHome
                .resolve(".config/systemd/user")
                .resolve(SystemdServiceInstaller.UNIT_NAME + ".d"));
  }

  DaemonSecretInstaller(
      ShellExec shell, SystemdServiceInstaller.Mode mode, Path secretDir, Path dropInDir) {
    this.shell = Objects.requireNonNull(shell, "shell");
    this.mode = Objects.requireNonNull(mode, "mode");
    this.secretDir = Objects.requireNonNull(secretDir, "secretDir");
    this.dropInDir = Objects.requireNonNull(dropInDir, "dropInDir");
  }

  /** Where the secret value is stored: {@code <secretDir>/<name>}, mode {@code 0600}. */
  public Path secretFilePath(DaemonSecret secret) {
    return secretDir.resolve(secret.name());
  }

  /** The drop-in that points the unit at {@link #secretFilePath}. */
  public Path dropInPath(DaemonSecret secret) {
    return dropInDir.resolve("20-" + secret.name() + ".conf");
  }

  /** Contents of the drop-in. Pure function — no I/O. */
  public String renderDropIn(DaemonSecret secret) {
    return "[Service]\nEnvironment=" + secret.envVar() + "=" + secretFilePath(secret) + "\n";
  }

  /**
   * Writes the secret file ({@code 0600}), reconciles the drop-in ({@code daemon-reload} only on
   * drift), and restarts {@code sail-api} so the daemon reads the new value.
   */
  public Applied install(DaemonSecret secret, String value)
      throws IOException, InterruptedException, TimeoutException {
    if (Strings.isBlank(value)) {
      throw new IllegalArgumentException("A non-blank value is required for " + secret.name());
    }
    var secretFile = writeSecretFile(secret, value);
    var dropInChanged = reconcileDropIn(secret);
    restart();
    return new Applied(secretFile, dropInPath(secret), dropInChanged);
  }

  private Path writeSecretFile(DaemonSecret secret, String value) throws IOException {
    var file = secretFilePath(secret);
    SailPaths.ensureDataDir(secretDir);
    if (Files.notExists(file)) {
      Files.createFile(
          file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY)));
    }
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(OWNER_ONLY));
    Files.writeString(file, value + "\n");
    return file;
  }

  private boolean reconcileDropIn(DaemonSecret secret)
      throws IOException, InterruptedException, TimeoutException {
    var dropIn = dropInPath(secret);
    var expected = renderDropIn(secret);
    String onDisk;
    try {
      onDisk = Files.readString(dropIn);
    } catch (NoSuchFileException nsf) {
      onDisk = null;
    }
    if (expected.equals(onDisk)) {
      return false;
    }
    Files.createDirectories(dropInDir);
    Files.writeString(dropIn, expected);
    var reload = shell.exec(SystemdServiceInstaller.systemctl(mode, "daemon-reload"));
    if (!reload.ok()) {
      throw new IOException("Failed to reload systemd units: " + reload.stderr());
    }
    return true;
  }

  private void restart() throws IOException, InterruptedException, TimeoutException {
    var unit = SystemdServiceInstaller.UNIT_NAME;
    var restart = shell.exec(SystemdServiceInstaller.systemctl(mode, "restart", unit));
    if (!restart.ok()) {
      throw new IOException(
          "Failed to restart "
              + unit
              + ": "
              + restart.stderr().strip()
              + ". The secret file and drop-in are in place. If the daemon is not installed yet,"
              + " run 'sail host service install'; if it runs as a system service, rerun this"
              + " command with sudo.");
    }
  }
}
