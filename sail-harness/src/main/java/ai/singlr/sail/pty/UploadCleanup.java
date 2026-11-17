/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Uploads leaving containers: a session's directory when it ends ({@link #remove}), and every
 * directory of a session no longer live at the next session made in a container ({@link #sweep}),
 * each as root on a thread of its own, never under the host's lock. No delete follows a link: each
 * first checks the uploads root is a directory, not a link, and root's, and {@code rm -r} removes a
 * link as itself, walking by descriptor. The dev user cannot write the root or replace its own
 * directory, so nothing in the container but root can race a delete. Each container has one lock
 * ({@link #lockOf}), which the sweep holds and a session's first upload takes too.
 */
final class UploadCleanup {

  /**
   * Run as root: with {@code $1} a directory, no link, owned by {@code $3}, removes {@code $2}
   * under it, a link there or inside it removed as the link, never followed: {@code rm -r} walks by
   * descriptor without following links. A root that is anything else is refused, not touched.
   */
  static final String REMOVE =
      """
      [ -e "$1" ] || [ -L "$1" ] || exit 0
      [ -d "$1" ] && [ ! -L "$1" ] && [ "$(stat -c %u "$1")" = "$3" ] || exit 10
      rm -rf --one-file-system -- "$1/$2"
      """;

  /**
   * Run as root: with {@code $1} root's own and no link, removes every directory under it named as
   * an instance id that is not among the ids after {@code $2}, the root's owner.
   */
  static final String SWEEP =
      """
      root=$1 rootuid=$2
      shift 2
      [ -e "$root" ] || [ -L "$root" ] || exit 0
      [ -d "$root" ] && [ ! -L "$root" ] && [ "$(stat -c %u "$root")" = "$rootuid" ] || exit 10
      for d in "$root"/*; do
        [ -d "$d" ] || continue
        id=${d##*/}
        case $id in *[!0-9a-f-]*) continue ;; esac
        keep=
        for live in "$@"; do [ "$id" = "$live" ] && keep=1; done
        [ -n "$keep" ] || rm -rf --one-file-system -- "$d"
      done
      """;

  private final UploadCopy copy;
  private final Map<String, ReentrantLock> containers = new ConcurrentHashMap<>();

  UploadCleanup(UploadCopy copy) {
    this.copy = copy;
  }

  /** The lock {@code container}'s sweep holds, which a session's first upload into it takes. */
  ReentrantLock lockOf(String container) {
    return containers.computeIfAbsent(container, name -> new ReentrantLock());
  }

  /**
   * Removes the uploads of the session of {@code instanceId} from {@code container}, on a thread of
   * its own holding {@code session} (its session's lock, null when it has none), so a commit that
   * holds it lands before. A removal that fails is logged; the next session's sweep takes it.
   */
  void remove(String container, String instanceId, ReentrantLock session) {
    Thread.ofVirtual()
        .name("pty-upload-remove")
        .start(
            () -> {
              if (session != null) {
                session.lock();
              }
              try {
                var root = copy.place();
                if (copy.run(
                        container, root.asRoot(), REMOVE, root.root(), instanceId, root.rootUid())
                    != 0) {
                  PtySessionHost.log(
                      "upload-cleanup-failed", "-", instanceId, "swept at the next session");
                }
              } finally {
                if (session != null) {
                  session.unlock();
                }
              }
            });
  }

  /**
   * Removes, on a thread of its own and under {@code container}'s lock, the uploads there of every
   * session not among those live as it runs ({@code live}, asked under the lock): what a session
   * that ended while its container was stopped left. A session's directory is made only by its
   * first upload, which needs the session live and takes the same lock, so the sweep never removes
   * what a live session made or is making.
   */
  void sweep(String container, Supplier<Collection<String>> live) {
    Thread.ofVirtual()
        .name("pty-upload-sweep")
        .start(
            () -> {
              var lock = lockOf(container);
              lock.lock();
              try {
                var root = copy.place();
                var args = new ArrayList<>(List.of(root.root(), root.rootUid()));
                args.addAll(live.get());
                if (copy.run(container, root.asRoot(), SWEEP, args.toArray(String[]::new)) != 0) {
                  PtySessionHost.log(
                      "upload-sweep-failed", "-", container, "kept until the next session");
                }
              } finally {
                lock.unlock();
              }
            });
  }
}
