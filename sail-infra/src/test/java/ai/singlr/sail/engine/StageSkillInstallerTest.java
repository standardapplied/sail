/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.BlobStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The installer against a container that is this machine: every command it sends into the container
 * runs here for real, under a temporary directory, so what is asserted is what the scripts did to
 * the folder and not which scripts were sent.
 */
class StageSkillInstallerTest {

  private static final String PROJECT = "acme";

  @TempDir Path home;
  private Path skillsDir;
  private Path folder;
  private LocalContainer container;

  @BeforeEach
  void setUp() {
    skillsDir = home.resolve(".claude/skills");
    folder = skillsDir.resolve("acme-review");
    container = new LocalContainer();
  }

  /** A skill's files as a test writes them: path to content, with the mode each is shared at. */
  private record Source(Map<String, String> contents, Map<String, Integer> modes) {

    static Source of(String... pathsAndContents) {
      var contents = new LinkedHashMap<String, String>();
      for (var i = 0; i < pathsAndContents.length; i += 2) {
        contents.put(pathsAndContents[i], pathsAndContents[i + 1]);
      }
      return new Source(contents, new LinkedHashMap<>());
    }

    Source mode(String path, int mode) {
      modes.put(path, mode);
      return this;
    }

    StageSkill skill() {
      var files =
          contents.entrySet().stream()
              .map(
                  entry ->
                      new StageSkill.File(
                          entry.getKey(),
                          BlobStore.hash(entry.getValue().getBytes(StandardCharsets.UTF_8)),
                          entry.getValue().length(),
                          modes.getOrDefault(entry.getKey(), 0644)))
              .toList();
      return new StageSkill("acme-review", "Judge it acme's way.", files);
    }

    InputStream open(StageSkill.File file) {
      return new ByteArrayInputStream(contents.get(file.path()).getBytes(StandardCharsets.UTF_8));
    }
  }

  private void install(String runId, Source source) throws Exception {
    install(runId, source.skill(), source::open);
  }

  private void install(
      String runId, StageSkill skill, Function<StageSkill.File, InputStream> content)
      throws Exception {
    StageSkillInstaller.install(container, PROJECT, folder.toString(), runId, skill, content);
  }

  private Map<String, String> installed() throws IOException {
    return read(folder);
  }

  private static Map<String, String> read(Path root) throws IOException {
    var files = new LinkedHashMap<String, String>();
    if (!Files.isDirectory(root)) {
      return files;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      for (var file : walk.filter(Files::isRegularFile).sorted().toList()) {
        files.put(root.relativize(file).toString(), Files.readString(file));
      }
    }
    return files;
  }

  private List<String> besideTheFolder() throws IOException {
    if (!Files.isDirectory(skillsDir)) {
      return List.of();
    }
    try (var entries = Files.list(skillsDir)) {
      return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
    }
  }

  private static String mode(Path file) throws IOException {
    return PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
  }

  private static Map<String, String> with(Map<String, String> files, String stamp) {
    var all = new LinkedHashMap<String, String>();
    all.put(".sail-skill", stamp);
    all.putAll(files);
    return all;
  }

  @Test
  void aSkillIsInstalledWholeWithEachFilesModeAndStampedLast() throws Exception {
    var source =
        Source.of(
                "SKILL.md", "Judge it acme's way.\n",
                "scripts/check.sh", "#!/bin/sh\nmvn -q verify\n",
                "reference/deep/rules.md", "Rules.\n")
            .mode("scripts/check.sh", 0755)
            .mode("reference/deep/rules.md", 0600);

    install("run-1", source);

    assertEquals(
        with(
            Map.of(
                "SKILL.md", "Judge it acme's way.\n",
                "reference/deep/rules.md", "Rules.\n",
                "scripts/check.sh", "#!/bin/sh\nmvn -q verify\n"),
            StageSkillInstaller.fingerprint(source.skill())),
        installed());
    assertEquals("rw-r--r--", mode(folder.resolve("SKILL.md")));
    assertEquals("rwxr-xr-x", mode(folder.resolve("scripts/check.sh")));
    assertEquals("rw-------", mode(folder.resolve("reference/deep/rules.md")));
    assertEquals(List.of("acme-review"), besideTheFolder(), "the build folder became the folder");
    assertEquals(
        List.of("--uid", "1000", "--gid", "1000", "--mode", "0755"),
        container.push("scripts/check.sh").subList(3, 9),
        "pushed as the dev user's, under its own mode written as four octal digits");
    var stamped = container.indexOf("printf '%s'");
    assertTrue(
        container.indexOf("file push") < stamped && stamped < container.indexOf("mv \"$2\""),
        "the stamp is written after the last file and before the folder is put in place");
  }

