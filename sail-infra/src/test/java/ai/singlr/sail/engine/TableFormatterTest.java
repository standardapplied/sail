/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.Help.Ansi;

class TableFormatterTest {

  @Test
  void bordersAlignWithShortNames() {
    var table = new TableFormatter(" Projects ", List.of("NAME", "STATUS", "IP", "CPU", "MEM"));
    table.addRow("acme", "Running", "10.0.0.42", "4", "12GB");
    table.addRow("fintech", "Stopped", "-", "2", "8GB");
    var output = render(table);

    assertBordersAligned(output);
    assertTrue(output.contains("acme"));
    assertTrue(output.contains("fintech"));
  }

  @Test
  void bordersAlignWithLongNames() {
    var table = new TableFormatter(" Projects ", List.of("NAME", "STATUS", "IP", "CPU", "MEM"));
    table.addRow("singular-website-blog", "Running", "10.145.172.48", "2", "4GB");
    table.addRow("manatee", "Running", "10.145.172.50", "4", "16GB");
    var output = render(table);

    assertBordersAligned(output);
    assertTrue(output.contains("singular-website-blog"));
    assertTrue(output.contains("manatee"));
  }

  @Test
  void bordersAlignWithVeryLongNames() {
    var table = new TableFormatter(" Test ", List.of("NAME", "VALUE"));
    table.addRow("a-very-long-name-that-exceeds-everything", "short");
    table.addRow("x", "y");
    var output = render(table);

    assertBordersAligned(output);
  }

  @Test
  void emptyTableShowsHeadersOnly() {
    var table = new TableFormatter(" Empty ", List.of("A", "B", "C"));
    var output = render(table);

    assertBordersAligned(output);
    assertTrue(output.contains("A"));
    assertTrue(output.contains("B"));
    assertTrue(output.contains("C"));
  }

  @Test
  void headerWiderThanData() {
    var table = new TableFormatter(" Test ", List.of("LONG_HEADER_NAME", "ANOTHER_LONG_ONE"));
    table.addRow("x", "y");
    var output = render(table);

    assertBordersAligned(output);
    assertTrue(output.contains("LONG_HEADER_NAME"));
  }

  @Test
  void dataWiderThanHeader() {
    var table = new TableFormatter(" Test ", List.of("N", "V"));
    table.addRow("this-is-a-very-long-cell-value", "another-very-long-cell-value");
    var output = render(table);

    assertBordersAligned(output);
  }

  @Test
  void minimumWidthEnforced() {
    var table = new TableFormatter(" T ", List.of("A", "B"));
    table.addRow("x", "y");
    var output = render(table);

    assertBordersAligned(output);
    var lines = output.split("\n");
    for (var line : lines) {
      if (line.contains("\u2500") && line.contains("\u256d")) {
        assertTrue(line.length() >= 56);
      }
    }
  }

  @Test
  void singleColumn() {
    var table = new TableFormatter(" Test ", List.of("ITEMS"));
    table.addRow("first");
    table.addRow("second");
    table.addRow("third-is-the-longest-one");
    var output = render(table);

    assertBordersAligned(output);
  }

  private static String render(TableFormatter table) {
    var baos = new ByteArrayOutputStream();
    table.render(new PrintStream(baos), Ansi.OFF);
    return baos.toString(StandardCharsets.UTF_8);
  }

  private static void assertBordersAligned(String output) {
    var lines = output.split("\n");
    for (var line : lines) {
      var pipeCount = line.chars().filter(c -> c == '\u2502').count();
      if (pipeCount > 0) {
        assertEquals(2, pipeCount, "Misaligned borders in: " + line);
      }
    }
  }
}
