/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * The pty event lane's drop meter: a small JSON file beside the pty socket, bumped by the session
 * host each time an event row could not be persisted and read back by {@code sail session ls
 * --json}. Writes serialize on one lock and publish by atomic rename, so concurrent failures all
 * count and a reader never parses a half-written file as fewer drops than were recorded. The file
 * is measurement, never enforcement — every operation here swallows its own failures, because the
 * meter must not introduce the failure mode it exists to observe. A missing or corrupt file reads
 * as zero drops.
 */
final class PtyEventDrops {

  static final String FILE_NAME = "pty-events.drops";

  record Drops(long count, String lastType, String lastCause, String lastAt) {
    static final Drops NONE = new Drops(0, null, null, null);
  }

  /**
   * One writer at a time: the meter's read-bump-write must not lose concurrent drops (losing drops
   * is the one thing a drop meter cannot do), and the host is the file's only writer.
   */
  private static final Object WRITE = new Object();

  private PtyEventDrops() {}

  static Path fileOf(Path socket) {
    return socket.resolveSibling(FILE_NAME);
  }

  static void record(Path file, String type, String cause) {
    synchronized (WRITE) {
      try {
        var map = new LinkedHashMap<String, Object>();
        map.put("count", read(file).count() + 1);
        map.put("last_type", type);
        map.put("last_cause", cause);
        map.put("last_at", DateTimeUtils.now().toString());
        var tmp = file.resolveSibling(FILE_NAME + ".tmp");
        Files.writeString(tmp, YamlUtil.dumpJson(map));
        try {
          Files.move(
              tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException plainMove) {
          Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
      } catch (IOException | RuntimeException swallowed) {
        var unused = swallowed;
      }
    }
  }

  static Drops read(Path file) {
    try {
      var map = YamlUtil.parseMap(Files.readString(file));
      return new Drops(
          ((Number) map.getOrDefault("count", 0L)).longValue(),
          Objects.toString(map.get("last_type"), null),
          Objects.toString(map.get("last_cause"), null),
          Objects.toString(map.get("last_at"), null));
    } catch (IOException | RuntimeException absentOrCorrupt) {
      return Drops.NONE;
    }
  }
}
