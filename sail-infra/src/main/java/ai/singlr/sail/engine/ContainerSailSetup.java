/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.concurrent.TimeoutException;

/**
 * Self-healing entry point for sail's in-container machinery. Reconciles two invariants on every
 * call:
 *
 * <ol>
 *   <li><b>Event-socket bind mount.</b> {@link IncusDeviceManager#ensureEventSocket} runs every
 *       time so the mount always points at the current {@link SailPaths#apiSocketHostDir()} inode.
 *       Incus tracks the bind by inode and the source directory can be recreated under the same
 *       path, so the mount is force-refreshed (remove + re-add) rather than compared.
 *   <li><b>Sail-owned files.</b> Every installed payload is a pure function of this binary. A
 *       successful full install stamps {@link #STAMP_PATH} with the SHA-256 {@link #fingerprint()}
 *       of all payloads, and the staleness probe is a content comparison: read the stamp, compare
 *       to the fingerprint this binary would install right now. A missing, mismatched, or corrupt
 *       stamp runs every installer and restamps — so a container converges on first touch after any
 *       binary change, with no per-file staleness bookkeeping.
 * </ol>
 *
 * <p>Designed for the dispatch hot path: the refresh is two idempotent {@code incus} calls, the
 * probe is one {@code cat}, and only a stale container pays for the installer shells.
 */
public final class ContainerSailSetup {

  /** Container-side path of the machinery fingerprint stamp. */
  public static final String STAMP_PATH = "/home/dev/.sail/.machinery";

  private ContainerSailSetup() {}

  /** Result of a setup reconciliation. */
  public enum Result {
    /** The stamp matched this binary's fingerprint; no installer ran. */
    ALREADY_PRESENT,
    /** The stamp was missing or stale; every installer ran and the stamp was rewritten. */
    UPDATED
  }

  /**
   * Reconciles the event-socket bind mount and the sail-owned files in {@code container}.
   * Idempotent at the user-visible level: post-call the mount points at the current inode and the
   * installed files match this binary's payloads.
   */
  public static Result ensureInstalled(ShellExec shell, String container)
      throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(container);
    new IncusDeviceManager(shell)
        .refreshEventSocket(
            container, SailPaths.apiSocketHostDir(), SailPaths.apiSocketContainerDir());
    var expected = fingerprint();
    if (stampMatches(shell, container, expected)) {
      return Result.ALREADY_PRESENT;
    }
    new SailEventHelper(shell).install(container);
    new SailStopGate(shell).install(container);
    new SpecCliHelper(shell).install(container);
    new ClaudeCodeHookConfig(shell).install(container);
    new CodexHookConfig(shell).install(container);
    writeStamp(shell, container, expected);
    return Result.UPDATED;
  }

  /**
   * SHA-256 over the ordered {@code (path, content)} pairs of every payload sail installs in a
   * container. Pure function of this binary: any change to any payload changes the fingerprint.
   */
  public static String fingerprint() {
    return fingerprintOf(installedFiles());
  }

  static String fingerprintOf(SequencedMap<String, String> files) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
    files.forEach(
        (path, content) -> {
          digest.update(path.getBytes(StandardCharsets.UTF_8));
          digest.update((byte) 0);
          digest.update(content.getBytes(StandardCharsets.UTF_8));
          digest.update((byte) 0);
        });
    return HexFormat.of().formatHex(digest.digest());
  }

  static SequencedMap<String, String> installedFiles() {
    var files = new LinkedHashMap<String, String>();
    files.put(SailEventHelper.SCRIPT_PATH, SailEventHelper.scriptContent());
    files.put(SailStopGate.SCRIPT_PATH, SailStopGate.scriptContent());
    files.put(SpecCliHelper.SCRIPT_PATH, SpecCliHelper.scriptContent());
    files.put(SpecCliHelper.PROFILE_PATH, SpecCliHelper.profileLine());
    files.put(ClaudeCodeHookConfig.SETTINGS_PATH, ClaudeCodeHookConfig.render());
    files.put(CodexHookConfig.SETTINGS_PATH, CodexHookConfig.render());
    return files;
  }

  private static boolean stampMatches(ShellExec shell, String container, String expected)
      throws IOException, InterruptedException, TimeoutException {
    var probe = shell.exec(ContainerExec.asDevUser(container, List.of("cat", STAMP_PATH)));
    return probe.ok() && probe.stdout().strip().equals(expected);
  }

  private static void writeStamp(ShellExec shell, String container, String fingerprint)
      throws IOException, InterruptedException, TimeoutException {
    var write =
        shell.exec(
            ContainerExec.asDevUser(
                container,
                List.of(
                    "bash", "-c", "printf '%s' \"$1\" > \"$2\"", "bash", fingerprint, STAMP_PATH)));
    if (!write.ok()) {
      throw new IOException(
          "Failed to stamp " + STAMP_PATH + " in " + container + ": " + write.stderr());
    }
  }
}
