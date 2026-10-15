/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The lock that orders every grant and revocation of a pairing's trust on this box. A pairing is a
 * key line in the operator's login, a record in the control plane and, when its key is the
 * workstation key, a line in each container a {@code project apply} provisions; no transaction
 * spans them. A pairing command holds it from its first read to its last write, so it never forgets
 * the record of a login just written or writes a login for an FDE just removed. An apply holds it
 * from resolving the workstation key to installing it, so a revocation scans the containers only
 * after the key is in them, or the apply reads the key only after it is revoked. It is a lock file
 * beside the database, as the sync rounds' is, so the gateway's account takes it too.
 */
public final class PairingTrust {

  private PairingTrust() {}

  /** Takes the lock of the control plane at {@code database}, waiting while another holds it. */
  public static FileMutex hold(Path database) throws IOException {
    return FileMutex.acquire(database.resolveSibling(database.getFileName() + ".pairings.lock"));
  }
}
