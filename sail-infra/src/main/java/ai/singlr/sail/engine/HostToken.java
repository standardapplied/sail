/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.ServerConnectionConfig;
import ai.singlr.sail.api.SessionAwareAuth;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.TokenStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

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

  /** What {@link #ensure} found and did. */
  public enum Outcome {
    HELD,
    MINTED,
    REISSUED
  }

  /**
   * Ensures the client config at {@code configPath} can reach the API of the database whose tokens
   * are {@code tokens}. A database holding no token mints the host token. A config host token the
   * database does not hold is the token of the box the database was copied from, its plaintext on
   * that box: the copy's host token is revoked and a new one minted and saved, every other token
   * kept. A config login session is the session store's to weigh, never this. Anything else is left
   * as it is.
   */
  public static Outcome ensure(TokenStore tokens, Path configPath) throws IOException {
    if (tokens.list().isEmpty()) {
      mint(tokens, null, configPath);
      return Outcome.MINTED;
    }
    if (!Files.exists(configPath)
        || !(YamlUtil.parseFile(configPath).get("token") instanceof String token)
        || token.startsWith(SessionAwareAuth.SESSION_PREFIX)
        || tokens.validate(token).isPresent()) {
      return Outcome.HELD;
    }
    tokens.revoke(NAME);
    mint(tokens, null, configPath);
    return Outcome.REISSUED;
  }

  /**
   * What {@link #ensure} did, {@code outcome}, as the operator reads it, for the box configured as
   * {@code box} whose client config is {@code configPath}; empty when it did nothing.
   */
  public static Optional<String> said(Outcome outcome, Path configPath, SyncConfig box) {
    return switch (outcome) {
      case HELD -> Optional.empty();
      case MINTED ->
          Optional.of("API token created and saved to " + configPath + "; " + describe(box));
      case REISSUED ->
          Optional.of("API token re-issued for this box: the database came from another box.");
    };
  }

  /** Who the host token acts as on a box configured as {@code box}. */
  public static String describe(SyncConfig box) {
    if (Strings.isNotBlank(box.handle())) {
      return "it acts as this box's FDE '" + box.handle() + "'";
    }
    return box.isNode()
        ? "this node names no FDE, so it is refused until you set its sync handle"
        : "this box has no sync handle, so it acts as no FDE";
  }
}