  @Test
  void theSameSkillInstalledAgainWritesNothing() throws Exception {
    var source = Source.of("SKILL.md", "Body.\n", "notes.md", "Notes.\n");
    install("run-1", source);
    container.commands.clear();

    install("run-2", source);

    assertEquals(1, container.commands.size(), container.commands.toString());
    assertEquals(
        List.of("cat", folder + "/.sail-skill"),
        LocalContainer.inner(container.commands.getFirst()),
        "one command reads the stamp, and nothing else runs");
  }

  @Test
  void aStampWithATrailingNewlineIsStillTheSkillsStamp() throws Exception {
    var source = Source.of("SKILL.md", "Body.\n");
    install("run-1", source);
    Files.writeString(
        folder.resolve(".sail-skill"), StageSkillInstaller.fingerprint(source.skill()) + "\n");
    container.commands.clear();

    install("run-2", source);

    assertEquals(1, container.commands.size(), container.commands.toString());
  }

  @Test
  void aStampReadThatFailedIsNotBelievedWhateverItPrinted() throws Exception {
    var source = Source.of("SKILL.md", "Body.\n");
    install("run-1", source);
    container.commands.clear();
    container.failingReads = true;

    install("run-2", source);

    assertEquals(1, container.pushes().size(), "the skill is installed again");
    assertEquals(
        with(Map.of("SKILL.md", "Body.\n"), StageSkillInstaller.fingerprint(source.skill())),
        installed());
  }

  @Test
  void aSkillThatGainedChangedAndLostFilesLeavesExactlyTheNewOnes() throws Exception {
    install(
        "run-1", Source.of("SKILL.md", "One.\n", "kept.md", "Kept.\n", "dropped/old.md", "Old.\n"));
    var changed = Source.of("SKILL.md", "Two.\n", "kept.md", "Kept.\n", "added/new.md", "New.\n");

    install("run-2", changed);

    assertEquals(
        with(
            Map.of("SKILL.md", "Two.\n", "added/new.md", "New.\n", "kept.md", "Kept.\n"),
            StageSkillInstaller.fingerprint(changed.skill())),
        installed());
    assertFalse(Files.exists(folder.resolve("dropped")), "nothing of the old folder is left");
    assertEquals(List.of("acme-review"), besideTheFolder());
  }

  @Test
  void aFileWhoseModeAloneChangedIsInstalledAgain() throws Exception {
    install("run-1", Source.of("SKILL.md", "Body.\n", "check.sh", "true\n"));
    assertEquals("rw-r--r--", mode(folder.resolve("check.sh")));

    install("run-2", Source.of("SKILL.md", "Body.\n", "check.sh", "true\n").mode("check.sh", 0755));

    assertEquals("rwxr-xr-x", mode(folder.resolve("check.sh")));
  }

