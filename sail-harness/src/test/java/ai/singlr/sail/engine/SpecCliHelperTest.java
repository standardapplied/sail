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

class SpecCliHelperTest {

  @Test
  void constructorRejectsNullShell() {
    assertThrows(NullPointerException.class, () -> new SpecCliHelper(null));
  }

  @Test
  void scriptIsBashAndDependencyFreeOverTheSocket() {
    var content = SpecCliHelper.scriptContent();
    assertTrue(content.startsWith("#!/usr/bin/env bash"), content.substring(0, 60));
    assertTrue(content.contains("--unix-socket"));
    assertTrue(content.contains(SailPaths.apiSocketContainerPath().toString()));
    assertFalse(content.contains("__SAIL_API_SOCKET__"), "the socket placeholder is resolved");
    assertTrue(content.contains("--data-urlencode"), "must url-encode via curl, never build JSON");
  }

  @Test
  void scriptDerivesProjectFromHostnameAndPresentsTheRunCredential() {
    var content = SpecCliHelper.scriptContent();
    assertTrue(content.contains("PROJECT=\"$(hostname)\""));
    assertTrue(content.contains("CREDENTIAL=\"${SAIL_RUN_CREDENTIAL:-}\""));
    assertTrue(content.contains("Authorization: Bearer $CREDENTIAL"));
    assertFalse(content.contains("SAIL_ACTOR"));
  }

  @Test
  void scriptHandlesEverySubcommand() {
    var content = SpecCliHelper.scriptContent();
    assertTrue(content.contains("box.credential"));
    assertTrue(
        content.contains("SAIL_RUN_CREDENTIAL:-"),
        "the run credential must stay the preferred identity");
    for (var sub :
        new String[] {
          "board)",
          "list)",
          "show)",
          "create)",
          "update|edit)",
          "content)",
          "archive)",
          "comment)",
          "comments)"
        }) {
      assertTrue(content.contains(sub), "missing subcommand: " + sub);
    }
  }

  @Test
  void commentAcceptsTheQuestionFlagInAnyOrder() {
    var content = SpecCliHelper.scriptContent();
    assertTrue(content.contains("--question) FIELDS+=(--data-urlencode \"question=true\")"));
    assertTrue(
        content.contains("comment accepts only --reply-to <message-id> and --question"),
        "unknown comment options must still die loudly");
    assertTrue(
        content.contains("[--reply-to <message-id>] [--question]"),
        "the usage text teaches the flag");
  }

  @Test
  void createPostsAndUpdateAndArchivePut() {
    var content = SpecCliHelper.scriptContent();
    assertTrue(content.contains("-X POST --data-urlencode \"project=$PROJECT\""));
    assertTrue(content.contains("-X PUT"));
    assertTrue(content.contains("--data-urlencode \"status=archived\""));
    assertTrue(content.contains("body@$2"), "spec bodies come from a file, url-encoded by curl");
  }

  @Test
  void installInvokesIncusExecAsDevUser() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    new SpecCliHelper(shell).install("light-grid");

    var cmds = shell.invocations();
    assertEquals(3, cmds.size());
    assertTrue(cmds.get(0).contains("mkdir -p /home/dev/.sail/bin"));
    assertTrue(cmds.get(1).contains("chmod 0755"));
    assertTrue(cmds.get(1).contains("/home/dev/.sail/bin/spec"));
    assertTrue(cmds.get(2).contains("/home/dev/.profile"), "puts ~/.sail/bin on the login PATH");
    assertTrue(
        cmds.get(2).contains("grep -Fqsx"),
        "the installer must test the exact export line — the same invariant the machinery"
            + " verifier probes — or a profile mentioning .sail/bin never converges");
    assertTrue(
        cmds.get(2).contains(SpecCliHelper.profileLine()),
        "presence check and append must both use the generated export line");
  }

  @Test
  void installPropagatesMkdirAndWriteFailures() {
    var mkdirFail = new ScriptedShellExecutor().onFail("mkdir", "permission denied");
    var ex1 =
        assertThrows(IOException.class, () -> new SpecCliHelper(mkdirFail).install("light-grid"));
    assertTrue(ex1.getMessage().contains("permission denied"));

    var writeFail =
        new ScriptedShellExecutor()
            .onOk("mkdir -p /home/dev/.sail/bin")
            .onFail("printf '%s'", "disk full");
    var ex2 =
        assertThrows(IOException.class, () -> new SpecCliHelper(writeFail).install("light-grid"));
    assertTrue(ex2.getMessage().contains("disk full"));

    var pathFail =
        new ScriptedShellExecutor()
            .onOk("mkdir -p /home/dev/.sail/bin")
            .onOk("printf '%s'")
            .onFail("/home/dev/.profile", "read-only file system");
    var ex3 =
        assertThrows(IOException.class, () -> new SpecCliHelper(pathFail).install("light-grid"));
    assertTrue(ex3.getMessage().contains("read-only file system"));
  }

  @Test
  void installRejectsInvalidContainerName() {
    assertThrows(
        Exception.class, () -> new SpecCliHelper(new ScriptedShellExecutor()).install("../bad"));
  }

  @Test
  void scriptPathConstantsMatch() {
    assertEquals("/home/dev/.sail/bin/spec", SpecCliHelper.SCRIPT_PATH);
    assertEquals("/home/dev/.sail/bin", SpecCliHelper.SCRIPT_DIR);
  }
}
