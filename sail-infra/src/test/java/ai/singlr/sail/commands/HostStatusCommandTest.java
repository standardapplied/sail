/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.HostDetector;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HostStatusCommandTest {

  private static final HostYaml RECORDED_AT_INIT =
      new HostYaml(
          "dir",
          "devpool",
          null,
          "incusbr0",
          "singlr-base",
          "ubuntu/24.04",
          "6.8",
          null,
          "2026-02-18T01:00:00Z");

  private static final HostDetector.HostInfo HOST =
      new HostDetector.HostInfo("main", "ubuntu", "24.04", "Ubuntu 24.04 LTS", 4, 8, 16384, true);

  @Test
  void theIncusVersionIsTheOneInstalledNowNotTheOneRecordedAtInit() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onOk("incus version", "Client version: 6.21\nServer version: 6.21\n");

    assertEquals("6.21", json(shell).get("incus_version"));
    assertTrue(text(shell).contains("│  Incus:       6.21 "));
  }

  @Test
  void anIncusThatDoesNotAnswerReadsUnknown() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onFail("incus version", "cannot connect to the incus daemon");

    assertEquals("unknown", json(shell).get("incus_version"));
    assertTrue(text(shell).contains("│  Incus:       unknown "));
  }

  private static Map<String, Object> json(ScriptedShellExecutor shell) throws Exception {
    return YamlUtil.parseMap(report(shell, true));
  }

  private static String text(ScriptedShellExecutor shell) throws Exception {
    return report(shell, false);
  }

  private static String report(ScriptedShellExecutor shell, boolean json) throws Exception {
    var out = new ByteArrayOutputStream();
    HostStatusCommand.report(
        RECORDED_AT_INIT, HOST, shell, json, new PrintStream(out, true, StandardCharsets.UTF_8));
    return out.toString(StandardCharsets.UTF_8);
  }
}
