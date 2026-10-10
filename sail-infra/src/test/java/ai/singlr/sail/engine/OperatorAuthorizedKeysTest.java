/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.ssh.SshPublicKey;
import ai.singlr.sail.ssh.TestSshKeys;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OperatorAuthorizedKeysTest {

  private static final String OPTIONS = "no-agent-forwarding,no-X11-forwarding,no-user-rc ";
  private static final String OPERATOR = TestSshKeys.ed25519("operator", "me@mac");
  private static final SshPublicKey ADA = SshPublicKey.parse(TestSshKeys.ed25519("ada", "x"));
  private static final SshPublicKey BOB = SshPublicKey.parse(TestSshKeys.ed25519("bob", null));

  @TempDir Path home;

  private Path file() {
    return home.resolve(".ssh").resolve("authorized_keys");
  }

  private static String keyOf(SshPublicKey key) {
    return key.type() + " " + key.blob();
  }

  private static String mode(Path path) throws IOException {
    return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
  }

  @Test
  void theFirstAppendCreatesTheOperatorsSshDirectoryAndFileOwnerOnly() throws Exception {
    new OperatorAuthorizedKeys(home).append(ADA, "sail-mast:ada", Optional.empty());

    assertEquals(OPTIONS + keyOf(ADA) + " sail-mast:ada\n", Files.readString(file()));
    assertEquals("rwx------", mode(home.resolve(".ssh")));
    assertEquals("rw-------", mode(file()));
  }

  @Test
  void aLoginWithADeadlineIsRefusedBySshdItselfOnceItPasses() throws Exception {
    new OperatorAuthorizedKeys(home)
        .append(ADA, "sail-mast:ada", Optional.of(Instant.parse("2027-01-02T03:04:05.999Z")));

    assertEquals(
        "no-agent-forwarding,no-X11-forwarding,no-user-rc,expiry-time=\"20270102030405Z\" "
            + keyOf(ADA)
            + " sail-mast:ada\n",
        Files.readString(file()));
    assertEquals(1, new OperatorAuthorizedKeys(home).removeCommented("sail-mast:ada"));
  }

  @Test
  void anAppendKeepsTheOperatorsOwnLinesAndEndsEveryLine() throws Exception {
    Files.createDirectories(home.resolve(".ssh"));
    Files.writeString(file(), "# my keys\n\n" + OPERATOR);

    new OperatorAuthorizedKeys(home).append(BOB, "sail-mast:bob", Optional.empty());

    assertEquals(
        "# my keys\n\n" + OPERATOR + "\n" + OPTIONS + keyOf(BOB) + " sail-mast:bob\n",
        Files.readString(file()));
    assertEquals("rw-------", mode(file()));
    try (var entries = Files.list(home.resolve(".ssh"))) {
      assertEquals(
          List.of(file(), file().resolveSibling("authorized_keys.sail.lock")),
          entries.sorted().toList(),
          "its lock stays beside the file, and no staging file does");
    }
  }

  @Test
  void removingByCommentTakesOnlyTheKeyLinesThatCarryIt() throws Exception {
    var keys = new OperatorAuthorizedKeys(home);
    Files.createDirectories(home.resolve(".ssh"));
    Files.writeString(file(), OPERATOR + "\n# sail-mast:ada\n");
    keys.append(ADA, "sail-mast:ada", Optional.empty());
    keys.append(BOB, "sail-mast:adam", Optional.empty());
    keys.append(BOB, "sail-mast:ada", Optional.empty());

    assertEquals(2, keys.removeCommented("sail-mast:ada"));

    assertEquals(
        OPERATOR + "\n# sail-mast:ada\n" + OPTIONS + keyOf(BOB) + " sail-mast:adam\n",
        Files.readString(file()));
    assertEquals(0, keys.removeCommented("sail-mast:ada"));
  }

  @Test
  void removingTheOnlyLineLeavesAnEmptyFile() throws Exception {
    var keys = new OperatorAuthorizedKeys(home);
    keys.append(ADA, "sail-mast:ada", Optional.empty());

    assertEquals(1, keys.removeCommented("sail-mast:ada"));

    assertEquals("", Files.readString(file()));
  }

  @Test
  void removingWhereNothingCarriesTheCommentWritesNothing() throws Exception {
    var keys = new OperatorAuthorizedKeys(home);

    assertEquals(0, keys.removeCommented("sail-mast:ada"));
    assertFalse(Files.exists(home.resolve(".ssh")), "a home with no keys is left as it is");

    Files.createDirectories(home.resolve(".ssh"));
    Files.writeString(file(), OPERATOR);
    Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("rw-r--r--"));

    assertEquals(0, keys.removeCommented("sail-mast:ada"));

    assertEquals(OPERATOR, Files.readString(file()));
    assertEquals("rw-r--r--", mode(file()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"rw-------", "r-x------"})
  void aFileThatCannotBeEditedIsAFailureAndNeverAnAbsence(String mode) throws Exception {
    var keys = new OperatorAuthorizedKeys(home);
    keys.append(ADA, "sail-mast:ada", Optional.empty());
    var written = Files.readString(file());
    Files.setPosixFilePermissions(home.resolve(".ssh"), PosixFilePermissions.fromString(mode));

    try {
      assertThrows(AccessDeniedException.class, () -> keys.removeCommented("sail-mast:ada"));
      assertThrows(
          AccessDeniedException.class, () -> keys.append(BOB, "sail-mast:bob", Optional.empty()));
    } finally {
      Files.setPosixFilePermissions(
          home.resolve(".ssh"), PosixFilePermissions.fromString("rwx------"));
    }
    assertEquals(written, Files.readString(file()));
  }

  @Test
  void aRemovalAndAnAppendRunningTogetherBothLand() throws Exception {
    try (var commands = Executors.newFixedThreadPool(2)) {
      for (var round = 0; round < 200; round++) {
        Files.deleteIfExists(file());
        new OperatorAuthorizedKeys(home).append(ADA, "sail-mast:ada", Optional.empty());
        var start = new CyclicBarrier(2);
        var removed =
            commands.submit(
                () -> {
                  start.await();
                  return new OperatorAuthorizedKeys(home).removeCommented("sail-mast:ada");
                });
        var appended =
            commands.submit(
                () -> {
                  start.await();
                  new OperatorAuthorizedKeys(home).append(BOB, "sail-mast:bob", Optional.empty());
                  return null;
                });

        assertEquals(1, removed.get());
        appended.get();
        assertEquals(
            OPTIONS + keyOf(BOB) + " sail-mast:bob\n", Files.readString(file()), "round " + round);
      }
    }
  }
}
