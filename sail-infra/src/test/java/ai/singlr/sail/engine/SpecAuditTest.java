/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecAuditEvent;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SpecAuditTest {

  private static final String CONTAINER = "acme-health";
  private static final String SPECS_DIR = "/home/dev/workspace/specs";
  private static final String HOST = "sail-host-01";
  private static final Instant FIXED_TS = Instant.parse("2026-05-21T12:34:56Z");

  @Test
  void constructorRejectsNullShell() {
    assertThrows(NullPointerException.class, () -> new SpecAudit(null, CONTAINER, SPECS_DIR));
  }

  @Test
  void constructorRejectsInvalidContainerName() {
    assertThrows(
        Exception.class, () -> new SpecAudit(new ScriptedShellExecutor(), "../bad", SPECS_DIR));
  }

  @Test
  void constructorRejectsBlankSpecsDir() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SpecAudit(new ScriptedShellExecutor(), CONTAINER, "   "));
  }

  @Test
  void constructorRejectsNullSpecsDir() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SpecAudit(new ScriptedShellExecutor(), CONTAINER, null));
  }

  @Test
  void auditPathBuildsCorrectLocation() {
    var audit = new SpecAudit(new ScriptedShellExecutor(), CONTAINER, SPECS_DIR);

    assertEquals(
        SPECS_DIR + "/oauth-flow/" + SpecAudit.AUDIT_FILENAME, audit.auditPath("oauth-flow"));
  }

  @Test
  void auditPathRejectsInvalidSpecId() {
    var audit = new SpecAudit(new ScriptedShellExecutor(), CONTAINER, SPECS_DIR);

    assertThrows(Exception.class, () -> audit.auditPath("../etc"));
  }

  @Test
  void appendBuildsAtomicShellRedirect() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var audit = new SpecAudit(shell, CONTAINER, SPECS_DIR);
    var event = new SpecAuditEvent(FIXED_TS, "dispatched", "sail", null, HOST, null);

    audit.append("oauth-flow", event);

    var cmds = shell.invocations();
    assertEquals(1, cmds.size());
    var cmd = cmds.getFirst();
    assertTrue(cmd.contains("incus exec " + CONTAINER));
    assertTrue(cmd.contains("printf '%s\\n'"));
    assertTrue(cmd.contains(">> \"$2\""));
    assertTrue(cmd.contains(SPECS_DIR + "/oauth-flow/audit.jsonl"));
    assertTrue(cmd.contains(event.toJsonLine()));
  }

  @Test
  void appendThrowsOnFailure() {
    var shell = new ScriptedShellExecutor().onFail("printf", "no space left on device");
    var audit = new SpecAudit(shell, CONTAINER, SPECS_DIR);
    var event = SpecAuditEvent.dispatched("sail", HOST, null);

    var ex = assertThrows(IOException.class, () -> audit.append("oauth-flow", event));
    assertTrue(ex.getMessage().contains("oauth-flow"));
    assertTrue(ex.getMessage().contains("no space left on device"));
  }

  @Test
  void appendRejectsNullEvent() {
    var audit = new SpecAudit(new ScriptedShellExecutor(), CONTAINER, SPECS_DIR);
    assertThrows(NullPointerException.class, () -> audit.append("oauth-flow", null));
  }

  @Test
  void appendRejectsInvalidSpecId() {
    var audit = new SpecAudit(new ScriptedShellExecutor(), CONTAINER, SPECS_DIR);
    var event = SpecAuditEvent.dispatched("sail", HOST, null);

    assertThrows(Exception.class, () -> audit.append("../etc", event));
  }

  @Test
  void readParsesMultipleLinesInOrder() throws Exception {
    var first = new SpecAuditEvent(FIXED_TS, "dispatched", "sail", null, HOST, null);
    var second =
        new SpecAuditEvent(FIXED_TS.plusSeconds(60), "started", "claude-code", 42, HOST, null);
    var contents = first.toJsonLine() + "\n" + second.toJsonLine() + "\n";

    var shell = new ScriptedShellExecutor().onOk("cat " + SPECS_DIR + "/oauth-flow", contents);
    var audit = new SpecAudit(shell, CONTAINER, SPECS_DIR);

    var events = audit.read("oauth-flow");

    assertEquals(2, events.size());
    assertEquals(first, events.get(0));
    assertEquals(second, events.get(1));
  }

  @Test
  void readReturnsEmptyListWhenFileMissing() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("cat " + SPECS_DIR + "/oauth-flow", "cat: No such file or directory");
    var audit = new SpecAudit(shell, CONTAINER, SPECS_DIR);

    assertTrue(audit.read("oauth-flow").isEmpty());
  }

  @Test
  void readThrowsOnOtherFailure() {
    var shell =
        new ScriptedShellExecutor().onFail("cat " + SPECS_DIR + "/oauth-flow", "permission denied");
    var audit = new SpecAudit(shell, CONTAINER, SPECS_DIR);

    var ex = assertThrows(IOException.class, () -> audit.read("oauth-flow"));
    assertTrue(ex.getMessage().contains("oauth-flow"));
    assertTrue(ex.getMessage().contains("permission denied"));
  }

  @Test
  void readSkipsBlankAndCorruptLines() throws Exception {
    var good = SpecAuditEvent.dispatched("sail", HOST, null);
    var contents = "\n" + good.toJsonLine() + "\n{not json at all}\n" + "\n";

    var shell = new ScriptedShellExecutor().onOk("cat " + SPECS_DIR + "/oauth-flow", contents);
    var audit = new SpecAudit(shell, CONTAINER, SPECS_DIR);

    var events = audit.read("oauth-flow");

    assertEquals(1, events.size());
    assertEquals(good.event(), events.getFirst().event());
  }

  @Test
  void readSkipsLineWithMissingRequiredField() throws Exception {
    var contents =
        "{\"ts\":\"2026-05-21T12:34:56Z\",\"agent\":\"sail\",\"host\":\"h\"}\n"
            + SpecAuditEvent.dispatched("sail", HOST, null).toJsonLine()
            + "\n";

    var shell = new ScriptedShellExecutor().onOk("cat " + SPECS_DIR + "/oauth-flow", contents);
    var audit = new SpecAudit(shell, CONTAINER, SPECS_DIR);

    var events = audit.read("oauth-flow");

    assertEquals(1, events.size());
  }

  @Test
  void readRejectsInvalidSpecId() {
    var audit = new SpecAudit(new ScriptedShellExecutor(), CONTAINER, SPECS_DIR);
    assertThrows(Exception.class, () -> audit.read("../etc"));
  }

  @Test
  void readReturnsEmptyForEmptyFile() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("cat " + SPECS_DIR + "/oauth-flow", "");
    var audit = new SpecAudit(shell, CONTAINER, SPECS_DIR);

    assertTrue(audit.read("oauth-flow").isEmpty());
  }
}
