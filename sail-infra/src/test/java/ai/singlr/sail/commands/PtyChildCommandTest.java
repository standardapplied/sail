/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command-line skin of the shim, run as the real {@code sail _pty-child} entry point in its own
 * JVM: the option parsing, the {@code --} separator, and the status that comes back.
 */
@EnabledOnOs(OS.LINUX)
class PtyChildCommandTest {

  @TempDir Path dir;

  @Test
  void theCommandRecordsTheStatusAndMirrorsIt() throws Exception {
    var exit = dir.resolve("s.exit");
    var shim =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty("java.class.path"),
                "ai.singlr.sail.Main",
                "_pty-child",
                "--exit",
                exit.toString(),
                "--",
                "sh",
                "-c",
                "echo shimmed; exit 7")
            .redirectErrorStream(true)
            .redirectOutput(dir.resolve("out.txt").toFile())
            .start();

    assertTrue(shim.waitFor(60, TimeUnit.SECONDS));
    assertEquals(7, shim.exitValue());
    assertEquals("7", Files.readString(exit));
    assertTrue(Files.readString(dir.resolve("out.txt")).contains("shimmed"), "stdio is inherited");
  }

  @Test
  void theShimCommandIsTheJvmEntryPointOffTheNativeImage() {
    var command = PtyHostCommand.shimCommand(dir.resolve("s.exit"));
    assertTrue(command.contains("ai.singlr.sail.pty.PtyChildShim"), command.toString());
    assertEquals(dir.resolve("s.exit").toString(), command.getLast());
  }
}
