/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Asks again, at most once a second, whether an open stream may go on: a stream outlives the
 * request that authenticated it, and a revoke, a scope change, a demotion, a disable or a removal
 * made since must reach it as it reaches the next request. The check costs what one request's
 * authentication does, so a stream pays it once a second at most, however many events it sends.
 */
final class StreamRecheck {

  static final Duration INTERVAL = Duration.ofSeconds(1);

  /** The stream may not go on, for {@code error}'s reason. */
  static final class Ended extends Exception {
    private final transient ApiError error;

    Ended(ApiError error) {
      super(error.message());
      this.error = error;
    }

    ApiError error() {
      return error;
    }
  }

  private final Supplier<Optional<ApiError>> check;
  private final InstantSource clock;
  private Instant last;

  /**
   * {@code check} answers why the stream may not go on, or empty when it may; it is first asked a
   * second after now.
   */
  StreamRecheck(Supplier<Optional<ApiError>> check, InstantSource clock) {
    this.check = Objects.requireNonNull(check, "check");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.last = clock.instant();
  }

  /**
   * Asks the check when a second or more has passed since it was last asked. Returns whether it was
   * asked, so a caller rebuilds what it derived from the caller's role and scope.
   *
   * @throws Ended when the check refuses
   */
  boolean recheck() throws Ended {
    var now = clock.instant();
    if (Duration.between(last, now).compareTo(INTERVAL) < 0) {
      return false;
    }
    last = now;
    var refused = check.get();
    if (refused.isPresent()) {
      throw new Ended(refused.get());
    }
    return true;
  }

  /** The stream's last event: why it ended, as an SSE {@code error} event. */
  static void writeEnded(OutputStream out, Ended ended) throws IOException {
    var data = YamlUtil.dumpJson(ended.error().toMap());
    out.write(("event: error\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }
}
