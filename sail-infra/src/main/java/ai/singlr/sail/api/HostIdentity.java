/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.ssh.SshGateway;
import ai.singlr.sail.store.FdeSshKeyStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.TokenStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Tokens, FDEs, SSH keys and the gateway decision: who may reach this box. */
public interface HostIdentity {
  /** This box's CLI operator: who a command acts as when it writes without the API. */
  Actor operator();

  List<TokenStore.TokenInfo> tokens();

  TokenStore.CreatedToken createToken(String name, String role, String fdeId, Duration ttl);

  boolean revokeToken(String name);

  Optional<FdeStore.Fde> fde(String handle);

  List<FdeSshKeyStore.SshKeyInfo> sshKeys();

  SshGateway.Decision authorizeGateway(String command, String handle);
}
