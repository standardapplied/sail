/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * The pty event lane's drop meter: a small JSON file beside the pty socket, bumped by the session
 * host each time an event row could not be persisted and read back by {@code sail session ls
 * --json}. The file is measurement, never enforcement — every operation here swallows its own
 * failures, because the meter must not introduce the failure mode it exists to observe. A missing
 * or corrupt file reads as zero drops.
 */
final class PtyEventDrops {

  static final String FILE_NAME = "pty-events.drops";

  record Drops(long count, String lastType, String lastCause, String lastAt) {
    static final Drops NONE = new Drops(0, null, null, null);
  }

  private PtyEventDrops() {}

  static Path fileOf(Path socket) {
    return socket.resolveSibling(FILE_NAME);
  }

  static void record(Path file, String type, String cause) {
    try {
      var map = new LinkedHashMap<String, Object>();
      map.put("count", read(file).count() + 1);
      map.put("last_type", type);
      map.put("last_cause", cause);
      map.put("last_at", DateTimeUtils.now().toString());
      Files.writeString(file, YamlUtil.dumpJson(map));
    } catch (IOException | RuntimeException swallowed) {
      var unused = swallowed;
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
