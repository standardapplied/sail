/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Both readers against real folders: on this box directly, and in a container that is this machine,
 * whose listing and reads run here for real.
 */
@EnabledOnOs(OS.LINUX)
class SkillFoldersTest {

  @TempDir Path dir;

  private Path folder(Map<String, String> files) throws IOException {
    var folder = dir.resolve("java");
    for (var file : files.entrySet()) {
      Files.createDirectories(folder.resolve(file.getKey()).getParent());
      Files.writeString(folder.resolve(file.getKey()), file.getValue());
    }
    return folder;
  }

  private static Map<String, String> contents(List<SkillFolders.Entry> entries) {
    var contents = new LinkedHashMap<String, String>();
    entries.forEach(entry -> contents.put(entry.path(), new String(entry.bytes(), UTF_8)));
    return contents;
  }

  private interface Reader {
    List<SkillFolders.Entry> read(Path folder, String name) throws Exception;
  }

  private static final Reader HOST = SkillFolders::onHost;
  private static final Reader CONTAINER =
      (folder, name) -> SkillFolders.inContainer(new Here(), "acme", folder.toString(), name);

  private void readsEveryFileWithItsModeInPathOrder(Reader reader) throws Exception {
    var folder =
        folder(Map.of("SKILL.md", "---\n", "scripts/run.sh", "#!/bin/sh\n", "a b.md", "spaced\n"));
    WorkspaceFiles.mode(folder.resolve("scripts/run.sh"), 0755);
    Files.createDirectories(folder.resolve("empty"));
    Files.write(folder.resolve("bin.dat"), new byte[] {0, (byte) 0xff, 10});

    var entries = reader.read(folder, "java");

    assertEquals(
        List.of("SKILL.md", "a b.md", "bin.dat", "scripts/run.sh"),
        entries.stream().map(SkillFolders.Entry::path).toList());
    assertEquals(
        List.of(0644, 0644, 0644, 0755), entries.stream().map(SkillFolders.Entry::mode).toList());
    assertEquals("#!/bin/sh\n", contents(entries).get("scripts/run.sh"));
    assertArrayEquals(new byte[] {0, (byte) 0xff, 10}, entries.get(2).bytes());
  }

  @Test
  void theHostReadsEveryFileWithItsModeInPathOrder() throws Exception {
    readsEveryFileWithItsModeInPathOrder(HOST);
  }

  @Test
  void theContainerReadsEveryFileWithItsModeInPathOrder() throws Exception {
    readsEveryFileWithItsModeInPathOrder(CONTAINER);
  }

  private void refusesWhatIsNotAFolder(Reader reader) throws IOException {
    var file = Files.writeString(dir.resolve("loose.md"), "x");
    var target = Files.createDirectories(dir.resolve("real"));
    var link = Files.createSymbolicLink(dir.resolve("link"), target);

    for (var path : List.of(file, link, dir.resolve("missing"))) {
      assertEquals(
          "Not a folder: " + path,
          assertThrows(IllegalArgumentException.class, () -> reader.read(path, "java"))
              .getMessage());
    }
  }

  @Test
  void theHostRefusesWhatIsNotAFolder() throws IOException {
    refusesWhatIsNotAFolder(HOST);
  }

  @Test
  void theContainerRefusesWhatIsNotAFolder() throws IOException {
    refusesWhatIsNotAFolder(CONTAINER);
  }

  private void refusesALinkAtAnyDepth(Reader reader) throws IOException {
    var folder = folder(Map.of("SKILL.md", "---\n"));
    Files.createDirectories(folder.resolve("scripts"));
    Files.createSymbolicLink(folder.resolve("scripts/run.sh"), dir.resolve("elsewhere"));

    assertEquals(
        "Skill 'java' holds scripts/run.sh, which is not a regular file.",
        assertThrows(IllegalArgumentException.class, () -> reader.read(folder, "java"))
            .getMessage());
  }

  @Test
  void theHostRefusesALinkAtAnyDepth() throws IOException {
    refusesALinkAtAnyDepth(HOST);
  }

  @Test
  void theContainerRefusesALinkAtAnyDepth() throws IOException {
    refusesALinkAtAnyDepth(CONTAINER);
  }

  private void refusesAPathThatCannotBeShared(Reader reader) throws IOException {
    var folder = folder(Map.of("SKILL.md", "---\n", "bad\tname.md", "x"));

    assertEquals(
        "Skill 'java' holds the path 'bad\tname.md', which cannot be shared.",
        assertThrows(IllegalArgumentException.class, () -> reader.read(folder, "java"))
            .getMessage());
  }

  @Test
  void theHostRefusesAPathThatCannotBeShared() throws IOException {
    refusesAPathThatCannotBeShared(HOST);
  }

