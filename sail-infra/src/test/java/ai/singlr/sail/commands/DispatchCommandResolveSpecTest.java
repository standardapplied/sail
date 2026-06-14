/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SpecAudit;
import ai.singlr.sail.engine.SpecWorkspace;
import java.util.List;
import org.junit.jupiter.api.Test;

class DispatchCommandResolveSpecTest {

  private static final String PROJECT = "acme-health";
  private static final String SPECS_DIR = "/home/dev/workspace/specs";
  private static final String HOST = "sail-host-01";

  @Test
  void autoSelectsNextPendingWhenNoSpecGiven() throws Exception {
    var specs =
        List.of(
            specWith("done-spec", "done"),
            specWith("oauth-flow", "pending"),
            specWith("next-pending", "pending"));
    var workspace = workspace(new ScriptedShellExecutor());
    var audit = audit(new ScriptedShellExecutor());

    var resolution = DispatchCommand.resolveSpec(null, false, specs, workspace, audit, HOST);

    assertNotNull(resolution.spec());
    assertEquals("oauth-flow", resolution.spec().id());
    assertFalse(resolution.restarted());
    assertNull(resolution.previousStatus());
  }

  @Test
  void autoSelectReturnsNullSpecWhenNoPendingSpecs() throws Exception {
    var specs = List.of(specWith("done-spec", "done"));
    var workspace = workspace(new ScriptedShellExecutor());
    var audit = audit(new ScriptedShellExecutor());

    var resolution = DispatchCommand.resolveSpec(null, false, specs, workspace, audit, HOST);

    assertNull(resolution.spec());
    assertFalse(resolution.restarted());
  }

  @Test
  void explicitSpecPassesWhenPending() throws Exception {
    var specs = List.of(specWith("oauth-flow", "pending"));
    var workspace = workspace(new ScriptedShellExecutor());
    var audit = audit(new ScriptedShellExecutor());

    var resolution =
        DispatchCommand.resolveSpec("oauth-flow", false, specs, workspace, audit, HOST);

    assertEquals("oauth-flow", resolution.spec().id());
    assertFalse(resolution.restarted());
  }

  @Test
  void explicitSpecPassesWithRestartFlagEvenWhenPendingWithoutSideEffects() throws Exception {
    var specs = List.of(specWith("oauth-flow", "pending"));
    var shell = new ScriptedShellExecutor();

    var resolution =
        DispatchCommand.resolveSpec(
            "oauth-flow", true, specs, workspace(shell), audit(shell), HOST);

    assertEquals("oauth-flow", resolution.spec().id());
    assertFalse(resolution.restarted(), "pending spec already pending — nothing to restart");
    assertTrue(shell.invocations().isEmpty(), "pending spec + --restart should be a no-op");
  }

  @Test
  void unknownSpecThrowsIllegalArgument() {
    var specs = List.of(specWith("oauth-flow", "pending"));
    var workspace = workspace(new ScriptedShellExecutor());
    var audit = audit(new ScriptedShellExecutor());

    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> DispatchCommand.resolveSpec("nope", false, specs, workspace, audit, HOST));
    assertTrue(ex.getMessage().contains("nope"));
  }

  @Test
  void nonPendingSpecWithoutRestartThrowsHelpfulError() {
    var specs = List.of(specWith("oauth-flow", "in_progress"));
    var workspace = workspace(new ScriptedShellExecutor());
    var audit = audit(new ScriptedShellExecutor());

    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> DispatchCommand.resolveSpec("oauth-flow", false, specs, workspace, audit, HOST));
    assertTrue(ex.getMessage().contains("oauth-flow"));
    assertTrue(ex.getMessage().contains("in_progress"));
    assertTrue(ex.getMessage().contains("--restart"));
  }

  @Test
  void doneSpecWithoutRestartThrows() {
    var specs = List.of(specWith("oauth-flow", "done"));
    var workspace = workspace(new ScriptedShellExecutor());
    var audit = audit(new ScriptedShellExecutor());

    assertThrows(
        IllegalStateException.class,
        () -> DispatchCommand.resolveSpec("oauth-flow", false, specs, workspace, audit, HOST));
  }

  @Test
  void reviewSpecWithoutRestartThrows() {
    var specs = List.of(specWith("oauth-flow", "review"));
    var workspace = workspace(new ScriptedShellExecutor());
    var audit = audit(new ScriptedShellExecutor());

    assertThrows(
        IllegalStateException.class,
        () -> DispatchCommand.resolveSpec("oauth-flow", false, specs, workspace, audit, HOST));
  }

  @Test
  void restartResetsStatusAndAppendsRestartedAudit() throws Exception {
    var specs = List.of(specWith("oauth-flow", "in_progress"));
    var stored = "{id: oauth-flow, title: Implement OAuth, status: in_progress}";
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("cat " + SPECS_DIR + "/oauth-flow/spec.yaml", stored);
    var workspace = workspace(shell);
    var audit = audit(shell);

    var resolution = DispatchCommand.resolveSpec("oauth-flow", true, specs, workspace, audit, HOST);

    assertEquals("oauth-flow", resolution.spec().id());
    assertTrue(resolution.restarted(), "non-pending spec + --restart must mark restarted=true");
    assertEquals(
        "in_progress",
        resolution.previousStatus(),
        "previousStatus must carry the pre-reset status so the caller can publish spec_restarted");
    var cmds = shell.invocations();
    var sawWriteMetadata =
        cmds.stream().anyMatch(c -> c.contains("printf '%s' ") && c.contains("spec.yaml"));
    var sawAuditAppend =
        cmds.stream().anyMatch(c -> c.contains("printf '%s\\n'") && c.contains("audit.jsonl"));
    assertTrue(sawWriteMetadata, "restart should write spec.yaml; saw: " + cmds);
    assertTrue(sawAuditAppend, "restart should append audit.jsonl; saw: " + cmds);
    var auditLine = cmds.stream().filter(c -> c.contains("audit.jsonl")).findFirst().orElseThrow();
    assertTrue(auditLine.contains("\"event\": \"restarted\""));
    assertTrue(auditLine.contains("\"agent\": \"sail\""));
    assertTrue(auditLine.contains("\"note\": \"restarted from in_progress\""));
  }

  @Test
  void restartPropagatesWorkspaceFailure() {
    var specs = List.of(specWith("oauth-flow", "in_progress"));
    var shell =
        new ScriptedShellExecutor()
            .onFail("cat " + SPECS_DIR + "/oauth-flow/spec.yaml", "permission denied");
    var workspace = workspace(shell);
    var audit = audit(shell);

    assertThrows(
        Exception.class,
        () -> DispatchCommand.resolveSpec("oauth-flow", true, specs, workspace, audit, HOST));
  }

  private static Spec specWith(String id, String status) {
    return new Spec(id, "Title for " + id, SpecStatus.fromWire(status), null, List.of(), null);
  }

  private static SpecWorkspace workspace(ShellExec shell) {
    return new SpecWorkspace(shell, PROJECT, SPECS_DIR);
  }

  private static SpecAudit audit(ShellExec shell) {
    return new SpecAudit(shell, PROJECT, SPECS_DIR);
  }
}
