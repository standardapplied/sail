/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static ai.singlr.sail.pty.ContainerDirectory.held;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Uploads leaving a {@link ContainerDirectory}, by the scripts production sends a container: a
 * session's directory removed, and a sweep that keeps what is live; no delete follows a link. Each
 * test plants the link or the name the rule it names must not be fooled by.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class UploadCleanupTest {

  private static final String ID = "01a1ea00-0000-7000-8000-000000000001";
  private static final String STALE = "0f0e0d0c-0000-0000-0000-000000000000";

  @TempDir Path dir;

  private ContainerDirectory c;
  private UploadCleanup cleanup;
  private Path outside;

  @BeforeEach
  void container() throws Exception {
    c = new ContainerDirectory(dir);
    cleanup = new UploadCleanup(c.copies());
    outside = Files.createDirectories(dir.resolve("home"));
    Files.writeString(outside.resolve("work"), "the person's own");
  }

  private Path root() throws Exception {
    Files.createDirectories(c.root());
    Files.setPosixFilePermissions(c.root(), PosixFilePermissions.fromString("rwxr-xr-x"));
    return c.root();
  }

  private Path directory(String id) throws Exception {
    var directory = Files.createDirectories(root().resolve(id));
    Files.writeString(directory.resolve("1-a.png"), "uploaded");
    return directory;
  }

  /** Major 5: links a session's directory holds are removed as links, never followed. */
  @Test
  void aSessionsDirectoryGoesWithItsLinksRemovedAsLinks() throws Exception {
    var session = directory(ID);
    Files.createSymbolicLink(session.resolve("2-a"), outside);
    Files.createSymbolicLink(session.resolve("3-a"), outside.resolve("work"));

    cleanup.remove("acme", ID, null);
    c.awaitFinished(UploadCleanup.REMOVE);

    assertFalse(Files.exists(session, LinkOption.NOFOLLOW_LINKS));
    assertEquals(List.of("work"), held(outside));
    assertEquals("the person's own", Files.readString(outside.resolve("work")));
  }

  /** Major 5: a session's directory that is a link goes as the link; what it names stays. */
  @Test
  void aSessionsDirectoryThatIsALinkGoesAsTheLink() throws Exception {
    var session = Files.createSymbolicLink(root().resolve(ID), outside);

    cleanup.remove("acme", ID, null);
    c.awaitFinished(UploadCleanup.REMOVE);

    assertFalse(Files.exists(session, LinkOption.NOFOLLOW_LINKS));
    assertEquals(List.of("work"), held(outside));
  }

  /** Major 5: a root that is a link is neither removed through nor swept. */
  @Test
  void aRootThatIsALinkIsNeitherRemovedThroughNorSwept() throws Exception {
    Files.createDirectories(outside.resolve(ID));
    Files.createDirectories(outside.resolve(STALE));
    Files.createSymbolicLink(c.root(), outside);

    cleanup.remove("acme", ID, null);
    c.awaitFinished(UploadCleanup.REMOVE);
    cleanup.sweep("acme", List::of);
    c.awaitFinished(UploadCleanup.SWEEP);

    assertEquals(List.of(ID, STALE, "work"), held(outside));
  }

  @Test
  void aSweepRemovesOnlyTheDirectoriesOfSessionsNotLive() throws Exception {
    var live = directory(ID);
    var stale = directory(STALE);
    var notAnId = Files.createDirectories(root().resolve("keep-me"));
    Files.createSymbolicLink(root().resolve("0a0b0c0d-1111-1111-1111-111111111111"), outside);

    cleanup.sweep("acme", () -> List.of(ID));
    c.awaitFinished(UploadCleanup.SWEEP);

    assertFalse(Files.exists(stale));
    assertTrue(Files.exists(live.resolve("1-a.png")), "a live session's file is kept");
    assertTrue(Files.exists(notAnId), "a name that is no instance id is kept");
    assertEquals(List.of("work"), held(outside), "no link followed");
  }

  @Test
  void aRootNotYetMadeIsNothingToRemoveOrSweep() throws Exception {
    cleanup.remove("acme", ID, null);
    c.awaitFinished(UploadCleanup.REMOVE);
    cleanup.sweep("acme", List::of);
    c.awaitFinished(UploadCleanup.SWEEP);

    assertFalse(Files.exists(c.root(), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void eachContainerHasOneLock() {
    assertTrue(cleanup.lockOf("acme") == cleanup.lockOf("acme"));
    assertFalse(cleanup.lockOf("acme") == cleanup.lockOf("acme-dock"));
  }
}
