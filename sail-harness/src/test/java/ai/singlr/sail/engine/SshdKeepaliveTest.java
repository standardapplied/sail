/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class SshdKeepaliveTest {

  @TempDir Path dir;

  @Test
  void theDropInTellsSshdToDropAVanishedPeerWithinAMinute() {
    var content = SshdKeepalive.content();
    assertTrue(content.contains("ClientAliveInterval 15\n"), content);
    assertTrue(content.contains("ClientAliveCountMax 3\n"), content);
  }

  @Test
  void installingWritesTheDropInAndReloadsARunningSshd() throws Exception {
    var systemctlLog = fakeSystemctl(0);
    var dropIn = dir.resolve("etc/ssh/sshd_config.d/10-sail.conf");

    assertEquals(0, run(SshdKeepalive.installCommand(dropIn.toString())));

    assertEquals(SshdKeepalive.content(), Files.readString(dropIn));
    assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(dropIn)));
    var calls = Files.readAllLines(systemctlLog);
    assertEquals(List.of("is-active --quiet ssh", "reload ssh"), calls);
  }

  @Test
  void aStoppedSshdKeepsTheDropInForWhenItStartsAndIsNotReloaded() throws Exception {
    var systemctlLog = fakeSystemctl(3);
    var dropIn = dir.resolve("10-sail.conf");

    assertEquals(0, run(SshdKeepalive.installCommand(dropIn.toString())));

    assertEquals(SshdKeepalive.content(), Files.readString(dropIn));
    assertEquals(List.of("is-active --quiet ssh"), Files.readAllLines(systemctlLog));
  }

  @Test
  void theContainerInstallRunsAsRootNotTheDevUser() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    new SshdKeepalive(shell).install("light-grid");

    var invocation = shell.invocations().getFirst();
    assertTrue(invocation.startsWith("incus exec light-grid -- bash"), invocation);
    assertFalse(invocation.contains("--user"), "sshd's config is root's: " + invocation);
    assertTrue(invocation.contains(SshdKeepalive.DROP_IN_PATH), invocation);
  }

  private Path fakeSystemctl(int isActiveExit) throws Exception {
    var bin = Files.createDirectories(dir.resolve("bin"));
    var log = dir.resolve("systemctl.log");
    var script =
        """
        #!/bin/sh
        printf '%%s\\n' "$*" >> "%s"
        case "$1" in is-active) exit %d ;; esac
        exit 0
        """
            .formatted(log, isActiveExit);
    Files.writeString(bin.resolve("systemctl"), script);
    Files.setPosixFilePermissions(
        bin.resolve("systemctl"), PosixFilePermissions.fromString("rwxr-xr-x"));
    return log;
  }

  private int run(List<String> command) throws Exception {
    var builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().put("PATH", dir.resolve("bin") + ":" + System.getenv("PATH"));
    var process = builder.start();
    var output = new String(process.getInputStream().readAllBytes());
    assertTrue(process.waitFor(10, TimeUnit.SECONDS), "the install script hung: " + output);
    return process.exitValue();
  }
}
