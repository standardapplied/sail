/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;

/**
 * A sync session failed at the transport layer — main refused a request (e.g. a read-only push) or
 * the channel returned something the protocol does not allow. Distinct from {@link
 * java.io.UncheckedIOException}, which signals the channel itself broke.
 */
public final class SyncTransportException extends RuntimeException implements Remedied {

  private final String kind;
  private final String reason;
  private final transient Remedy remedy;

  public SyncTransportException(String message) {
    this("protocol", message, null);
  }

  public SyncTransportException(String kind, String message, Throwable cause) {
    this(kind, message, Remedy.NONE, cause);
  }

  /** A failure of {@code kind} saying what happened and what to do about it. */
  public SyncTransportException(String kind, String reason, Remedy remedy, Throwable cause) {
    super(Remedied.message(reason, remedy), cause);
    this.reason = reason;
    this.remedy = remedy;
    this.kind =
        switch (kind) {
          case "unreachable", "refused", "protocol", "store" -> kind;
          case null, default -> "protocol";
        };
  }

  public String kind() {
    return kind;
  }

  @Override
  public String reason() {
    return reason;
  }

  @Override
  public Remedy remedy() {
    return remedy;
  }
}
