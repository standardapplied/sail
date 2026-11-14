/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivateDirectoryTest {

  @TempDir Path dir;

  @Test
  void aDirectoryMadeOrFoundIsThisAccountsAloneAndALinkIsRefused() throws Exception {
    var made = PrivateDirectory.ensure(dir.resolve("made"));
    var found = Files.createDirectory(dir.resolve("found"));
    Files.setAttribute(found, "unix:mode", 0755);
    PrivateDirectory.ensure(found);
    var link = Files.createSymbolicLink(dir.resolve("link"), made);

    assertEquals(0700, (int) Files.getAttribute(made, "unix:mode") & 07777);
    assertEquals(0700, (int) Files.getAttribute(found, "unix:mode") & 07777);
    assertThrows(UncheckedIOException.class, () -> PrivateDirectory.ensure(link));
  }

  @Test
  void theProcessIdsAreTheEffectiveOnesItsStatusNames() throws Exception {
    var status =
        Files.writeString(
            dir.resolve("status"), "Name:\tsail\nUid:\t1000\t0\t0\t0\nGid:\t1000\t27\t27\t27\n");

    assertEquals(
        new PrivateDirectory.ProcessOwner(0, 27), PrivateDirectory.ProcessOwner.read(status));
    assertThrows(
        IllegalStateException.class,
        () ->
            PrivateDirectory.ProcessOwner.read(
                Files.writeString(dir.resolve("none"), "Name:\tx\n")));
    assertThrows(
        UncheckedIOException.class,
        () -> PrivateDirectory.ProcessOwner.read(dir.resolve("missing")));
  }
}
