/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class SailBinaryTest {

  private static final byte[] ELF = {0x7f, 'E', 'L', 'F', 4, 5, 6, 7};

  @TempDir Path dir;

  @Test
  void aSailAnswerIsItsVersionAndAnythingElseIsRefusedNamingWhatItSaid() {
    assertEquals(
        SemVer.parse("0.46.1"),
        SailBinary.parseVersion(new ShellExec.Result(0, "sail 0.46.1\n", "")));

    var other =
        assertThrows(
            SailBinary.NotSail.class,
            () -> SailBinary.parseVersion(new ShellExec.Result(0, "gradle 8.1", "")));
    assertEquals("it answered 'gradle 8.1', not 'sail <version>'", other.getMessage());
    var failed =
        assertThrows(
            SailBinary.NotSail.class,
            () -> SailBinary.parseVersion(new ShellExec.Result(2, "", "Unknown option: '-V'")));
    assertEquals("it answered 'Unknown option: '-V'', not 'sail <version>'", failed.getMessage());
    assertThrows(
        SailBinary.NotSail.class,
        () -> SailBinary.parseVersion(new ShellExec.Result(0, "sail dev", "")));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void theVersionIsWhatTheFileItselfAnswers() throws Exception {
    var sail = script("sail", "echo 'sail 1.2.3'");

    assertEquals(SemVer.parse("1.2.3"), SailBinary.versionOf(sail));
    var unrunnable =
        assertThrows(SailBinary.NotSail.class, () -> SailBinary.versionOf(dir.resolve("missing")));
    assertTrue(unrunnable.getMessage().startsWith("it could not run ("), unrunnable.getMessage());
  }

  @Test
  void onlyABinaryWithTheExpectedChecksumAndThisPlatformsHeaderIsAcceptable() {
    var notElf = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};

    assertTrue(SailBinary.isAcceptable(ELF, SailBinary.sha256(ELF), "Linux"));
    assertTrue(SailBinary.isAcceptable(ELF, SailBinary.sha256(ELF).toUpperCase(), "Linux"));
    assertFalse(SailBinary.isAcceptable(ELF, "00ff", "Linux"));
    assertFalse(SailBinary.isAcceptable(notElf, SailBinary.sha256(notElf), "Linux"));
    assertFalse(SailBinary.isAcceptable(ELF, "00ff"), "whatever this platform is");
  }

  @Test
  void aStagedBinarySitsBesideItsTargetWithItsModeAndReplacesItInOneStep() throws Exception {
    var target = Files.write(dir.resolve("sail"), new byte[] {1});
    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-x---"));

    try (var staged = SailBinary.stage(ELF, target)) {
      assertEquals(dir.resolve("sail.tmp"), staged.file());
      assertEquals(
          "rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(staged.file())));
      staged.install();
    }

    assertArrayEquals(ELF, Files.readAllBytes(target));
    assertFalse(Files.exists(dir.resolve("sail.tmp")));
  }

  @Test
  void aStagedBinaryNeverInstalledLeavesNothingBehind() throws Exception {
    var target = dir.resolve("sail");

    try (var staged = SailBinary.stage(ELF, target)) {
      assertEquals(
          "rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(staged.file())));
    }

    assertFalse(Files.exists(dir.resolve("sail.tmp")));
    assertFalse(Files.exists(target));
  }

  private Path script(String name, String body) throws Exception {
    var file = Files.writeString(dir.resolve(name), "#!/bin/sh\n" + body + "\n");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    return file;
  }
}
