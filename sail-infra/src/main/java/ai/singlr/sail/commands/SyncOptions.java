/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import picocli.CommandLine.Option;

/**
 * The shared {@code --no-sync} opt-out for spec commands. Mixed into a command with {@code @Mixin}
 * so the flag that skips the automatic sync with main is declared once rather than copied into a
 * dozen commands. Spec commands forward it to the server as the {@code X-Sail-No-Sync} request
 * header, where the operations seam skips both the read freshen and the write-triggered propagation
 * for that request. Scripts can set it fleet-wide with {@code SAIL_NO_SYNC=true}.
 */
public final class SyncOptions {

  @Option(
      names = "--no-sync",
      defaultValue = "${env:SAIL_NO_SYNC:-false}",
      description =
          "Skip the automatic sync with main around this command; act on the local replica as-is."
              + " Defaults to the SAIL_NO_SYNC environment variable.")
  private boolean noSync;

  public boolean noSync() {
    return noSync;
  }
}
