/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * A line Claude Code 2.1.332 wrote, or was written, in the S0 probes of 2026-11-27, cut whole from
 * its recording: {@code claude-chat/<recording>.<line>.json} is line {@code <line>} of {@code
 * probe/claude/<recording>.jsonl} (the first probe), {@code probe2/claude/<recording>.jsonl} (the
 * second: always rules behind wrappers, the bypass mode, read-deny paths, a subagent's request and
 * Edits freely), {@code probe3/claude/<recording>.jsonl} (the third: Asks first launched as {@code
 * manual}, and the refusals of the room lane's read-deny rules) or {@code
 * probe4/claude/<recording>.jsonl} (the fourth: what a read-deny rule stops, technique by
 * technique), its {@code msg} as it went over the pipe, or for a recording's first line the {@code
 * argv} it ran and the probe's {@code note}.
 */
public final class Recorded {

  private Recorded() {}

  /** Line {@code number} of {@code recording}, as the pipe carried it. */
  public static String line(String recording, int number) {
    var name = "/ai/singlr/sail/engine/claude-chat/" + recording + "." + number + ".json";
    try (var in = Objects.requireNonNull(Recorded.class.getResourceAsStream(name), name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
    } catch (IOException unread) {
      throw new UncheckedIOException(unread);
    }
  }

  /** Line {@code number} of {@code recording}, parsed. */
  public static Map<String, Object> parsed(String recording, int number) {
    return parsed(line(recording, number));
  }

  /** {@code line} parsed as the reader parses one. */
  public static Map<String, Object> parsed(String line) {
    return YamlUtil.parseJsonLine(line, LiveStream.MAX_LINE_CHARS);
  }
}
