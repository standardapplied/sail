/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Function;

/**
 * A failure that says what happened ({@link #reason}) apart from what to do about it ({@link
 * #remedy}), so a door renders the remedy's commands for its reader and the API hands them on as
 * data. Its message, for a path that prints it unrendered, is the {@link Remedy#plain} reading.
 */
public interface Remedied {

  /** What happened, naming no command. */
  String reason();

  /** What to do about it; {@link Remedy#NONE} when there is nothing to name. */
  Remedy remedy();

  /** The reason and the remedy as one line, each command written by {@code command}. */
  default String render(Function<Remedy.Command, String> command) {
    return joined(reason(), remedy().render(command));
  }

  /** The reason and remedy of {@code failure} as one remedy, or its message as words. */
  static Remedy whole(Throwable failure) {
    var reason = reasonOf(failure);
    var remedy = remedyOf(failure);
    if (Strings.isBlank(reason)) {
      return remedy;
    }
    return Remedy.of(reason, remedy.isEmpty() ? "" : " ").then(remedy);
  }

  /**
   * What {@code failure} says happened: its reason when it names a remedy apart, else its message,
   * else its type. A failure wrapped by another keeps saying it through this, never through {@link
   * Throwable#getMessage()}, which has a remedy's plain form in it.
   */
  static String reasonOf(Throwable failure) {
    return failure instanceof Remedied remedied
        ? remedied.reason()
        : Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName());
  }

  /** What {@code failure} says to do about it; {@link Remedy#NONE} when it names nothing. */
  static Remedy remedyOf(Throwable failure) {
    return failure instanceof Remedied remedied ? remedied.remedy() : Remedy.NONE;
  }

  /** An {@link IllegalStateException} that says what happened and what to do about it. */
  static IllegalStateException state(String reason, Remedy remedy) {
    return new State(reason, remedy, null);
  }

  /** As {@link #state(String, Remedy)}, caused by {@code cause}. */
  static IllegalStateException state(String reason, Remedy remedy, Throwable cause) {
    return new State(reason, remedy, cause);
  }

  /** An {@link IllegalArgumentException} that says what was wrong and what to do about it. */
  static IllegalArgumentException argument(String reason, Remedy remedy) {
    return new Argument(reason, remedy);
  }

  /** An {@link IOException} that says what failed and what to do about it. */
  static IOException io(String reason, Remedy remedy) {
    return new Io(reason, remedy);
  }

  /** {@code first} and {@code second} with one space between, whichever of them are not blank. */
  static String joined(String first, String second) {
    if (Strings.isBlank(second)) {
      return first;
    }
    return Strings.isBlank(first) ? second : first + " " + second;
  }

  /** The message a {@link Remedied} failure carries for a path that prints it as it is. */
  static String message(String reason, Remedy remedy) {
    return joined(reason, remedy.plain());
  }

  /** {@link #state}'s failure. */
  final class State extends IllegalStateException implements Remedied {
    private final String reason;
    private final transient Remedy remedy;

    private State(String reason, Remedy remedy, Throwable cause) {
      super(message(reason, remedy), cause);
      this.reason = reason;
      this.remedy = remedy;
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

  /** {@link #io}'s failure. */
  final class Io extends IOException implements Remedied {
    private final String reason;
    private final transient Remedy remedy;

    private Io(String reason, Remedy remedy) {
      super(message(reason, remedy));
      this.reason = reason;
      this.remedy = remedy;
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

  /** {@link #argument}'s failure. */
  final class Argument extends IllegalArgumentException implements Remedied {
    private final String reason;
    private final transient Remedy remedy;

    private Argument(String reason, Remedy remedy) {
      super(message(reason, remedy));
      this.reason = reason;
      this.remedy = remedy;
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
}
