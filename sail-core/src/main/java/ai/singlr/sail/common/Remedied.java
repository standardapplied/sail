/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.util.Collections;
import java.util.IdentityHashMap;
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

  /**
   * What kind of refusal it is, as the API's error codes name one ({@code login_not_stored}), so a
   * client tells refusals apart by data, never by their words; blank for none.
   */
  default String code() {
    return "";
  }

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

  /**
   * What {@code failure} says on one line, then what each of its causes says, each remedy written
   * by {@code render}: through every failure that names no fix, ending at the first that does, its
   * fix included, so a line never runs on past a fix. A cause adds nothing the line already says:
   * words the line holds, a restatement that begins with it, or no words and a type the line
   * already names, as "Interrupted while…" names an {@link InterruptedException}. A cause met again
   * ends the line.
   */
  static String told(Throwable failure, Function<Remedy, String> render) {
    var line = "";
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    for (var next = failure; next != null && seen.add(next); next = next.getCause()) {
      if (!remedyOf(next).isEmpty()) {
        return followedBy(line, render.apply(whole(next)));
      }
      line = followedBy(line, wordsOf(next, line));
    }
    return line;
  }

  private static String wordsOf(Throwable failure, String line) {
    if (Strings.isNotBlank(failure.getMessage())) {
      return failure.getMessage();
    }
    var type = failure.getClass().getSimpleName();
    return !line.isEmpty() && line.contains(type.replaceFirst("(Exception|Error)$", ""))
        ? ""
        : type;
  }

  private static String followedBy(String line, String said) {
    if (line.isEmpty() || line.contains(said)) {
      return line.isEmpty() ? said : line;
    }
    var head = line.endsWith(".") ? line.substring(0, line.length() - 1) : line;
    return said.startsWith(head + ": ") ? said : head + ": " + said;
  }

  /**
   * What a file's failure says, for a line that already names the file: a {@link
   * FileSystemException}'s reason, never its message, which repeats the path; another failure's
   * words; else its type, as an {@code AccessDeniedException} the platform gives no reason is said.
   */
  static String fileReasonOf(IOException failure) {
    var words =
        failure instanceof FileSystemException file ? file.getReason() : failure.getMessage();
    return Strings.isBlank(words) ? failure.getClass().getSimpleName() : words;
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
    return new Io(reason, remedy, "");
  }

  /** As {@link #io(String, Remedy)}, of the kind {@code code} names ({@link #code}). */
  static IOException io(String reason, Remedy remedy, String code) {
    return new Io(reason, remedy, code);
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
    private final String code;

    private Io(String reason, Remedy remedy, String code) {
      super(message(reason, remedy));
      this.reason = reason;
      this.remedy = remedy;
      this.code = Objects.requireNonNull(code, "code");
    }

    @Override
    public String code() {
      return code;
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
