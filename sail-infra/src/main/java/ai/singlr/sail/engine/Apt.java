/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
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
    run("apt-get update", List.of("-o", "APT::Update::Error-Mode=any", "update"));
  }

  /** Installs {@code packages}. */
  public void install(List<String> packages) throws IOException, InterruptedException {
    run(
        "apt-get install " + String.join(" ", packages),
        withPackages(List.of("install", "-y"), packages));
  }

  /** Moves the installed, held {@code packages} to apt's candidates, installing nothing new. */
  public void upgradeHeld(List<String> packages) throws IOException, InterruptedException {
    run(
        "apt-get install --only-upgrade " + String.join(" ", packages),
        withPackages(
            List.of("install", "-y", "--only-upgrade", "--allow-change-held-packages"), packages));
  }

  /**
   * Holds {@code packages}, so apt moves them only when asked ({@code
   * --allow-change-held-packages}).
   */
  public void hold(List<String> packages) throws IOException, InterruptedException {
    var reader = new Reader();
    var result = shell.run(withPackages(HOLD, packages), reader::read);
    if (!result.ok()) {
      throw new IOException(
          "apt-mark hold "
              + String.join(" ", packages)
              + " failed: "
              + reason(result, reader.heldBy));
    }
  }

  private void run(String what, List<String> args) throws IOException, InterruptedException {
    var reader = new Reader();
    var command = new ArrayList<>(COMMAND);
    command.addAll(args);
    var result = shell.run(command, reader::read);
    if (!result.ok()) {
      throw new IOException(what + " failed: " + reason(result, reader.heldBy));
    }
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
    var said =
        !errors.isEmpty()
            ? errors
            : Strings.isBlank(result.stderr())
                ? "exit " + result.exitCode()
                : result.stderr().strip();
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

  /** Reads one run's stdout, remembering the lock holder while apt is still waiting on it. */
  private final class Reader {
    private String heldBy;

    void read(String line) {
      if (line.startsWith(WAITING)) {
        var waiting = line.substring(WAITING.length());
        heldBy = waiting.endsWith("...") ? waiting.substring(0, waiting.length() - 3) : waiting;
        progress.accept(LOCK_WAIT);
        return;
      }
      heldBy = null;
      status(line).ifPresent(progress);
    }
  }
}
