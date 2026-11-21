/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    files = scripts(UnaryOperator.identity(), Map.of());
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

    Save(FileScripts with, String path, String base, String seen, long size) throws IOException {
      script =
          with.start(
              CONTAINER,
              FileScripts.WRITE,
              List.of(path, base, seen),
              true,
              "" + size,
              "" + FilesConnection.READ_CAP);
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

  private Saved save(Path path, String base, String seen, String text) throws IOException {
    return save(path.toString(), base, seen, text);
  }

  private Saved save(String path, String base, String seen, String text) throws IOException {
    var bytes = text.getBytes(StandardCharsets.UTF_8);
    var save = new Save(files, path, base, seen, bytes.length);
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

  /** The SHA-256 of what {@code path} holds now, as a whole read gives it: a save's base. */
  private static String digest(Path path) throws IOException {
    return sha256(Files.readAllBytes(path));
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
          .filter(
              name ->
                  name.matches(
                      java.util.regex.Pattern.quote(FileScripts.SAVING) + "[A-Za-z0-9]{10}\\.part"))
          .toList();
    }
  }

  /**
   * What gives each script sockets for its input and its output, as {@code incus exec} does, not
   * pipes: a script that reopened a descriptor by path would fail here as it fails there. It relays
   * this process's input to the script and the script's output back, an end of input as the end of
   * the socket, and exits with the script's status; the script dies with it, as a workspace's
   * process ends once its {@code incus exec} has gone.
   */
  private static final String SOCKETS =
      """
      import ctypes, os, socket, subprocess, sys, threading
      given, taken = socket.socketpair()
      said, says = socket.socketpair()
      def dies_with_relay():
          ctypes.CDLL(None).prctl(1, 9)
      script = subprocess.Popen(sys.argv[1:], stdin=taken, stdout=says, preexec_fn=dies_with_relay)
      taken.close()
      says.close()
      def relay():
          try:
              while True:
                  data = os.read(0, 65536)
                  if not data:
                      break
                  given.sendall(data)
              given.shutdown(socket.SHUT_WR)
          except OSError:
              pass
      threading.Thread(target=relay, daemon=True).start()
      while True:
          data = said.recv(65536)
          if not data:
              break
          os.write(1, data)
      code = script.wait()
      sys.exit(code if code >= 0 else 128 - code)
      """;

  private FileScripts edited(UnaryOperator<String> edit) {
    return scripts(edit, Map.of());
  }

  /**
   * The scripts run here as {@link FileScriptsTest#over} runs them, each edited by {@code edit}
   * first, with {@code env} in its environment and sockets for its input and output ({@link
   * #SOCKETS}); every command and process kept.
   */
  private FileScripts scripts(UnaryOperator<String> edit, Map<String, String> env) {
    return new FileScripts(
        command -> {
          commands.add(List.copyOf(command));
          var inner =
              new java.util.ArrayList<>(command.subList(command.indexOf("--") + 1, command.size()));
          inner.set(2, edit.apply(inner.get(2)));
          inner.addAll(0, List.of("python3", "-I", "-c", SOCKETS));
          var builder = new ProcessBuilder(inner).redirectError(ProcessBuilder.Redirect.DISCARD);
          builder.environment().putAll(env);
          var process = builder.start();
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

  /** Every script here reads and writes sockets, as under {@code incus exec}, never pipes. */
  @Test
  void everyScriptHereHasSocketsForItsInputAndOutput() throws Exception {
    var probed = edited(script -> "[ -S /proc/self/fd/0 ] && [ -S /proc/self/fd/1 ] || exit 9");
    assertEquals(0, run(probed, FileScripts.REMOVE, home + "/none"));
  }

  @Test
  void aSaveOnItsVersionReplacesTheFileWholeKeepsItsModeAndAnswersTheVersionItHasNow()
      throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "old words");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
    var inode = Files.getAttribute(file, "unix:ino");

    var saved = save(file, digest(file), version(file), "new words");

    assertEquals(0, saved.status());
    assertEquals("new words", Files.readString(file));
    assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    assertFalse(
        inode.equals(Files.getAttribute(file, "unix:ino")), "renamed over, never rewritten");
    assertEquals(version(file), saved.last(), "the version after the rename and the mode");
    assertEquals(
        0,
        save(
                file,
                sha256("new words".getBytes(StandardCharsets.UTF_8)),
                saved.last(),
                "newer words")
            .status(),
        "so the next save, on the digest it saved and the version it answered, is no conflict");
    assertEquals("newer words", Files.readString(file));
    assertEquals(List.of(), temporaries(home));
  }

  @Test
  void aSaveThroughALinkReplacesItsTargetAndTheLinkStaysALink() throws Exception {
    var src = Files.createDirectory(home.resolve("src"));
    var target = Files.writeString(src.resolve("App.java"), "class App {}");
    var link = Files.createSymbolicLink(home.resolve("App.java"), target);

    assertEquals(0, save(link, digest(link), version(link), "class App { }").status());

    assertTrue(Files.isSymbolicLink(link));
    assertEquals("class App { }", Files.readString(target));
    assertEquals(List.of(), temporaries(src), "the temporary was made beside the target");
  }

  @Test
  void aSaveOnAStaleVersionWritesNothingAndSaysThePresentOneOrAbsent() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "mine");
    var base = digest(file);
    var seen = version(file);
    Files.writeString(file, "an agent's");

    var stale = save(file, base, seen, "mine, edited");
    assertEquals(FileScripts.CONFLICT, stale.status());
    assertEquals(List.of(digest(file)), stale.said(), "the present content's digest");
    assertEquals("an agent's", Files.readString(file));

    Files.delete(file);
    var gone = save(file, base, seen, "mine, edited");
    assertEquals(FileScripts.CONFLICT, gone.status());
    assertEquals(List.of("absent"), gone.said());
    assertFalse(Files.exists(file), "a save on a version never makes the file again");
    assertEquals(List.of(), temporaries(home));
  }

  @Test
  void aChangeBetweenTheCopyAndTheGoIsAConflictAndTheChangeStays() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "mine");
    var bytes = "mine, edited".getBytes(StandardCharsets.UTF_8);
    var save = new Save(files, file.toString(), digest(file), version(file), bytes.length);
    assertEquals("ready", save.first);
    save.script.input().write(bytes);
    save.script.input().flush();
    assertEquals("copied", save.script.field());
    save.script.field();
    Files.writeString(file, "an agent's");

    save.script.input().write(FileScripts.nul("go"));
    save.script.input().close();

    assertEquals(digest(file), save.script.field(), "the present content's digest");
    assertEquals(FileScripts.CONFLICT, save.script.status());
    assertEquals("an agent's", Files.readString(file));
    assertEquals(List.of(), temporaries(home));
  }

  @Test
  void aSaveOverAFileTheUserMayNotWriteIsRefusedAndTheFileIsAsItWas() throws Exception {
    var file = Files.writeString(home.resolve("locked.md"), "kept");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));

    var saved = save(file, digest(file), version(file), "replaced");

    assertEquals(FileScripts.NOT_PERMITTED, saved.status());
    assertEquals("kept", Files.readString(file));
    assertEquals(List.of(), temporaries(home));
  }

  @Test
  void aNewFileTakesItsNameOnlyWhileNoNameIsThere() throws Exception {
    var fresh = home.resolve("fresh.md");
    var saved = save(fresh, PtyMessage.ABSENT, "", "first");
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

    assertEquals(FileScripts.EXISTS, save(fresh, PtyMessage.ABSENT, "", "second").status());
    assertEquals("first", Files.readString(fresh));
    Files.createDirectory(home.resolve("folder"));
    assertEquals(
        FileScripts.EXISTS, save(home.resolve("folder"), PtyMessage.ABSENT, "", "x").status());
    Files.createSymbolicLink(home.resolve("dangling"), home.resolve("nowhere"));
    assertEquals(
        FileScripts.EXISTS, save(home.resolve("dangling"), PtyMessage.ABSENT, "", "x").status());
    assertFalse(Files.exists(home.resolve("nowhere")), "a dangling link is never followed");

    var raced = home.resolve("raced.md");
    var bytes = "mine".getBytes(StandardCharsets.UTF_8);
    var save = new Save(files, raced.toString(), PtyMessage.ABSENT, "", bytes.length);
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
    var into = new Save(files, folder.toString(), PtyMessage.ABSENT, "", bytes.length);
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

    var short_ = new Save(files, file.toString(), digest(file), version(file), 10);
    short_.script.input().write("12345".getBytes(StandardCharsets.UTF_8));
    short_.script.input().close();
    assertNull(short_.script.field());
    assertEquals(FileScripts.UNWRITTEN, short_.script.status());

    var bytes = "replaced".getBytes(StandardCharsets.UTF_8);
    assertEquals(
        FileScripts.UNWRITTEN,
        new Save(files, file.toString(), digest(file), version(file), bytes.length)
            .send(bytes, "no")
            .status());
    assertEquals(
        FileScripts.UNWRITTEN,
        new Save(files, file.toString(), digest(file), version(file), bytes.length)
            .send(bytes, null)
            .status());

    var cut = new Save(files, file.toString(), digest(file), version(file), 1 << 20);
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
    var stale = Files.writeString(src.resolve(FileScripts.SAVING + "Killed0001.part"), "x");
    var fresh = Files.writeString(src.resolve(FileScripts.SAVING + "UnderWay01.part"), "x");
    var held = Files.writeString(src.resolve(FileScripts.SAVING + "HeldOpen01.part"), "x");
    var persons =
        List.of(
            Files.writeString(src.resolve(FileScripts.SAVING + "notes"), "a person's"),
            Files.writeString(src.resolve(FileScripts.SAVING + "notes.part"), "a person's"),
            Files.writeString(src.resolve(FileScripts.SAVING + "Short01.part"), "a person's"),
            Files.writeString(src.resolve(FileScripts.SAVING + "TooLong0001x.part"), "x"),
            Files.writeString(src.resolve(FileScripts.SAVING + "has_under.part"), "x"));
    var old = FileTime.from(Instant.now().minusSeconds(600));
    Files.setLastModifiedTime(stale, old);
    Files.setLastModifiedTime(held, old);
    for (var person : persons) {
      Files.setLastModifiedTime(person, old);
    }
    var holder = new ProcessBuilder("sleep", "30").redirectInput(held.toFile()).start();
    try {
      assertEquals(
          0, save(src.resolve("App.java"), PtyMessage.ABSENT, "", "class App {}").status());
    } finally {
      holder.destroyForcibly();
    }

    assertFalse(Files.exists(stale), "older than the patience and held by no one");
    assertTrue(Files.exists(fresh), "a write under way keeps its temporary");
    assertTrue(Files.exists(held), "a temporary a process holds open stays");
    for (var person : persons) {
      assertTrue(
          Files.exists(person), "m3: a name of Sail's prefix but not its shape stays: " + person);
    }
    var listing = files.start(CONTAINER, FileScripts.LIST, src.toString());
    listing.field();
    var names = new java.util.HashSet<String>();
    for (var entry = listing.entry(); entry != null; entry = listing.entry()) {
      names.add(entry.name());
    }
    assertTrue(
        names.containsAll(persons.stream().map(p -> p.getFileName().toString()).toList()),
        "m3: a listing shows a person's names of Sail's prefix: " + names);
    assertFalse(names.contains(fresh.getFileName().toString()), "and hides Sail's own shape");
  }

  /**
   * m3: a save waiting for its go keeps its temporary open, so another save's sweep in its folder
   * never takes it, however long the wait.
   */
  @Test
  void aWaitingSavesTemporaryIsNeverSweptByAnotherSave() throws Exception {
    var waiting = new Save(files, home + "/first.md", PtyMessage.ABSENT, "", 5);
    waiting.script.input().write("first".getBytes(StandardCharsets.UTF_8));
    waiting.script.input().flush();
    assertEquals("copied", waiting.script.field());
    waiting.script.field();
    var temporary = home.resolve(temporaries(home).getFirst());
    Files.setLastModifiedTime(temporary, FileTime.from(Instant.now().minusSeconds(600)));

    assertEquals(0, save(home.resolve("second.md"), PtyMessage.ABSENT, "", "second").status());
    assertTrue(Files.exists(temporary), "held open by the waiting save, so not swept");

    waiting.script.input().write(FileScripts.nul("go"));
    waiting.script.input().close();
    assertNotNull(waiting.script.field());
    assertEquals(0, waiting.script.status());
    assertEquals("first", Files.readString(home.resolve("first.md")));
  }

  /** A {@code bin} of this machine's programs with {@code sha256sum} as {@code fake}, or none. */
  private Path binWith(String fake) throws Exception {
    var bin =
        Files.createDirectories(dir.resolve("bin-" + (fake == null ? "none" : fake.hashCode())));
    for (var from : List.of(Path.of("/usr/bin"), Path.of("/bin"))) {
      try (var programs = Files.list(from)) {
        for (var program : programs.toList()) {
          var to = bin.resolve(program.getFileName());
          if (!program.getFileName().toString().equals("sha256sum")
              && !Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
            Files.createSymbolicLink(to, program);
          }
        }
      }
    }
    if (fake != null) {
      var sha = Files.writeString(bin.resolve("sha256sum"), "#!/bin/sh\n" + fake + "\n");
      Files.setPosixFilePermissions(sha, PosixFilePermissions.fromString("rwx------"));
    }
    return bin;
  }

  /**
   * Digest rule 1: every digest a save computes is 64 lowercase hex, or nothing is written: with no
   * {@code sha256sum}, or one that prints nothing or no SHA-256, two digests never agree.
   */
  @Test
  void aSaveIsDecidedOnlyByGenuineDigests() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "kept");
    var base = digest(file);
    for (var fake :
        java.util.Arrays.asList(null, "cat > /dev/null", "cat > /dev/null; echo 'zzzz  -'")) {
      var broken = scripts(UnaryOperator.identity(), Map.of("PATH", binWith(fake).toString()));
      var early = new Save(broken, file.toString(), base, "a stat that differs", 3);
      assertNull(early.first, "sha256sum " + fake + ": no digest, no ready");
      assertEquals(FileScripts.BAD_DIGEST, early.script.status(), "sha256sum: " + fake);
      var late = new Save(broken, file.toString(), base, version(file), 3);
      assertEquals("ready", late.first);
      assertEquals(
          FileScripts.BAD_DIGEST, late.send("new".getBytes(StandardCharsets.UTF_8), "go").status());
      assertEquals("kept", Files.readString(file), "nothing written: " + fake);
    }
    assertEquals(List.of(), temporaries(home));
  }

  /**
   * Digest rule 2: after its go, a save hashes the target WHOLE, so a change anywhere in it, of the
   * same size, is a conflict; and a change that leaves the bytes as they were (a {@code touch}) is
   * none, before its bytes or after its go.
   */
  @Test
  void aSaveComparesTheWholeTargetsContentNotItsStat() throws Exception {
    var bytes = new byte[300_000];
    java.util.Arrays.fill(bytes, (byte) 'a');
    var file = Files.write(home.resolve("big.txt"), bytes);
    var base = digest(file);
    var save = new Save(files, file.toString(), base, version(file), 3);
    save.script.input().write("new".getBytes(StandardCharsets.UTF_8));
    save.script.input().flush();
    assertEquals("copied", save.script.field());
    save.script.field();
    bytes[bytes.length - 1] = 'b';
    Files.write(file, bytes);
    save.script.input().write(FileScripts.nul("go"));
    save.script.input().close();
    assertEquals(digest(file), save.script.field(), "its last byte changed: a conflict");
    assertEquals(FileScripts.CONFLICT, save.script.status());

    var touched = Files.writeString(home.resolve("touched.md"), "same");
    var read = digest(touched);
    var seen = version(touched);
    Files.setLastModifiedTime(touched, FileTime.from(Instant.now().plusSeconds(60)));
    assertEquals(0, save(touched, read, seen, "after a touch").status(), "the bytes are as read");
    assertEquals("after a touch", Files.readString(touched));
  }

  /**
   * Digest rule 3: a base from a ranged read is no whole file's digest, so the compare refuses it
   * as a conflict and never writes over the file.
   */
  @Test
  void aBaseFromARangedReadIsAConflictNeverAnOverwrite() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "0123456789");
    var ranged = sha256("012".getBytes(StandardCharsets.UTF_8));

    var saved = save(file, ranged, version(file), "mine");
    assertEquals(FileScripts.CONFLICT, saved.status());
    var late = save(file, ranged, "a stat that differs", "mine");
    assertEquals(FileScripts.CONFLICT, late.status());
    assertEquals("0123456789", Files.readString(file));
  }

  /**
   * Digest rule 4: a file over 20 MiB can never have a whole read's digest, so it is never saved:
   * refused before any of its bytes, before it is hashed.
   */
  @Test
  void aTargetOverTwentyMebibytesIsRefusedBeforeItsBytes() throws Exception {
    var file = home.resolve("huge.bin");
    try (var huge =
        java.nio.channels.FileChannel.open(
            file,
            java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE)) {
      huge.truncate(FilesConnection.READ_CAP + 1);
      huge.write(java.nio.ByteBuffer.wrap(new byte[] {1}), FilesConnection.READ_CAP);
    }
    var save = new Save(files, file.toString(), sha256(new byte[0]), "a stat that differs", 3);
    assertNull(save.first, "no ready");
    assertEquals(FileScripts.TOO_LARGE, save.script.status());
  }

  /** m4: a target the dev user may not write is refused before any of its bytes are taken. */
  @Test
  void aTargetTheUserMayNotWriteIsRefusedBeforeItsBytes() throws Exception {
    var file = Files.writeString(home.resolve("locked.md"), "kept");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));

    var save = new Save(files, file.toString(), digest(file), version(file), 1 << 20);

    assertNull(save.first, "no ready: refused before its bytes");
    assertEquals(FileScripts.NOT_PERMITTED, save.script.status());
    assertEquals(List.of(), temporaries(home));
  }

  /**
   * m5: a name is said taken only when it is: a folder gone before the link or the mkdir is said
   * gone, and a move onto another disk inside the home is refused before it starts.
   */
  @Test
  void aNameIsSaidTakenOnlyWhenItIsAndAMoveAcrossDisksIsRefusedFirst() throws Exception {
    var folder = Files.createDirectory(home.resolve("going"));
    var gone =
        edited(
            script ->
                script
                    .replace(
                        "ln -T -- \"$tmp\" \"$target\"",
                        "rm -rf -- \"$dir\"; ln -T -- \"$tmp\" \"$target\"")
                    .replace("mkdir -- \"$target\"", "rm -rf -- \"$dir\"; mkdir -- \"$target\""));
    assertEquals(
        FileScripts.FOLDER_GONE, run(gone, FileScripts.CREATE, folder + "/new.md", "file"));
    Files.createDirectory(folder);
    assertEquals(FileScripts.FOLDER_GONE, run(gone, FileScripts.CREATE, folder + "/new", "folder"));

    var bin = Files.createDirectory(dir.resolve("bin"));
    var mount = Files.createDirectory(home.resolve("mounted"));
    var fake = bin.resolve("stat");
    Files.writeString(
        fake,
        """
        #!/bin/sh
        for last; do :; done
        if [ "$1" = -c ] && [ "$2" = %%d ] && [ "$last" = '%s' ]; then echo 4242; exit 0; fi
        exec /usr/bin/stat "$@"
        """
            .formatted(mount));
    Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"));
    var otherDisk =
        scripts(UnaryOperator.identity(), Map.of("PATH", bin + ":" + System.getenv("PATH")));
    var a = Files.writeString(home.resolve("a.md"), "a");
    assertEquals(
        FileScripts.OTHER_DISK, run(otherDisk, FileScripts.MOVE, a.toString(), mount + "/a.md"));
    assertEquals("a", Files.readString(a), "nothing moved");
    assertFalse(Files.exists(mount.resolve("a.md")));
  }

  /** m2: a save's script past its go is bounded: past it, killed, the lock freed only once dead. */
  @Test
  void aSavePastItsGoIsBoundedAndItsLockFreedOnlyOnceItsScriptIsDead() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "kept");
    var hung =
        new FilesConnection(
            running(
                script ->
                    script.replace(
                        "IFS= read -r -d '' word && [ \"$word\" = go ] || exit 43",
                        "IFS= read -r -d '' word && [ \"$word\" = go ] || exit 43\nsleep 30")),
            CONTAINER,
            "dock",
            said::add,
            () -> null,
            endedWith::add,
            new FilesConnection.Writes(lock, () -> null, java.time.Duration.ofMillis(300)));

    save(hung, 1, file.toString(), digest(file), version(file), "new");

    var failed = assertInstanceOf(PtyMessage.FilesRefused.class, answer());
    assertEquals(PtyMessage.FilesRefusal.FAILED, failed.kind());
    assertEquals(
        "Sail could not tell whether "
            + file
            + " was saved: its save did not end within 0.3 s and was ended; the file holds its old"
            + " or its new bytes; read it again.",
        failed.message());
    assertFalse(processes.getLast().isAlive(), "its script is dead before the lock is freed");
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (lock.isLocked()) {
      assertTrue(System.nanoTime() < deadline, "and then the lock is freed");
      Thread.onSpinWait();
    }
  }

  @Test
  void aListingNeverShowsASavesTemporary() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "kept");
    var save = new Save(files, file.toString(), digest(file), version(file), 4);
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

    assertEquals(
        FileScripts.NOT_A_FILE, save(pipe, sha256(new byte[0]), version(pipe), "x").status());
    assertEquals(
        FileScripts.IS_FOLDER, save(folder, sha256(new byte[0]), version(folder), "x").status());
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

    assertEquals(
        0,
        save(saved, sha256("body".getBytes(StandardCharsets.UTF_8)), version(saved), "saved")
            .status());
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
        scripts(UnaryOperator.identity(), Map.of("LANG", "de_DE.UTF-8", "LC_ALL", "de_DE.UTF-8"));
    Files.writeString(home.resolve("taken"), "x");
    assertEquals(FileScripts.EXISTS, run(german, FileScripts.CREATE, home + "/taken", "file"));
    assertEquals(FileScripts.NOT_FOUND, run(german, FileScripts.REMOVE, home + "/none"));
  }

  @Test
  void noPathNameOrByteOfASaveReachesACommandLine() throws Exception {
    var file = Files.writeString(home.resolve("secret-plan.md"), "old");
    save(file, digest(file), version(file), "the secret body");
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

  private FileScripts running(UnaryOperator<String> edit) {
    return scripts(edit, Map.of());
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
        new FilesConnection.Writes(lock, gate, FilesConnection.AFTER_GO));
  }

  /** Sends save {@code id} of {@code text} over {@code path} on {@code base}, bytes and end. */
  private static void save(
      FilesConnection connection, int id, String path, String base, String seen, String text)
      throws InterruptedException {
    var bytes = text.getBytes(StandardCharsets.UTF_8);
    connection.write(new PtyMessage.FilesWrite(id, path, base, seen, bytes.length));
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
    var base = digest(file);
    var seen = version(file);
    var mine = connection(() -> null);
    var theirs = connection(() -> null);

    lock.lock();
    try {
      save(mine, 1, file.toString(), base, seen, "mine");
      save(theirs, 2, file.toString(), base, seen, "theirs");
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
    assertEquals(digest(file), lost.digest(), "the loser is told the winner's content");
    assertTrue(List.of("mine", "theirs").contains(Files.readString(file)));
    everyScriptEnds();
    assertEquals(List.of(), temporaries(home));
  }

  /**
   * A save's chunks are no requests: with its connection's other scripts busy and 64 requests
   * waiting, the requests sent between its chunks are read past the bound, so its next chunk is
   * read and the save completes, never stalled until its idle limit.
   */
  @Test
  void aSaveCompletesWhileSixtyFourRequestsWaitOnItsConnection() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "base");
    var busy = Files.createDirectory(home.resolve("busy"));
    var connection =
        connection(
            running(script -> script.equals(FileScripts.LIST) ? "exec sleep 30" : script),
            () -> null);
    var chunk = 64 << 10;
    var bytes = new byte[3 * chunk];
    new java.util.Random(7).nextBytes(bytes);
    var reader =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    connection.write(
                        new PtyMessage.FilesWrite(
                            1, file.toString(), digest(file), version(file), bytes.length));
                    connection.chunk(
                        new PtyMessage.PutChunk(
                            1, 0, java.util.Arrays.copyOfRange(bytes, 0, chunk)));
                    connection.chunk(
                        new PtyMessage.PutChunk(
                            1, chunk, java.util.Arrays.copyOfRange(bytes, chunk, 2 * chunk)));
                    for (var i = 0; i < 3 + FilesConnection.WAITING + 1; i++) {
                      connection.list(new PtyMessage.FilesList(100 + i, busy.toString()));
                    }
                    connection.chunk(
                        new PtyMessage.PutChunk(
                            1,
                            2 * chunk,
                            java.util.Arrays.copyOfRange(bytes, 2 * chunk, 3 * chunk)));
                    connection.end(
                        new PtyMessage.PutEnd(1, HexFormat.of().parseHex(sha256(bytes))));
                  } catch (InterruptedException | IOException interrupted) {
                    throw new AssertionError(interrupted);
                  }
                });

    assertTrue(
        reader.join(java.time.Duration.ofSeconds(10)),
        "the request past the bound was read, and the save's last chunk after it");
    assertInstanceOf(PtyMessage.FilesWritten.class, answer());
    assertArrayEquals(bytes, Files.readAllBytes(file));
    connection.close();
  }

  /** A save is answered the new content's digest, the next save's base, and its stat after. */
  @Test
  void aSaveIsAnsweredItsNewContentsDigestAndItsStat() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "base");
    var connection = connection(() -> null);
    save(connection, 1, file.toString(), digest(file), version(file), "new words");

    var written = assertInstanceOf(PtyMessage.FilesWritten.class, answer());
    assertEquals(digest(file), written.digest());
    assertEquals(version(file), written.version());
    save(connection, 2, file.toString(), written.digest(), written.version(), "newer");
    assertInstanceOf(PtyMessage.FilesWritten.class, answer(), "the next save on it is written");
  }

  /** A save's bytes stream in without the lock: it is held only from the go to the version. */
  @Test
  void aSavesBytesAreCopiedWhileAnotherHoldsTheLock() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "base");
    var connection = connection(() -> null);
    lock.lock();
    try {
      save(connection, 1, file.toString(), digest(file), version(file), "new");
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

    save(refusing, 1, file.toString(), digest(file), version(file), "new");
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
    save(unasked, 4, file.toString(), digest(file), version(file), "new");
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
    connection.write(
        new PtyMessage.FilesWrite(1, file.toString(), digest(file), version(file), 1 << 20));
    connection.chunk(new PtyMessage.PutChunk(1, 0, new byte[64 << 10]));
    assertEquals(new PtyMessage.PutAck(1, 64 << 10), said.poll(5, TimeUnit.SECONDS));
    assertEquals(1, temporaries(home).size());

    connection.close();

    everyScriptEnds();
    assertEquals(List.of(), temporaries(home), "removed by the script itself, not left to a sweep");
    assertEquals("kept", Files.readString(file));
    assertNull(said.poll(), "nothing is answered after the end");
  }

  /**
   * A save whose script ends mid-body, as a workspace stopped outside Sail ends it, is answered at
   * once, the workspace asked again, never held until its bytes' idle limit; nothing is written.
   */
  @Test
  void aSaveWhoseScriptEndsMidBodyIsAnsweredAtOnce() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "kept");
    var connection = connection(() -> null);
    connection.write(
        new PtyMessage.FilesWrite(1, file.toString(), digest(file), version(file), 1 << 20));
    connection.chunk(new PtyMessage.PutChunk(1, 0, new byte[64 << 10]));
    assertEquals(new PtyMessage.PutAck(1, 64 << 10), said.poll(5, TimeUnit.SECONDS));

    processes.getLast().destroyForcibly();

    var answered = assertInstanceOf(PtyMessage.FilesRefused.class, answer());
    assertEquals(PtyMessage.FilesRefusal.FAILED, answered.kind());
    assertEquals(
        "Sail could not save " + file + " in dock; nothing was changed.", answered.message());
    assertEquals("kept", Files.readString(file));
  }

  /** A save cancelled mid-body is answered no more, and removes its own temporary. */
  @Test
  void aCancelledSaveIsAnsweredNoMoreAndLeavesNoTemporary() throws Exception {
    var file = Files.writeString(home.resolve("notes.md"), "kept");
    var connection = connection(() -> null);
    connection.write(
        new PtyMessage.FilesWrite(1, file.toString(), digest(file), version(file), 1 << 20));
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

    connection.write(new PtyMessage.FilesWrite(1, file.toString(), digest(file), version(file), 3));
    connection.chunk(new PtyMessage.PutChunk(1, 0, "new".getBytes(StandardCharsets.UTF_8)));
    connection.end(new PtyMessage.PutEnd(1, new byte[32]));
    assertEquals(
        new PtyMessage.FilesRefused(
            1,
            PtyMessage.FilesRefusal.FAILED,
            file + " was not saved: the bytes Sail received are not the bytes sent."),
        answer());

    connection.write(new PtyMessage.FilesWrite(2, file.toString(), digest(file), version(file), 6));
    connection.chunk(new PtyMessage.PutChunk(2, 3, "new".getBytes(StandardCharsets.UTF_8)));
    assertEquals(
        new PtyMessage.FilesRefused(
            2,
            PtyMessage.FilesRefusal.FAILED,
            file
                + " was not saved: its bytes did not arrive in order and within its size; nothing"
                + " was changed."),
        answer());
    connection.write(new PtyMessage.FilesWrite(3, file.toString(), digest(file), version(file), 2));
    connection.chunk(new PtyMessage.PutChunk(3, 0, "new".getBytes(StandardCharsets.UTF_8)));
    assertEquals(PtyMessage.FilesRefusal.FAILED, ((PtyMessage.FilesRefused) answer()).kind());
    everyScriptEnds();
    var base = digest(file);
    var seen = version(file);
    var scripts = processes.size();

    connection.write(
        new PtyMessage.FilesWrite(4, file.toString(), base, "", FilesConnection.READ_CAP + 1));
    assertEquals(PtyMessage.FilesRefusal.TOO_LARGE, ((PtyMessage.FilesRefused) answer()).kind());
    for (var notADigest : List.of(seen, "", base.toUpperCase(java.util.Locale.ROOT), base + "0")) {
      connection.write(new PtyMessage.FilesWrite(9, file.toString(), notADigest, seen, 1));
      assertEquals(
          new PtyMessage.FilesRefused(
              9,
              PtyMessage.FilesRefusal.FAILED,
              "A save names the content it was based on: the SHA-256 of a whole read, in lowercase"
                  + " hex, or absent."),
          answer(),
          "digest rule 1: " + notADigest);
    }
    var bad = home + "/" + FileNames.decode(new byte[] {'b', (byte) 0xff});
    for (var name :
        List.of(bad, home + "/two\nlines", home + "/" + FileScripts.SAVING + "Abcdef0123.part")) {
      connection.write(new PtyMessage.FilesWrite(5, name, PtyMessage.ABSENT, "", 1));
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
    var base = digest(file);
    var seen = version(file);
    Files.writeString(file, "theirs");
    var connection = connection(() -> null);
    save(connection, 1, file.toString(), base, seen, "mine");
    assertEquals(new PtyMessage.FilesConflict(1, digest(file)), answer());
    assertEquals(
        List.of(new PtyMessage.FilesConflict(1, digest(file))),
        heard,
        "a stale base is told before any byte is taken");

    var full =
        connection(
            running(script -> script.replace("head -c \"$size\" > \"$tmp\"", "false")), () -> null);
    save(full, 2, file.toString(), digest(file), version(file), "mine");
    var refused = (PtyMessage.FilesRefused) answer();
    assertEquals(PtyMessage.FilesRefusal.NO_SPACE, refused.kind());
    assertEquals(
        file + " was not saved: dock's disk or its quota is full; nothing was changed.",
        refused.message(),
        "nit 3: a disk that shows room is full by its quota, and no number is said");

    save(connection, 3, file.toString(), PtyMessage.ABSENT, "", "mine");
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
    connection.create(
        new PtyMessage.FilesCreate(8, home + "/" + FileScripts.SAVING + "notes", false));
    assertEquals(
        new PtyMessage.FilesDone(8), answer(), "m3: a person's name of Sail's prefix is made");
    assertEquals("theirs", Files.readString(file));
    everyScriptEnds();
    assertEquals(List.of(), temporaries(home));
  }
}
