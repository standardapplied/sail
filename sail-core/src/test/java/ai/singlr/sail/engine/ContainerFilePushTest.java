/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainerFilePushTest {

  private static Path stagedTempFile(String invocation) {
    return Arrays.stream(invocation.split(" "))
        .filter(token -> token.contains("/staging/push-"))
        .map(Path::of)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void buildsIncusPushWithFlagsAndDestination() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus file push");
    ContainerFilePush.push(
        shell, "acme", "/etc/sail/project.yaml", "hi", List.of("--mode", "0600"));

    var push =
        shell.invocations().stream()
            .filter(c -> c.startsWith("incus file push"))
            .findFirst()
            .orElseThrow();
    assertTrue(push.contains("--mode 0600"), push);
    assertTrue(push.endsWith("acme/etc/sail/project.yaml"), push);
  }

  @Test
  void throwsWhenPushFailsIncludingPathAndStderr() {
    var shell = new ScriptedShellExecutor().onFail("incus file push", "boom");
    var ex =
        assertThrows(
            IOException.class,
            () -> ContainerFilePush.push(shell, "acme", "/tmp/x", "data", List.of()));
    assertTrue(ex.getMessage().contains("/tmp/x"));
    assertTrue(ex.getMessage().contains("boom"));
  }

  @Test
  void deletesStagedTempFileOnSuccess() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus file push");
    ContainerFilePush.push(shell, "acme", "/tmp/x", "data", List.of());

    var staged = stagedTempFile(shell.invocations().getFirst());
    assertFalse(Files.exists(staged), "Temp file should be deleted after a successful push");
  }

  @Test
  void pushesACallersFileAsItIsAndLeavesItWhereItWas(@TempDir Path dir) throws Exception {
    var source = Files.writeString(dir.resolve("check.sh"), "#!/bin/sh\ntrue\n");
    var shell = new ScriptedShellExecutor().onOk("incus file push");

    ContainerFilePush.push(
        shell,
        "acme",
        "/home/dev/.claude/skills/x/check.sh",
        source,
        List.of("--uid", "1000", "--gid", "1000", "--mode", "0755"));

    assertEquals(
        List.of(
            List.of(
                "incus",
                "file",
                "push",
                "--uid",
                "1000",
                "--gid",
                "1000",
                "--mode",
                "0755",
                source.toString(),
                "acme/home/dev/.claude/skills/x/check.sh")),
        shell.arguments());
    assertEquals("#!/bin/sh\ntrue\n", Files.readString(source), "the caller's file is its own");
  }

  @Test
  void aCallersFileThatCannotBePushedFailsNamingThePathAndIsLeftToo(@TempDir Path dir)
      throws Exception {
    var source = Files.writeString(dir.resolve("check.sh"), "true");
    var shell = new ScriptedShellExecutor().onFail("incus file push", "boom");

    var failed =
        assertThrows(
            IOException.class,
            () -> ContainerFilePush.push(shell, "acme", "/tmp/x", source, List.of()));

    assertEquals("Failed to push file to /tmp/x: boom", failed.getMessage());
    assertTrue(Files.exists(source));
  }

  @Test
  void deletesStagedTempFileOnFailure() {
    var shell = new ScriptedShellExecutor().onFail("incus file push", "boom");
    assertThrows(
        IOException.class,
        () -> ContainerFilePush.push(shell, "acme", "/tmp/x", "data", List.of()));

    var staged = stagedTempFile(shell.invocations().getFirst());
    assertFalse(Files.exists(staged), "Temp file should be deleted even when the push fails");
  }

  @Test
  void contentIsStagedOnlyInTheBoxsOwnPrivateDirectoryBesideItsDatabase() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("incus file push");
    ContainerFilePush.push(shell, "acme", "/tmp/x", "secret", List.of());

    var dir = stagedTempFile(shell.invocations().getFirst()).getParent();
    assertEquals(SailPaths.stagingDir(), dir);
    assertEquals(SailPaths.dataDir(), dir.getParent(), "never in the shared /tmp");
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
  }

  @Test
  void aStagingDirectoryFoundReadableByOthersIsMadePrivateAgain(@TempDir Path tmp)
      throws Exception {
    var dir = ContainerFilePush.staging(tmp.resolve("staging"));
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));

    ContainerFilePush.staging(tmp.resolve("staging"));

    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
  }

  @Test
  void aStagingDirectoryThatIsALinkIsRefused(@TempDir Path tmp) throws Exception {
    var elsewhere = Files.createDirectory(tmp.resolve("elsewhere"));
    Files.createSymbolicLink(tmp.resolve("staging"), elsewhere);

    assertThrows(IOException.class, () -> ContainerFilePush.staging(tmp.resolve("staging")));
  }

  @Test
  void whatAKilledPushLeftIsRemovedOnceItIsOldAndAPushInFlightIsKept(@TempDir Path tmp)
      throws Exception {
    var dir = ContainerFilePush.staging(tmp.resolve("staging"));
    var left = Files.writeString(dir.resolve("push-left.tmp"), "secret");
    Files.setLastModifiedTime(left, FileTime.from(Instant.now().minus(Duration.ofMinutes(5))));
    var inFlight = Files.writeString(dir.resolve("push-now.tmp"), "secret");

    assertEquals(1, ContainerFilePush.removeStaged(dir, Duration.ofMinutes(1)));

    assertFalse(Files.exists(left));
    assertTrue(Files.exists(inFlight));
  }
}