  @Test
  void theFingerprintIsOfEveryPathHashAndModeUnderTheSkillsName() {
    var base = Source.of("SKILL.md", "Body.\n", "a.md", "A.\n").skill();
    var fingerprint = StageSkillInstaller.fingerprint(base);
    var files = new LinkedHashMap<String, String>();
    files.put("acme-review/SKILL.md", BlobStore.hash("Body.\n".getBytes()) + " 0644");
    files.put("acme-review/a.md", BlobStore.hash("A.\n".getBytes()) + " 0644");

    assertEquals(ContainerSailSetup.fingerprintOf(files), fingerprint);
    assertNotEquals(
        fingerprint,
        StageSkillInstaller.fingerprint(Source.of("SKILL.md", "Body.\n", "b.md", "A.\n").skill()));
    assertNotEquals(
        fingerprint,
        StageSkillInstaller.fingerprint(Source.of("SKILL.md", "Body.\n", "a.md", "B.\n").skill()));
    assertNotEquals(
        fingerprint,
        StageSkillInstaller.fingerprint(
            Source.of("SKILL.md", "Body.\n", "a.md", "A.\n").mode("a.md", 0600).skill()));
    assertNotEquals(
        fingerprint,
        StageSkillInstaller.fingerprint(new StageSkill("acme-other", base.body(), base.files())),
        "the same files under another skill's name are another skill");
  }

  @Test
  void aFileThatChangedUnderTheLaunchFailsItAndLeavesTheInstalledFolderAsItWas() throws Exception {
    var first = Source.of("SKILL.md", "One.\n", "rules.md", "Rules one.\n");
    install("run-1", first);
    var second = Source.of("SKILL.md", "Two.\n", "rules.md", "Rules two.\n");
    var changed = new IllegalStateException("rules.md is no longer the file that was resolved");

    var thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                install(
                    "run-2",
                    second.skill(),
                    file -> {
                      if (file.path().equals("rules.md")) {
                        throw changed;
                      }
                      return second.open(file);
                    }));

