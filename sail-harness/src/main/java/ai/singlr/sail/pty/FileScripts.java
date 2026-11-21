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

  /** A save over a file larger than a whole read may be, which never has a whole read's digest. */
  static final int TOO_LARGE = 49;

  /** The changes' and a diff's exit when the workspace has no {@code git}. */
  static final int NO_GIT = 60;

  /** The changes' exit when the workspace holds no repository, and a diff's for a path in none. */
  static final int NO_REPOSITORY = 61;

  /** A diff's exit when the change is binary, which git does not show as text. */
  static final int BINARY = 62;

  /** A diff's exit when the path passes through a filter the configuration defines. */
  static final int FILTERED = 63;

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
   * Saves {@code $2} bytes of standard input as the path, read with its base and the {@code stat}
   * version the client read at: made anew where no name is when the base is {@code absent}, else
   * written over the file the path resolves to, which must be a file the dev user may write, of at
   * most {@code $3} bytes. The base is the SHA-256 of the whole file as the client read it. A
   * pre-check before any byte can only refuse: when the file's {@code stat} is not the one read at,
   * the file is hashed, and a digest other than the base is a conflict answered at once. It makes
   * its temporary in the target's folder ({@link #TEMPORARY}), says {@code ready}, copies the bytes
   * into it, syncs them and holds it open, says {@code copied} and their digest, then waits for the
   * host's {@code go}: anything else, or fewer bytes, writes nothing. On {@code go}, under the
   * host's lock, it hashes the whole target and compares that digest with the base (printing the
   * present digest, or absent, for a conflict), refuses a file the dev user may not write, keeps
   * its mode, and renames over it in one call, or links the new name in one call that fails when
   * the name was taken meanwhile; then syncs the folder and prints the version the file has now.
   * Every digest is a SHA-256 in lowercase hex, or nothing is written. A full disk prints the bytes
   * free.
   */
  static final String WRITE =
      RESOLVE
          + """
          size=$2 cap=$3
          IFS= read -r -d '' path || exit 1
          IFS= read -r -d '' base || exit 1
          IFS= read -r -d '' seen || exit 1
          content_digest() {
            local found
            found=$(sha256sum < "$1" 2>/dev/null | cut -c1-64)
            [[ $found =~ ^[0-9a-f]{64}$ ]] || return 1
            printf '%%s' "$found"
          }
          if [ "$base" = absent ]; then
            resolve_parent "$path"
            there "$target" && exit 40
            at=$target
          else
            [[ $base =~ ^[0-9a-f]{64}$ ]] || exit 48
            resolve_parent "$path"
            [ -e "$target" ] || { printf 'absent\\0'; exit 41; }
            resolve "$path"
            [ -d "$at" ] && exit 23
            [ -f "$at" ] || exit 25
            [ -w "$at" ] || exit 21
            [ "$(stat -L -c %%s -- "$at")" -le "$cap" ] || exit 49
            dir=${at%%/*}
            now=$(stat -L --printf '%1$s' -- "$at") || exit 1
            if [ "$now" != "$seen" ]; then
              found=$(content_digest "$at") || exit 48
              [ "$found" = "$base" ] || { printf '%%s\\0' "$found"; exit 41; }
            fi
          fi
          """
              .formatted(VERSION)
          + TEMPORARY
          + """
          printf 'ready\\0%%s\\0' "$tmp"
          if ! head -c "$size" > "$tmp" 2>/dev/null; then
            free=$(df -B1 --output=avail -- "$dir" 2>/dev/null | tail -n 1 | tr -d ' ')
            printf '%%s\\0' "${free:-0}"
            exit 42
          fi
          [ "$(stat -c %%s -- "$tmp")" = "$size" ] || exit 43
          sync -- "$tmp" 2>/dev/null || exit 1
          exec 5< "$tmp"
          copied=$(content_digest "$tmp") || exit 48
          printf 'copied\\0%%s\\0' "$copied"
          IFS= read -r -d '' word && [ "$word" = go ] || exit 43
          if [ "$base" = absent ]; then
            chmod -- "$(printf %%03o $((0666 & ~0$(umask))))" "$tmp" || { [ -d "$dir" ] || exit 46; exit 1; }
            ln -T -- "$tmp" "$at" 2>/dev/null || { there "$at" && exit 40; [ -d "$dir" ] || exit 46; exit 1; }
            rm -f -- "$tmp"
          else
            hashed=$(stat -L --printf '%1$s' -- "$at" 2>/dev/null) || { printf 'absent\\0'; exit 41; }
            found=$(content_digest "$at") || exit 48
            [ "$found" = "$base" ] || { printf '%%s\\0' "$found"; exit 41; }
            [ -w "$at" ] || exit 21
            chmod --reference="$at" -- "$tmp" || exit 1
            if [ "$(stat -L --printf '%1$s' -- "$at" 2>/dev/null)" != "$hashed" ]; then
              found=$(content_digest "$at") || { printf 'absent\\0'; exit 41; }
              printf '%%s\\0' "$found"
              exit 41
            fi
            mv -fT -- "$tmp" "$at" || exit 1
          fi
          sync -- "$dir" 2>/dev/null
          stat -L --printf '%1$s\\0' -- "$at" 2>/dev/null || printf 'absent\\0'
          """
              .formatted(VERSION);

  /**
   * Whether a save's temporary, the path read on standard input, is still held open by a process in
   * the workspace: {@code 50} when one holds it, {@code 0} when none does or it is gone, since a
   * removed file's descriptor reads as {@code <path> (deleted)}, which never matches. A save holds
   * its temporary open from its copy to its rename, so a save whose process outlived its kill (one
   * a hung disk holds) is seen by this, however long it lasts.
   */
  static final String HELD =
      """
      export LC_ALL=C
      IFS= read -r -d '' held || exit 1
      pattern=$(printf '%s' "$held" | sed 's/[][*?\\\\]/\\\\&/g')
      [ -n "$(find /proc/[0-9]*/fd -lname "$pattern" 2>/dev/null | head -n 1)" ] && exit 50
      exit 0
      """;

  /**
   * The one way the scripts run git ({@code safe_git}, after {@code git_in} names the folder), so
   * that looking at a repository runs none of its code. Every {@code GIT_*} variable inherited is
   * dropped, and the git started is told, through {@code GIT_CONFIG_COUNT}, which wins over every
   * config file, an included one too, and is inherited by any git it starts: no fsmonitor (its hook
   * or its daemon); no transport at all ({@code protocol.allow=never}), so a lazy fetch runs no
   * upload-pack, ssh command or credential helper; and every filter the configuration of that
   * folder defines (its own, included, global) has an empty clean, smudge and process, which is no
   * filter. Optional locks are off, so {@code status} writes no index and fires no hook; trace2 is
   * off; pathspecs are literal. A diff driver's command and text conversion, and an external diff,
   * are off by {@code --no-ext-diff --no-textconv} on each diff. Nothing else the configuration can
   * name runs for the calls the scripts make: no pager without a terminal, no editor, no
   * maintenance, and a submodule is a folder no diff takes. {@code FILTERS} keeps the names of the
   * filters defined, for a diff to say a path passes through one.
   */
  static final String GIT =
      """
      for name in $(compgen -e); do case $name in GIT_*) unset "$name" ;; esac; done
      export GIT_OPTIONAL_LOCKS=0 GIT_LITERAL_PATHSPECS=1
      export GIT_TRACE2=0 GIT_TRACE2_EVENT=0 GIT_TRACE2_PERF=0
      SAFE_DIR=. FILTERS=()
      safe_config() {
        export "GIT_CONFIG_KEY_$GIT_CONFIG_COUNT=$1" "GIT_CONFIG_VALUE_$GIT_CONFIG_COUNT=$2"
        GIT_CONFIG_COUNT=$((GIT_CONFIG_COUNT + 1))
      }
      git_in() {
        local key name
        SAFE_DIR=$1 FILTERS=()
        for name in $(compgen -e); do case $name in GIT_CONFIG_KEY_*|GIT_CONFIG_VALUE_*) unset "$name" ;; esac; done
        export GIT_CONFIG_COUNT=0
        safe_config core.fsmonitor false
        safe_config protocol.allow never
        while IFS= read -r -d '' key; do
          name=${key#filter.} name=${name%.*}
          FILTERS+=("$name")
          safe_config "filter.$name.clean" ''
          safe_config "filter.$name.smudge" ''
          safe_config "filter.$name.process" ''
          safe_config "filter.$name.required" false
        done < <(command git -C "$SAFE_DIR" config --null --name-only --get-regexp '^filter\\..*\\.(clean|smudge|process)$' 2>/dev/null)
      }
      safe_git() { command git -C "$SAFE_DIR" "$@"; }
      """;

  /**
   * What differs from {@code HEAD} in each repository of the workspace: the workspace folder itself
   * and each folder directly under it whose own top {@code git} says it is, under the home. Each is
   * said as {@code repo} and its root, then git's own {@code status} in porcelain v2 with NUL ends,
   * untracked files listed one by one and ignored paths as git matched them, an ignored folder one
   * path git never walks, then {@code end}; or, for a repository git refuses (a corrupt one, one
   * another user owns), {@code failed} and the first line git said, and the next repository goes
   * on. Every field NUL-terminated, git run only as {@link #GIT} runs it, so it runs none of the
   * repository's code and never takes the index's lock. No git, or no repository, is its own exit.
   */
  static final String CHANGES =
      GIT
          + """
          export LC_ALL=C
          home=$1
          command -v git > /dev/null || exit 60
          found=''
          for dir in "$home/workspace" "$home"/workspace/*/; do
            dir=${dir%/}
            [ -d "$dir" ] || continue
            real=$(realpath -e -- "$dir" 2>/dev/null && echo x) || continue
            real=${real%?x}
            case $real/ in "$home"/*) ;; *) continue ;; esac
            git_in "$real"
            if top=$(safe_git rev-parse --show-toplevel 2>/dev/null && echo x); then
              top=${top%?x}
              [ "$top" = "$real" ] || continue
              found=1
              printf 'repo\\0%s\\0' "$top"
              exec 3>&1
              if why=$(safe_git status --porcelain=v2 -z --branch --untracked-files=all --ignored=matching --ignore-submodules=dirty 2>&1 1>&3 3>&-); then
                printf 'end\\0'
              else
                printf 'failed\\0%s\\0' "${why%%$'\\n'*}"
              fi
              exec 3>&-
            elif [ -e "$real/.git" ]; then
              found=1
              why=$(safe_git rev-parse --show-toplevel 2>&1 > /dev/null)
              printf 'repo\\0%s\\0failed\\0%s\\0' "$real" "${why%%$'\\n'*}"
            fi
          done
          [ -n "$found" ] || exit 61
          """;

  /**
   * The path's change against {@code HEAD} as a unified diff in one shape whatever the repository's
   * diff settings ({@code a/} and {@code b/}, paths from the top, no colour, no renames, three
   * lines of context), at most {@code $2} bytes and one more, so a longer one is seen: an untracked
   * file's against nothing, and against git's empty tree in a repository with no commit yet. The
   * repository is the one git finds from the nearest folder of the path that still exists, which
   * must resolve under the home, as its top must. A path that passes through a filter the
   * configuration defines (Git LFS and its like) is refused, since its sides would be the filtered
   * and the unfiltered bytes; so is a file larger than {@code $2} on either side, or a binary
   * change, each before any byte. Git runs only as {@link #GIT} runs it.
   */
  static final String DIFF =
      RESOLVE
          + GIT
          + """
          cap=$2
          IFS= read -r -d '' path || exit 1
          command -v git > /dev/null || exit 60
          folder=${path%/*} rest=${path##*/}
          until [ -d "$folder" ] || [ "$folder" = "$home" ]; do
            rest=${folder##*/}/$rest folder=${folder%/*}
          done
          resolve "$folder"
          cd -- "$at" 2>/dev/null || exit 21
          git_in .
          top=$(safe_git rev-parse --show-toplevel 2>/dev/null && echo x) || exit 61
          top=${top%?x}
          case $top/ in "$home"/*) ;; *) exit 22 ;; esac
          [ -d "$rest" ] && [ ! -L "$rest" ] && exit 23
          { IFS= read -r -d '' _; IFS= read -r -d '' _; IFS= read -r -d '' filter; } < <(safe_git check-attr -z filter -- "$rest" 2>/dev/null)
          for name in "${FILTERS[@]}"; do [ "$name" = "$filter" ] && exit 63; done
          base=HEAD
          safe_git rev-parse -q --verify 'HEAD^{commit}' > /dev/null || base=$(safe_git hash-object -t tree /dev/null) || exit 1
          if { [ -e "$rest" ] || [ -L "$rest" ]; } && ! safe_git ls-files --error-unmatch -- "$rest" > /dev/null 2>&1; then
            set -- --no-index -- /dev/null "$rest"
          else
            [ "$(safe_git cat-file -s "$base:./$rest" 2>/dev/null || echo 0)" -le "$cap" ] || exit 49
            set -- "$base" -- "$rest"
          fi
          if [ -f "$rest" ] && [ ! -L "$rest" ] && [ "$(stat -c %s -- "$rest")" -gt "$cap" ]; then exit 49; fi
          counted=$(safe_git diff --no-ext-diff --no-textconv --numstat "$@")
          [ $? -le 1 ] || exit 1
          case $counted in -$'\\t'-$'\\t'*) exit 62 ;; esac
          safe_git diff --no-color --no-ext-diff --no-textconv --src-prefix=a/ --dst-prefix=b/ --no-relative --no-renames -U3 --inter-hunk-context=0 "$@" | head -c $((cap + 1))
          status=${PIPESTATUS[0]}
          [ "$status" -le 1 ] || [ "$status" -eq 141 ] || exit 1
          """;

  /**
   * Whether a save of an earlier host, or of this one before its workspace's lock was dropped, is
   * still stuck in the workspace: a process holding open a file of Sail's exact temporary shape
   * under the home ({@code $1}) whose last change is older than {@code $2} seconds, the save bound,
   * and which is none of the temporaries read on standard input, those of the saves this host runs.
   * Prints that temporary, NUL-terminated, and exits {@code 50}; else exits {@code 0}. One {@code
   * find} over every process's descriptors, as the sweep's.
   */
  static final String STUCK =
      """
      export LC_ALL=C
      home=$1 bound=$2 now=$(date +%%s)
      declare -A mine=()
      while IFS= read -r -d '' running; do mine["$running"]=1; done
      while IFS= read -r -d '' held; do
        case $held in "$home"/*) ;; *) continue ;; esac
        [ -z "${mine["$held"]+x}" ] || continue
        changed=$(stat -c %%Y -- "$held" 2>/dev/null) || continue
        [ $((now - changed)) -gt "$bound" ] || continue
        printf '%%s\\0' "$held"
        exit 50
      done < <(find /proc/[0-9]*/fd -lname '*/%1$s' -printf '%%l\\0' 2>/dev/null)
      exit 0
      """
          .formatted(TEMPORARY_SHAPE);

  /** {@link #HELD}'s answer when a process still holds the temporary open. */
  static final int STILL_HELD = 50;

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
      case TOO_LARGE -> FilesRefusal.TOO_LARGE;
      case NO_GIT -> FilesRefusal.NO_GIT;
      case NO_REPOSITORY -> FilesRefusal.NO_REPOSITORY;
      case BINARY -> FilesRefusal.BINARY;
      case FILTERED -> FilesRefusal.FILTERED;
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
     * Ends its {@code incus exec} now and waits for it to exit. Whether the process in the
     * workspace has ended too is a question for {@link #HELD}.
     */
    void kill() throws InterruptedException {
      process.destroyForcibly();
      process.waitFor();
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
