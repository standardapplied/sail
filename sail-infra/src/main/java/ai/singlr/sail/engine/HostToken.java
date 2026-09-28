/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.ServerConnectionConfig;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.TokenStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The API token this box's host CLI holds in its client config, bound to the box's FDE, so a host
 * CLI write names that FDE and acts with the role the role rule gives it. A box with no sync handle
 * has no FDE to be, and its token names none. A node never mints one that names none: an FDE-less
 * token acts with its minted admin role, which the roster does not cap. A token minted before the
 * box had a sync handle names none until {@link #bind} binds it, which {@code sail migrate} and
 * every sync round do.
 */
public final class HostToken {

  /** The host token's name. */
  public static final String NAME = "admin";

  private HostToken() {}

  /** A minted host token and the FDE it is bound to, null when none. */
  public record Minted(TokenStore.CreatedToken created, String fde) {}

  /**
   * Mints the host token — non-expiring when {@code ttl} is null — bound to the box's FDE when the
   * roster knows it, and saves it to {@code configPath}. Refuses on a node whose roster does not
   * know its FDE yet.
   */
  public static Minted mint(
      TokenStore tokens, FdeStore roster, SyncConfig box, Duration ttl, Path configPath)
      throws IOException {
    var fde = boxFde(roster, box);
    if (fde == null && box.isNode() && Strings.isNotBlank(box.handle())) {
      throw new IllegalStateException(
          "Cannot mint the host CLI's API token: FDE '"
              + box.handle()
              + "' is not in this node's roster yet. Run 'sudo sail sync' to pull the roster, then"
              + " 'sudo sail server init'.");
    }
    var created = tokens.create(NAME, "admin", fde == null ? null : fde.id(), ttl);
    ServerConnectionConfig.saveLocalToken(created.token(), configPath);
    return new Minted(created, fde == null ? null : fde.handle());
  }

  /**
   * Binds the FDE-less host token to the box's FDE. Returns the FDE's handle when it bound the
   * token, and null when there was nothing to bind: no host token, one bound already, or no FDE the
   * roster knows.
   */
  public static String bind(TokenStore tokens, FdeStore roster, SyncConfig box) {
    var fde = boxFde(roster, box);
    return fde != null && tokens.bind(NAME, fde.id()) ? fde.handle() : null;
  }

  /**
   * What minting said about the token's FDE: the FDE it acts as, or why it acts as none when the
   * box has a sync handle.
   */
  public static String describe(Minted minted, SyncConfig box) {
    if (minted.fde() != null) {
      return "it acts as FDE '" + minted.fde() + "'";
    }
    if (Strings.isBlank(box.handle())) {
      return "this box has no sync handle, so it acts as no FDE";
    }
    return "FDE '"
        + box.handle()
        + "' is not in this box's roster yet, so it acts as no FDE; run 'sudo sail migrate' once"
        + " it is";
  }

  private static FdeStore.Fde boxFde(FdeStore roster, SyncConfig box) {
    return Strings.isBlank(box.handle()) ? null : roster.byHandle(box.handle()).orElse(null);
  }
}
