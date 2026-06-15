/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class CheckboxPickerTest {

  private static final Path ROOT = Path.of("/home/dev/workspace");
  private static final FilePicker.Entry FILE =
      new FilePicker.Entry(ROOT.resolve("a.txt"), false, 12);
  private static final FilePicker.Entry DIR = new FilePicker.Entry(ROOT.resolve("src"), true, 0);

  private static CheckboxPicker.Screen screen() {
    return CheckboxPicker.Screen.of(ROOT, ROOT, List.of(FILE, DIR), new LinkedHashSet<>());
  }

  @Test
  void mapsArrowsSpaceAndConfirmCancelKeys() {
    assertEquals(CheckboxPicker.Key.UP, CheckboxPicker.key(CheckboxPicker.ARROW_UP));
    assertEquals(CheckboxPicker.Key.DOWN, CheckboxPicker.key(CheckboxPicker.ARROW_DOWN));
    assertEquals(CheckboxPicker.Key.OPEN, CheckboxPicker.key(CheckboxPicker.ARROW_RIGHT));
    assertEquals(CheckboxPicker.Key.PARENT, CheckboxPicker.key(CheckboxPicker.ARROW_LEFT));
    assertEquals(CheckboxPicker.Key.TOGGLE, CheckboxPicker.key(' '));
    assertEquals(CheckboxPicker.Key.CONFIRM, CheckboxPicker.key('\r'));
    assertEquals(CheckboxPicker.Key.CONFIRM, CheckboxPicker.key('s'));
    assertEquals(CheckboxPicker.Key.CANCEL, CheckboxPicker.key('q'));
    assertEquals(CheckboxPicker.Key.CANCEL, CheckboxPicker.key(27));
    assertEquals(CheckboxPicker.Key.CANCEL, CheckboxPicker.key(3));
    assertEquals(CheckboxPicker.Key.ALL, CheckboxPicker.key('a'));
    assertEquals(CheckboxPicker.Key.UP, CheckboxPicker.key('k'));
    assertEquals(CheckboxPicker.Key.DOWN, CheckboxPicker.key('j'));
    assertEquals(CheckboxPicker.Key.NONE, CheckboxPicker.key('z'));
  }

  @Test
  void cursorMovesAndClampsAtBothEnds() {
    var atTop = CheckboxPicker.apply(screen(), CheckboxPicker.Key.UP);
    assertEquals(0, atTop.screen().cursor());

    var down = CheckboxPicker.apply(screen(), CheckboxPicker.Key.DOWN);
    assertEquals(1, down.screen().cursor());

    var clampedBottom = CheckboxPicker.apply(down.screen(), CheckboxPicker.Key.DOWN);
    assertEquals(1, clampedBottom.screen().cursor());
  }

  @Test
  void spaceTogglesTheHighlightedEntry() {
    var checked = CheckboxPicker.apply(screen(), CheckboxPicker.Key.TOGGLE);
    assertTrue(checked.screen().picked().contains(FILE.path()));

    var unchecked = CheckboxPicker.apply(checked.screen(), CheckboxPicker.Key.TOGGLE);
    assertFalse(unchecked.screen().picked().contains(FILE.path()));
  }

  @Test
  void toggleAllFlipsEveryEntry() {
    var all = CheckboxPicker.apply(screen(), CheckboxPicker.Key.ALL);
    assertTrue(all.screen().picked().contains(FILE.path()));
    assertTrue(all.screen().picked().contains(DIR.path()));
  }

  @Test
  void openOnAFolderRequestsNavigationOpenOnAFileChecksIt() {
    var onFolder = CheckboxPicker.apply(screen(), CheckboxPicker.Key.DOWN);
    var open = CheckboxPicker.apply(onFolder.screen(), CheckboxPicker.Key.OPEN);
    assertEquals(CheckboxPicker.Outcome.OPEN, open.outcome());
    assertEquals(DIR.path(), open.target());

    var openFile = CheckboxPicker.apply(screen(), CheckboxPicker.Key.OPEN);
    assertEquals(CheckboxPicker.Outcome.BROWSING, openFile.outcome());
    assertTrue(openFile.screen().picked().contains(FILE.path()));
  }

  @Test
  void parentStaysPutAtRootAndNavigatesUpBelowIt() {
    var atRoot = CheckboxPicker.apply(screen(), CheckboxPicker.Key.PARENT);
    assertEquals(CheckboxPicker.Outcome.BROWSING, atRoot.outcome());

    var deeper =
        CheckboxPicker.Screen.of(ROOT, ROOT.resolve("src"), List.of(FILE), new LinkedHashSet<>());
    var up = CheckboxPicker.apply(deeper, CheckboxPicker.Key.PARENT);
    assertEquals(CheckboxPicker.Outcome.PARENT, up.outcome());
    assertEquals(ROOT, up.target());
  }

  @Test
  void confirmAndCancelReportOutcomes() {
    assertEquals(
        CheckboxPicker.Outcome.CONFIRMED,
        CheckboxPicker.apply(screen(), CheckboxPicker.Key.CONFIRM).outcome());
    assertEquals(
        CheckboxPicker.Outcome.CANCELLED,
        CheckboxPicker.apply(screen(), CheckboxPicker.Key.CANCEL).outcome());
  }

  @Test
  void noneAndEmptyFolderKeysLeaveTheScreenUntouched() {
    var s = screen();
    assertSame(s, CheckboxPicker.apply(s, CheckboxPicker.Key.NONE).screen());

    var empty = CheckboxPicker.Screen.of(ROOT, ROOT, List.of(), new LinkedHashSet<>());
    assertSame(empty, CheckboxPicker.apply(empty, CheckboxPicker.Key.TOGGLE).screen());
    assertSame(empty, CheckboxPicker.apply(empty, CheckboxPicker.Key.DOWN).screen());
    assertEquals(
        CheckboxPicker.Outcome.BROWSING,
        CheckboxPicker.apply(empty, CheckboxPicker.Key.OPEN).outcome());
  }

  @Test
  void renderShowsCursorCheckboxesCountAndLegend() {
    var checked = CheckboxPicker.apply(screen(), CheckboxPicker.Key.TOGGLE).screen();
    var lines = CheckboxPicker.render(checked);
    var text = String.join("\n", lines);

    assertTrue(text.contains("check files to share"));
    assertTrue(text.contains("[x] a.txt"));
    assertTrue(text.contains("[ ] src/"));
    assertTrue(lines.get(1).contains("›"), "cursor points at the first row");
    assertTrue(text.contains("(1 checked)"));
    assertTrue(text.contains("space check"));
  }

  @Test
  void renderMarksAnEmptyFolder() {
    var empty = CheckboxPicker.Screen.of(ROOT, ROOT, List.of(), new LinkedHashSet<>());
    assertTrue(String.join("\n", CheckboxPicker.render(empty)).contains("(empty folder)"));
  }
}
