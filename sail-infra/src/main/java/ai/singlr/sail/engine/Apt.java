/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The one way sail runs apt-get, and apt-mark, on its host. Nothing can wait on a question: no
 * frontend, and dpkg keeps a changed config file. Nothing is killed on a clock either: apt bounds a
 * stalled connection itself ({@code Acquire::http::Timeout}) and retries a failed fetch, and it
 * waits up to ten minutes for another apt to let go of the dpkg lock; a deadline that lands while
 * dpkg runs leaves the box half-installed, refusing every later apt call until someone runs {@code
 * dpkg --configure -a}. It runs at {@code -q}, not {@code -qq}, because {@code -qq} hides apt's
 * lock-wait lines and with them the process holding the lock, and in the C locale, because those
 * lines are read by their English wording. Its status lines become the progress shown.
 *
 * <p>An install a mirror answered 404 for, a file its own index names, runs again: such a mirror
 * misses a few files a run, different ones each time, apt's own retries do not cover a 404, and a
 * failed run keeps what it fetched, so the next asks only for the files still missing.
 */
public final class Apt {

  /** The progress shown while another apt holds the dpkg lock. */
  public static final String LOCK_WAIT = "waiting for another apt to finish";

  static final List<String> COMMAND =
      List.of(
          "env",
          "LC_ALL=C",
          "DEBIAN_FRONTEND=noninteractive",
          "apt-get",
          "-q",
          "-o",
          "APT::Status-Fd=1",
          "-o",
          "Acquire::Retries=3",
          "-o",
          "DPkg::Lock::Timeout=600",
          "-o",
          "Dpkg::Options::=--force-confdef",
          "-o",
          "Dpkg::Options::=--force-confold");

  /** The front of every apt-mark call: the same frontend, locale and lock wait as apt-get's. */
  public static final List<String> HOLD =
      List.of(
          "env",
          "LC_ALL=C",
          "DEBIAN_FRONTEND=noninteractive",
          "apt-mark",
          "-o",
          "DPkg::Lock::Timeout=600",
          "hold");

  /**
   * How many times an install runs while a mirror answers 404: at the worst rate measured, two in
   * three first runs and one in eight of the runs after, one install in some 700 still stops.
   */
  static final int FETCH_ATTEMPTS = 4;

  /**
   * apt-get's line for a fetch an HTTP server answered 404: {@code Failed to fetch <uri> <code>
   * <reason>}, the status code just after the URI's two spaces, where any other failure of a fetch
   * reads as words. It names the mirror's host and the file.
   */
  private static final Pattern NOT_SERVED =
      Pattern.compile("E: Failed to fetch [a-z+]+://([^/\\s]+)/\\S*?([^/\\s]+)  404\\b");

  /** apt-get's line after its failed fetches, which says nothing of its own. */
  private static final String UNFETCHED = "E: Unable to fetch some archives";

  private static final String WAITING = "Waiting for cache lock: ";
  private static final List<String> STATUS = List.of("dlstatus", "pmstatus");

  private final ShellExec shell;
  private final Consumer<String> progress;

  public Apt(ShellExec shell, Consumer<String> progress) {
    this.shell = shell;
    this.progress = progress;
  }

  /**
   * Refreshes apt's index of every configured source. A source that fails to download fails the
   * refresh, since apt otherwise exits 0 and answers from the index it had.
   */
  public void update() throws IOException, InterruptedException {
    update(new Reader(""));
  }

  private void update(Reader reader) throws IOException, InterruptedException {
    var result = run(List.of("-o", "APT::Update::Error-Mode=any", "update"), reader);
    if (!result.ok()) {
      throw failed("apt-get update", result, reader);
    }
  }

  /** Installs {@code packages}. */
  public void install(List<String> packages) throws IOException, InterruptedException {
    fetch(
        "apt-get install " + String.join(" ", packages),
        withPackages(List.of("install", "-y"), packages));
  }

  /** Moves the installed, held {@code packages} to apt's candidates, installing nothing new. */
  public void upgradeHeld(List<String> packages) throws IOException, InterruptedException {
    fetch(
        "apt-get install --only-upgrade " + String.join(" ", packages),
        withPackages(
            List.of("install", "-y", "--only-upgrade", "--allow-change-held-packages"), packages));
  }

  /**
   * Holds {@code packages}, so apt moves them only when asked ({@code
   * --allow-change-held-packages}).
   */
  public void hold(List<String> packages) throws IOException, InterruptedException {
    var reader = new Reader("");
    var result = shell.run(withPackages(HOLD, packages), reader::read);
    if (!result.ok()) {
      throw failed("apt-mark hold " + String.join(" ", packages), result, reader);
    }
  }

