/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePickerTest {

  @TempDir Path root;

  @BeforeEach
  void tree() throws Exception {
    Files.createDirectories(root.resolve("src/api"));
    Files.createDirectories(root.resolve("scripts"));
    Files.writeString(root.resolve("README.md"), "readme");
    Files.writeString(root.resolve("src/Main.java"), "class Main {}");
    Files.writeString(root.resolve("src/api/Handler.java"), "class Handler {}");
    Files.writeString(root.resolve("scripts/deploy.sh"), "echo deploy");
  }

  private FilePicker.State start() {
    return FilePicker.State.at(root);
  }

  @Test
  void listsFoldersFirstThenFilesAlphabetical() throws Exception {
    var names = FilePicker.list(root).stream().map(e -> e.path().getFileName().toString()).toList();
    assertEquals(java.util.List.of("scripts", "src", "README.md"), names);
  }

  @Test
  void aLoneDirectoryNumberDescendsAndDotDotReturns() throws Exception {
    var s0 = start();
    var entries = FilePicker.list(s0.cwd());

    var descended = FilePicker.step(s0, entries, "2");
    assertEquals(FilePicker.Status.BROWSING, descended.status());
    assertEquals(root.resolve("src"), descended.state().cwd());

    var up = FilePicker.step(descended.state(), FilePicker.list(descended.state().cwd()), "..");
    assertEquals(root, up.state().cwd());
  }

  @Test
  void dotDotIsFencedAtTheRoot() throws Exception {
    var step = FilePicker.step(start(), FilePicker.list(root), "..");
    assertEquals(root, step.state().cwd());
    assertTrue(step.message().contains("top"));
  }

  @Test
  void numbersToggleFilesOnAndOff() throws Exception {
    var s0 = start();
    var entries = FilePicker.list(s0.cwd());

    var picked = FilePicker.step(s0, entries, "3");
    assertTrue(picked.state().picked().contains(root.resolve("README.md")));

    var unpicked = FilePicker.step(picked.state(), entries, "3");
    assertFalse(unpicked.state().picked().contains(root.resolve("README.md")));
  }

  @Test
  void aSelectsTheWholeCurrentFolder() throws Exception {
    var inSrc = FilePicker.step(start(), FilePicker.list(root), "2").state();
    var step = FilePicker.step(inSrc, FilePicker.list(inSrc.cwd()), "a");
    assertTrue(step.state().picked().contains(root.resolve("src")));
  }

  @Test
  void selectedFilesExpandsFoldersRecursively() throws Exception {
    var picked = new java.util.LinkedHashSet<Path>();
    picked.add(root.resolve("src"));
    picked.add(root.resolve("README.md"));
    var state = new FilePicker.State(root, root, picked);

    var files = FilePicker.selectedFiles(state);

    assertEquals(
        java.util.List.of(
            root.resolve("README.md"),
            root.resolve("src/Main.java"),
            root.resolve("src/api/Handler.java")),
        files);
  }

  @Test
  void doneConfirmsAndQuitCancels() throws Exception {
    var entries = FilePicker.list(root);
    assertEquals(FilePicker.Status.CONFIRMED, FilePicker.step(start(), entries, "done").status());
    assertEquals(FilePicker.Status.CONFIRMED, FilePicker.step(start(), entries, "d").status());
    assertEquals(FilePicker.Status.CANCELLED, FilePicker.step(start(), entries, "q").status());
  }

  @Test
  void unknownChoiceIsRejectedWithoutChangingState() throws Exception {
    var entries = FilePicker.list(root);
    var step = FilePicker.step(start(), entries, "99");
    assertEquals(FilePicker.Status.BROWSING, step.status());
    assertTrue(step.state().picked().isEmpty());
    assertTrue(step.message().contains("Not a choice"));
  }

  @Test
  void emptyInputJustRedraws() throws Exception {
    var step = FilePicker.step(start(), FilePicker.list(root), "  ");
    assertEquals(FilePicker.Status.BROWSING, step.status());
  }

  @Test
  void renderNumbersEntriesAndMarksPicks() throws Exception {
    var picked = new java.util.LinkedHashSet<Path>();
    picked.add(root.resolve("README.md"));
    var rendered =
        FilePicker.render(new FilePicker.State(root, root, picked), FilePicker.list(root));

    assertTrue(rendered.contains("[1] scripts/"));
    assertTrue(rendered.contains("[3] README.md"));
    assertTrue(rendered.contains("* [3]"), "the picked file is marked");
    assertTrue(rendered.contains("1 picked"));
  }
}
