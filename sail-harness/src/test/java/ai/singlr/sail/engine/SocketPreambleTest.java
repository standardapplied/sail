/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every in-container helper sends its credential one way, {@code sail_curl}: the header reaches
 * curl on a descriptor, never in an argv, so no process listing shows it.
 */
class SocketPreambleTest {

  private static final String CREDENTIAL = "sailrun_secret_0123456789";

  @TempDir Path home;

  @Test
  void everyHelperSendsItsCredentialOnlyThroughSailCurl() {
    var helpers =
        Map.of(
            "spec", SpecCliHelper.scriptContent(),
            "skill", SkillCliHelper.scriptContent(),
            "portal", PortalSkill.script(),
            "event", SailEventHelper.scriptContent(),
            "session report", SailSessionReport.scriptContent(),
            "stop gate", SailStopGate.scriptContent(),
            "room relay", SailRoomRelay.scriptContent());

    helpers.forEach(
        (name, script) -> {
          assertTrue(script.contains(SocketPreamble.credentialCurl()), name);
          assertTrue(script.contains("sail_curl "), name);
          var rest = script.replace(SocketPreamble.credentialCurl(), "");
          assertFalse(rest.contains("Authorization"), name + " names the header outside sail_curl");
          assertFalse(rest.contains("Bearer"), name);
        });
  }

  @Test
  void theFunctionIsPinned() {
    assertEquals(
        """
        sail_curl() {
          case $CREDENTIAL in *[!A-Za-z0-9_]*) echo "sail_curl: a malformed credential" >&2; return 2 ;; esac
          { printf 'header = "Authorization: Bearer %s"\\n' "$CREDENTIAL" \\
            | curl --config /dev/fd/3 "$@" 3<&0 <&4 4<&-; } 4<&0
        }
        """,
        SocketPreamble.credentialCurl());
  }

  @ParameterizedTest
  @ValueSource(strings = {"sh", "bash"})
  void theHeaderReachesCurlOutsideItsArgvAndABodyOnStdinStillReachesIt(String shell)
      throws Exception {
    var bin = Files.createDirectories(home.resolve("bin"));
    executable(bin.resolve("curl"), FakeCurl.script("cat >> \"$HOME/curl.stdin\"\n"));
    var script =
        "CREDENTIAL=\"$(cat \"$HOME/cred\")\"\n"
            + SocketPreamble.credentialCurl()
            + "printf 'the body' | sail_curl --silent -X POST --data-binary @- http://sail/x\n";
    Files.writeString(home.resolve("cred"), CREDENTIAL);

    var process =
        new ProcessBuilder(shell, "-c", script)
            .directory(home.toFile())
            .redirectErrorStream(true)
            .redirectOutput(home.resolve("out").toFile());
    process.environment().put("HOME", home.toString());
    process.environment().put("PATH", bin + ":/usr/bin:/bin");
    var ran = process.start();
    assertTrue(ran.waitFor(30, TimeUnit.SECONDS));

    assertEquals(0, ran.exitValue(), Files.readString(home.resolve("out")));
    var argv = Files.readString(home.resolve("curl.argv"));
    assertFalse(argv.contains(CREDENTIAL), argv);
    assertTrue(argv.contains("--config /dev/fd/3"), argv);
    assertEquals(
        List.of(
            "--silent -X POST --data-binary @- http://sail/x" + FakeCurl.presenting(CREDENTIAL)),
        Files.readAllLines(home.resolve("curl.log")));
    assertEquals("the body", Files.readString(home.resolve("curl.stdin")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"sh", "bash"})
  void aCredentialThatCouldCarryAConfigLineIsRefusedBeforeCurlRuns(String shell) throws Exception {
    var bin = Files.createDirectories(home.resolve("bin"));
    executable(bin.resolve("curl"), FakeCurl.script(""));
    var script =
        "CREDENTIAL=\"$(printf 'x\\noutput = \\\"injected')\"\n"
            + SocketPreamble.credentialCurl()
            + "sail_curl --silent http://sail/x; echo \"rc=$?\"\n";

    var process =
        new ProcessBuilder(shell, "-c", script)
            .directory(home.toFile())
            .redirectErrorStream(true)
            .redirectOutput(home.resolve("out").toFile());
    process.environment().put("HOME", home.toString());
    process.environment().put("PATH", bin + ":/usr/bin:/bin");
    var ran = process.start();
    assertTrue(ran.waitFor(30, TimeUnit.SECONDS));

    assertTrue(
        Files.readString(home.resolve("out")).contains("rc=2"),
        Files.readString(home.resolve("out")));
    assertFalse(Files.exists(home.resolve("curl.argv")), "curl never ran");
  }

  private static void executable(Path path, String content) throws IOException {
    Files.writeString(path, content);
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
  }
}
