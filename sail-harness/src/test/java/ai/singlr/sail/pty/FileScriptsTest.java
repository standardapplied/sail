/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.pty.PtyMessage.FileEntry;
import ai.singlr.sail.pty.PtyMessage.FileKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The files scripts run here against a directory standing as the workspace's home, each command
 * handed over as a container's {@code incus exec} would be: a 5,000-entry folder, names with tabs
 * and newlines, every kind of link, an unreadable folder, a write's temporary, reads at an offset,
 * and a watch that reports each change once and nothing when nothing changed. GNU {@code find},
 * {@code stat}, {@code realpath} and {@code dd} and bash 5, as a workspace has, so Linux alone.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FileScriptsTest {

  static final String CONTAINER = "acme-dock";

  @TempDir Path dir;
  Path home;
  Path outside;
  final List<List<String>> commands = new CopyOnWriteArrayList<>();
  final List<FileScripts.Script> started = new CopyOnWriteArrayList<>();
  FileScripts files;

  /**
   * Files read in {@code home} here: a command reaches the machine only for {@link #CONTAINER},
   * after its {@code incus exec ... --}, as the dev user does in a workspace.
   */
  static FileScripts over(Path home, List<List<String>> commands) {
    var place =
        new UploadCopy.Place(
            container -> List.of("incus", "exec", container, "--"),
            container -> List.of("incus", "exec", container, "--user", "1000", "--"),
            "/unused",
            "0",
            "1000",
            "1000",
            List.of(),
            Duration.ofSeconds(10));
    return new FileScripts(
        command -> {
          commands.add(List.copyOf(command));
          if (!command.get(2).equals(CONTAINER)) {
            throw new IOException("Error: Instance not found");
          }
          var inner = command.subList(command.indexOf("--") + 1, command.size());
          return new ProcessBuilder(inner).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        },
        place,
        home.toString());
  }

  @BeforeEach
  void setUp() throws IOException {
    home = Files.createDirectory(dir.toRealPath().resolve("home"));
    outside = Files.createDirectory(dir.toRealPath().resolve("outside"));
    files = over(home, commands);
  }

  @AfterEach
  void tearDown() throws IOException {
    started.forEach(FileScripts.Script::end);
    open(dir);
  }

  /** Makes {@code folder} and every folder under it its owner's again, so it can be removed. */
  private static void open(Path folder) throws IOException {
    Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
    try (var children = Files.list(folder)) {
      for (var child : children.toList()) {
        if (Files.isDirectory(child, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
          open(child);
        }
      }
    }
  }

  /** What a listing of {@code folder} answers: its version, then every entry, by name. */
  private record Listed(String version, Map<String, FileEntry> entries, int status) {}

  private Listed list(Path folder) throws IOException {
    var script = files.start(CONTAINER, FileScripts.LIST, folder.toString());
    var version = script.field();
    var entries = new HashMap<String, FileEntry>();
    if (version != null) {
      for (var entry = script.entry(); entry != null; entry = script.entry()) {
        entries.put(entry.name(), entry);
      }
    }
    return new Listed(version, entries, script.status());
  }

  private int status(String script, Path path, String... args) throws IOException {
    var running = files.start(CONTAINER, script, path.toString(), args);
    while (running.field() != null) {
      continue;
    }
    return running.status();
  }

  @Test
  void aFolderOfFiveThousandIsListedWholeEachEntryWithItsKindSizeTimeAndMode() throws Exception {
    var big = Files.createDirectory(home.resolve("big"));
    for (var i = 0; i < 5_000; i++) {
      Files.writeString(big.resolve("src-" + i + ".java"), "x".repeat(i % 7));
    }
    Files.setPosixFilePermissions(
        big.resolve("src-3.java"), PosixFilePermissions.fromString("rwxr-x---"));

    var listed = list(big);

    assertEquals(0, listed.status());
    assertEquals(5_000, listed.entries().size());
    var three = listed.entries().get("src-3.java");
    assertEquals(FileKind.FILE, three.kind());
    assertEquals(FileKind.NONE, three.target(), "only a link has a target");
    assertEquals(3, three.size());
    assertEquals(0750, three.mode());
    assertEquals(
        Files.getLastModifiedTime(big.resolve("src-3.java")).toInstant(),
        java.time.Instant.EPOCH.plusNanos(three.mtimeNanos()));
    assertEquals(
        5, listed.version().split(":").length, "device, inode, size, mtime and ctime: " + listed);
  }

  @Test
  void aNameWithATabANewlineOrATrailingNewlineIsListedAsItIs() throws Exception {
    var odd = Files.createDirectory(home.resolve("odd\tfolder\n"));
    Files.writeString(odd.resolve("a\tb\nc é"), "");
    Files.writeString(odd.resolve("ends\n"), "");

    var listed = list(odd);

    assertEquals(0, listed.status(), "a folder whose own name ends in a newline resolves");
    assertEquals(
        java.util.Set.of("a\tb\nc é", "ends\n"), listed.entries().keySet(), listed.toString());
  }

  @Test
  void aLinkIsListedAsALinkWithWhatItLeadsToAndAWritesTemporaryIsNotListed() throws Exception {
    var tree = Files.createDirectory(home.resolve("tree"));
    Files.createDirectory(tree.resolve("src"));
    Files.createSymbolicLink(tree.resolve("to-src"), tree.resolve("src"));
    Files.createSymbolicLink(tree.resolve("nowhere"), tree.resolve("gone"));
    Files.createSymbolicLink(tree.resolve("self"), tree.resolve("self"));
    Files.writeString(outside.resolve("hosts"), "secret");
    Files.createSymbolicLink(tree.resolve("hosts"), outside.resolve("hosts"));
    Files.writeString(tree.resolve(FileScripts.WRITING + "App.java.7"), "half");
    Files.createFile(tree.resolve("App.java"));

    var entries = list(tree).entries();

    assertEquals(
        java.util.Set.of("src", "to-src", "nowhere", "self", "hosts", "App.java"),
        entries.keySet());
    assertEquals(FileKind.FOLDER, entries.get("src").kind());
    assertEquals(List.of(FileKind.LINK, FileKind.FOLDER), kinds(entries.get("to-src")));
    assertEquals(List.of(FileKind.LINK, FileKind.MISSING), kinds(entries.get("nowhere")));
    assertEquals(List.of(FileKind.LINK, FileKind.LOOP), kinds(entries.get("self")));
    assertEquals(List.of(FileKind.LINK, FileKind.FILE), kinds(entries.get("hosts")));
  }

  private static List<FileKind> kinds(FileEntry entry) {
    return List.of(entry.kind(), entry.target());
  }

  @Test
  void aLinkToAFolderInTheHomeListsItsTargetAndOneThatLeavesItIsRefused() throws Exception {
    var src = Files.createDirectory(home.resolve("src"));
    Files.createFile(src.resolve("App.java"));
    Files.createSymbolicLink(home.resolve("to-src"), src);
    Files.createSymbolicLink(home.resolve("etc"), outside);
    Files.writeString(outside.resolve("hosts"), "secret");
    Files.createSymbolicLink(home.resolve("hosts"), outside.resolve("hosts"));

    var through = list(home.resolve("to-src"));

    assertEquals(java.util.Set.of("App.java"), through.entries().keySet());
    assertEquals(list(src).version(), through.version(), "the version is the target's");
    assertEquals(FileScripts.OUTSIDE_HOME, list(home.resolve("etc")).status());
    assertNull(list(home.resolve("etc")).version(), "nothing outside the home is said");
    assertEquals(
        FileScripts.OUTSIDE_HOME, status(FileScripts.READ, home.resolve("hosts"), "0", "9"));
    assertEquals(FileScripts.OUTSIDE_HOME, status(FileScripts.STAT, home.resolve("hosts")));
    assertEquals(
        FileScripts.OUTSIDE_HOME,
        status(FileScripts.LIST, home.resolve("src/../../outside")),
        "a path that climbs out is refused once resolved");
  }

  @Test
  void eachRefusalIsItsOwnStatus() throws Exception {
    var locked = Files.createDirectory(home.resolve("build"));
    Files.createFile(locked.resolve("out.class"));
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("-wx------"));
    var shut = Files.createDirectory(home.resolve("shut"));
    Files.createFile(shut.resolve("inner"));
    Files.setPosixFilePermissions(shut, PosixFilePermissions.fromString("rw-------"));
    Files.createSymbolicLink(home.resolve("self"), home.resolve("self"));
    var file = Files.writeString(home.resolve("notes.md"), "notes");
    var unreadable = Files.writeString(home.resolve("key"), "k");
    Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("-w-------"));
    var pipe = home.resolve("pipe");
    assertEquals(0, new ProcessBuilder("mkfifo", pipe.toString()).start().waitFor());

    assertEquals(FileScripts.NOT_PERMITTED, list(locked).status(), "an unreadable folder");
    assertEquals(FileScripts.NOT_PERMITTED, list(shut).status(), "a folder it may not enter");
    assertEquals(
        FileScripts.NOT_PERMITTED,
        status(FileScripts.STAT, shut.resolve("inner")),
        "a path through a folder it may not search");
    assertEquals(FileScripts.NOT_FOUND, list(home.resolve("gone")).status());
    assertEquals(FileScripts.NOT_FOUND, list(file.resolve("below")).status());
    assertEquals(FileScripts.LOOP, list(home.resolve("self")).status());
    assertEquals(FileScripts.NOT_A_FOLDER, list(file).status());
    assertEquals(FileScripts.IS_FOLDER, status(FileScripts.READ, home, "0", "9"));
    assertEquals(FileScripts.NOT_A_FILE, status(FileScripts.READ, pipe, "0", "9"));
    assertEquals(FileScripts.NOT_PERMITTED, status(FileScripts.READ, unreadable, "0", "9"));
    assertEquals(FileScripts.LOOP, status(FileScripts.READ, home.resolve("self"), "0", "9"));
    assertEquals(FileScripts.NOT_FOUND, status(FileScripts.STAT, home.resolve("gone")));
    for (var status = 20; status <= 26; status++) {
      assertEquals(
          status,
          List.of(20, 21, 22, 23, 24, 25, 26).get(FileScripts.refusalOf(status).ordinal()),
          "each status is its refusal, in order");
    }
    assertNull(FileScripts.refusalOf(1), "a status the scripts do not name is no refusal");
  }

  @Test
  void aReadGivesTheVersionAndSizeThenTheBytesFromItsOffsetAndAStatSaysWhatAPathIs()
      throws Exception {
    var bytes = new byte[300_000];
    for (var i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) (i * 31);
    }
    var file = Files.write(home.resolve("data.bin"), bytes);

    var read = files.start(CONTAINER, FileScripts.READ, file.toString(), "1000", "262144");
    var version = read.field();
    assertEquals("300000", read.field());
    assertArrayEquals(java.util.Arrays.copyOfRange(bytes, 1000, 263_144), read.bytes(1 << 20));
    assertEquals(0, read.status());

    var stat = files.start(CONTAINER, FileScripts.STAT, file.toString());
    assertEquals(version, stat.field(), "a read and a stat say one version");
    var entry = stat.entry();
    assertEquals("data.bin", entry.name());
    assertEquals(List.of(FileKind.FILE, FileKind.NONE), kinds(entry));
    assertEquals(300_000, entry.size());
    assertNull(stat.entry());
    assertEquals(0, stat.status());

    Files.writeString(file, "rewritten");
    var again = files.start(CONTAINER, FileScripts.STAT, file.toString());
    assertNotEquals(version, again.field(), "a rewrite is a new version");
    var home = files.start(CONTAINER, FileScripts.STAT, this.home.toString());
    home.field();
    assertEquals("home", home.entry().name(), "the home itself is in the home");
  }

  @Test
  void noPathOrNameReachesACommandLine() throws Exception {
    var secret = Files.createDirectory(home.resolve("secret-plan"));
    list(secret);
    status(FileScripts.READ, secret, "0", "1");
    status(FileScripts.STAT, secret);

    assertEquals(3, commands.size());
    for (var command : commands) {
      assertTrue(
          command.stream().noneMatch(arg -> arg.contains("secret-plan")), command.toString());
      assertEquals(
          List.of("incus", "exec", CONTAINER, "--user", "1000", "--"), command.subList(0, 6));
    }
  }

  @Test
  void onlyTheHomeAndWhatIsUnderItMayBeAskedAndNoNameHoldsANul() {
    assertTrue(files.inHome(home.toString()));
    assertTrue(files.inHome(home + "/workspace/src"));
    assertFalse(files.inHome(home + "-other/x"), "a sibling that shares the prefix is outside");
    assertFalse(files.inHome("/etc/hosts"));
    assertFalse(files.inHome("workspace/src"), "a relative path is never resolved against /");
    assertFalse(files.inHome(home + "/a\0b"));
  }

  @Test
  void findsTimesAndLettersReadAsTheyMean() {
    assertEquals(1_795_210_162_152_082_747L, FileScripts.nanos("1795210162.1520827470"));
    assertEquals(-1_500_000_000L, FileScripts.nanos("-1.5"));
    assertEquals(-500_000_000L, FileScripts.nanos("-0.5"));
    assertEquals(7_000_000_000L, FileScripts.nanos("7"));
    assertEquals(FileKind.OTHER, FileScripts.kindOf("p"));
    assertEquals(FileKind.OTHER, FileScripts.kindOf("s"));
    assertEquals(FileKind.MISSING, FileScripts.targetOf("?"));
  }

  /** A watch loop over {@code home}, its output read field by field on a thread of its own. */
  private final class Watch {
    final FileScripts.Script loop;
    final java.util.concurrent.LinkedBlockingQueue<String> said =
        new java.util.concurrent.LinkedBlockingQueue<>();
    final Thread reader;

    Watch() throws IOException {
      loop = files.start(CONTAINER, FileScripts.WATCH, null);
      started.add(loop);
      reader =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      for (var field = loop.field(); field != null; field = loop.field()) {
                        said.add(field);
                      }
                    } catch (IOException ended) {
                      var unused = ended;
                    }
                    said.add("<end>");
                  });
    }

    /** Sends a watch set: each path from the version the client holds of it. */
    void set(Map<Path, String> paths) throws IOException {
      var bytes = new java.io.ByteArrayOutputStream();
      for (var watched : paths.entrySet()) {
        bytes.write(FileScripts.nul(watched.getKey().toString()));
        bytes.write(FileScripts.nul(watched.getValue()));
      }
      bytes.write(0);
      loop.input().write(bytes.toByteArray());
      loop.input().flush();
    }

    /** The next batch of changed paths, skipping beats; fails when none comes within 5 s. */
    List<String> changed() throws InterruptedException {
      var batch = new ArrayList<String>();
      var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < deadline) {
        var field = said.poll(100, TimeUnit.MILLISECONDS);
        if (field == null) {
          continue;
        }
        assertNotEquals("<end>", field, "the loop ended");
        if (!field.isEmpty()) {
          batch.add(field);
        } else if (!batch.isEmpty()) {
          return batch;
        }
      }
      throw new AssertionError("no change was reported within 5 s; batch so far " + batch);
    }

    /** Waits for {@code beats} beats, failing on any change said meanwhile. */
    void onlyBeats(int beats) throws InterruptedException {
      var seen = 0;
      while (seen < beats) {
        var field = said.poll(5, TimeUnit.SECONDS);
        assertEquals("", field, "a beat, and nothing changed");
        seen++;
      }
    }
  }

  private String version(Path path) throws IOException {
    var stat = files.start(CONTAINER, FileScripts.STAT, path.toString());
    var version = stat.field();
    stat.status();
    return version;
  }

  @Test
  void aWatchReportsAnAddARenameADeleteAndARewriteOnceAndNothingWhenNothingChanged()
      throws Exception {
    var src = Files.createDirectory(home.resolve("src"));
    var app = Files.writeString(src.resolve("App.java"), "class App {}");
    var watch = new Watch();
    watch.set(Map.of(src, version(src), app, version(app)));

    watch.onlyBeats(2);
    Files.createFile(src.resolve("New.java"));
    assertEquals(List.of(src.toString()), watch.changed(), "an add");
    Files.move(src.resolve("New.java"), src.resolve("Renamed.java"));
    assertEquals(List.of(src.toString()), watch.changed(), "a rename");
    Files.delete(src.resolve("Renamed.java"));
    assertEquals(List.of(src.toString()), watch.changed(), "a delete");
    Files.writeString(app, "class App { int x; }");
    assertEquals(List.of(app.toString()), watch.changed(), "a rewrite");
    watch.onlyBeats(2);
    Files.delete(app);
    assertEquals(List.of(app.toString(), src.toString()), sorted(watch.changed()), "a removal");
  }

  private static List<String> sorted(List<String> paths) {
    return paths.stream().sorted(java.util.Comparator.reverseOrder()).toList();
  }

  @Test
  void aChangeMadeBeforeTheWatchTookThePathIsReportedAndANewSetReplacesTheOld() throws Exception {
    var src = Files.createDirectory(home.resolve("src"));
    var docs = Files.createDirectory(home.resolve("docs"));
    var seen = version(src);
    Files.createFile(src.resolve("Late.java"));
    var watch = new Watch();

    watch.set(Map.of(src, seen));
    assertEquals(List.of(src.toString()), watch.changed(), "held at an older version");
    watch.set(Map.of(docs, version(docs)));
    watch.onlyBeats(1);
    Files.createFile(src.resolve("Unwatched.java"));
    watch.onlyBeats(2);
    var gone = home.resolve("gone");
    watch.set(Map.of(gone, ""));
    watch.onlyBeats(1);
    Files.createDirectory(gone);
    assertEquals(List.of(gone.toString()), watch.changed(), "held missing, then made");
  }

  @Test
  void theLoopEndsWhenItsInputClosesAndAPathCutAcrossWritesIsWhole() throws Exception {
    var src = Files.createDirectory(home.resolve("src"));
    var watch = new Watch();
    var path = FileScripts.nul(src.toString());
    watch.loop.input().write(path, 0, 4);
    watch.loop.input().flush();
    watch.onlyBeats(1);
    watch.loop.input().write(path, 4, path.length - 4);
    watch.loop.input().write(FileScripts.nul("stale"));
    watch.loop.input().write(0);
    watch.loop.input().flush();
    assertEquals(List.of(src.toString()), watch.changed(), "the path read whole across writes");

    watch.loop.input().close();
    assertEquals(0, watch.loop.status(), "its input closed, it ended");
    watch.reader.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse(watch.reader.isAlive());
  }

  @Test
  void aScriptForAnInstanceThatIsNotThereFailsToStart() {
    var other = over(home, commands);
    var refused =
        org.junit.jupiter.api.Assertions.assertThrows(
            IOException.class, () -> other.start("acme-gone", FileScripts.LIST, "/x"));
    assertEquals("Error: Instance not found", refused.getMessage());
  }
}
