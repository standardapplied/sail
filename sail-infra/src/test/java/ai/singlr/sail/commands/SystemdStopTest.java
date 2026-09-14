/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class SystemdStopTest {

  private static final String USER_CGROUP =
      "0::/user.slice/user-1000.slice/user@1000.service/app.slice/sail-pty-host.service\n";
  private static final String SYSTEM_CGROUP = "0::/system.slice/sail-pty-host.service\n";

  @Test
  void theUnitAndTheManagerAreReadFromTheCgroup() {
    assertEquals("sail-pty-host.service", SystemdStop.unitOf(USER_CGROUP));
    assertEquals("sail-pty-host.service", SystemdStop.unitOf(SYSTEM_CGROUP));
    assertEquals(
        "sail-it-pty-host-77.service",
        SystemdStop.unitOf(
            "0::/user.slice/user-1000.slice/user@1000.service/app.slice/sail-it-pty-host-77.service"));
    assertNull(SystemdStop.unitOf("0::/user.slice/user-1000.slice/session-3.scope\n"));
    assertNull(SystemdStop.unitOf(""));
  }

  @Test
  void theJobTypeOnTheUnitIsWhatListJobsSays() {
    var listing = "29724 sail-pty-host.service restart running\n29725 other.service stop waiting\n";
    assertEquals("restart", SystemdStop.jobTypeOn("sail-pty-host.service", listing));
    assertEquals("stop", SystemdStop.jobTypeOn("other.service", listing));
    assertNull(SystemdStop.jobTypeOn("absent.service", listing));
    assertNull(SystemdStop.jobTypeOn("sail-pty-host.service", ""));
  }

  @Test
  void aStopJobIsAStopAndEverythingElseIsARestart() {
    var stopping =
        new ScriptedShellExecutor().onOk("list-jobs", "1 sail-pty-host.service stop running\n");
    assertTrue(SystemdStop.stoppingForGood(stopping, USER_CGROUP));
    assertTrue(
        stopping.invocations().stream().anyMatch(c -> c.contains("systemctl --user list-jobs")),
        "a user service asks the user manager: " + stopping.invocations());

    var restarting =
        new ScriptedShellExecutor().onOk("list-jobs", "1 sail-pty-host.service restart running\n");
    assertFalse(SystemdStop.stoppingForGood(restarting, SYSTEM_CGROUP));
    assertTrue(
        restarting.invocations().stream()
            .anyMatch(c -> c.contains("NOTIFY_SOCKET systemctl list-jobs")),
        "a system service asks the system manager: " + restarting.invocations());

    assertFalse(
        SystemdStop.stoppingForGood(new ScriptedShellExecutor().onOk("list-jobs", ""), USER_CGROUP),
        "no job on the unit reads as a restart, the safe default");
    assertFalse(
        SystemdStop.stoppingForGood(
            new ScriptedShellExecutor().onFail("list-jobs", "no bus"), USER_CGROUP),
        "an unanswerable manager reads as a restart");
    assertFalse(
        SystemdStop.stoppingForGood(
            new ScriptedShellExecutor().onThrow("list-jobs", new IOException("boom")),
            USER_CGROUP));
    assertFalse(
        SystemdStop.stoppingForGood(
            new ScriptedShellExecutor(new ShellExec.Result(0, "1 x.service stop running", "")),
            "0::/init.scope\n"),
        "outside a service there is no unit to ask about");
  }
}
