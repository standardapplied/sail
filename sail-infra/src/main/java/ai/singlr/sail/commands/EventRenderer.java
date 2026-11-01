/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.common.DateTimeUtils;
import picocli.CommandLine.Help.Ansi;

/** Pure formatting of an {@link Event} for human-readable terminal output. */
final class EventRenderer {

  private EventRenderer() {}

  /**
   * One-line human-friendly representation: {@code <ts> <project>[/<spec>] <type> by <agent> on
   * <host> [pid=N] [data=...]}. Uses picocli {@link Ansi} for styling.
   */
  static String human(Event event) {
    var spec = event.spec() == null ? "" : "/" + event.spec();
    var pid = event.data().get("pid");
    var pidPart = pid == null ? "" : " pid=" + pid;
    var data = event.data().isEmpty() ? "" : "  " + event.data();
    return Ansi.AUTO.string(
        "@|faint "
            + DateTimeUtils.format(event.ts())
            + "|@  @|bold "
            + event.project()
            + spec
            + "|@  "
            + event.type()
            + " @|faint by "
            + event.agent()
            + pidPart
            + " on "
            + event.host()
            + "|@"
            + data);
  }
}
