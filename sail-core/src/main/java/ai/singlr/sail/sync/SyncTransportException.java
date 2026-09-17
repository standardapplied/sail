/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

/**
 * A sync session failed at the transport layer — main refused a request (e.g. a read-only push) or
 * the channel returned something the protocol does not allow. Distinct from {@link
 * java.io.UncheckedIOException}, which signals the channel itself broke.
 */
public final class SyncTransportException extends RuntimeException {

  private final String kind;

  public SyncTransportException(String message) {
    this("protocol", message, null);
  }

  public SyncTransportException(String kind, String message, Throwable cause) {
    super(message, cause);
    this.kind =
        switch (kind) {
          case "unreachable", "refused", "protocol", "store" -> kind;
          case null, default -> "protocol";
        };
  }

  public String kind() {
    return kind;
  }
}
