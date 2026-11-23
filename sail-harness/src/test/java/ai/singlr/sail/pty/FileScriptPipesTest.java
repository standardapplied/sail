/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static ai.singlr.sail.pty.FileChangesTest.CONTAINER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.TrackedProcess;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every script a connection or a changes run starts has its stdin and stdout closed by the thread
 * that read it, once it is done with it, never left to the JDK's reaper: however the request ends.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FileScriptPipesTest {

  private static final Duration PATIENCE = Duration.ofSeconds(10);

  @TempDir Path dir;
  private Path home;
  private Path workspace;
  private final List<TrackedProcess> started = new CopyOnWriteArrayList<>();
  private final LinkedBlockingQueue<PtyMessage> said = new LinkedBlockingQueue<>();
  private FileScripts files;

  @BeforeEach
  void setUp() throws Exception {
    home = Files.createDirectory(dir.toRealPath().resolve("home"));
    workspace = Files.createDirectory(home.resolve("workspace"));
    var user = Files.createDirectory(dir.resolve("user"));
    files =
        FileScriptsTest.over(
            home,
            new CopyOnWriteArrayList<>(),
            Map.of("HOME", user.toString(), "GIT_CONFIG_NOSYSTEM", "1"),
            UnaryOperator.identity(),
            process -> {
              var tracked = TrackedProcess.of(process);
              started.add(tracked);
              return tracked;
            });
  }

  private FilesConnection connection() {
    return new FilesConnection(
        files,
        CONTAINER,
        "dock",
        said::add,
        () -> null,
        unused -> {},
        new FilesConnection.Writes(
            new FilesConnection.WorkspaceLock(),
            ConcurrentHashMap.newKeySet(),
            () -> null,
            FilesConnection.AFTER_GO,
            FilesConnection.HOLD_BOUND),
        new WorkspaceChanges(files, CONTAINER, WorkspaceChanges.PATIENCE, () -> {}));
  }

  /** The last answer to request {@code id}, within 30 s. */
  private PtyMessage.Answer last(int id) throws InterruptedException {
    while (true) {
      var next = said.poll(30, TimeUnit.SECONDS);
      if (next == null) {
        throw new AssertionError("request " + id + " was never answered");
      }
      if (next instanceof PtyMessage.Answer answer && answer.id() == id && answer.last()) {
        return answer;
      }
    }
  }

  private void assertEveryPipeClosed() throws InterruptedException {
    assertFalse(started.isEmpty(), "a script ran");
    for (var process : started) {
      process.awaitClosed(PATIENCE);
    }
  }

  @Test
  void aListingClosesItsScriptsPipes() throws Exception {
    Files.writeString(workspace.resolve("a.md"), "a\n");

    connection().list(new PtyMessage.FilesList(1, workspace.toString()));

    assertInstanceOf(PtyMessage.FilesListing.class, last(1));
    assertEveryPipeClosed();
  }

  /** A listing cut at its cap ends its script before reading it all, and closes its pipes. */
  @Test
  void aListingCutAtItsCapClosesItsScriptsPipes() throws Exception {
    var big = Files.createDirectory(home.resolve("big"));
    for (var i = 0; i <= PtyMessage.LISTING_CAP; i++) {
      Files.createFile(big.resolve("f" + i));
    }

    connection().list(new PtyMessage.FilesList(1, big.toString()));

    assertTrue(assertInstanceOf(PtyMessage.FilesListing.class, last(1)).truncated());
    assertEveryPipeClosed();
  }

  @Test
  void aReadClosesItsScriptsPipes() throws Exception {
    var file = Files.writeString(workspace.resolve("a.md"), "hello\n");

    connection().read(new PtyMessage.FilesRead(1, file.toString(), 0, 100));

    assertInstanceOf(PtyMessage.FilesData.class, last(1));
    assertEveryPipeClosed();
  }

  /** A save closes its script's pipes, and those of the probe its lock's first write asks. */
  @Test
  void aSaveClosesThePipesOfEveryScriptItRan() throws Exception {
    var bytes = "saved\n".getBytes(StandardCharsets.UTF_8);
    var connection = connection();
    var path = workspace.resolve("new.md").toString();

    connection.write(new PtyMessage.FilesWrite(1, path, PtyMessage.ABSENT, "", bytes.length));
    connection.chunk(new PtyMessage.PutChunk(1, 0, bytes));
    connection.end(new PtyMessage.PutEnd(1, MessageDigest.getInstance("SHA-256").digest(bytes)));

    var written = assertInstanceOf(PtyMessage.FilesWritten.class, last(1));
    assertEquals(
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        written.digest());
    assertEveryPipeClosed();
  }

  /** The watch's script, ended when its connection closes, has its pipes closed by its reader. */
  @Test
  void aWatchEndedByItsConnectionClosingClosesItsScriptsPipes() throws Exception {
    var connection = connection();
    connection.watch(
        new PtyMessage.FilesWatch(List.of(new PtyMessage.Watched(workspace.toString(), ""))));
    assertTrue(said.poll(30, TimeUnit.SECONDS) != null, "the watch is under way");

    connection.close();

    assertEveryPipeClosed();
  }

  @Test
  void aChangesRunClosesItsScriptsPipes() throws Exception {
    connection().changes(new PtyMessage.FilesChanges(1));

    last(1);
    assertEveryPipeClosed();
  }
}
