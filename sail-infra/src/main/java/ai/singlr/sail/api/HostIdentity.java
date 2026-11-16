/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.HostToken;
import ai.singlr.sail.engine.WorkspaceOwner;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.ssh.SshGateway;
import ai.singlr.sail.store.FdeSshKeyStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.TokenStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Tokens, FDEs, SSH keys and the gateway decision: who may reach this box. */
public interface HostIdentity {
  /** This box's CLI operator: who a command acts as when it writes without the API. */
  Actor operator();

  List<TokenStore.TokenInfo> tokens();

  TokenStore.CreatedToken createToken(String name, String role, String fdeId, Duration ttl);

  /**
   * Mints this box's host CLI token, which acts as the box's FDE ({@link HostToken}), and saves it
   * to the client config at {@code configPath}.
   */
  void mintHostToken(Path configPath) throws IOException;

  /** The box's sync configuration, which names the FDE its host CLI acts as. */
  SyncConfig box();

  boolean revokeToken(String name);

  Optional<FdeStore.Fde> fde(String handle);

  List<FdeSshKeyStore.SshKeyInfo> sshKeys();

  /**
   * Person {@code handle} as the owner of a workspace of this box ({@link WorkspaceOwner#of}): with
   * the box's workstation key when they are the box's person.
   */
  WorkspaceOwner workspaceOwner(String handle);

  /**
   * The git tokens this box's person stored with {@code sail git login}, by lower-case host: the
   * one source a host command clones with. Empty when the box names no person on its roster.
   */
  Optional<Map<String, String>> gitTokens();

  SshGateway.Decision authorizeGateway(String command, String handle);

  /** As {@link #authorizeGateway}, for the person a key is pinned to by this box's id for them. */
  SshGateway.Decision authorizeGatewayById(String command, String fdeId);
}
