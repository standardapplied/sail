/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Incus lane keeps evidence of a make that got no address, and of nothing else. */
class NoAddressEvidenceTest {

  private static final Path DIR = Path.of("target", "incus-evidence");

  private static List<Path> keptOf(String instance) throws IOException {
    if (!Files.isDirectory(DIR)) {
      return List.of();
    }
    try (var files = Files.list(DIR)) {
      return files
          .filter(file -> file.getFileName().toString().startsWith(instance + "-"))
          .toList();
    }
  }

  @Test
  void aMakeThatGotNoAddressKeepsWhatTheBoxSawBeforeItsAnswerGoesBack() throws Exception {
    var instance = "evtest-" + System.nanoTime();
    var incus =
        new ScriptedShellExecutor()
            .onFail("bash -c", instance + " got no address on eth0 within 30 s, so its repos …")
            .onOk(
                "incus network list --format json", "[{\"name\": \"incusbr0\", \"managed\": true}]")
            .onOk("incus network list-leases incusbr0", "LEASES")
            .onOk("incus info " + instance, "INFO")
            .onOk("incus config show " + instance, "CONFIG")
            .onOk("journalctl", "DNSMASQ");

    var answered =
        new NoAddressEvidence(incus).exec(List.of("incus", "exec", instance, "--", "bash", "-c"));

    assertTrue(answered.stderr().contains("got no address"));
    var kept = keptOf(instance);
    assertEquals(1, kept.size(), String.valueOf(kept));
    var said = Files.readString(kept.getFirst());
    Files.delete(kept.getFirst());
    for (var section :
        List.of(
            "== the refusal\n" + instance + " got no address",
            "== /proc/loadavg\n",
            "== incus network list-leases incusbr0\nexit 0\nLEASES",
            "== incus info " + instance + "\nexit 0\nINFO",
            "== incus config show " + instance + " --expanded\nexit 0\nCONFIG",
            "dnsmasq-dhcp\nexit 0\nDNSMASQ")) {
      assertTrue(said.contains(section), section + " in:\n" + said);
    }
  }

  @Test
  void anyOtherFailureKeepsNothing() throws Exception {
    var instance = "evtest-" + System.nanoTime();
    var incus = new ScriptedShellExecutor().onFail("bash -c", instance + ": fetch failed");

    new NoAddressEvidence(incus).exec(List.of("incus", "exec", instance, "--", "bash", "-c"));

    assertEquals(List.of(), keptOf(instance));
  }
}
