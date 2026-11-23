/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.pty.PtyMessage.PutRefusal;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One upload's bytes on their way into a container, whole or not at all, and the shell that does
 * it: {@link #OPEN} makes the session's directory and opens a temporary in it whose input takes the
 * bytes, {@link #COMMIT} gives the temporary its name, {@link #ABORT} removes it. Every script
 * reaches a container as its {@link Place} says, and says what it refused by its exit status alone.
 * Nothing here knows a frame or a session.
 */
final class UploadCopy {

  /**
   * Where sessions' uploads are kept in a container, one directory each, under their instance id.
   */
  static final String ROOT = "/var/lib/sail-paste";

  /**
   * How long one script in a container may take, and an upload's copy may wait to open, before it
   * is given up as failed: a container that never answers holds no lock past it.
   */
  static final Duration SCRIPT = Duration.ofMinutes(2);

  /** Exit statuses of the scripts that refuse: what is unsafe, too many files, too many bytes. */
  static final int UNSAFE = 10;

  static final int TOO_MANY = 11;
  static final int FULL = 12;

  /**
   * The status a script exits with when the workspace's disk has no room for the file, after it
   * prints {@code free N}, the bytes free on it.
   */
  static final int NO_SPACE = 28;

  /** The status {@link #ABORT} exits with when a process still holds the temporary after it. */
  static final int HELD = 3;

  /**
   * The one root rule, a shell function the scripts that make, write or remove under the uploads
   * root call before they touch it: {@code ours DIR UID} succeeds only when {@code DIR} is a
   * directory, not a link, owned by {@code UID}.
   */
  static final String OURS =
      """
      ours() { [ -d "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u "$1")" = "$2" ]; }
      """;

  /**
   * Run as root: checks the disk the uploads are kept on (the root's, or its parent's before it is
   * made) has room for {@code $4} bytes, before anything is made on it, makes {@code $1} root's own
   * and {@code $1/$2} the dev user's alone, refusing a link at either, then, as the dev user
   * ({@code shift 9; "$@"}), counts what the directory holds against {@code $5} files and {@code
   * $6} bytes, creates the temporary for the first number no name, link or temporary takes, with
   * {@code set -C} ({@code O_EXCL}) behind those checks, prints that number, and copies its input
   * into it through the descriptor it opened. A measurement that fails is {@code failed}, never
   * read as a full disk or a full directory; a copy that fails is {@code no_space} when it left the
   * disk under 64 KiB free; the host removes its temporary.
   */
  static final String OPEN =
      OURS
          + """
      root=$1 id=$2 name=$3 size=$4 files=$5 bytes=$6 rootuid=$7 devuid=$8 devgid=$9
      shift 9
      at=$root
      [ -d "$at" ] || at=${root%/*}
      disk=$(df -Pk "$at") || exit 1
      free=$(printf "%s\\n" "$disk" | awk "NR == 2 { print \\$4 * 1024 }")
      case $free in ""|*[!0-9]*) exit 1 ;; esac
      [ "$free" -ge "$size" ] || { echo "free $free"; exit 28; }
      [ -d "$root" ] || mkdir -m 0755 "$root" || exit 10
      ours "$root" "$rootuid" || exit 10
      chmod 0755 "$root"
      dir=$root/$id
      [ -d "$dir" ] || mkdir -m 0700 "$dir" || exit 10
      [ -d "$dir" ] && [ ! -L "$dir" ] || exit 10
      chown "$devuid:$devgid" "$dir" && chmod 0700 "$dir" || exit 10
      exec "$@" sh -c '
      cd "$1" || exit 1
      names=$(find . -mindepth 1 -maxdepth 1 ! -name ".*.part" -printf x) || exit 1
      [ "${#names}" -lt "$4" ] || exit 11
      sizes=$(find . -mindepth 1 -maxdepth 1 -type f -printf "%s\\n") || exit 1
      used=$(printf "%s\\n" "$sizes" | awk "{ s += \\$1 } END { print s + 0 }")
      [ $((used + $3)) -le "$5" ] || exit 12
      n=1
      while [ -e "$n-$2" ] || [ -L "$n-$2" ] || [ -e ".$n-$2.part" ] || [ -L ".$n-$2.part" ]; do
        n=$((n + 1))
      done
      umask 077
      set -C
      exec 3>".$n-$2.part"
      set +C
      echo "$n"
      if ! cat >&3; then
        free=$(df -Pk . | awk "NR == 2 { print \\$4 * 1024 }")
        [ "$free" -lt 65536 ] && { echo "free $free"; exit 28; }
        exit 1
      fi
      ' sail-put "$dir" "$name" "$size" "$files" "$bytes"
      """;

  /**
   * Run as the dev user in the session's directory {@code $1}: gives the temporary of number {@code
   * $2} and name {@code $3} its name when it is a file of {@code $4} bytes, by a hard link that
   * refuses an existing name, a link or a directory there included ({@code ln -T}), and removes the
   * temporary either way.
   */
  static final String COMMIT =
      """
      cd "$1" || exit 1
      part=".$2-$3.part"
      [ -f "$part" ] && [ ! -L "$part" ] && [ "$(stat -c %s "$part")" = "$4" ] && ln -T "$part" "$2-$3"
      done=$?
      rm -f "$part"
      exit $done
      """;

  /**
   * Run as the dev user: ends every process that holds open the temporary of number {@code $2} and
   * name {@code $3} in the directory {@code $1}, then removes it. Ending the host's {@code incus
   * exec} does not end the copy inside the workspace, and a copy stopped there would hold the file
   * for ever. A process is matched only when one of its descriptors is this temporary itself, by
   * its whole path, or that path once removed ({@code (deleted)}): never a file whose name merely
   * begins with it, such as another upload's temporary. Only the dev user's processes can open it,
   * in the dev user's own {@code 0700} directory, and the file is thrown away, so what holds it is
   * the copy or a process of the person reading a file that is going. The directory may be gone
   * already, the session's removal having come first: the path is matched all the same. Five tries,
   * a fifth of a second apart; a process still holding it then (one in an uninterruptible wait on a
   * stalled disk) is left, and the script exits {@link #HELD}.
   */
  static final String ABORT =
      """
      dir=$(cd "$1" 2>/dev/null && pwd -P) || dir=$1
      part="$dir/.$2-$3.part"
      try=0
      while held=$(find /proc/[0-9]*/fd \\( -lname "$part" -o -lname "$part (deleted)" \\) -printf "%h\\n" 2>/dev/null | cut -d/ -f3); [ -n "$held" ]; do
        [ "$try" -lt 5 ] || { rm -f "$part"; exit 3; }
        kill -KILL $held 2>/dev/null
        try=$((try + 1))
        sleep 0.2
      done
      rm -f "$part"
      """;

  /**
   * How the scripts reach a container, and whose ids they use: production's runs them by {@code
   * incus exec} as root ({@code asRoot}) or the dev user ({@code asDev}), in {@link #ROOT}, root's
   * and the dev user's ids, dropping to the dev user with {@code setpriv}, each script given up
   * after {@link #SCRIPT} ({@code patience}). A test runs them here.
   */
  record Place(
      Function<String, List<String>> asRoot,
      Function<String, List<String>> asDev,
      String root,
      String rootUid,
      String devUid,
      String devGid,
      List<String> drop,
      Duration patience) {

    static final Place CONTAINERS =
        new Place(
            container -> ContainerExec.asRoot(container, List.of()),
            container -> ContainerExec.asDevUser(container, "/", Map.of(), List.of()),
            ROOT,
            "0",
            ContainerExec.DEV_UID,
            ContainerExec.DEV_GID,
            List.of(
                "setpriv",
                "--reuid=" + ContainerExec.DEV_UID,
                "--regid=" + ContainerExec.DEV_GID,
                "--clear-groups"),
            SCRIPT);
  }

  /** Starts a command, its input to write and its output to read. */
  @FunctionalInterface
  interface Processes {
    Process start(List<String> command) throws IOException;
  }

  /** The status a script's run is given when it does not end within the place's patience. */
  static final int UNANSWERED = -1;

  /**
   * Why a script that ended with {@code status} failed, for a log line: the status and the fixed
   * reason it stands for, never what the script said, so no path or name reaches a log.
   */
  static String reason(int status) {
    return switch (status) {
      case UNANSWERED -> "no exit: it did not end within its patience";
      case UNSAFE -> "exit " + UNSAFE + ": the uploads root is not root's own directory";
      case HELD -> "exit " + HELD + ": a process still holds the temporary";
      default -> "exit " + status;
    };
  }

  private final Processes processes;
  private final Place place;

  UploadCopy(Processes processes, Place place) {
    this.processes = processes;
    this.place = place;
  }

  /** Copies into the containers by {@code incus exec}, what the scripts say on stderr dropped. */
  static UploadCopy intoContainers() {
    return new UploadCopy(
        command ->
            new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start(),
        Place.CONTAINERS);
  }

  Place place() {
    return place;
  }

  /** The refusal a script's exit {@code status} says; any status it does not name is a failure. */
  static PutRefusal refusalOf(int status) {
    return switch (status) {
      case TOO_MANY -> PutRefusal.TOO_MANY;
      case FULL -> PutRefusal.SESSION_FULL;
      case NO_SPACE -> PutRefusal.NO_SPACE;
      default -> PutRefusal.FAILED;
    };
  }

  /**
   * Opens a copy of {@code size} bytes named {@code name} into the directory of {@code instanceId}
   * in {@code container}: numbered once the container holds its temporary, or unnumbered when the
   * container refused it ({@link Copy#refusal}) or did not answer within the place's patience;
   * {@code started} hears its process as soon as it runs, so it can be ended while it opens.
   */
  Copy open(String container, String instanceId, String name, long size, Consumer<Process> started)
      throws IOException {
    var command = new ArrayList<>(place.asRoot().apply(container));
    command.addAll(
        List.of(
            "sh",
            "-c",
            OPEN,
            "sail-open",
            place.root(),
            instanceId,
            name,
            Long.toString(size),
            Integer.toString(UploadAllowance.FILES),
            Long.toString(UploadAllowance.SESSION),
            place.rootUid(),
            place.devUid(),
            place.devGid()));
    command.addAll(place.drop());
    var process = processes.start(command);
    started.accept(process);
    var unanswered =
        CompletableFuture.runAsync(
            process::destroyForcibly,
            CompletableFuture.delayedExecutor(place.patience().toMillis(), TimeUnit.MILLISECONDS));
    var output =
        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    var line = output.readLine();
    unanswered.cancel(false);
    return new Copy(container, place.root() + "/" + instanceId, name, size, process, output, line);
  }

  /** One copy into a container: the process whose input takes its bytes, and its number. */
  final class Copy {
    private final String container;
    private final String directory;
    private final String name;
    private final long size;
    private final Process process;
    private final BufferedReader output;
    private final String first;
    private final int number;

    private Copy(
        String container,
        String directory,
        String name,
        long size,
        Process process,
        BufferedReader output,
        String first) {
      this.container = container;
      this.directory = directory;
      this.name = name;
      this.size = size;
      this.process = process;
      this.output = output;
      this.first = first;
      this.number = first != null && first.matches("[0-9]{1,9}") ? Integer.parseInt(first) : 0;
    }

    /**
     * Whether the container holds the copy's temporary: false when it refused or never answered.
     */
    boolean opened() {
      return number > 0;
    }

    /** Where the copy is, once it is committed. */
    String path() {
      return directory + "/" + number + "-" + name;
    }

    /** Writes {@code bytes} to the temporary, failing when the copy has ended. */
    void write(byte[] bytes) throws IOException {
      OutputStream in = process.getOutputStream();
      in.write(bytes);
      in.flush();
    }

    /** What the copy's script refused, once it has ended. */
    PutRefusal refusal() {
      return refusalOf(finish(process));
    }

    /**
     * The bytes free on the workspace's disk as the script said when it refused {@link
     * PutRefusal#NO_SPACE}, once it has ended; 0 when it said none.
     */
    long free() {
      var said = first;
      if (opened()) {
        try {
          said = output.readLine();
        } catch (IOException e) {
          said = null;
        }
      }
      return said != null && said.matches("free [0-9]{1,18}")
          ? Long.parseLong(said.substring(5))
          : 0;
    }

    /** Ends the copy: its input closed, its script's status once it exits. */
    int close() {
      try {
        process.getOutputStream().close();
      } catch (IOException e) {
        var unused = e;
      }
      return finish(process);
    }

    /**
     * Gives the temporary its name ({@link #COMMIT}): the script's status, 0 when it is stored, a
     * failure logged with its {@link #reason}.
     */
    int commit() {
      var status =
          run(
              container,
              place.asDev(),
              COMMIT,
              directory,
              Integer.toString(number),
              name,
              Long.toString(size));
      if (status != 0) {
        PtySessionHost.log("upload-commit-failed", "-", instanceId(), reason(status));
      }
      return status;
    }

    private String instanceId() {
      return directory.substring(directory.lastIndexOf('/') + 1);
    }

    /**
     * Ends what holds the temporary and removes it ({@link #ABORT}), when the container made one:
     * the script's status, {@link #HELD} logged.
     */
    int abort() {
      if (!opened()) {
        return 0;
      }
      var status = run(container, place.asDev(), ABORT, directory, Integer.toString(number), name);
      if (status == HELD) {
        PtySessionHost.log(
            "upload-abort-held", "-", instanceId(), reason(status) + " after 5 tries");
      }
      return status;
    }
  }

  /**
   * Runs {@code script} in {@code container} as {@code as} gives: its exit status, 1 if it could
   * not start, {@link #UNANSWERED} if it did not end within the place's patience. Nothing of its
   * output is read, so a script that never ends holds its caller no longer than the patience.
   */
  int run(String container, Function<String, List<String>> as, String script, String... args) {
    var command = new ArrayList<>(as.apply(container));
    command.addAll(List.of("sh", "-c", script, "sail-upload"));
    command.addAll(List.of(args));
    try {
      var process = processes.start(command);
      process.getOutputStream().close();
      try {
        return finish(process);
      } finally {
        process.getInputStream().close();
      }
    } catch (IOException e) {
      return 1;
    }
  }

  /**
   * {@code process}'s exit status, waiting at most the place's patience; {@link #UNANSWERED} when
   * it hangs.
   */
  private int finish(Process process) {
    try {
      if (process.waitFor(place.patience().toMillis(), TimeUnit.MILLISECONDS)) {
        return process.exitValue();
      }
      process.destroyForcibly();
      return UNANSWERED;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      return UNANSWERED;
    }
  }
}
