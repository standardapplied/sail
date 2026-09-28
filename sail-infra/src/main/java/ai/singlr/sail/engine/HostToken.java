/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.ServerConnectionConfig;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.store.TokenStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The API token this box's host CLI holds in its client config. It names no FDE of its own: it acts
 * as the box's FDE, the one the box's sync handle names when the token is used ({@code TokenAuth}),
 * so a host CLI write names that FDE and acts with the role the role rule gives it, however the
 * handle or the roster changes after minting. A box with no sync handle has no FDE to be.
 */
public final class HostToken {

  /** The host token's name, which marks it as the host CLI's. */
  public static final String NAME = "admin";

  private HostToken() {}

  /**
   * Mints the host token — non-expiring when {@code ttl} is null — and saves it to {@code
   * configPath}.
   */
  public static void mint(TokenStore tokens, Duration ttl, Path configPath) throws IOException {
    ServerConnectionConfig.saveLocalToken(
        tokens.create(NAME, "admin", null, ttl).token(), configPath);
  }

  /** Who the host token acts as on a box configured as {@code box}. */
  public static String describe(SyncConfig box) {
    return Strings.isBlank(box.handle())
        ? "this box has no sync handle, so it acts as no FDE"
        : "it acts as this box's FDE '" + box.handle() + "'";
  }
}
