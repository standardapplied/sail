/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import picocli.CommandLine.Help.Ansi;

/**
 * Renders bordered CLI tables with dynamically computed column widths. Columns auto-size to fit the
 * widest cell in each column (including headers), ensuring aligned borders regardless of content
 * length; when the terminal is narrower, the widest columns give way and their cells end in "…".
 */
final class TableFormatter {

  private static final int MIN_WIDTH = 56;
  private static final int COL_GAP = 2;

  private final String title;
  private final List<String> headers;
  private final List<List<String>> rows = new ArrayList<>();

  TableFormatter(String title, List<String> headers) {
    this.title = title;
    this.headers = headers;
  }

  void addRow(String... cells) {
    rows.add(List.of(cells));
  }

  void render(PrintStream out, Ansi ansi) {
    var widths = new int[headers.size()];
    for (var i = 0; i < widths.length; i++) {
      widths[i] = Strings.columns(headers.get(i));
    }
    for (var row : rows) {
      for (var i = 0; i < widths.length && i < row.size(); i++) {
        widths[i] = Math.max(widths[i], Strings.columns(row.get(i)));
      }
    }
    var w = Banner.boxWidth(Math.max(MIN_WIDTH, contentWidth(widths) + 1));
    narrow(widths, w - 1);

    out.println();
    Banner.topBorder(out, ansi, w, title);
    Banner.emptyRow(out, ansi, w);

    var header = Strings.fit(formatRow(headers, widths), w - 2);
    Banner.boxLine(out, ansi, w, "  @|bold " + header + "|@", 2 + Strings.columns(header));

    var sep = "\u2500".repeat(Math.max(0, w - 4));
    Banner.boxLine(out, ansi, w, "  @|faint " + sep + "|@  ", w);

    for (var row : rows) {
      var line = Strings.fit(formatRow(row, widths), w - 2);
      Banner.boxLine(out, ansi, w, "  " + line, 2 + Strings.columns(line));
    }

    Banner.emptyRow(out, ansi, w);
    Banner.bottomBorder(out, ansi, w);
    out.println();
  }

  private static int contentWidth(int[] widths) {
    return 2 + Arrays.stream(widths).sum() + COL_GAP * (widths.length - 1);
  }

  /** Takes a column at a time from the widest column until the row fits {@code room}. */
  private static void narrow(int[] widths, int room) {
    while (contentWidth(widths) > room) {
      var widest = 0;
      for (var i = 1; i < widths.length; i++) {
        if (widths[i] > widths[widest]) {
          widest = i;
        }
      }
      if (widths[widest] <= 1) {
        return;
      }
      widths[widest]--;
    }
  }

  private String formatRow(List<String> cells, int[] widths) {
    var sb = new StringBuilder();
    for (var i = 0; i < widths.length; i++) {
      var cell = Strings.fit(i < cells.size() ? cells.get(i) : "", widths[i]);
      sb.append(cell);
      if (i < widths.length - 1) {
        sb.append(" ".repeat(widths[i] - Strings.columns(cell) + COL_GAP));
      }
    }
    return sb.toString();
  }
}
