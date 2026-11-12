/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access.baseline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A directory of goldens answers to the snapshot's rules: every file that differs is named, with
 * the first line that does, a line's terminator is part of the line, and a recording leaves the
 * directory holding exactly what was written.
 */
class GoldenFilesTest {

  @TempDir Path dir;

  private static SortedMap<String, String> files(String... namesAndTexts) {
    var files = new TreeMap<String, String>();
    for (var i = 0; i < namesAndTexts.length; i += 2) {
      files.put(namesAndTexts[i], namesAndTexts[i + 1]);
    }
    return files;
  }

  @Test
  void aDirectoryThatHoldsWhatIsWrittenNowDiffersNowhere() {
    var files = files("a.json", "{\n}\n", "b.sse", "id: 1\n\n");

    assertEquals(List.of(), GoldenFiles.differences(files, files));
  }

  @Test
  void everyFileThatDiffersIsNamedWithItsFirstDifferingLine() {
    var recorded = files("a.json", "{\n  \"wake\": \"on\",\n  \"x\": 1\n}\n", "b.sse", "id: 1\n");
    var now = files("a.json", "{\n  \"woke\": \"on\",\n  \"x\": 2\n}\n", "c.sse", "id: 2\n");

    assertEquals(
        List.of(
            "a.json: 2 lines differ, the first is line "
                + Baseline.difference(2, "  \"wake\": \"on\",", "  \"woke\": \"on\","),
            "b.sse: 2 lines differ, the first is line "
                + Baseline.difference(1, "id: 1", "(absent)"),
            "c.sse: 2 lines differ, the first is line "
                + Baseline.difference(1, "(absent)", "id: 2")),
        GoldenFiles.differences(recorded, now));
  }

  @Test
  void aLineThatEndsOtherwiseIsADifferentLine() {
    assertEquals(
        List.of(
            "b.sse: 1 lines differ, the first is line "
                + Baseline.difference(1, "id: 1", "id: 1\r")),
        GoldenFiles.differences(files("b.sse", "id: 1\n"), files("b.sse", "id: 1\r\n")));
  }

  @Test
  void aRecordingLeavesTheDirectoryHoldingExactlyWhatWasWritten() {
    var goldens = dir.resolve("goldens");
    GoldenFiles.record(goldens, files("a.json", "{}\n", "stale.json", "{}\n"));
    var now = files("a.json", "{\"x\": 1}\n", "b.sse", "id: 1\r\n");

    GoldenFiles.record(goldens, now);

    assertEquals(now, GoldenFiles.read(goldens));
    assertEquals(files(), GoldenFiles.read(dir.resolve("never-recorded")));
  }
}