  @Test
  void theContainerRefusesAPathThatCannotBeShared() throws IOException {
    refusesAPathThatCannotBeShared(CONTAINER);
  }

  private void refusesAFolderOverTheBoundsBeforeReadingIt(Reader reader, String tooMany)
      throws IOException {
    var many = new LinkedHashMap<String, String>();
    for (var i = 0; i <= StageSkill.MAX_FILES + 5; i++) {
      many.put("f" + i + ".md", "x");
    }
    var crowded = folder(many);
    var tooBig = Files.createDirectories(dir.resolve("big"));
    Files.write(tooBig.resolve("big.bin"), new byte[(int) StageSkill.MAX_BYTES + 1]);

    assertEquals(
        tooMany,
        assertThrows(IllegalArgumentException.class, () -> reader.read(crowded, "java"))
            .getMessage());
    assertEquals(
        "Skill 'big' is 1048577 bytes; the limit is 1048576.",
        assertThrows(IllegalArgumentException.class, () -> reader.read(tooBig, "big"))
            .getMessage());
  }

  @Test
  void theHostRefusesAFolderOverTheBoundsBeforeReadingIt() throws IOException {
    refusesAFolderOverTheBoundsBeforeReadingIt(HOST, "Skill 'java' has 38 files; the limit is 32.");
  }

  @Test
  void theContainerRefusesAFolderOverTheBoundsBeforeReadingIt() throws IOException {
    refusesAFolderOverTheBoundsBeforeReadingIt(
        CONTAINER, "Skill 'java' has more than 32 files; the limit is 32.");
  }

  @Test
  void aContainerPathThatIsNotAbsoluteOrHoldsAControlCharacterIsRefusedBeforeAnyCommand() {
    var shell = new Here();

    for (var path : List.of("java", "/home/dev/ja\nva")) {
      assertEquals(
          "The folder to share must be an absolute path in the container: " + path,
          assertThrows(
                  IllegalArgumentException.class,
                  () -> SkillFolders.inContainer(shell, "acme", path, "java"))
              .getMessage());
    }
    assertEquals(List.of(), shell.commands);
  }

  @Test
  void theContainerPathReachesTheShellOnlyAsAnArgument() throws Exception {
    var folder = folder(Map.of("SKILL.md", "---\n"));
    var shell = new Here();

    SkillFolders.inContainer(shell, "acme", folder.toString(), "java");

    var listing = shell.commands.getFirst();
    var read = shell.commands.getLast();
    assertEquals(
        List.of(folder.toString(), "33"), listing.subList(listing.size() - 2, listing.size()));
    assertEquals(List.of(folder + "/SKILL.md", "5"), read.subList(read.size() - 2, read.size()));
  }

  @Test
  void aContainerThatCannotListOrReadTheFolderFailsSayingWhich() {
    var cannotList = new ScriptedShellExecutor(new ShellExec.Result(1, "", "gone\n"));
    var cannotRead =
        new ScriptedShellExecutor(new ShellExec.Result(1, "", "unreadable\n"))
            .onOk("find", "f 4 644 SKILL.md\0");

    assertEquals(
        "Failed to list /home/dev/java in acme: gone",
        assertThrows(
                IOException.class,
                () -> SkillFolders.inContainer(cannotList, "acme", "/home/dev/java", "java"))
            .getMessage());
    assertEquals(
        "Failed to read /home/dev/java/SKILL.md in acme: unreadable",
        assertThrows(
                IOException.class,
                () -> SkillFolders.inContainer(cannotRead, "acme", "/home/dev/java", "java"))
            .getMessage());
  }

