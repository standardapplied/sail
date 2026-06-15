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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePickerTest {

  @TempDir Path root;
  private final FileSource source = new HostFileSource();

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
    var names =
        FilePicker.list(source, root).stream().map(e -> e.path().getFileName().toString()).toList();
    assertEquals(java.util.List.of("scripts", "src", "README.md"), names);
  }

  @Test
  void aLoneDirectoryNumberDescendsAndDotDotReturns() throws Exception {
    var s0 = start();
    var entries = FilePicker.list(source, s0.cwd());

    var descended = FilePicker.step(s0, entries, "2");
    assertEquals(FilePicker.Status.BROWSING, descended.status());
    assertEquals(root.resolve("src"), descended.state().cwd());

    var up =
        FilePicker.step(descended.state(), FilePicker.list(source, descended.state().cwd()), "..");
    assertEquals(root, up.state().cwd());
  }

  @Test
  void dotDotIsFencedAtTheRoot() throws Exception {
    var step = FilePicker.step(start(), FilePicker.list(source, root), "..");
    assertEquals(root, step.state().cwd());
    assertTrue(step.message().contains("top"));
  }

  @Test
  void numbersToggleFilesOnAndOff() throws Exception {
    var s0 = start();
    var entries = FilePicker.list(source, s0.cwd());

    var picked = FilePicker.step(s0, entries, "3");
    assertTrue(picked.state().picked().contains(root.resolve("README.md")));

    var unpicked = FilePicker.step(picked.state(), entries, "3");
    assertFalse(unpicked.state().picked().contains(root.resolve("README.md")));
  }

  @Test
  void aSelectsTheWholeCurrentFolder() throws Exception {
    var inSrc = FilePicker.step(start(), FilePicker.list(source, root), "2").state();
    var step = FilePicker.step(inSrc, FilePicker.list(source, inSrc.cwd()), "a");
    assertTrue(step.state().picked().contains(root.resolve("src")));
  }

  @Test
  void selectedFilesExpandsFoldersRecursively() throws Exception {
    var picked = new java.util.LinkedHashSet<Path>();
    picked.add(root.resolve("src"));
    picked.add(root.resolve("README.md"));
    var state = new FilePicker.State(root, root, picked);

    var files = FilePicker.selectedFiles(source, state);

    assertEquals(
        java.util.List.of(
            root.resolve("README.md"),
            root.resolve("src/Main.java"),
            root.resolve("src/api/Handler.java")),
        files);
  }

  @Test
  void doneConfirmsAndQuitCancels() throws Exception {
    var entries = FilePicker.list(source, root);
    assertEquals(FilePicker.Status.CONFIRMED, FilePicker.step(start(), entries, "done").status());
    assertEquals(FilePicker.Status.CONFIRMED, FilePicker.step(start(), entries, "d").status());
    assertEquals(FilePicker.Status.CANCELLED, FilePicker.step(start(), entries, "q").status());
  }

  @Test
  void confirmAndCancelAcceptSynonymsAndAnyCase() throws Exception {
    var entries = FilePicker.list(source, root);
    for (var confirm : List.of("share", "ok", "DONE", "Done")) {
      assertEquals(
          FilePicker.Status.CONFIRMED,
          FilePicker.step(start(), entries, confirm).status(),
          confirm + " should confirm");
    }
    for (var cancel : List.of("quit", "cancel", "exit", "Q")) {
      assertEquals(
          FilePicker.Status.CANCELLED,
          FilePicker.step(start(), entries, cancel).status(),
          cancel + " should cancel");
    }
  }

  @Test
  void footerShowsHowToConfirmAndThePickedCount() throws Exception {
    var entries = FilePicker.list(source, root);
    var empty = FilePicker.render(start(), entries);
    assertTrue(empty.contains("Type a command"));
    assertTrue(empty.contains("nothing picked yet"));
    assertTrue(empty.contains("q  cancel"));

    var withOne = FilePicker.step(start(), entries, "3");
    var rendered = FilePicker.render(withOne.state(), entries);
    assertTrue(rendered.contains("share the 1 picked file(s)"));
  }

  @Test
  void unknownChoiceIsRejectedWithoutChangingState() throws Exception {
    var entries = FilePicker.list(source, root);
    var step = FilePicker.step(start(), entries, "99");
    assertEquals(FilePicker.Status.BROWSING, step.status());
    assertTrue(step.state().picked().isEmpty());
    assertTrue(step.message().contains("Not a choice"));
  }

  @Test
  void emptyInputJustRedraws() throws Exception {
    var step = FilePicker.step(start(), FilePicker.list(source, root), "  ");
    assertEquals(FilePicker.Status.BROWSING, step.status());
  }

  @Test
  void junkDirectoriesAreHiddenFromTheListing() throws Exception {
    Files.createDirectories(root.resolve("node_modules/pkg"));
    Files.createDirectories(root.resolve(".git"));
    Files.writeString(root.resolve("node_modules/pkg/index.js"), "junk");
    Files.writeString(root.resolve(".git/config"), "[core]");

    var names =
        FilePicker.list(source, root).stream().map(e -> e.path().getFileName().toString()).toList();

    assertFalse(names.contains("node_modules"));
    assertFalse(names.contains(".git"));
    assertTrue(names.contains("src"));
  }

  @Test
  void selectedFilesSkipsJunkDirectoriesWhenExpandingAFolder() throws Exception {
    Files.createDirectories(root.resolve("node_modules/pkg"));
    Files.writeString(root.resolve("node_modules/pkg/index.js"), "junk");
    var picked = new java.util.LinkedHashSet<Path>();
    picked.add(root);

    var files = FilePicker.selectedFiles(source, new FilePicker.State(root, root, picked));

    assertTrue(files.contains(root.resolve("README.md")));
    assertFalse(
        files.contains(root.resolve("node_modules/pkg/index.js")), "node_modules is not walked");
  }

  @Test
  void isShareablePathAllowsRealNamesButBlocksTraversal() {
    assertTrue(FilePicker.isShareablePath("scripts/My Deploy (final).sh"));
    assertTrue(FilePicker.isShareablePath("docs/café.md"));
    assertFalse(FilePicker.isShareablePath("../escape"));
    assertFalse(FilePicker.isShareablePath("a/../../b"));
    assertFalse(FilePicker.isShareablePath("/etc/passwd"));
    assertFalse(FilePicker.isShareablePath(""));
    assertFalse(FilePicker.isShareablePath("with\ttab"), "control characters are rejected");
  }

  @Test
  void renderNumbersEntriesAndMarksPicks() throws Exception {
    var picked = new java.util.LinkedHashSet<Path>();
    picked.add(root.resolve("README.md"));
    var rendered =
        FilePicker.render(new FilePicker.State(root, root, picked), FilePicker.list(source, root));

    assertTrue(rendered.contains("[1] scripts/"));
    assertTrue(rendered.contains("[3] README.md"));
    assertTrue(rendered.contains("* [3]"), "the picked file is marked");
    assertTrue(rendered.contains("1 picked"));
  }
}
