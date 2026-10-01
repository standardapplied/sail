/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

/**
 * How a dependency of an offer stands against one box's database: held, so the offer can be
 * decided; pending, so it is awaited by sync order; or gone, so it can never arrive. A blocking
 * standing names why, in the words main's refusal or denial carries.
 */
public sealed interface Standing {

  /** The dependency is held: the offer can be decided. */
  record Held() implements Standing {}

  /** The dependency is still to arrive; {@code reason} says what is awaited. */
  record Pending(String reason) implements Standing {}

  /** The dependency can never arrive; {@code reason} says what is gone. */
  record Gone(String reason) implements Standing {}

  Standing HELD = new Held();

  /** The worse of this standing and {@code other}: gone over pending over held. */
  default Standing worse(Standing other) {
    return rank(other) > rank(this) ? other : this;
  }

  /** Whether this standing blocks the offer. */
  default boolean blocks() {
    return !(this instanceof Held);
  }

  /** The reason a blocking standing carries; null when held. */
  default String reason() {
    return switch (this) {
      case Held ignored -> null;
      case Pending pending -> pending.reason();
      case Gone gone -> gone.reason();
    };
  }

  private static int rank(Standing standing) {
    return switch (standing) {
      case Held ignored -> 0;
      case Pending ignored -> 1;
      case Gone ignored -> 2;
    };
  }
}