  @Test
  void theContainerHoldsNoMoreOfAListingOrAFileThanASkillMayBe() throws Exception {
    var shell =
        new ScriptedShellExecutor().onOk("find", "f 4 644 a.md\0".repeat(StageSkill.MAX_FILES + 1));

    assertEquals(
        "Skill 'java' has more than 32 files; the limit is 32.",
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillFolders.inContainer(shell, "acme", "/home/dev/java", "java"))
            .getMessage());
    assertEquals(1, shell.invocations().size(), "no file was read");
  }

  @Test
  void aNameNoSkillCanTakeIsRefusedBeforeTheFolderIsListed() {
    var shell = new Here();

    assertEquals(
        "Skill name '' must be 1 to 64 lowercase letters, digits and hyphens, with no hyphen"
            + " first, last or next to another.",
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillFolders.inContainer(shell, "acme", "/", ""))
            .getMessage());
    assertEquals(List.of(), shell.commands);
  }

  @Test
  void aFileThatGrewOrBecameALinkSinceItWasListedIsRefusedAndReadNoFurther() throws Exception {
    var folder = folder(Map.of("SKILL.md", "---\n"));
    var grown =
        new ScriptedShellExecutor().onOk("find", "f 2 644 SKILL.md\0").onOk("base64", "LS0t");
    var linked =
        new ScriptedShellExecutor()
            .onOk("find", "f 4 644 SKILL.md\0")
            .on("base64", new ShellExec.Result(SkillFolders.NOT_WHAT_IT_WAS, "", ""));
    Files.writeString(folder.resolve("SKILL.md"), "---\nmore\n");

    assertEquals(
        "Skill 'java' changed while it was being read: /home/dev/java/SKILL.md is no longer 2"
            + " bytes. Share it again.",
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillFolders.inContainer(grown, "acme", "/home/dev/java", "java"))
            .getMessage());
    assertEquals(
        "Skill 'java' changed while it was being read: /home/dev/java/SKILL.md is no longer a"
            + " regular file. Share it again.",
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillFolders.inContainer(linked, "acme", "/home/dev/java", "java"))
            .getMessage());
    var real = new Here();
    var read = SkillFolders.inContainer(real, "acme", folder.toString(), "java");
    assertEquals("---\nmore\n", new String(read.getFirst().bytes(), UTF_8));
  }

  @Test
  void aFolderOrFileTheContainerCouldReadOnlyInPartIsAFailureNotAShorterSkill() {
    var partList =
        new ScriptedShellExecutor()
            .on(
                "find",
                new ShellExec.Result(
                    0, "f 4 644 SKILL.md\0", "find: './scripts': Permission denied\n"));
    var partRead =
        new ScriptedShellExecutor()
            .onOk("find", "f 4 644 SKILL.md\0")
            .on(
                "base64",
                new ShellExec.Result(0, "", "head: cannot open 'SKILL.md': Permission denied\n"));

    assertEquals(
        "Failed to list /home/dev/java in acme: find: './scripts': Permission denied",
        assertThrows(
                IOException.class,
                () -> SkillFolders.inContainer(partList, "acme", "/home/dev/java", "java"))
            .getMessage());
    assertEquals(
        "Failed to read /home/dev/java/SKILL.md in acme: head: cannot open 'SKILL.md': Permission"
            + " denied",
        assertThrows(
                IOException.class,
                () -> SkillFolders.inContainer(partRead, "acme", "/home/dev/java", "java"))
            .getMessage());
  }

  /**
   * The container as it was when listed and as it is when read: the listing is the one given, the
   * reads run here for real, and every read's answer is kept.
   */
  private static final class ListedThenChanged implements ShellExec {
    private final String listing;
    private final Here here = new Here();
    final List<Result> reads = new ArrayList<>();

    ListedThenChanged(String listing) {
      this.listing = listing;
    }

    @Override
    public Result exec(List<String> command)
        throws IOException, InterruptedException, TimeoutException {
      if (String.join(" ", command).contains("find")) {
        return new Result(0, listing, "");
      }
      var read = here.exec(command);
      reads.add(read);
      return read;
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout)
        throws IOException, InterruptedException, TimeoutException {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }

  @Test
  void aFileThatGrewSinceItWasListedIsReadNoFurtherThanItsListedSizeAndOneByte() throws Exception {
    var folder = folder(Map.of("SKILL.md", "x".repeat(100_000)));
    var shell = new ListedThenChanged("f 2 644 SKILL.md\0");

    assertEquals(
        "Skill 'java' changed while it was being read: "
            + folder
            + "/SKILL.md is no longer 2 bytes. Share it again.",
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillFolders.inContainer(shell, "acme", folder.toString(), "java"))
            .getMessage());
    assertEquals("eHh4", shell.reads.getFirst().stdout().strip(), "three bytes were read");
  }

  @Test
  void aFileSwappedForALinkToOneOfTheSameSizeIsRefusedNotFollowed() throws Exception {
    var folder = folder(Map.of());
    Files.createDirectories(folder);
    var elsewhere = Files.writeString(dir.resolve("secret"), "abc");
    Files.createSymbolicLink(folder.resolve("SKILL.md"), elsewhere);
    var shell = new ListedThenChanged("f 3 644 SKILL.md\0");

    assertEquals(
        "Skill 'java' changed while it was being read: "
            + folder
            + "/SKILL.md is no longer a regular file. Share it again.",
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillFolders.inContainer(shell, "acme", folder.toString(), "java"))
            .getMessage());
  }

  @Test
  void aFileThatChangedBetweenTheListingAndItsReadIsRefused() {
    var changed =
        new ScriptedShellExecutor().onOk("find", "f 9 644 SKILL.md\0").onOk("base64", "LS0tCg==\n");

    assertEquals(
        "Skill 'java' changed while it was being read: /home/dev/java/SKILL.md is no longer 9"
            + " bytes. Share it again.",
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillFolders.inContainer(changed, "acme", "/home/dev/java", "java"))
            .getMessage());
  }
}