    assertSame(changed, thrown);
    assertEquals(
        with(
            Map.of("SKILL.md", "One.\n", "rules.md", "Rules one.\n"),
            StageSkillInstaller.fingerprint(first.skill())),
        installed(),
        "nothing half-installed is in place, and the stamp still names what is");
    assertEquals(List.of("acme-review"), besideTheFolder(), "the failed build is removed");
  }

  @Test
  void aFirstInstallThatFailsLeavesNoFolderAndNoStamp() {
    var source = Source.of("SKILL.md", "Body.\n", "rules.md", "Rules.\n");

    assertThrows(
        IllegalStateException.class,
        () ->
            install(
                "run-1",
                source.skill(),
                file -> {
                  if (file.path().equals("rules.md")) {
                    throw new IllegalStateException("changed");
                  }
                  return source.open(file);
                }));

    assertFalse(Files.exists(folder));
    assertFalse(Files.exists(Path.of(folder + ".run-1")));
  }

  @Test
  void aBuildFolderADeadLaunchLeftIsRemovedOnceNoLiveLaunchCanBeWritingIt() throws Exception {
    var dead = Files.createDirectories(Path.of(folder + ".dead-run"));
    Files.writeString(dead.resolve("SKILL.md"), "Half a skill.");
    var live = Files.createDirectories(Path.of(folder + ".live-run"));
    Files.writeString(live.resolve("SKILL.md"), "A launch is writing this now.");
    var neighbour = Files.createDirectories(skillsDir.resolve("acme-review-two.old-run"));
    var anHourAndABit = Instant.now().minus(Duration.ofMinutes(61));
    Files.setLastModifiedTime(dead, FileTime.from(anHourAndABit));
    Files.setLastModifiedTime(neighbour, FileTime.from(anHourAndABit));

    install("run-1", Source.of("SKILL.md", "Body.\n"));

    assertEquals(
        List.of("acme-review", "acme-review-two.old-run", "acme-review.live-run"),
        besideTheFolder(),
        "only this skill's build folders are swept, and only those old enough to be dead");
  }

  @Test
  void aBuildFolderJustUnderTheStaleAgeIsLeft() throws Exception {
    var recent = Files.createDirectories(Path.of(folder + ".slow-run"));
    Files.setLastModifiedTime(recent, FileTime.from(Instant.now().minus(Duration.ofMinutes(58))));

    install("run-1", Source.of("SKILL.md", "Body.\n"));

    assertEquals(List.of("acme-review", "acme-review.slow-run"), besideTheFolder());
  }

  @Test
  void twoLaunchesInstallingOneSkillAtOnceEachLeaveAWholeStampedFolder() throws Exception {
    install("run-0", Source.of("SKILL.md", "Old.\n"));
    var changed = Source.of("SKILL.md", "New.\n", "a.md", "A.\n", "b.md", "B.\n", "c/d.md", "D.\n");
    var whole = with(changed.contents(), StageSkillInstaller.fingerprint(changed.skill()));
    var beside = new ArrayList<Map<String, String>>();
    var mine = new ArrayList<Map<String, String>>();

    install(
        "run-a",
        changed.skill(),
        file -> {
          if (file.path().equals("b.md")) {
            try {
              install("run-b", changed);
              beside.add(installed());
              mine.add(read(Path.of(folder + ".run-a")));
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }
          return changed.open(file);
        });

    assertEquals(List.of(whole), beside, "the launch that landed first left a whole folder");
    assertEquals(
        List.of(Map.of("SKILL.md", "New.\n", "a.md", "A.\n")),
        mine,
        "and deleted nothing of the build the other launch was still writing");
    assertEquals(whole, installed(), "the launch that landed last left a whole folder too");
    assertEquals(List.of("acme-review"), besideTheFolder());

    container.commands.clear();
    install("run-c", changed);
    assertEquals(1, container.commands.size(), "the next launch of that skill writes nothing");
  }

  @Test
  void everyNameAndPathReachesTheShellOnlyAsAnArgument() throws Exception {
    var hostile = "ref/it's $(touch pwned) `touch pwned` \"q\" ; touch pwned *.md";
    var source = Source.of("SKILL.md", "Body.\n", hostile, "Reference.\n");
    folder = skillsDir.resolve("odd $(touch pwned) 'dir'");

    install("run $(touch pwned)", source);

    assertEquals(
        with(
            Map.of("SKILL.md", "Body.\n", hostile, "Reference.\n"),
            StageSkillInstaller.fingerprint(source.skill())),
        installed());
    try (Stream<Path> walk = Files.walk(home)) {
      assertEquals(
          List.of(), walk.filter(path -> path.getFileName().toString().equals("pwned")).toList());
    }
    assertFalse(Files.exists(Path.of("pwned")), "nothing ran in the working directory either");
    for (var command : container.commands) {
      var inner = LocalContainer.inner(command);
      var script = inner.indexOf("-c");
      if (script >= 0) {
        assertFalse(inner.get(script + 1).contains("pwned"), "a script holds no input: " + inner);
        assertFalse(inner.get(script + 1).contains(home.toString()), inner.toString());
      }
    }
  }

  @Test
  void eachStepThatFailsFailsTheInstallSayingWhichAndRemovesItsBuild() throws Exception {
    install("run-0", Source.of("SKILL.md", "Old.\n"));
    var before = installed();
    var changed = Source.of("SKILL.md", "New.\n", "ref/a.md", "A.\n");
    var steps = new LinkedHashMap<String, String>();
    steps.put("find", "Failed to clear stale builds of skill 'acme-review' in acme: refused");
    steps.put("mkdir -p", "Failed to create " + folder + ".run-1: refused");
    steps.put("file push", "Failed to push file to " + folder + ".run-1/SKILL.md: refused");
    steps.put("printf '%s'", "Failed to stamp skill 'acme-review' in acme: refused");
    steps.put("mv \"$2\"", "Failed to put in place skill 'acme-review' in acme: refused");

    for (var step : steps.entrySet()) {
      container.failing = step.getKey();

      var failed = assertThrows(IOException.class, () -> install("run-1", changed));

      assertEquals(step.getValue(), failed.getMessage());
      assertEquals(before, installed(), "after a failed " + step.getKey());
      assertEquals(List.of("acme-review"), besideTheFolder(), "after a failed " + step.getKey());
    }
  }

  @Test
  void theStagedHostFileIsDeletedWhetherThePushWorkedOrNot() throws Exception {
    var source = Source.of("SKILL.md", "Body.\n");
    install("run-1", source);
    var pushed = Path.of(container.push("SKILL.md").get(9));

    container.failing = "file push";
    assertThrows(IOException.class, () -> install("run-2", Source.of("SKILL.md", "Changed.\n")));
    var refused = Path.of(container.pushes().getLast().get(9));

    assertTrue(pushed.getFileName().toString().startsWith("sail-skill-"), pushed.toString());
    assertFalse(Files.exists(pushed));
    assertNotEquals(pushed, refused);
    assertFalse(Files.exists(refused));
  }

  @Test
  void aContainerThatStopsAnsweringMidInstallFailsItAndTheCleanupIsItsFootnote() {
    var source = Source.of("SKILL.md", "Body.\n");
    var gone = new TimeoutException("incus exec timed out");
    container.throwing = "file push";
    container.thrown = gone;
    container.alsoThrowing = "-- rm -rf";

    var failed = assertThrows(TimeoutException.class, () -> install("run-1", source));

    assertSame(gone, failed);
    assertEquals(1, failed.getSuppressed().length, "the discard that could not run is kept");
    assertEquals("discard refused", failed.getSuppressed()[0].getMessage());
  }

  /** The container: {@code incus exec} runs its command here, {@code incus file push} copies. */
  private static final class LocalContainer implements ShellExec {
    private final List<List<String>> commands = new ArrayList<>();
    private String failing;
    private String throwing;
    private TimeoutException thrown;
    private String alsoThrowing;
    private boolean failingReads;

    static List<String> inner(List<String> command) {
      var boundary = command.indexOf("--");
      return boundary < 0 ? command : command.subList(boundary + 1, command.size());
    }

    List<List<String>> pushes() {
      return commands.stream()
          .filter(command -> command.subList(0, 3).equals(List.of("incus", "file", "push")))
          .toList();
    }

    List<String> push(String path) {
      return pushes().stream()
          .filter(command -> command.getLast().endsWith("/" + path))
          .findFirst()
          .orElseThrow();
    }

    int indexOf(String fragment) {
      for (var i = commands.size() - 1; i >= 0; i--) {
        if (String.join(" ", commands.get(i)).contains(fragment)) {
          return i;
        }
      }
      throw new AssertionError("never ran: " + fragment);
    }

    @Override
    public Result exec(List<String> command)
        throws IOException, InterruptedException, TimeoutException {
      commands.add(List.copyOf(command));
      var joined = String.join(" ", command);
      if (throwing != null && joined.contains(throwing)) {
        throw thrown;
      }
      if (alsoThrowing != null && joined.contains(alsoThrowing)) {
        throw new IOException("discard refused");
      }
      if (failing != null && joined.contains(failing)) {
        return new Result(1, "", "refused");
      }
      if (command.subList(0, 3).equals(List.of("incus", "file", "push"))) {
        return push(command);
      }
      assertEquals(List.of("incus", "exec", PROJECT), command.subList(0, 3));
      assertEquals(List.of("--user", "1000", "--group", "1000"), command.subList(3, 7));
      var process = new ProcessBuilder(inner(command)).start();
      var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      var stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      var exit = process.waitFor();
      return new Result(
          failingReads && inner(command).getFirst().equals("cat") ? 1 : exit, stdout, stderr);
    }

    private static Result push(List<String> command) {
      var target = Path.of(command.getLast().substring(PROJECT.length()));
      var mode = Integer.parseInt(command.get(command.indexOf("--mode") + 1), 8);
      try {
        Files.copy(Path.of(command.get(command.size() - 2)), target);
        WorkspaceFiles.mode(target, mode);
        return new Result(0, "", "");
      } catch (IOException e) {
        return new Result(1, "", e.toString());
      }
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
}