  /**
   * Runs an install, again while a mirror answers 404 ({@link #NOT_SERVED}), saying so, up to
   * {@link #FETCH_ATTEMPTS} runs, apt's index refreshed once before the second, since an index the
   * mirror has moved past names files it no longer serves. Every progress line of the tries after
   * the first says which try it is. Any other failure ends it at once.
   */
  private void fetch(String what, List<String> args) throws IOException, InterruptedException {
    var trying = "";
    for (var attempt = 1; ; attempt++) {
      var reader = new Reader(trying);
      var result = run(args, reader);
      if (result.ok()) {
        return;
      }
      var unserved = unserved(result.stderr());
      if (unserved.isEmpty()) {
        throw failed(what, result, reader);
      }
      var mirror = named(unserved, 1);
      if (attempt == FETCH_ATTEMPTS) {
        throw new IOException(
            what
                + " failed: the mirror "
                + mirror
                + " is not serving files its own index names ("
                + attempt
                + " tries; the last answered 404 for "
                + named(unserved, 2)
                + "). That is the mirror's fault, not this box's: run the same command again, and"
                + " apt fetches only the files still missing.");
      }
      var answered =
          "the mirror " + mirror + " answered 404 for " + unserved.size() + " of its files";
      progress.accept(
          answered + "; trying again (" + (attempt + 1) + " of " + FETCH_ATTEMPTS + ")");
      trying = "try " + (attempt + 1) + " of " + FETCH_ATTEMPTS + " after a 404: ";
      if (attempt == 1) {
        try {
          update(new Reader(trying));
        } catch (IOException refresh) {
          throw new IOException(
              what + " failed: " + answered + ", and before trying again " + refresh.getMessage(),
              refresh);
        }
      }
    }
  }

  /**
   * The 404 lines of {@code stderr} when they are all apt said went wrong, besides the line it ends
   * them with; none when it said anything else, which a try again would not cure.
   */
  private static List<MatchResult> unserved(String stderr) {
    var errors =
        stderr
            .lines()
            .filter(line -> line.startsWith("E: ") && !line.startsWith(UNFETCHED))
            .toList();
    var unserved =
        errors.stream()
            .map(NOT_SERVED::matcher)
            .filter(Matcher::lookingAt)
            .map(Matcher::toMatchResult)
            .toList();
    return unserved.size() == errors.size() ? unserved : List.of();
  }

  private ShellExec.Result run(List<String> args, Reader reader)
      throws IOException, InterruptedException {
    var command = new ArrayList<>(COMMAND);
    command.addAll(args);
    return shell.run(command, reader::read);
  }

  private static IOException failed(String what, ShellExec.Result result, Reader reader) {
    return new IOException(what + " failed: " + reason(result, reader.heldBy));
  }

  private static String named(List<MatchResult> unserved, int group) {
    return unserved.stream()
        .map(found -> found.group(group))
        .distinct()
        .collect(Collectors.joining(", "));
  }

  private static List<String> withPackages(List<String> args, List<String> packages) {
    var all = new ArrayList<>(args);
    all.addAll(packages);
    return all;
  }

  /**
   * What apt said went wrong: its {@code E:} lines, else its stderr, else its exit code. When apt
   * gave up still waiting on the lock, the holder it last named ends the reason, since {@code E:}
   * alone does not name it.
   */
  static String reason(ShellExec.Result result, String heldBy) {
    var errors =
        result
            .stderr()
            .lines()
            .filter(line -> line.startsWith("E: "))
            .collect(Collectors.joining(" "));
    var said = errors.isEmpty() ? result.reason() : errors;
    return heldBy == null ? said : said + " " + heldBy + ".";
  }

  /** {@code dlstatus} and {@code pmstatus} lines as "<percent>% <item>"; empty for the rest. */
  static Optional<String> status(String line) {
    var fields = line.split(":", 4);
    if (fields.length < 4 || !STATUS.contains(fields[0])) {
      return Optional.empty();
    }
    try {
      return Optional.of((int) Double.parseDouble(fields[2]) + "% " + fields[3]);
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  /**
   * Reads one run's stdout, remembering the lock holder while apt is still waiting on it, each
   * progress line led by the try it belongs to ({@code trying}), empty for a first try.
   */
  private final class Reader {
    private final String trying;
    private String heldBy;

    Reader(String trying) {
      this.trying = trying;
    }

    void read(String line) {
      if (line.startsWith(WAITING)) {
        var waiting = line.substring(WAITING.length());
        heldBy = waiting.endsWith("...") ? waiting.substring(0, waiting.length() - 3) : waiting;
        progress.accept(trying + LOCK_WAIT);
        return;
      }
      heldBy = null;
      status(line).map(trying::concat).ifPresent(progress);
    }
  }
}
