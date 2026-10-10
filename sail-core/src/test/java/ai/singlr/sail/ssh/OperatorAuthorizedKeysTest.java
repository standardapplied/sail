/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    new OperatorAuthorizedKeys(home).append(ADA, "sail-mast:ada");

    assertEquals(OPTIONS + keyOf(ADA) + " sail-mast:ada\n", Files.readString(file()));
    assertEquals("rwx------", mode(home.resolve(".ssh")));
    assertEquals("rw-------", mode(file()));
  }

  @Test
  void anAppendKeepsTheOperatorsOwnLinesAndEndsEveryLine() throws Exception {
    Files.createDirectories(home.resolve(".ssh"));
    Files.writeString(file(), "# my keys\n\n" + OPERATOR);

    new OperatorAuthorizedKeys(home).append(BOB, "sail-mast:bob");

    assertEquals(
        "# my keys\n\n" + OPERATOR + "\n" + OPTIONS + keyOf(BOB) + " sail-mast:bob\n",
        Files.readString(file()));
    assertEquals("rw-------", mode(file()));
    try (var entries = Files.list(home.resolve(".ssh"))) {
      assertEquals(List.of(file()), entries.toList(), "no staging file is left behind");
    }
  }

  @Test
  void removingByCommentTakesOnlyTheKeyLinesThatCarryIt() throws Exception {
    var keys = new OperatorAuthorizedKeys(home);
    Files.createDirectories(home.resolve(".ssh"));
    Files.writeString(file(), OPERATOR + "\n# sail-mast:ada\n");
    keys.append(ADA, "sail-mast:ada");
    keys.append(BOB, "sail-mast:adam");
    keys.append(BOB, "sail-mast:ada");

    assertEquals(2, keys.removeCommented("sail-mast:ada"));

    assertEquals(
        OPERATOR + "\n# sail-mast:ada\n" + OPTIONS + keyOf(BOB) + " sail-mast:adam\n",
        Files.readString(file()));
    assertEquals(0, keys.removeCommented("sail-mast:ada"));
  }

  @Test
  void removingTheOnlyLineLeavesAnEmptyFile() throws Exception {
    var keys = new OperatorAuthorizedKeys(home);
    keys.append(ADA, "sail-mast:ada");

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
}
