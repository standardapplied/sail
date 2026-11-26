/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class ClosesWhatItOpensTest {

  @TempDir Path dir;

  @Test
  void aClassThatLeavesAnHttpClientOpenFailsNamingItsEventfdAndItsEpoll() throws Exception {
    var before = OpenDescriptors.list();

    try (var client = HttpClient.newHttpClient()) {
      var refusal =
          assertThrows(AssertionError.class, () -> ClosesWhatItOpens.assertClosed("Leaky", before));

      assertTrue(
          named(refusal).containsAll(List.of("anon_inode:[eventfd]", "anon_inode:[eventpoll]")),
          refusal.getMessage());
    }
  }

  @Test
  void aClassThatLeavesAStoreOpenFailsNamingItsThreeFiles() throws Exception {
    var path = dir.toRealPath().resolve("left.db");
    var before = OpenDescriptors.list();

    try (var db = Sqlite.open(path)) {
      new SchemaManager(db).migrate();
      var refusal =
          assertThrows(AssertionError.class, () -> ClosesWhatItOpens.assertClosed("Leaky", before));

      assertTrue(
          named(refusal).containsAll(List.of(path.toString(), path + "-wal", path + "-shm")),
          refusal.getMessage());
    }
  }

  /**
   * A channel open before the class and closed during it stands for another class's leftover that
   * is collected meanwhile.
   */
  @Test
  void aClassThatClosesWhatItOpensPassesWhileAnotherClassesLeftoverCloses() throws Exception {
    var elsewhere = FileChannel.open(Files.writeString(dir.resolve("elsewhere"), ""));
    var before = OpenDescriptors.list();

    elsewhere.close();
    try (var client = HttpClient.newHttpClient();
        var db = Sqlite.open(dir.resolve("tidy.db"))) {
      new SchemaManager(db).migrate();
    }

    assertDoesNotThrow(() -> ClosesWhatItOpens.assertClosed("Tidy", before));
  }

  /**
   * The class path's jars are opened as the first class needing each loads; a resource no jar holds
   * makes the class loader open every one, and the jars the fork held before stand for ones it
   * opens during the class.
   */
  @Test
  void aClassThatOpensNothingPassesWhileTheJvmOpensItsClassPath() throws Exception {
    var before = OpenDescriptors.list();
    var jars = before.keySet().stream().filter(number -> target(number).endsWith(".jar")).toList();
    jars.forEach(before::remove);

    assertNull(ClassLoader.getSystemClassLoader().getResource("no/such/resource"));

    assertFalse(jars.isEmpty(), "the fork holds jars of its class path");
    assertDoesNotThrow(() -> ClosesWhatItOpens.assertClosed("Quiet", before));
  }

  /**
   * The JVM reads its cgroup's files from its own threads, a compiler thread inside whichever class
   * runs; a read of one open across the check stands for that.
   */
  @Test
  void aClassPassesWhileTheJvmReadsItsCgroup() throws Exception {
    var memory = Path.of("/sys/fs/cgroup/memory.current");
    assumeTrue(Files.isReadable(memory), "a host whose cgroup names its memory");
    var before = OpenDescriptors.list();

    try (var read = Files.newByteChannel(memory)) {
      assertDoesNotThrow(() -> ClosesWhatItOpens.assertClosed("Quiet", before));
    }
  }

  /** What a refusal of class {@code Leaky} names as left open, without the descriptors' numbers. */
  private static List<String> named(AssertionError refusal) {
    var prefix = "Leaky left these open: ";
    assertTrue(refusal.getMessage().startsWith(prefix), refusal.getMessage());
    return Arrays.stream(refusal.getMessage().substring(prefix.length()).split(", "))
        .map(left -> left.substring(left.indexOf(' ') + 1))
        .toList();
  }

  private static String target(String number) {
    try {
      return Files.readSymbolicLink(Path.of("/proc/self/fd", number)).toString();
    } catch (IOException closedMeanwhile) {
      return "";
    }
  }
}
