/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class NeedrestartPolicyTest {

  @TempDir Path dir;

  @Test
  void thePolicyDefersEveryIncusRestartAndNoSailOne() {
    var directives =
        NeedrestartPolicy.DROP_IN.content().lines().filter(line -> !line.startsWith("#")).toList();

    assertEquals(List.of("$nrconf{override_rc}{qr(^incus)} = 0;"), directives);
  }

  @Test
  void installingWritesTheSnippetWithMode0644IntoADirectoryItCreates() throws Exception {
    var snippet = dir.resolve("etc/needrestart/conf.d/50-sail.conf");

    assertEquals(0, run(NeedrestartPolicy.DROP_IN.installCommand(snippet.toString())));

    assertEquals(NeedrestartPolicy.DROP_IN.content(), Files.readString(snippet));
    assertEquals(
        "rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(snippet)));
  }

  @Test
  void installingTwiceLeavesOneIdenticalFile() throws Exception {
    var snippet = dir.resolve("50-sail.conf");

    assertEquals(0, run(NeedrestartPolicy.DROP_IN.installCommand(snippet.toString())));
    assertEquals(0, run(NeedrestartPolicy.DROP_IN.installCommand(snippet.toString())));

    assertEquals(NeedrestartPolicy.DROP_IN.content(), Files.readString(snippet));
    try (var files = Files.list(dir)) {
      assertEquals(List.of(snippet), files.toList());
    }
  }

  @Test
  void theContentReachesBashAsAnArgumentNeverAsScript() {
    var policy = NeedrestartPolicy.DROP_IN;

    assertEquals(
        List.of(
            "bash",
            "-c",
            HostDropIn.WRITE_SCRIPT,
            "bash",
            policy.content(),
            "/etc/needrestart/conf.d/50-sail.conf"),
        policy.installCommand(policy.path()));
  }

  private int run(List<String> command) throws Exception {
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    var output = new String(process.getInputStream().readAllBytes());
    assertTrue(process.waitFor(10, TimeUnit.SECONDS), "the install script hung: " + output);
    return process.exitValue();
  }
}
