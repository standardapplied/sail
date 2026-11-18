/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static ai.singlr.sail.pty.ContainerDirectory.held;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.pty.PtyMessage.PutRefusal;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * One copy into a {@link ContainerDirectory}, by the scripts production sends a container: a file
 * appears whole, the dev user's alone, or not at all, and no link is written through. Each test
 * tries the wrong thing the rule it names forbids.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class UploadCopyTest {

  private static final String ID = "01a1ea00-0000-7000-8000-000000000001";

  @TempDir Path dir;

  private ContainerDirectory c;
  private UploadCopy copies;

  @BeforeEach
  void container() throws Exception {
    c = new ContainerDirectory(dir);
    copies = c.copies();
  }

  private Path session() {
    return c.root().resolve(ID);
  }

  /** A copy of {@code bytes} as {@code name}, written, ended and committed. */
  private UploadCopy.Copy stored(String name, byte[] bytes) throws Exception {
    return stored(ID, name, bytes);
  }

  private UploadCopy.Copy stored(String instanceId, String name, byte[] bytes) throws Exception {
    var copy = copies.open("acme", instanceId, name, bytes.length, process -> {});
    assertTrue(copy.opened(), () -> "the container refused " + name + ": " + copy.refusal());
    copy.write(bytes);
    assertEquals(0, copy.close());
    assertEquals(0, copy.commit());
    return copy;
  }

  @Test
  void aFileIsWholeAndTheDevUsersAloneUnderARootOfRootsOwnAndNoTemporaryIsLeft() throws Exception {
    var bytes = new byte[UploadAllowance.CHUNK * 2 + 5];
    bytes[bytes.length - 1] = 9;

    var copy = stored("shot.png", bytes);

    assertEquals(session() + "/1-shot.png", copy.path());
    var file = Path.of(copy.path());
    assertArrayEquals(bytes, Files.readAllBytes(file));
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    assertEquals(
        "rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(session())));
    assertEquals(
        "rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(c.root())));
    assertEquals(List.of("1-shot.png"), held(session()), "no temporary is left");
  }

  @Test
  void noNameAFileOrALinkHoldsIsWrittenAndALinkAtTheTemporaryIsNeverWrittenThrough()
      throws Exception {
    var first = stored("a.png", "one".getBytes(StandardCharsets.UTF_8));
    Files.writeString(session().resolve("2-a.png"), "a host before this one stored it");
    var target = dir.resolve("elsewhere");
    Files.createSymbolicLink(session().resolve("3-a.png"), target);
    Files.createSymbolicLink(session().resolve(".4-a.png.part"), target);

    var next = stored("a.png", "two".getBytes(StandardCharsets.UTF_8));

    assertEquals(session() + "/5-a.png", next.path());
    assertEquals("one", Files.readString(Path.of(first.path())));
    assertEquals(
        "a host before this one stored it", Files.readString(session().resolve("2-a.png")));
    assertEquals("two", Files.readString(Path.of(next.path())));
    assertFalse(Files.exists(target), "nothing was written through a planted link");
  }

  /** Major 1: a copy that kept fewer bytes than were sent never takes the file's name. */
  @Test
  void aCopyShorterThanItsSizeIsNeverStored() throws Exception {
    c.copyTruncates = true;
    var copy = copies.open("acme", ID, "a", 3, process -> {});
    copy.write(new byte[] {1, 2, 3});
    assertEquals(0, copy.close());

    assertNotEquals(0, copy.commit());
    assertEquals(List.of(), held(session()));
  }

  /**
   * Round 2 Minor 1: a file, a link or a directory planted at the final name between the open and
   * the commit is never overwritten or linked into, and the temporary goes.
   */
  @Test
  void aNamePlantedBeforeTheCommitIsNeitherOverwrittenNorLinkedInto() throws Exception {
    var target = dir.resolve("elsewhere");
    Files.createDirectories(target);
    for (var plant : List.of("file", "link", "directory")) {
      var copy = copies.open("acme", ID, plant, 1, process -> {});
      copy.write(new byte[] {1});
      assertEquals(0, copy.close());
      var name = Path.of(copy.path());
      switch (plant) {
        case "file" -> Files.writeString(name, "theirs");
        case "link" -> Files.createSymbolicLink(name, target);
        default -> Files.createDirectory(name);
      }

      assertNotEquals(0, copy.commit(), plant);

      assertEquals(List.of(), held(target), plant + ": nothing linked through");
      if (plant.equals("file")) {
        assertEquals("theirs", Files.readString(name));
      }
      if (plant.equals("directory")) {
        assertEquals(List.of(), held(name), "nothing linked into the directory");
      }
      assertFalse(held(session()).stream().anyMatch(n -> n.endsWith(".part")), plant);
    }
  }

  /**
   * A temporary that is no longer a regular file when the commit comes, a pipe of the declared size
   * planted in its place, never takes the name.
   */
  @Test
  void aTemporaryThatIsNotARegularFileIsNeverStored() throws Exception {
    var copy = copies.open("acme", ID, "a", 0, process -> {});
    assertEquals(0, copy.close());
    var name = Path.of(copy.path());
    var part = name.resolveSibling("." + name.getFileName() + ".part");
    Files.delete(part);
    assertEquals(0, new ProcessBuilder("mkfifo", part.toString()).start().waitFor());

    assertNotEquals(0, copy.commit());

    assertFalse(Files.exists(name, LinkOption.NOFOLLOW_LINKS), "nothing took the name");
  }

  /** Round 2 Minor 5: a measurement that fails is a failure, never read as a full directory. */
  @Test
  void aMeasurementThatFailsIsAFailure() throws Exception {
    c.findFails = true;

    var copy = copies.open("acme", ID, "a", 1, process -> {});

    assertFalse(copy.opened());
    assertEquals(PutRefusal.FAILED, copy.refusal());
  }

  /**
   * Round 3 Minor 1: an abort ends what holds its own temporary open and nothing else, not a
   * process holding another upload's temporary whose name begins with this one's.
   */
  @Test
  void anAbortEndsWhatHoldsItsOwnTemporaryAndNoOtherFile() throws Exception {
    var copy = copies.open("acme", ID, "a", 1, process -> {});
    var sibling = holding(session().resolve(".1-a.part.part"));
    try {
      assertEquals(0, copy.abort());

      assertEquals(List.of(sibling.pid()), ContainerDirectory.holders(session()));
      assertFalse(Files.exists(session().resolve(".1-a.part")));
    } finally {
      sibling.destroyForcibly().waitFor();
    }
  }

  /**
   * Round 3 Minor 1: a process that holds the temporary again as often as it is ended is left after
   * the abort's tries, and the abort says so by its status.
   */
  @Test
  void anAbortGivesUpOnAHolderItCannotEndAndSaysSo() throws Exception {
    var copy = copies.open("acme", ID, "a", 1, process -> {});
    var part = session().resolve(".1-a.part");
    var respawner =
        new ProcessBuilder(
                "sh",
                "-c",
                "while :; do sleep 600 >> \"$1\" & wait; done",
                "respawn",
                part.toString())
            .start();
    try {
      assertEquals(UploadCopy.HELD, copy.abort());
    } finally {
      respawner.destroyForcibly().waitFor();
      for (var pid : ContainerDirectory.holders(session())) {
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
      }
    }
  }

  /** A process that holds {@code file} open for writing, as a copy holds its temporary. */
  private static Process holding(Path file) throws IOException {
    return new ProcessBuilder("sleep", "600")
        .redirectOutput(ProcessBuilder.Redirect.appendTo(file.toFile()))
        .start();
  }

  @Test
  void anAbortedCopyLeavesNothing() throws Exception {
    var copy = copies.open("acme", ID, "a", 3, process -> {});
    copy.write(new byte[] {1, 2});
    copy.close();

    copy.abort();

    assertEquals(List.of(), held(session()));
  }

  /** The container's own count: the files and bytes a directory holds, and the disk's room. */
  @Test
  void aDirectoryHoldingItsBoundOrADiskWithoutRoomRefusesTheCopyByItsStatus() throws Exception {
    stored("a", new byte[] {1});
    for (var i = 2; i <= UploadAllowance.FILES; i++) {
      Files.createFile(session().resolve(i + "-left"));
    }
    var tooMany = copies.open("acme", ID, "b", 1, process -> {});
    assertFalse(tooMany.opened());
    assertEquals(PutRefusal.TOO_MANY, tooMany.refusal());

    var other = "01a1ea00-0000-7000-8000-000000000002";
    stored(other, "first", new byte[] {1});
    try (var sparse =
        new RandomAccessFile(c.root().resolve(other).resolve("2-sparse").toFile(), "rw")) {
      sparse.setLength(UploadAllowance.SESSION);
    }
    var sessionFull = copies.open("acme", other, "b", 1, process -> {});
    assertFalse(sessionFull.opened());
    assertEquals(PutRefusal.SESSION_FULL, sessionFull.refusal());

    var noRoom =
        copies.open("acme", "01a1ea00-0000-7000-8000-000000000003", "c", 1L << 60, process -> {});
    assertFalse(noRoom.opened());
    assertEquals(PutRefusal.NO_SPACE, noRoom.refusal());
    var free = noRoom.free();
    assertTrue(free > 0 && free < 1L << 60, "the refusal says what is free: " + free);
    assertFalse(Files.exists(c.root().resolve("01a1ea00-0000-7000-8000-000000000003")));
  }

  /** Major 5: a root or a session's directory that is a link is never written through. */
  @Test
  void aRootOrASessionsDirectoryThatIsALinkIsNeverWrittenThrough() throws Exception {
    var outside = Files.createDirectories(dir.resolve("home"));
    Files.writeString(outside.resolve("work"), "the person's own");
    Files.createSymbolicLink(c.root(), outside);

    var throughRoot = copies.open("acme", ID, "a", 1, process -> {});

    assertFalse(throughRoot.opened());
    assertEquals(PutRefusal.FAILED, throughRoot.refusal());
    assertEquals(List.of("work"), held(outside));

    Files.delete(c.root());
    Files.createDirectory(c.root());
    Files.createSymbolicLink(session(), outside);

    var throughDirectory = copies.open("acme", ID, "a", 1, process -> {});

    assertFalse(throughDirectory.opened());
    assertEquals(PutRefusal.FAILED, throughDirectory.refusal());
    assertEquals(List.of("work"), held(outside));
    assertEquals("the person's own", Files.readString(outside.resolve("work")));
  }

  /** A root that is a directory but not root's own is never written into. */
  @Test
  void aRootSomeoneElseOwnsIsNeverWrittenInto() throws Exception {
    Files.createDirectory(c.root());
    c.rootOwner = "4294967294";

    var copy = c.copies().open("acme", ID, "a", 1, process -> {});

    assertFalse(copy.opened());
    assertEquals(PutRefusal.FAILED, copy.refusal());
    assertEquals(List.of(), held(c.root()));
  }

  /** An open the container never answers is given up within the place's patience. */
  @Test
  void anOpenTheContainerNeverAnswersIsGivenUp() throws Exception {
    c.openHangs = true;

    var copy = copies.open("acme", ID, "a", 1, process -> {});

    assertFalse(copy.opened());
    assertEquals(PutRefusal.FAILED, copy.refusal());
  }

  @Test
  void eachExitStatusIsTheRefusalItNamesAndAnyOtherIsAFailure() {
    assertEquals(PutRefusal.TOO_MANY, UploadCopy.refusalOf(UploadCopy.TOO_MANY));
    assertEquals(PutRefusal.SESSION_FULL, UploadCopy.refusalOf(UploadCopy.FULL));
    assertEquals(PutRefusal.NO_SPACE, UploadCopy.refusalOf(UploadCopy.NO_SPACE));
    assertEquals(PutRefusal.FAILED, UploadCopy.refusalOf(UploadCopy.UNSAFE));
    assertEquals(PutRefusal.FAILED, UploadCopy.refusalOf(1));
    assertEquals(PutRefusal.FAILED, UploadCopy.refusalOf(137));
  }
}
