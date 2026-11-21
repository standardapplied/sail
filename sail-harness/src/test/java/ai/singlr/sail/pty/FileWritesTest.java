/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The scripts that change a workspace's files, run against a directory as {@link FileScriptsTest}
 * runs the reading ones: a save renames over a file only while it is at the version it was based
 * on, makes a new name only while no name is there, and leaves no temporary however it ends; a
 * create, a move and a remove never replace a name, never follow the last link, and say what they
 * could not do.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FileWritesTest {

  private static final String CONTAINER = FileScriptsTest.CONTAINER;

  @TempDir Path dir;
  private Path home;
  private final List<List<String>> commands = new CopyOnWriteArrayList<>();
  private final List<FileScripts.Script> started = new CopyOnWriteArrayList<>();
  private FileScripts files;

  @BeforeEach
  void setUp() throws IOException {
    home = Files.createDirectory(dir.toRealPath().resolve("home"));
    files = FileScriptsTest.over(home, commands);
  }

  @AfterEach
  void tearDown() throws IOException {
    started.forEach(FileScripts.Script::end);
    try (var all = Files.walk(dir)) {
      for (var path : all.toList()) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
          Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        }
      }
    }
  }

  /**
   * What a save answered: the fields it printed after {@code ready} and {@code copied}, and its
   * exit.
   */
  private record Saved(List<String> said, int status) {
    String last() {
      return said.isEmpty() ? null : said.getLast();
    }
  }

  /** A save under way: started, its {@code ready} read. */
  private final class Save {
    final FileScripts.Script script;
    final String first;

    Save(FileScripts with, String path, String base, long size) throws IOException {
      script = with.start(CONTAINER, FileScripts.WRITE, List.of(path, base), true, "" + size);
      started.add(script);
      first = script.field();
    }

    /** Sends {@code bytes}, reads {@code copied} and its digest, and answers {@code word}. */
    Saved send(byte[] bytes, String word) throws IOException {
      script.input().write(bytes);
      script.input().flush();
      var said = new java.util.ArrayList<String>();
      var copied = script.field();
      if (!"copied".equals(copied)) {
        if (copied != null) {
          said.add(copied);
        }
        return new Saved(said, script.status());
      }
      assertEquals(sha256(bytes), script.field(), "the digest of the bytes on disk");
      if (word != null) {
        script.input().write(FileScripts.nul(word));
      }
      script.input().close();
      for (var field = script.field(); field != null; field = script.field()) {
        said.add(field);
      }
      return new Saved(said, script.status());
    }
  }

  private Saved save(Path path, String base, String text) throws IOException {
    return save(path.toString(), base, text);
  }

  private Saved save(String path, String base, String text) throws IOException {
    var bytes = text.getBytes(StandardCharsets.UTF_8);
    var save = new Save(files, path, base, bytes.length);
    if (!"ready".equals(save.first)) {
      return new Saved(save.first == null ? List.of() : List.of(save.first), save.script.status());
    }
    return save.send(bytes, "go");
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException absent) {
      throw new AssertionError(absent);
    }
  }

  private String version(Path path) throws IOException {
    return version(path.toString());
  }

  private String version(String path) throws IOException {
    var stat = files.start(CONTAINER, FileScripts.STAT, path);
    var version = stat.field();
    stat.status();
    return version;
  }

  private int run(String script, String... fields) throws IOException {
    return run(files, script, fields);
  }

  private int run(FileScripts with, String script, String... fields) throws IOException {
    var running = with.start(CONTAINER, script, List.of(fields), false);
    started.add(running);
    while (running.field() != null) {
      continue;
    }
    return running.status();
  }

  private List<String> temporaries(Path folder) throws IOException {
    try (var names = Files.list(folder)) {
      return names
          .map(path -> path.getFileName().toString())
          .filter(name -> name.startsWith(FileScripts.SAVING))
          .toList();
    }
  }

  private FileScripts edited(UnaryOperator<String> edit) {
    return new FileScripts(
        command -> {
          var inner =
              new java.util.ArrayList<>(command.subList(command.indexOf("--") + 1, command.size()));
          inner.set(2, edit.apply(inner.get(2)));
          return new ProcessBuilder(inner).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        },
        new UploadCopy.Place(
            container -> List.of("incus", "exec", container, "--"),
            container -> List.of("incus", "exec", container, "--user", "1000", "--"),
            "/unused",
            "0",
            "1000",
            "1000",
            List.of(),
            java.time.Duration.ofSeconds(10)),
        home.toString());
  }

  @Test
  void aSaveOnItsVersionReplacesTheFileWholeKeepsItsModeAndAnswersTheVersionItHasNow()
      throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "old words");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
    var inode = Files.getAttribute(file, "unix:ino");

    var saved = save(file, version(file), "new words");

    assertEquals(0, saved.status());
    assertEquals("new words", Files.readString(file));
    assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    assertFalse(
        inode.equals(Files.getAttribute(file, "unix:ino")), "renamed over, never rewritten");
    assertEquals(version(file), saved.last(), "the version after the rename and the mode");
    assertEquals(
        0, save(file, saved.last(), "newer words").status(), "so the next save is no conflict");
    assertEquals("newer words", Files.readString(file));
    assertEquals(List.of(), temporaries(home));
  }

  @Test
  void aSaveThroughALinkReplacesItsTargetAndTheLinkStaysALink() throws Exception {
    var src = Files.createDirectory(home.resolve("src"));
    var target = Files.writeString(src.resolve("App.java"), "class App {}");
    var link = Files.createSymbolicLink(home.resolve("App.java"), target);

    assertEquals(0, save(link, version(link), "class App { }").status());

    assertTrue(Files.isSymbolicLink(link));
    assertEquals("class App { }", Files.readString(target));
    assertEquals(List.of(), temporaries(src), "the temporary was made beside the target");
  }

  @Test
  void aSaveOnAStaleVersionWritesNothingAndSaysThePresentOneOrAbsent() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "mine");
    var base = version(file);
    Files.writeString(file, "an agent's");

    var stale = save(file, base, "mine, edited");
    assertEquals(FileScripts.CONFLICT, stale.status());
    assertEquals(List.of(version(file)), stale.said());
    assertEquals("an agent's", Files.readString(file));

    Files.delete(file);
    var gone = save(file, base, "mine, edited");
    assertEquals(FileScripts.CONFLICT, gone.status());
    assertEquals(List.of("absent"), gone.said());
    assertFalse(Files.exists(file), "a save on a version never makes the file again");
    assertEquals(List.of(), temporaries(home));
  }

  @Test
  void aChangeBetweenTheCopyAndTheGoIsAConflictAndTheChangeStays() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "mine");
    var bytes = "mine, edited".getBytes(StandardCharsets.UTF_8);
    var save = new Save(files, file.toString(), version(file), bytes.length);
    assertEquals("ready", save.first);
    save.script.input().write(bytes);
    save.script.input().flush();
    assertEquals("copied", save.script.field());
    save.script.field();
    Files.writeString(file, "an agent's");

    save.script.input().write(FileScripts.nul("go"));
    save.script.input().close();

    assertEquals(version(file), save.script.field());
    assertEquals(FileScripts.CONFLICT, save.script.status());
    assertEquals("an agent's", Files.readString(file));
    assertEquals(List.of(), temporaries(home));
  }

  @Test
  void aSaveOverAFileTheUserMayNotWriteIsRefusedAndTheFileIsAsItWas() throws Exception {
    var file = Files.writeString(home.resolve("locked.md"), "kept");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));

    var saved = save(file, version(file), "replaced");

    assertEquals(FileScripts.NOT_PERMITTED, saved.status());
    assertEquals("kept", Files.readString(file));
    assertEquals(List.of(), temporaries(home));
  }

  @Test
  void aNewFileTakesItsNameOnlyWhileNoNameIsThere() throws Exception {
    var fresh = home.resolve("fresh.md");
    var saved = save(fresh, PtyMessage.ABSENT, "first");
    assertEquals(0, saved.status());
    assertEquals("first", Files.readString(fresh));
    assertEquals(version(fresh), saved.last());
    var umask =
        Integer.parseInt(
            new String(
                    new ProcessBuilder("sh", "-c", "umask").start().getInputStream().readAllBytes(),
                    StandardCharsets.US_ASCII)
                .strip(),
            8);
    assertEquals(
        0666 & ~umask,
        (int) Files.getAttribute(fresh, "unix:mode") & 0777,
        "a new file's mode is the umask's, never the temporary's 0600");

    assertEquals(FileScripts.EXISTS, save(fresh, PtyMessage.ABSENT, "second").status());
    assertEquals("first", Files.readString(fresh));
    Files.createDirectory(home.resolve("folder"));
    assertEquals(FileScripts.EXISTS, save(home.resolve("folder"), PtyMessage.ABSENT, "x").status());
    Files.createSymbolicLink(home.resolve("dangling"), home.resolve("nowhere"));
    assertEquals(
        FileScripts.EXISTS, save(home.resolve("dangling"), PtyMessage.ABSENT, "x").status());
    assertFalse(Files.exists(home.resolve("nowhere")), "a dangling link is never followed");

    var raced = home.resolve("raced.md");
    var bytes = "mine".getBytes(StandardCharsets.UTF_8);
    var save = new Save(files, raced.toString(), PtyMessage.ABSENT, bytes.length);
    save.script.input().write(bytes);
    save.script.input().flush();
    assertEquals("copied", save.script.field());
    save.script.field();
    Files.writeString(raced, "theirs");
    save.script.input().write(FileScripts.nul("go"));
    save.script.input().close();
    assertNull(save.script.field());
    assertEquals(FileScripts.EXISTS, save.script.status(), "the name taken meanwhile is kept");
    assertEquals("theirs", Files.readString(raced));

    var folder = home.resolve("raced-folder");
    var into = new Save(files, folder.toString(), PtyMessage.ABSENT, bytes.length);
    into.script.input().write(bytes);
    into.script.input().flush();
    assertEquals("copied", into.script.field());
    into.script.field();
    Files.createDirectory(folder);
    into.script.input().write(FileScripts.nul("go"));
    into.script.input().close();
    assertNull(into.script.field());
    assertEquals(FileScripts.EXISTS, into.script.status(), "a folder made meanwhile is no file's");
    try (var inside = Files.list(folder)) {
      assertEquals(0, inside.count(), "nothing was linked into it");
    }
    assertEquals(List.of(), temporaries(home));
  }

  @Test
  void aSaveCutShortOrToldNoGoWritesNothingAndLeavesNoTemporary() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "kept");

    var short_ = new Save(files, file.toString(), version(file), 10);
    short_.script.input().write("12345".getBytes(StandardCharsets.UTF_8));
    short_.script.input().close();
    assertNull(short_.script.field());
    assertEquals(FileScripts.UNWRITTEN, short_.script.status());

    var bytes = "replaced".getBytes(StandardCharsets.UTF_8);
    assertEquals(
        FileScripts.UNWRITTEN,
        new Save(files, file.toString(), version(file), bytes.length).send(bytes, "no").status());
    assertEquals(
        FileScripts.UNWRITTEN,
        new Save(files, file.toString(), version(file), bytes.length).send(bytes, null).status());

    var cut = new Save(files, file.toString(), version(file), 1 << 20);
    cut.script.input().write(new byte[4096]);
    cut.script.input().flush();
    cut.script.cut();
    assertEquals(FileScripts.UNWRITTEN, cut.script.status(), "a cut write ends by its own hand");

    assertEquals("kept", Files.readString(file));
    assertEquals(List.of(), temporaries(home));
  }

  @Test
  void aStaleTemporaryGoesAtTheNextWriteInItsFolderAndAFreshOrHeldOneStays() throws Exception {
    var src = Files.createDirectory(home.resolve("src"));
    var stale = Files.writeString(src.resolve(FileScripts.SAVING + "killed.part"), "x");
    var fresh = Files.writeString(src.resolve(FileScripts.SAVING + "under-way.part"), "x");
    var held = Files.writeString(src.resolve(FileScripts.SAVING + "held[1].part"), "x");
    var old = FileTime.from(Instant.now().minusSeconds(180));
    Files.setLastModifiedTime(stale, old);
    Files.setLastModifiedTime(held, old);
    var holder = new ProcessBuilder("sleep", "30").redirectInput(held.toFile()).start();
    try {
      assertEquals(0, save(src.resolve("App.java"), PtyMessage.ABSENT, "class App {}").status());
    } finally {
      holder.destroyForcibly();
    }

    assertFalse(Files.exists(stale), "older than the patience and held by no one");
    assertTrue(Files.exists(fresh), "a write under way keeps its temporary");
    assertTrue(Files.exists(held), "a temporary a process holds open stays");
  }

  @Test
  void aListingNeverShowsASavesTemporary() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "kept");
    var save = new Save(files, file.toString(), version(file), 4);
    assertEquals("ready", save.first);
    assertEquals(1, temporaries(home).size(), "the temporary is there, beside the file");

    var listing = files.start(CONTAINER, FileScripts.LIST, home.toString());
    listing.field();
    var names = new java.util.ArrayList<String>();
    for (var entry = listing.entry(); entry != null; entry = listing.entry()) {
      names.add(entry.name());
    }
    assertEquals(List.of("notes.md"), names);
    save.script.cut();
    assertEquals(FileScripts.UNWRITTEN, save.script.status());
  }

  @Test
  void aSaveOntoAPipeOrAFolderIsRefusedAndNothingWaits() throws Exception {
    var pipe = home.resolve("pipe");
    assertEquals(0, new ProcessBuilder("mkfifo", pipe.toString()).start().waitFor());
    var folder = Files.createDirectory(home.resolve("folder"));

    assertEquals(FileScripts.NOT_A_FILE, save(pipe, version(pipe), "x").status());
    assertEquals(FileScripts.IS_FOLDER, save(folder, version(folder), "x").status());
  }

  @Test
  void aCreateMakesAFileOrAFolderAndNeverOverANameThere() throws Exception {
    assertEquals(0, run(FileScripts.CREATE, home + "/notes.md", "file"));
    assertEquals(0, Files.size(home.resolve("notes.md")));
    assertEquals(0, run(FileScripts.CREATE, home + "/docs", "folder"));
    assertTrue(Files.isDirectory(home.resolve("docs")));

    Files.writeString(home.resolve("notes.md"), "kept");
    assertEquals(FileScripts.EXISTS, run(FileScripts.CREATE, home + "/notes.md", "file"));
    assertEquals(FileScripts.EXISTS, run(FileScripts.CREATE, home + "/notes.md", "folder"));
    assertEquals(FileScripts.EXISTS, run(FileScripts.CREATE, home + "/docs", "file"));
    Files.createSymbolicLink(home.resolve("dangling"), home.resolve("nowhere"));
    assertEquals(FileScripts.EXISTS, run(FileScripts.CREATE, home + "/dangling", "file"));
    assertEquals(FileScripts.NOT_FOUND, run(FileScripts.CREATE, home + "/none/notes.md", "file"));
    assertEquals("kept", Files.readString(home.resolve("notes.md")));
    assertFalse(Files.exists(home.resolve("nowhere")));
    assertEquals(List.of(), temporaries(home));
  }

  @Test
  void aMoveNeverReplacesANameNeverGoesIntoItselfAndMovesALinkAsALink() throws Exception {
    var a = Files.writeString(home.resolve("a.md"), "a");
    var b = Files.writeString(home.resolve("b.md"), "b");
    var src = Files.createDirectories(home.resolve("src/main"));
    var target = Files.writeString(src.resolve("App.java"), "app");
    var link = Files.createSymbolicLink(home.resolve("app"), target);

    assertEquals(FileScripts.EXISTS, run(FileScripts.MOVE, a.toString(), b.toString()));
    assertEquals("a", Files.readString(a));
    assertEquals("b", Files.readString(b));

    assertEquals(
        FileScripts.INTO_ITSELF, run(FileScripts.MOVE, home + "/src", home + "/src/main/src"));
    assertEquals(FileScripts.INTO_ITSELF, run(FileScripts.MOVE, home + "/src", home + "/src/src"));
    assertTrue(Files.isDirectory(home.resolve("src/main")));

    assertEquals(0, run(FileScripts.MOVE, a.toString(), src + "/a.md"));
    assertEquals("a", Files.readString(src.resolve("a.md")));
    assertFalse(Files.exists(a));

    assertEquals(0, run(FileScripts.MOVE, link.toString(), home + "/app-link"));
    assertTrue(Files.isSymbolicLink(home.resolve("app-link")), "the link moved, not its target");
    assertEquals("app", Files.readString(target));

    assertEquals(FileScripts.NOT_FOUND, run(FileScripts.MOVE, home + "/none", home + "/x"));
    Files.createDirectory(home.resolve("workspace"));
    assertEquals(
        FileScripts.NOT_PERMITTED, run(FileScripts.MOVE, home + "/workspace", home + "/elsewhere"));
    assertTrue(Files.isDirectory(home.resolve("workspace")));
  }

  @Test
  void aMoveWhoseSourceWentMeanwhileIsNeverCalledMoved() throws Exception {
    var a = Files.writeString(home.resolve("a.md"), "a");
    var gone =
        edited(
            script ->
                script.replace("mv -nT -- \"$src\" \"$dst\" 2>/dev/null", "rm -f -- \"$src\""));

    assertEquals(1, run(gone, FileScripts.MOVE, a.toString(), home + "/b.md"));
    var taken =
        edited(
            script ->
                script.replace("mv -nT -- \"$src\" \"$dst\" 2>/dev/null", "printf b > \"$dst\""));
    Files.writeString(a, "a");
    assertEquals(FileScripts.EXISTS, run(taken, FileScripts.MOVE, a.toString(), home + "/c.md"));
    assertEquals("a", Files.readString(a));

    var racer =
        edited(
            script ->
                script.replace(
                    "inode=$(stat -c %d:%i -- \"$src\") || exit 1",
                    "inode=$(stat -c %d:%i -- \"$src\") || exit 1; printf theirs > \"$dst\""));
    assertEquals(FileScripts.EXISTS, run(racer, FileScripts.MOVE, a.toString(), home + "/d.md"));
    assertEquals("a", Files.readString(a), "a name taken after the check is never replaced");
    assertEquals("theirs", Files.readString(home.resolve("d.md")));
  }

  @Test
  void aRemoveTakesAFolderWholeALinkAsItselfAndSaysWhatItLeft() throws Exception {
    var tree = Files.createDirectories(home.resolve("build/classes"));
    Files.writeString(tree.resolve("App.class"), "x");
    assertEquals(0, run(FileScripts.REMOVE, home + "/build"));
    assertFalse(Files.exists(home.resolve("build")));

    var kept = Files.createDirectory(home.resolve("kept"));
    Files.writeString(kept.resolve("inside"), "x");
    var link = Files.createSymbolicLink(home.resolve("kept-link"), kept);
    assertEquals(0, run(FileScripts.REMOVE, link.toString()));
    assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
    assertTrue(Files.exists(kept.resolve("inside")), "the link went, never what it led to");

    var locked = Files.createDirectories(home.resolve("locked/inner"));
    Files.writeString(locked.resolve("stays"), "x");
    Files.writeString(home.resolve("locked/goes"), "x");
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"));
    assertEquals(FileScripts.PARTLY_REMOVED, run(FileScripts.REMOVE, home + "/locked"));
    assertTrue(Files.exists(locked.resolve("stays")));

    Files.createDirectory(home.resolve("workspace"));
    assertEquals(FileScripts.NOT_PERMITTED, run(FileScripts.REMOVE, home + "/workspace"));
    assertEquals(FileScripts.OUTSIDE_HOME, run(FileScripts.REMOVE, home.toString()));
    assertEquals(FileScripts.NOT_FOUND, run(FileScripts.REMOVE, home + "/none"));
    assertTrue(Files.isDirectory(home.resolve("workspace")));
  }

  /** A name that is not UTF-8 is saved, moved and removed as its own bytes, its look-alike kept. */
  @Test
  void aNameThatIsNotUtf8IsSavedMovedAndRemovedByItsBytes() throws Exception {
    var src = Files.createDirectory(home.resolve("src"));
    var saved = shellMade(src, new byte[] {'b', 'a', 'd', (byte) 0xff, 'n'});
    var removed = shellMade(src, new byte[] {'b', 'a', 'd', (byte) 0xfe, 'n'});
    var lookalike = shellMade(src, "bad\uFFFDn".getBytes(StandardCharsets.UTF_8));

    assertEquals(0, save(saved, version(saved), "saved").status());
    assertEquals(0, run(FileScripts.MOVE, saved, src + "/renamed"));
    assertEquals(0, run(FileScripts.REMOVE, removed));

    assertEquals("saved", Files.readString(src.resolve("renamed")));
    assertEquals("body", Files.readString(Path.of(lookalike)), "the look-alike is untouched");
    try (var names = Files.list(src)) {
      assertEquals(2, names.count(), "the renamed file and the look-alike, nothing else");
    }
  }

  /** {@code name} under {@code folder}, made by the shell, since Java names files as UTF-8. */
  private static String shellMade(Path folder, byte[] name) throws Exception {
    var octal = new StringBuilder();
    for (var b : name) {
      octal.append(String.format("\\%03o", b & 0xff));
    }
    var made =
        new ProcessBuilder(
                "sh",
                "-c",
                "printf 'body' > \"$1/$(printf '" + octal + "')\"",
                "sh",
                folder.toString())
            .start();
    assertEquals(0, made.waitFor());
    return folder + "/" + FileNames.decode(name);
  }

  @Test
  void everyRefusalKeepsItsStatusInAWorkspaceOfAnotherLanguage() throws Exception {
    var german =
        FileScriptsTest.over(
            home, commands, Map.of("LANG", "de_DE.UTF-8", "LC_ALL", "de_DE.UTF-8"));
    Files.writeString(home.resolve("taken"), "x");
    assertEquals(FileScripts.EXISTS, run(german, FileScripts.CREATE, home + "/taken", "file"));
    assertEquals(FileScripts.NOT_FOUND, run(german, FileScripts.REMOVE, home + "/none"));
  }

  @Test
  void noPathNameOrByteOfASaveReachesACommandLine() throws Exception {
    var file = Files.writeString(home.resolve("secret-plan.md"), "old");
    save(file, version(file), "the secret body");
    run(FileScripts.CREATE, home + "/secret-new", "file");
    run(FileScripts.MOVE, home + "/secret-new", home + "/secret-moved");
    run(FileScripts.REMOVE, home + "/secret-moved");

    for (var command : commands) {
      assertTrue(command.stream().noneMatch(arg -> arg.contains("secret")), command.toString());
    }
    assertArrayEquals(
        new String[] {"sail-files", home.toString(), "15"},
        commands.stream()
            .filter(command -> command.contains(FileScripts.WRITE))
            .findFirst()
            .orElseThrow()
            .subList(9, 12)
            .toArray(String[]::new),
        "a save's argv holds its size alone");
  }

  /** Every process the connections' scripts ran, so a test can wait for each to end. */
  private final List<Process> processes = new CopyOnWriteArrayList<>();

  /** Replies of the connections this test opens, and the reasons any of them was ended. */
  private final LinkedBlockingQueue<PtyMessage> said = new LinkedBlockingQueue<>();

  private final List<PtyMessage> heard = new CopyOnWriteArrayList<>();

  private final List<PtyMessage.Err> endedWith = new CopyOnWriteArrayList<>();
  private final ReentrantLock lock = new ReentrantLock(true);

  /** The scripts run here, each edited by {@code edit} first, every process kept. */
  private FileScripts running(UnaryOperator<String> edit) {
    return new FileScripts(
        command -> {
          commands.add(List.copyOf(command));
          var inner =
              new java.util.ArrayList<>(command.subList(command.indexOf("--") + 1, command.size()));
          inner.set(2, edit.apply(inner.get(2)));
          var process =
              new ProcessBuilder(inner).redirectError(ProcessBuilder.Redirect.DISCARD).start();
          processes.add(process);
          return process;
        },
        new UploadCopy.Place(
            container -> List.of("incus", "exec", container, "--"),
            container -> List.of("incus", "exec", container, "--user", "1000", "--"),
            "/unused",
            "0",
            "1000",
            "1000",
            List.of(),
            java.time.Duration.ofSeconds(10)),
        home.toString());
  }

  private FilesConnection connection(FilesConnection.Gate gate) {
    return connection(running(UnaryOperator.identity()), gate);
  }

  private FilesConnection connection(FileScripts with, FilesConnection.Gate gate) {
    return new FilesConnection(
        with,
        CONTAINER,
        "dock",
        message -> {
          heard.add(message);
          said.add(message);
        },
        () -> null,
        endedWith::add,
        new FilesConnection.Writes(lock, gate));
  }

  /** Sends save {@code id} of {@code text} over {@code path} on {@code base}, bytes and end. */
  private static void save(
      FilesConnection connection, int id, String path, String base, String text)
      throws InterruptedException {
    var bytes = text.getBytes(StandardCharsets.UTF_8);
    connection.write(new PtyMessage.FilesWrite(id, path, base, bytes.length));
    connection.chunk(new PtyMessage.PutChunk(id, 0, bytes));
    connection.end(new PtyMessage.PutEnd(id, HexFormat.of().parseHex(sha256(bytes))));
  }

  /** The next reply that is not an acknowledgement; fails when none comes within 5 s. */
  private PtyMessage answer() throws InterruptedException {
    while (true) {
      var next = said.poll(5, TimeUnit.SECONDS);
      if (next == null) {
        throw new AssertionError("no answer within 5 s");
      }
      if (!(next instanceof PtyMessage.PutAck)) {
        return next;
      }
    }
  }

  /** Waits for every script to end, within 5 s each. */
  private void everyScriptEnds() throws InterruptedException {
    for (var process : processes) {
      assertTrue(process.waitFor(5, TimeUnit.SECONDS), "a script ended within 5 s");
    }
  }

  /** Waits until {@code count} threads wait for the write lock, within 5 s. */
  private void waitingForTheLock(int count) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (lock.getQueueLength() < count) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError(lock.getQueueLength() + " writes wait for the lock, not " + count);
      }
      Thread.onSpinWait();
    }
  }

  /** Two connections save one base at once: through the workspace's lock exactly one is written. */
  @Test
  void twoSavesOnOneBaseThroughTheLockWriteExactlyOne() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "base");
    var base = version(file);
    var mine = connection(() -> null);
    var theirs = connection(() -> null);

    lock.lock();
    try {
      save(mine, 1, file.toString(), base, "mine");
      save(theirs, 2, file.toString(), base, "theirs");
      waitingForTheLock(2);
    } finally {
      lock.unlock();
    }

    var answers = List.of(answer(), answer());
    assertEquals(
        1,
        answers.stream().filter(PtyMessage.FilesWritten.class::isInstance).count(),
        "" + answers);
    var lost =
        (PtyMessage.FilesConflict)
            answers.stream()
                .filter(PtyMessage.FilesConflict.class::isInstance)
                .findFirst()
                .orElseThrow();
    assertEquals(version(file), lost.version(), "the loser is told the winner's version");
    assertTrue(List.of("mine", "theirs").contains(Files.readString(file)));
    everyScriptEnds();
    assertEquals(List.of(), temporaries(home));
  }

  /** A save's bytes stream in without the lock: it is held only from the go to the version. */
  @Test
  void aSavesBytesAreCopiedWhileAnotherHoldsTheLock() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "base");
    var connection = connection(() -> null);
    lock.lock();
    try {
      save(connection, 1, file.toString(), version(file), "new");
      assertEquals(new PtyMessage.PutAck(1, 3), said.poll(5, TimeUnit.SECONDS));
      waitingForTheLock(1);
      assertEquals("base", Files.readString(file), "nothing written before its go");
    } finally {
      lock.unlock();
    }
    assertInstanceOf(PtyMessage.FilesWritten.class, answer());
  }

  /**
   * Each write asks the workspace's decision again, under the lock and after a save's bytes are
   * copied: a refusal ends the connection in its words and nothing is written; a decision that
   * cannot be asked refuses the write, and nothing is written.
   */
  @Test
  void eachWriteAsksTheDecisionUnderTheLockAndNeverWritesWithoutIt() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "base");
    var asked = new CopyOnWriteArrayList<String>();
    var refusal =
        new PtyMessage.Err(
            "Workspace 'dock' belongs to 'uday', not you.", Remedy.NONE, "forbidden");
    var refusing =
        connection(
            () -> {
              asked.add(lock.isHeldByCurrentThread() + " " + temporaries(home).size());
              return refusal;
            });

    save(refusing, 1, file.toString(), version(file), "new");
    refusing.create(new PtyMessage.FilesCreate(2, home + "/made", false));
    refusing.remove(new PtyMessage.FilesRemove(3, file.toString()));
    awaitEnded(3);
    assertEquals(List.of(refusal, refusal, refusal), endedWith);
    assertEquals(
        java.util.Set.of("true 1", "true 0"),
        java.util.Set.copyOf(asked),
        "asked under the lock, a save once its temporary holds its bytes");
    everyScriptEnds();
    assertEquals("base", Files.readString(file));
    assertFalse(Files.exists(home.resolve("made")));
    assertEquals(List.of(), temporaries(home));
    assertTrue(said.stream().noneMatch(PtyMessage.FilesWritten.class::isInstance));

    var unasked =
        connection(
            () -> {
              throw new IOException("incus list failed");
            });
    save(unasked, 4, file.toString(), version(file), "new");
    var failed = assertInstanceOf(PtyMessage.FilesRefused.class, answer());
    assertEquals(PtyMessage.FilesRefusal.FAILED, failed.kind());
    assertEquals(
        file
            + " was not changed: Sail could not ask whether you may still change dock's files;"
            + " try again.",
        failed.message());
    unasked.move(new PtyMessage.FilesMove(5, file.toString(), home + "/moved"));
    assertEquals(PtyMessage.FilesRefusal.FAILED, ((PtyMessage.FilesRefused) answer()).kind());
    everyScriptEnds();
    assertEquals("base", Files.readString(file));
    assertEquals(3, endedWith.size(), "an outage ends nothing");
  }

  private void awaitEnded(int count) {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (endedWith.size() < count) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("ended " + endedWith.size() + " times, not " + count);
      }
      Thread.onSpinWait();
    }
  }

  /** A save cut by its connection's end removes its own temporary, and the file stays. */
  @Test
  void aConnectionThatEndsMidSaveLeavesTheFileAndNoTemporary() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "kept");
    var connection = connection(() -> null);
    connection.write(new PtyMessage.FilesWrite(1, file.toString(), version(file), 1 << 20));
    connection.chunk(new PtyMessage.PutChunk(1, 0, new byte[64 << 10]));
    assertEquals(new PtyMessage.PutAck(1, 64 << 10), said.poll(5, TimeUnit.SECONDS));
    assertEquals(1, temporaries(home).size());

    connection.close();

    everyScriptEnds();
    assertEquals(List.of(), temporaries(home), "removed by the script itself, not left to a sweep");
    assertEquals("kept", Files.readString(file));
    assertNull(said.poll(), "nothing is answered after the end");
  }

  /** A save cancelled mid-body is answered no more, and removes its own temporary. */
  @Test
  void aCancelledSaveIsAnsweredNoMoreAndLeavesNoTemporary() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "kept");
    var connection = connection(() -> null);
    connection.write(new PtyMessage.FilesWrite(1, file.toString(), version(file), 1 << 20));
    connection.chunk(new PtyMessage.PutChunk(1, 0, new byte[64 << 10]));
    assertEquals(new PtyMessage.PutAck(1, 64 << 10), said.poll(5, TimeUnit.SECONDS));

    connection.cancel(new PtyMessage.FilesCancel(1));

    everyScriptEnds();
    assertEquals(List.of(), temporaries(home));
    connection.stat(new PtyMessage.FilesStat(2, file.toString()));
    assertInstanceOf(PtyMessage.FilesEntry.class, answer(), "the next answer is the stat's");
    assertEquals("kept", Files.readString(file));
  }

  /**
   * A save whose bytes are not the ones sent, out of order, past its size or past the chunks it may
   * hold is refused and writes nothing; one too large, or making a name Sail does not make, never
   * starts a script.
   */
  @Test
  void aSaveWhoseBytesAreWrongOrWhoseNameSailDoesNotMakeWritesNothing() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "kept");
    var connection = connection(() -> null);

    connection.write(new PtyMessage.FilesWrite(1, file.toString(), version(file), 3));
    connection.chunk(new PtyMessage.PutChunk(1, 0, "new".getBytes(StandardCharsets.UTF_8)));
    connection.end(new PtyMessage.PutEnd(1, new byte[32]));
    assertEquals(
        new PtyMessage.FilesRefused(
            1,
            PtyMessage.FilesRefusal.FAILED,
            file + " was not saved: the bytes Sail received are not the bytes sent."),
        answer());

    connection.write(new PtyMessage.FilesWrite(2, file.toString(), version(file), 6));
    connection.chunk(new PtyMessage.PutChunk(2, 3, "new".getBytes(StandardCharsets.UTF_8)));
    assertEquals(
        new PtyMessage.FilesRefused(
            2,
            PtyMessage.FilesRefusal.FAILED,
            file
                + " was not saved: its bytes did not arrive in order and within its size; nothing"
                + " was changed."),
        answer());
    connection.write(new PtyMessage.FilesWrite(3, file.toString(), version(file), 2));
    connection.chunk(new PtyMessage.PutChunk(3, 0, "new".getBytes(StandardCharsets.UTF_8)));
    assertEquals(PtyMessage.FilesRefusal.FAILED, ((PtyMessage.FilesRefused) answer()).kind());
    everyScriptEnds();
    var scripts = processes.size();

    connection.write(
        new PtyMessage.FilesWrite(4, file.toString(), version(file), FilesConnection.READ_CAP + 1));
    assertEquals(PtyMessage.FilesRefusal.TOO_LARGE, ((PtyMessage.FilesRefused) answer()).kind());
    var bad = home + "/" + FileNames.decode(new byte[] {'b', (byte) 0xff});
    for (var name : List.of(bad, home + "/two\nlines", home + "/" + FileScripts.SAVING + "x")) {
      connection.write(new PtyMessage.FilesWrite(5, name, PtyMessage.ABSENT, 1));
      var unsafe = (PtyMessage.FilesRefused) answer();
      assertEquals(PtyMessage.FilesRefusal.UNSAFE, unsafe.kind(), name);
      assertEquals(
          "Sail does not make "
              + FileNames.shown(name)
              + ": a name it makes is UTF-8 with no control character, and a folder never moves"
              + " into itself.",
          unsafe.message());
      connection.create(new PtyMessage.FilesCreate(6, name, true));
      assertEquals(PtyMessage.FilesRefusal.UNSAFE, ((PtyMessage.FilesRefused) answer()).kind());
      connection.move(new PtyMessage.FilesMove(7, file.toString(), name));
      assertEquals(PtyMessage.FilesRefusal.UNSAFE, ((PtyMessage.FilesRefused) answer()).kind());
    }
    assertEquals(scripts, processes.size(), "no script ran for any of them");
    assertEquals("kept", Files.readString(file));
    assertEquals(List.of(), temporaries(home));
  }

  /**
   * Every way a save ends unwritten is its own answer: a conflict found before the bytes, a full
   * disk with the bytes free, a taken name; a move onto a taken name names that name.
   */
  @Test
  void aSaveEndedUnwrittenSaysWhyAndAMoveNamesTheTakenName() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "kept");
    var base = version(file);
    Files.writeString(file, "theirs");
    var connection = connection(() -> null);
    save(connection, 1, file.toString(), base, "mine");
    assertEquals(new PtyMessage.FilesConflict(1, version(file)), answer());
    assertEquals(
        List.of(new PtyMessage.FilesConflict(1, version(file))),
        heard,
        "a stale base is told before any byte is taken");

    var full =
        connection(
            running(script -> script.replace("head -c \"$size\" > \"$tmp\"", "false")), () -> null);
    save(full, 2, file.toString(), version(file), "mine");
    var refused = (PtyMessage.FilesRefused) answer();
    assertEquals(PtyMessage.FilesRefusal.NO_SPACE, refused.kind());
    assertTrue(
        refused
            .message()
            .matches(
                java.util.regex.Pattern.quote(file + " was not saved: dock's disk has ")
                    + "[0-9]+ bytes free and the save needs 4; nothing was changed."),
        refused.message());

    save(connection, 3, file.toString(), PtyMessage.ABSENT, "mine");
    assertEquals(
        new PtyMessage.FilesRefused(
            3, PtyMessage.FilesRefusal.EXISTS, file + " exists already, so nothing was replaced."),
        answer());
    var other = Files.writeString(home.resolve("other.md"), "other");
    connection.move(new PtyMessage.FilesMove(4, other.toString(), file.toString()));
    assertEquals(
        new PtyMessage.FilesRefused(
            4, PtyMessage.FilesRefusal.EXISTS, file + " exists already, so nothing was replaced."),
        answer());
    connection.create(new PtyMessage.FilesCreate(5, home + "/made", true));
    assertEquals(new PtyMessage.FilesDone(5), answer());
    connection.move(new PtyMessage.FilesMove(6, other.toString(), home + "/made/other.md"));
    assertEquals(new PtyMessage.FilesDone(6), answer());
    connection.remove(new PtyMessage.FilesRemove(7, home + "/made"));
    assertEquals(new PtyMessage.FilesDone(7), answer());
    assertEquals("theirs", Files.readString(file));
    everyScriptEnds();
    assertEquals(List.of(), temporaries(home));
  }
}
