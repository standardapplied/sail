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
import org.junit.jupiter.api.Test;

class SailEventHelperTest {

  @Test
  void constructorRejectsNullShell() {
    assertThrows(NullPointerException.class, () -> new SailEventHelper(null));
  }

  @Test
  void scriptStartsWithShebang() {
    var content = SailEventHelper.scriptContent();
    assertTrue(content.startsWith("#!/usr/bin/env bash"), content.substring(0, 80));
  }

  @Test
  void scriptUsesUnixSocket() {
    assertTrue(SailEventHelper.scriptContent().contains("--unix-socket"));
    assertTrue(SailEventHelper.scriptContent().contains("/run/sail/api.sock"));
  }

  @Test
  void scriptTakesEventTypeArgOnly() {
    var content = SailEventHelper.scriptContent();
    assertTrue(content.contains("EVENT_TYPE=\"${1:?event type required}\""));
  }

  @Test
  void scriptReadsSpecFromEnvVar() {
    var content = SailEventHelper.scriptContent();
    assertTrue(content.contains("SPEC_ID=\"${SAIL_SPEC_ID:-}\""));
    assertTrue(content.contains("AGENT=\"${SAIL_AGENT:-claude-code}\""));
  }

  @Test
  void scriptNoOpsWhenSpecIdMissing() {
    var content = SailEventHelper.scriptContent();
    var idx = content.indexOf("if [ -z \"$SPEC_ID\" ]; then");
    assertTrue(idx > 0, "must short-circuit when SAIL_SPEC_ID is unset");
    var afterCheck = content.substring(idx);
    assertTrue(
        afterCheck.indexOf("exit 0") < afterCheck.indexOf("curl"),
        "exit 0 must come before the curl call so engineer sessions skip the POST");
  }

  @Test
  void scriptFallsBackWhenSocketMissing() {
    var content = SailEventHelper.scriptContent();
    assertTrue(content.contains("if [ ! -S \"$SOCKET\" ]; then"));
    assertTrue(content.contains("exit 0"));
  }

  @Test
  void scriptIsBestEffort() {
    var content = SailEventHelper.scriptContent();
    assertTrue(content.contains("|| true"), "curl failure must not propagate");
  }

  @Test
  void scriptEmbedsSpecFieldUnconditionally() {
    var content = SailEventHelper.scriptContent();
    assertTrue(
        content.contains("\\\"spec\\\":\\\"$SPEC_ID\\\""),
        "spec field is always populated when the event is sent");
    assertFalse(
        content.contains("SPEC_FIELD"),
        "the old conditional SPEC_FIELD branch should have been removed");
  }

  @Test
  void installInvokesIncusExecAsDevUser() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var helper = new SailEventHelper(shell);

    helper.install("light-grid");

    var cmds = shell.invocations();
    assertEquals(2, cmds.size());
    assertTrue(cmds.get(0).contains("mkdir -p /home/dev/.sail/bin"));
    assertTrue(cmds.get(1).contains("chmod 0755"));
    assertTrue(cmds.get(1).contains("/home/dev/.sail/bin/sail-event.sh"));
  }

  @Test
  void installPropagatesMkdirFailure() {
    var shell = new ScriptedShellExecutor().onFail("mkdir", "permission denied");
    var helper = new SailEventHelper(shell);

    var ex = assertThrows(IOException.class, () -> helper.install("light-grid"));
    assertTrue(ex.getMessage().contains("permission denied"));
  }

  @Test
  void installPropagatesWriteFailure() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("mkdir -p /home/dev/.sail/bin")
            .onFail("printf '%s'", "disk full");
    var helper = new SailEventHelper(shell);

    var ex = assertThrows(IOException.class, () -> helper.install("light-grid"));
    assertTrue(ex.getMessage().contains("disk full"));
  }

  @Test
  void installRejectsInvalidContainerName() {
    var helper = new SailEventHelper(new ScriptedShellExecutor());
    assertThrows(Exception.class, () -> helper.install("../bad"));
  }

  @Test
  void scriptPathConstantsMatch() {
    assertEquals("/home/dev/.sail/bin/sail-event.sh", SailEventHelper.SCRIPT_PATH);
    assertEquals("/home/dev/.sail/bin", SailEventHelper.SCRIPT_DIR);
  }
}
