/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.pty.PtyMessage.FileKind;
import ai.singlr.sail.pty.PtyMessage.FilesRefusal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The shell that reads a workspace's files, sent with each {@code incus exec} as the dev user
 * ({@link UploadCopy.Place#asDev}), so nothing is installed in the workspace: {@link #LIST} lists a
 * folder, {@link #STAT} says what a path is, {@link #READ} reads a file's bytes, and {@link #WATCH}
 * stats a watch set every 250 ms until its input closes. A path reaches a script on its standard
 * input, never as an argument; each script resolves it first and refuses one that leaves the home,
 * a link's target included, saying what it refused by its exit status alone. Every version is one
 * {@code stat -L} of the resolved file: device, inode, size, and modification and change times in
 * nanoseconds. Nothing here knows a frame or a connection.
 */
final class FileScripts {

  /**
   * The prefix of the temporaries a save puts beside the file it replaces, in its folder: no
   * listing shows them, and the watch, which stats only the paths a client shows, never names one.
   */
  static final String SAVING = ".sail-save-";

  /**
   * The exact name of a save's temporary, as a shell glob: the prefix, the ten characters {@code
   * mktemp} chose, {@code .part}. Only a name of this shape is hidden from a listing and the watch,
   * and only a regular file of it, the dev user's own, is ever swept: a person's {@code
   * .sail-save-notes} is seen and kept.
   */
  static final String TEMPORARY_SHAPE = SAVING + "[A-Za-z0-9]".repeat(10) + ".part";

  /** How long a cut write has to remove its temporary before its process is ended. */
  static final Duration GRACE = Duration.ofSeconds(2);

  /**
   * Exit statuses of the scripts that refuse, each one {@link FilesRefusal}: the reads' from 20,
   * the writes' own from 40.
   */
  static final int NOT_FOUND = 20;

  static final int NOT_PERMITTED = 21;
  static final int OUTSIDE_HOME = 22;
  static final int IS_FOLDER = 23;
  static final int NOT_A_FOLDER = 24;
  static final int NOT_A_FILE = 25;
  static final int LOOP = 26;
  static final int EXISTS = 40;

  /** A save's base is not the file's version now: the present one, or absent, was printed. */
  static final int CONFLICT = 41;

  /** The disk filled: the bytes free on it were printed. */
  static final int NO_SPACE = 42;

  /** A save's bytes came short, or the host said no go: nothing was written. */
  static final int UNWRITTEN = 43;

  /** A folder moved into itself or a folder it holds. */
  static final int INTO_ITSELF = 44;

  static final int PARTLY_REMOVED = 45;

  /** The folder a new name was to be made in went before it was made. */
  static final int FOLDER_GONE = 46;

  /** A move whose source and target folder are on different disks, refused before it starts. */
  static final int OTHER_DISK = 47;

  /**
   * A write's exit when a digest it computed is no SHA-256 (no {@code sha256sum}, or one that
   * failed): nothing is written, and two empty digests never compare equal.
   */
  static final int BAD_DIGEST = 48;

  /** The read's exit when the workspace has no {@code sha256sum} to prove what it read with. */
  static final int NO_SHA256 = 27;

  /** The read's exit when a pass's digest is no SHA-256: nothing proved, nothing answered whole. */
  static final int NO_DIGEST = 28;

  /** One version, as every script and the watch write it. */
  static final String VERSION = "%d:%i:%s:%.9Y:%.9Z";

  /**
   * The functions every script resolves with, the home {@code $1}: {@code resolve} resolves a path
   * as {@code at}, refusing one that does not exist, loops, crosses a folder the dev user may not
   * search, or resolves outside the home; {@code resolve_parent} resolves a path's folder so, as
   * {@code dir}, refusing one that is no folder, and names {@code target} in it by the path's last
   * name as given, never followed; {@code there} says whether a name exists, a dangling link
   * included. A trailing newline of a name survives the substitution.
   */
  static final String RESOLVE =
      """
      export LC_ALL=C
      home=$1
      resolve() {
        at=$(realpath -e -- "$1" 2>/dev/null && echo x) || {
          why=$(realpath -e -- "$1" 2>&1 >/dev/null)
          case $why in
            *"symbolic links"*) exit 26 ;;
            *"ermission denied"*) exit 21 ;;
          esac
          exit 20
        }
        at=${at%?x}
        case $at/ in "$home"/*) ;; *) exit 22 ;; esac
      }
      resolve_parent() {
        name=${1##*/}
        resolve "${1%/*}"
        [ -d "$at" ] || exit 24
        dir=$at target=$at/$name
      }
      there() { [ -e "$1" ] || [ -L "$1" ]; }
      """;

  /** {@link #RESOLVE}'s functions, and the path read and resolved as {@code at}. */
  static final String RESOLVED =
      RESOLVE
          + """
          IFS= read -r -d '' path || exit 1
          resolve "$path"
          """;

  /**
   * Lists the folder: its version, then each entry but {@code .}, {@code ..} and a write's
   * temporary as six NUL-terminated fields (kind, a link's target kind, size, mtime, mode, name).
   * An entry gone between its folder's read and its own stat is left out, never a failure.
   */
  static final String LIST =
      RESOLVED
          + """
          [ -d "$at" ] || exit 24
          cd -- "$at" 2>/dev/null && [ -r . ] || exit 21
          stat -L --printf '%s\\0' . || exit 1
          find . -mindepth 1 -maxdepth 1 ! -name '%s' -printf '%%y\\0%%Y\\0%%s\\0%%T@\\0%%m\\0%%P\\0' 2>/dev/null
          exit 0
          """
              .formatted(VERSION, TEMPORARY_SHAPE);

  /** Says what the path is: its version, then its own entry as a listing's, named by its base. */
  static final String STAT =
      RESOLVED
          + """
          stat -L --printf '%s\\0' -- "$at" || exit 1
          find "$path" -maxdepth 0 -printf '%%y\\0%%Y\\0%%s\\0%%T@\\0%%m\\0%%f\\0' || exit 1
          """
              .formatted(VERSION);

  /**
   * Reads at most {@code $3} bytes of the file from {@code $2}: its version and its size, each
   * NUL-terminated, then the bytes, sent on a descriptor duplicated from its output and never by a
   * path such as {@code /dev/fd/3}, which {@code incus exec}'s socket refuses to open again; then
   * the SHA-256 of the bytes it sent, the same range hashed again from the file after sending them,
   * and its version stat-ed again, each NUL-terminated but the last. A read whose two digests and
   * two versions agree sent the file as it was at one moment, whatever the filesystem's clock; any
   * other changed under it. A folder, and anything that is not a file (a pipe, whose read would
   * wait for a writer; a socket; a device), is refused; the bytes are read without waiting, so a
   * pipe swapped in after those checks ends the read at once rather than holding it.
   */
  static final String READ =
      RESOLVED
          + """
          [ -d "$at" ] && exit 23
          [ -f "$at" ] || exit 25
          [ -r "$at" ] || exit 21
          command -v sha256sum > /dev/null || exit 27
          before=$(stat -L --printf '%s' -- "$at") || exit 1
          size=${before#*:*:}
          printf '%%s\\0%%s\\0' "$before" "${size%%%%:*}"
          set -o pipefail
          exec 3>&1
          sent=$( { dd if="$at" iflag=skip_bytes,count_bytes,nonblock skip="$2" count="$3" bs=262144 status=none | tee >(sha256sum >&4) >&3; } 4>&1 ) || exit 1
          again=$(dd if="$at" iflag=skip_bytes,count_bytes,nonblock skip="$2" count="$3" bs=262144 status=none | sha256sum) || exit 1
          [[ ${sent%%%% *} =~ ^[0-9a-f]{64}$ && ${again%%%% *} =~ ^[0-9a-f]{64}$ ]] || exit 28
          after=$(stat -L --printf '%s' -- "$at") || exit 1
          printf '%%s\\0%%s\\0%%s' "${sent%%%% *}" "${again%%%% *}" "$after"
          """
              .formatted(VERSION, VERSION);

  /**
   * The watch loop: reads watch sets from standard input, each path then the version the client
   * holds of it, NUL-terminated, a set ended by an empty path, and replaces its set with each, a
   * save's temporary left out, and any path that resolves outside the home ({@code $1}) left out
   * and printed after a {@code -}, NUL-terminated, so the client is told. Every 250 ms it stats the
   * set once and prints each path whose version is not the one last held, NUL-terminated, then a
   * NUL; once a second it prints a NUL alone. It ends when its input closes.
   */
  static final String WATCH =
      """
      export LC_ALL=C
      home=$1
      declare -A held=() given=()
      paths=() next=() part='' want=path name=''
      last=${EPOCHREALTIME/./} beat=$last
      while :; do
        if IFS= read -r -d '' -t 0.25 token; then
          token=$part$token part=''
          if [ $want = version ]; then
            at=$(realpath -m -- "$name" 2>/dev/null && echo x)
            at=${at%%?x}
            case ${name##*/}:$at/ in
              %s:*) ;;
              *:"$home"/*) next+=("$name") given[$name]=$token ;;
              *) printf -- '-%%s\\0' "$name" ;;
            esac
            want=path
          elif [ -n "$token" ]; then
            name=$token want=version
          else
            paths=("${next[@]}") held=()
            for name in "${!given[@]}"; do held[$name]=${given[$name]}; done
            next=() given=()
          fi
        else
          [ $? -gt 128 ] || exit 0
          part=$part$token
        fi
        now=${EPOCHREALTIME/./}
        [ $((now - last)) -ge 250000 ] || continue
        last=$now
        declare -A seen=()
        if [ ${#paths[@]} -gt 0 ]; then
          while IFS= read -r -d '' name && IFS= read -r -d '' sig; do
            seen[$name]=$sig
          done < <(stat -L --printf '%%n\\0%s\\0' -- "${paths[@]}" 2>/dev/null)
        fi
        changed=''
        for name in "${paths[@]}"; do
          [ "${seen[$name]-}" = "${held[$name]-}" ] && continue
          printf '%%s\\0' "$name"
          held[$name]=${seen[$name]-}
          changed=1
        done
        [ -z "$changed" ] || printf '\\0'
        unset seen
        if [ $((now - beat)) -ge 1000000 ]; then
          printf '\\0'
          beat=$now
        fi
      done
      """
          .formatted(TEMPORARY_SHAPE, VERSION);

  /**
   * The temporary a write makes in folder {@code $dir} as {@code tmp}, removed at the script's
   * exit, after removing the folder's temporaries left more than two minutes ago that no process
   * holds open, a killed script's or a stopped workspace's.
   */
  private static final String TEMPORARY =
      """
      for old in "$dir"/%2$s; do
        [ -f "$old" ] && [ ! -L "$old" ] && [ -O "$old" ] || continue
        [ -n "$(find "$old" -maxdepth 0 -mmin +2 2>/dev/null)" ] || continue
        pattern=$(printf '%%s' "$old" | sed 's/[][*?\\\\]/\\\\&/g')
        [ -n "$(find /proc/[0-9]*/fd -lname "$pattern" 2>/dev/null | head -n 1)" ] || rm -f -- "$old"
      done
      tmp=$(mktemp --suffix=.part -- "$dir/%1$sXXXXXXXXXX" 2>/dev/null) || exit 21
      trap 'rm -f -- "$tmp"' EXIT
      """
          .formatted(SAVING, TEMPORARY_SHAPE);

  /**
   * Saves {@code $2} bytes of standard input as the path, read with its base: made anew where no
   * name is when the base is {@code absent}, else written over the file the path resolves to, which
   * must be a file. It makes its temporary in the target's folder ({@link #TEMPORARY}), says {@code
   * ready}, copies the bytes into it and syncs them, says {@code copied} and their SHA-256, then
   * waits for the host's {@code go}: anything else, or fewer bytes, writes nothing. On {@code go}
   * it compares the file's version with the base (printing the present version, or absent, for a
   * conflict), refuses a file the dev user may not write, keeps its mode, and renames over it in
   * one call, or links the new name in one call that fails when the name was taken meanwhile; then
   * prints the version the file has now. A full disk prints the bytes free.
   */
  static final String WRITE =
      RESOLVE
          + """
          size=$2
          IFS= read -r -d '' path || exit 1
          IFS= read -r -d '' base || exit 1
          if [ "$base" = absent ]; then
            resolve_parent "$path"
            there "$target" && exit 40
            at=$target
          else
            resolve_parent "$path"
            [ -e "$target" ] || { printf 'absent\\0'; exit 41; }
            resolve "$path"
            [ -d "$at" ] && exit 23
            [ -f "$at" ] || exit 25
            [ -w "$at" ] || exit 21
            dir=${at%%/*}
            now=$(stat -L --printf '%s' -- "$at") || exit 1
            [ "$now" = "$base" ] || { printf '%%s\\0' "$now"; exit 41; }
          fi
          """
              .formatted(VERSION)
          + TEMPORARY
          + """
          printf 'ready\\0'
          if ! head -c "$size" > "$tmp" 2>/dev/null; then
            free=$(df -B1 --output=avail -- "$dir" 2>/dev/null | tail -n 1 | tr -d ' ')
            printf '%%s\\0' "${free:-0}"
            exit 42
          fi
          [ "$(stat -c %%s -- "$tmp")" = "$size" ] || exit 43
          sync -- "$tmp" 2>/dev/null || exit 1
          exec 5< "$tmp"
          printf 'copied\\0%%s\\0' "$(sha256sum < "$tmp" | cut -c1-64)"
          IFS= read -r -d '' word && [ "$word" = go ] || exit 43
          if [ "$base" = absent ]; then
            chmod -- "$(printf %%03o $((0666 & ~0$(umask))))" "$tmp" || exit 1
            ln -T -- "$tmp" "$at" 2>/dev/null || { there "$at" && exit 40; [ -d "$dir" ] || exit 46; exit 1; }
            rm -f -- "$tmp"
          else
            now=$(stat -L --printf '%1$s' -- "$at" 2>/dev/null) || { printf 'absent\\0'; exit 41; }
            [ "$now" = "$base" ] || { printf '%%s\\0' "$now"; exit 41; }
            [ -w "$at" ] || exit 21
            chmod --reference="$at" -- "$tmp" || exit 1
            mv -fT -- "$tmp" "$at" || exit 1
          fi
          sync -- "$dir" 2>/dev/null
          stat -L --printf '%1$s\\0' -- "$at" 2>/dev/null || printf 'absent\\0'
          """
              .formatted(VERSION);

  /**
   * Makes the path anew, a folder when the kind read after it is {@code folder}, else an empty file
   * linked in one call that fails when the name was taken meanwhile; never over a name there.
   */
  static final String CREATE =
      RESOLVE
          + """
          IFS= read -r -d '' path || exit 1
          IFS= read -r -d '' kind || exit 1
          resolve_parent "$path"
          there "$target" && exit 40
          if [ "$kind" = folder ]; then
            mkdir -- "$target" 2>/dev/null && { sync -- "$dir" 2>/dev/null; exit 0; }
            there "$target" && exit 40
            [ -d "$dir" ] || exit 46
            exit 21
          fi
          """
          + TEMPORARY
          + """
          chmod -- "$(printf %03o $((0666 & ~0$(umask))))" "$tmp" || exit 1
          ln -T -- "$tmp" "$target" 2>/dev/null || { there "$target" && exit 40; [ -d "$dir" ] || exit 46; exit 1; }
          sync -- "$dir" 2>/dev/null
          """;

  /**
   * Moves the first path to the second, a link as itself, never into itself and never over a name
   * there: moved only when the second name is now the first's own inode and the first is gone. The
   * workspace's own folder stays where it is.
   */
  static final String MOVE =
      RESOLVE
          + """
          IFS= read -r -d '' from || exit 1
          IFS= read -r -d '' to || exit 1
          resolve_parent "$from"
          src=$target
          there "$src" || exit 20
          [ "$src" = "$home/workspace" ] && exit 21
          resolve_parent "$to"
          dst=$target
          case $dir/ in "$src"/*) exit 44 ;; esac
          there "$dst" && exit 40
          [ "$(stat -c %d -- "$src")" = "$(stat -c %d -- "$dir")" ] || exit 47
          inode=$(stat -c %d:%i -- "$src") || exit 1
          mv -nT -- "$src" "$dst" 2>/dev/null
          if [ "$(stat -c %d:%i -- "$dst" 2>/dev/null)" = "$inode" ] && ! there "$src"; then
            sync -- "$dir" "${src%/*}" 2>/dev/null
            exit 0
          fi
          there "$dst" && exit 40
          exit 1
          """;

  /**
   * Removes the path, a link as itself, a folder with all it holds on its own filesystem; the
   * workspace's own folder stays. Anything left is said.
   */
  static final String REMOVE =
      RESOLVE
          + """
          IFS= read -r -d '' path || exit 1
          resolve_parent "$path"
          there "$target" || exit 20
          [ "$target" = "$home/workspace" ] && exit 21
          rm -rf --one-file-system -- "$target" 2>/dev/null
          there "$target" && exit 45
          sync -- "$dir" 2>/dev/null
          exit 0
          """;

  private final UploadCopy.Processes processes;
  private final UploadCopy.Place place;
  private final String home;

  FileScripts(UploadCopy.Processes processes, UploadCopy.Place place, String home) {
    this.processes = processes;
    this.place = place;
    this.home = home;
  }

  /**
   * Reads the files of workspaces by {@code incus exec}, what the scripts say on stderr dropped.
   */
  static FileScripts ofContainers() {
    return new FileScripts(
        command ->
            new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start(),
        UploadCopy.Place.CONTAINERS,
        ContainerExec.DEV_HOME);
  }

  /** The home every path must resolve under. */
  String home() {
    return home;
  }

  /**
   * Whether {@code path} may be asked of a script at all: absolute, under the home or the home
   * itself, spelled plainly (no {@code .} or {@code ..} part, no empty one), with no NUL, which a
   * name cannot hold and the scripts end a path with. Where a link leads is the scripts' to check:
   * each resolves its path, the watch included.
   */
  boolean inHome(String path) {
    if (path.indexOf('\0') >= 0 || !(path.equals(home) || path.startsWith(home + "/"))) {
      return false;
    }
    for (var part : path.substring(home.length()).split("/", -1)) {
      if (part.equals(".") || part.equals("..")) {
        return false;
      }
    }
    return !path.endsWith("/") && !path.contains("//");
  }

  /**
   * Starts {@code script} in {@code container} as the dev user with {@code args} after the home,
   * and {@code path} then a NUL on its input, which stays open for {@link #WATCH}'s sets and is
   * closed for every other script. A script that has said nothing within the place's patience is
   * ended, as one in a frozen container would never answer.
   */
  Script start(String container, String script, String path, String... args) throws IOException {
    return start(
        container, script, path == null ? List.of() : List.of(path), script.equals(WATCH), args);
  }

  /**
   * Starts {@code script} as {@link #start(String, String, String, String...)} does, with {@code
   * fields} on its input, each then a NUL: closed after them unless {@code keepOpen}, as a save's
   * input stays open for its bytes and the host's go.
   */
  Script start(
      String container, String script, List<String> fields, boolean keepOpen, String... args)
      throws IOException {
    var command = new ArrayList<>(place.asDev().apply(container));
    command.addAll(List.of("bash", "-c", script, "sail-files", home));
    command.addAll(List.of(args));
    var process = processes.start(command);
    var running = new Script(process);
    for (var field : fields) {
      running.input().write(nul(field));
    }
    running.input().flush();
    if (!keepOpen) {
      running.input().close();
    }
    return running;
  }

  /** {@code path} as the scripts read it: its bytes, then a NUL. */
  static byte[] nul(String path) {
    var bytes = FileNames.encode(path);
    var ended = new byte[bytes.length + 1];
    System.arraycopy(bytes, 0, ended, 0, bytes.length);
    return ended;
  }

  /** The refusal a script's exit {@code status} says; null for one it does not name. */
  static FilesRefusal refusalOf(int status) {
    return switch (status) {
      case NOT_FOUND -> FilesRefusal.NOT_FOUND;
      case NOT_PERMITTED -> FilesRefusal.NOT_PERMITTED;
      case OUTSIDE_HOME -> FilesRefusal.OUTSIDE_HOME;
      case IS_FOLDER -> FilesRefusal.IS_FOLDER;
      case NOT_A_FOLDER -> FilesRefusal.NOT_A_FOLDER;
      case NOT_A_FILE -> FilesRefusal.NOT_A_FILE;
      case LOOP -> FilesRefusal.LOOP;
      case EXISTS -> FilesRefusal.EXISTS;
      case INTO_ITSELF -> FilesRefusal.UNSAFE;
      case PARTLY_REMOVED -> FilesRefusal.PARTLY_REMOVED;
      default -> null;
    };
  }

  /** One script under way in a container: its output read field by field, its end awaited. */
  final class Script {
    private final Process process;
    private final InputStream output;
    private final CompletableFuture<Void> answered = new CompletableFuture<>();

    private Script(Process process) {
      this.process = process;
      this.output = process.getInputStream();
      var unanswered =
          CompletableFuture.runAsync(
              process::destroyForcibly,
              CompletableFuture.delayedExecutor(
                  place.patience().toMillis(), TimeUnit.MILLISECONDS));
      answered.thenRun(() -> unanswered.cancel(false));
    }

    /** The script's input. */
    OutputStream input() {
      return process.getOutputStream();
    }

    /** Whether it still runs. */
    boolean alive() {
      return process.isAlive();
    }

    /**
     * Ends its process now and waits for it, then {@link #GRACE} more, as long as Incus takes to
     * end the process in the workspace once its {@code incus exec} has gone.
     */
    void killAndAwait() throws InterruptedException {
      process.destroyForcibly();
      process.waitFor();
      Thread.sleep(GRACE);
    }

    /** Runs {@code then} once it has ended, however it ended. */
    void onEnd(Runnable then) {
      process.onExit().thenRun(then);
    }

    /**
     * The next NUL-terminated field of its output, null at its end; its first field settles the
     * patience it was given.
     */
    String field() throws IOException {
      var bytes = new ByteArrayOutputStream();
      int next;
      while ((next = output.read()) > 0) {
        bytes.write(next);
      }
      answered.complete(null);
      return next < 0 && bytes.size() == 0 ? null : FileNames.decode(bytes.toByteArray());
    }

    /** Up to {@code count} bytes of its output, fewer only at its end. */
    byte[] bytes(int count) throws IOException {
      return output.readNBytes(count);
    }

    /** The next entry of a listing, null at its end. */
    PtyMessage.FileEntry entry() throws IOException {
      var kind = field();
      if (kind == null) {
        return null;
      }
      var target = field();
      var size = field();
      var mtime = field();
      var mode = field();
      var name = field();
      if (name == null) {
        throw new IOException("a listing ended inside an entry");
      }
      var isLink = kind.equals("l");
      return new PtyMessage.FileEntry(
          name,
          kindOf(kind),
          isLink ? targetOf(target) : FileKind.NONE,
          Long.parseLong(size),
          nanos(mtime),
          Integer.parseInt(mode, 8));
    }

    /** Its exit status, waiting at most the place's patience; -1 when it does not end by then. */
    int status() {
      try {
        if (process.waitFor(place.patience().toMillis(), TimeUnit.MILLISECONDS)) {
          return process.exitValue();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      process.destroyForcibly();
      return -1;
    }

    /** Ends it now: its input closed, then its process. */
    void end() {
      try {
        process.getOutputStream().close();
      } catch (IOException closed) {
        var unused = closed;
      }
      process.destroyForcibly();
    }

    /**
     * Ends a write: its input closed, so it reads fewer bytes than it was told, or no go, and
     * removes its own temporary; its process ended after {@link #GRACE} if it has not ended by
     * then.
     */
    void cut() {
      try {
        process.getOutputStream().close();
      } catch (IOException closed) {
        var unused = closed;
      }
      CompletableFuture.runAsync(
          process::destroyForcibly,
          CompletableFuture.delayedExecutor(GRACE.toMillis(), TimeUnit.MILLISECONDS));
    }
  }

  /** What {@code find}'s {@code %y} names. */
  static FileKind kindOf(String letter) {
    return switch (letter) {
      case "f" -> FileKind.FILE;
      case "d" -> FileKind.FOLDER;
      case "l" -> FileKind.LINK;
      default -> FileKind.OTHER;
    };
  }

  /** What a link leads to, as {@code find}'s {@code %Y} names it. */
  static FileKind targetOf(String letter) {
    return switch (letter) {
      case "N", "?" -> FileKind.MISSING;
      case "L" -> FileKind.LOOP;
      default -> kindOf(letter);
    };
  }

  /** {@code find}'s {@code %T@}, seconds with a fraction, in nanoseconds since the epoch. */
  static long nanos(String seconds) {
    var negative = seconds.startsWith("-");
    var dot = seconds.indexOf('.');
    var whole = Long.parseLong(dot < 0 ? seconds : seconds.substring(0, dot));
    var fraction = dot < 0 ? "" : seconds.substring(dot + 1);
    var nanos = Long.parseLong((fraction + "000000000").substring(0, 9));
    return whole * 1_000_000_000L + (negative ? -nanos : nanos);
  }
}
