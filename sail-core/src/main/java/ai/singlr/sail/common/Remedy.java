/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What to do about a failure, as words and the commands among them, each command kept as data (a
 * verb and its arguments) until the door that prints it renders it for the account reading it. A
 * process that cannot see its reader, sail-api or the sync lane, never renders one: it hands the
 * data on, and the terminal, the API client or Mast renders it for the reader it has.
 */
public record Remedy(List<Part> parts) {

  /** A remedy that names nothing to do. */
  public static final Remedy NONE = new Remedy(List.of());

  /** Words, or a command among them. */
  public sealed interface Part permits Words, Command {}

  /** Words read as they are. */
  public record Words(String text) implements Part {
    public Words {
      Objects.requireNonNull(text, "text");
    }
  }

  /** Who runs a command, which decides how a door renders it for its reader. */
  public enum Runs {
    /** {@code sail} on the box that answered. */
    SAIL,
    /** {@code sail} on a thin client, where sail never runs as root. */
    CLIENT,
    /** Another program on the files of the account sail ran as, such as {@code ssh-keygen -R}. */
    PROGRAM
  }

  /** A command: {@code verb} (the words naming it) and its {@code args}, for {@code runs}. */
  public record Command(Runs runs, String verb, List<String> args) implements Part {
    public Command {
      Objects.requireNonNull(runs, "runs");
      if (Strings.isBlank(verb)) {
        throw new IllegalArgumentException("A command names its verb.");
      }
      args = List.copyOf(args);
    }

    /** The verb and its arguments as one line, without who runs them. */
    public String line() {
      return args.isEmpty() ? verb : verb + " " + String.join(" ", args);
    }
  }

  public Remedy {
    parts = merged(parts);
  }

  /** {@code sail <verb> <args>} on the box that answered. */
  public static Command sail(String verb, String... args) {
    return new Command(Runs.SAIL, verb, Arrays.asList(args));
  }

  /** {@code sail <verb> <args>} on a thin client. */
  public static Command client(String verb, String... args) {
    return new Command(Runs.CLIENT, verb, Arrays.asList(args));
  }

  /** {@code <program> <args>} on the files of the account sail ran as. */
  public static Command program(String program, String... args) {
    return new Command(Runs.PROGRAM, program, Arrays.asList(args));
  }

  /**
   * The README's step that makes a box a main or joins it to one, which installs sail-api and sets
   * up sync: what a box that has not run it yet is told.
   */
  public static Remedy setUp() {
    return of(
        sail("init", "--as-main", "--handle", "<you>"), ", or ", sail("init", "--main", "<main>"));
  }

  /**
   * A remedy read in the order given: a {@link String} is words, a {@link Command} a command and a
   * {@link Remedy} its parts.
   */
  public static Remedy of(Object... parts) {
    var all = new ArrayList<Part>();
    for (var part : parts) {
      switch (part) {
        case String text -> all.add(new Words(text));
        case Part one -> all.add(one);
        case Remedy remedy -> all.addAll(remedy.parts());
        case null, default ->
            throw new IllegalArgumentException("A remedy is words and commands, not " + part);
      }
    }
    return new Remedy(all);
  }

  /** This remedy followed by {@code parts}, read as {@link #of} reads them. */
  public Remedy then(Object... parts) {
    var all = new ArrayList<Object>(this.parts);
    all.addAll(Arrays.asList(parts));
    return of(all.toArray());
  }

  public boolean isEmpty() {
    return parts.isEmpty();
  }

  /** Every command this remedy names, in order. */
  public List<Command> commands() {
    return parts.stream().filter(Command.class::isInstance).map(Command.class::cast).toList();
  }

  /** The remedy as a reader reads it, each command written by {@code command}. */
  public String render(Function<Command, String> command) {
    return parts.stream()
        .map(
            part ->
                switch (part) {
                  case Words words -> words.text();
                  case Command one -> command.apply(one);
                })
        .collect(Collectors.joining());
  }

  /**
   * The remedy as an account whose own plain {@code sail} runs reads it: what a path that prints it
   * without rendering it (an exception's message, an older peer) shows.
   */
  public String plain() {
    return render(
        command -> command.runs() == Runs.PROGRAM ? command.line() : "sail " + command.line());
  }

  /** The remedy as a reader who types nothing reads it: each command its verb and arguments. */
  public String described() {
    return render(Command::line);
  }

  /**
   * The remedy as data a client renders: words as {@code {"text"}}, a command as {@code {"run",
   * "verb", "args"}}.
   */
  public List<Map<String, Object>> toMaps() {
    return parts.stream()
        .<Map<String, Object>>map(
            part -> {
              var map = new LinkedHashMap<String, Object>();
              switch (part) {
                case Words words -> map.put("text", words.text());
                case Command command -> {
                  map.put("run", command.runs().name().toLowerCase(Locale.ROOT));
                  map.put("verb", command.verb());
                  map.put("args", command.args());
                }
              }
              return map;
            })
        .toList();
  }

  /** The remedy {@link #toMaps} wrote, refused when a part is neither words nor a command. */
  public static Remedy fromMaps(List<?> maps) {
    var parts = new ArrayList<Part>();
    for (var item : maps) {
      if (!(item instanceof Map<?, ?> map)) {
        throw new IllegalArgumentException("A remedy part is an object, not " + item);
      }
      if (map.get("text") instanceof String text) {
        parts.add(new Words(text));
      } else if (map.get("verb") instanceof String verb
          && map.get("args") instanceof List<?> args
          && map.get("run") instanceof String run) {
        parts.add(
            new Command(
                Runs.valueOf(run.toUpperCase(Locale.ROOT)),
                verb,
                args.stream().map(String::valueOf).toList()));
      } else {
        throw new IllegalArgumentException("A remedy part is words or a command, not " + map);
      }
    }
    return new Remedy(parts);
  }

  private static List<Part> merged(List<Part> parts) {
    var merged = new ArrayList<Part>();
    for (var part : parts) {
      if (part instanceof Words words && words.text().isEmpty()) {
        continue;
      }
      if (part instanceof Words words
          && !merged.isEmpty()
          && merged.getLast() instanceof Words previous) {
        merged.set(merged.size() - 1, new Words(previous.text() + words.text()));
      } else {
        merged.add(Objects.requireNonNull(part, "part"));
      }
    }
    return List.copyOf(merged);
  }
}
