/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * A sail executable on this box: the version it reports, whether a download may be trusted, and how
 * it replaces the installed one. A replacement is staged beside its target, so the file that
 * answers for its version is the very file that is then renamed over the target in one step — no
 * process ever sees half a binary, and a running one keeps the inode it started from. Both {@code
 * sail upgrade} and the auto-upgrader install through here.
 */
public final class SailBinary {

  private static final String STAGED = "sail.tmp";

  private SailBinary() {}

  /** A file that did not answer {@code -V} as sail does; {@link #getMessage} says what happened. */
  public static final class NotSail extends IllegalArgumentException {
    NotSail(String what, Throwable cause) {
      super(what, cause);
    }
  }

  /**
   * The version {@code binary -V} reports. Refused ({@link NotSail}, naming what it answered or why
   * it could not run) when the file is no sail.
   */
  public static SemVer versionOf(Path binary) {
    ShellExec.Result result;
    try {
      result = new ShellExecutor(false).exec(List.of(binary.toString(), "-V"));
    } catch (IOException | TimeoutException e) {
      throw new NotSail("it could not run (" + e.getMessage() + ")", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted reading the version of " + binary, e);
    }
    return parseVersion(result);
  }

  /** The version in a {@code sail -V} answer, {@code sail <version>}. */
  static SemVer parseVersion(ShellExec.Result result) {
    var answer = result.stdout().strip();
    var version =
        answer.startsWith("sail ")
            ? SemVer.tryParse(answer.substring("sail ".length()))
            : Optional.<SemVer>empty();
    if (!result.ok() || version.isEmpty()) {
      throw new NotSail(
          "it answered '"
              + (answer.isEmpty() ? result.stderr().strip() : answer)
              + "', not 'sail <version>'",
          null);
    }
    return version.get();
  }

  /**
   * Whether a downloaded artifact may be trusted: its SHA-256 matches the published checksum and it
   * carries this platform's executable header.
   */
  public static boolean isAcceptable(byte[] binary, String expectedChecksum) {
    return isAcceptable(binary, expectedChecksum, System.getProperty("os.name", ""));
  }

  static boolean isAcceptable(byte[] binary, String expectedChecksum, String osName) {
    return sha256(binary).equalsIgnoreCase(expectedChecksum)
        && PlatformDetector.isValidBinary(binary, osName);
  }

  /** The lowercase hex SHA-256 of {@code binary}. */
  public static String sha256(byte[] binary) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
    } catch (NoSuchAlgorithmException unreachable) {
      throw new IllegalStateException("This JVM has no SHA-256", unreachable);
    }
  }

  /**
   * Writes {@code binary} beside {@code target}, runnable with the target's permissions — {@code
   * 0755} when there is no target yet — ready to answer {@code -V} and then to replace it. The
   * staged file is deleted on close unless it was installed.
   */
  public static Staged stage(byte[] binary, Path target) throws IOException {
    var file = target.resolveSibling(STAGED);
    Files.write(file, binary);
    Files.setPosixFilePermissions(
        file,
        Files.exists(target)
            ? Files.getPosixFilePermissions(target)
            : PosixFilePermissions.fromString("rwxr-xr-x"));
    return new Staged(file, target);
  }

  /** A replacement written beside its target and not yet installed. */
  public static final class Staged implements AutoCloseable {
    private final Path file;
    private final Path target;
    private boolean installed;

    private Staged(Path file, Path target) {
      this.file = file;
      this.target = target;
    }

    public Path file() {
      return file;
    }

    /** Renames the staged file over the target in one step. */
    public void install() throws IOException {
      Files.move(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      installed = true;
    }

    @Override
    public void close() throws IOException {
      if (!installed) {
        Files.deleteIfExists(file);
      }
    }
  }
}
