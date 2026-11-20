/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.CommandTimedOut;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StepTimeoutDiagnosisTest {

  private static final Duration BOUND = Duration.ofSeconds(1);
  private static final String USERADD =
      "sh -c id -u sail >/dev/null 2>&1 || useradd --system --no-create-home --home-dir /home/sail"
          + " --shell /bin/bash sail";
  private static final String PROCESSES =
      """
          PID    PPID STAT WCHAN                                ELAPSED COMMAND
         4201   32760 D    folio_wait_bit_common                  02:00 /usr/sbin/useradd --system
        32760       1 S    do_wait                                02:00 %s
      """
          .formatted(USERADD);

  @TempDir Path tempDir;

  private final ScriptedShellExecutor box =
      new ScriptedShellExecutor()
          .onOk("lslocks", "COMMAND PID TYPE MODE PATH\nuseradd 4201 POSIX WRITE /etc/.pwd.lock\n")
          .on(
              "ls -l",
              new ShellExec.Result(
                  2,
                  "-rw------- 1 root root 0 Nov 20 19:56 /etc/.pwd.lock\n",
                  "ls: cannot access '/etc/passwd.lock': No such file or directory\n"))
          .on("systemctl is-active systemd-userdbd", new ShellExec.Result(3, "inactive\n", ""))
          .on("getent passwd sail", new ShellExec.Result(2, "", ""));

  private static CommandTimedOut timedOut() {
    return new CommandTimedOut("Command timed out after 120s: " + USERADD, PROCESSES);
  }

  @Test
  void aStepsTimeoutIsSummedUpInOneSentenceAndRecordedWholeInTheInitLog() throws Exception {
    var log = tempDir.resolve("log/sail/init.log");

    var sentence =
        new StepTimeoutDiagnosis(box, log, BOUND)
            .of(new CliCommand.Printed("Command timed out", timedOut()));

    assertEquals(
        Optional.of(
            "At the timeout the step was still running useradd (pid 4201, blocked uninterruptibly"
                + " in folio_wait_bit_common); getent passwd sail answered and systemd-userdbd was"
                + " inactive; the step's processes and this box's file locks are in "
                + log
                + "."),
        sentence);
    var written = Files.readString(log);
    assertTrue(written.contains("Command timed out after 120s: " + USERADD), written);
    assertTrue(written.contains(PROCESSES), written);
    assertTrue(written.contains("useradd 4201 POSIX WRITE /etc/.pwd.lock"), written);
    assertTrue(written.contains("/etc/passwd.lock': No such file or directory"), written);
    assertTrue(
        written.contains("-- systemd-userdbd: systemctl is-active systemd-userdbd"), written);
    assertTrue(written.contains("-- an account lookup: getent passwd sail"), written);
    assertEquals(
        List.of(
            "lslocks --output COMMAND,PID,TYPE,MODE,PATH",
            "ls -l /etc/.pwd.lock /etc/passwd.lock /etc/shadow.lock /etc/group.lock"
                + " /etc/gshadow.lock",
            "systemctl is-active systemd-userdbd",
            "getent passwd sail"),
        box.invocations());
  }

  @Test
  void aProbeThatHangsIsCutAtItsOwnBoundAndSaidNotToHaveAnswered() {
    var log = tempDir.resolve("init.log");
    var hangingLookup = new HangingOn("getent", box);

    var sentence =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () -> new StepTimeoutDiagnosis(hangingLookup, log, BOUND).of(timedOut()));

    assertTrue(
        sentence.orElseThrow().contains("getent passwd sail did not answer within 1 s and"),
        sentence.orElseThrow());
    assertTrue(
        readString(log)
            .contains("-- an account lookup: getent passwd sail\ndid not answer within 1 s"),
        readString(log));
  }

  @Test
  void aStepThatDidNotTimeOutIsNotDiagnosed() {
    var log = tempDir.resolve("init.log");

    var sentence =
        new StepTimeoutDiagnosis(box, log, BOUND)
            .of(new CliCommand.Printed("refused", new IllegalStateException("refused")));

    assertEquals(Optional.empty(), sentence);
    assertFalse(Files.exists(log));
    assertEquals(List.of(), box.invocations());
  }

  @Test
  void aTimeoutWhoseProcessesCouldNotBeListedAndALogThatCannotBeWrittenAreSaidSo()
      throws Exception {
    var blocker = Files.writeString(tempDir.resolve("not-a-dir"), "");
    var log = blocker.resolve("init.log");

    var sentence =
        new StepTimeoutDiagnosis(box, log, BOUND)
            .of(new CommandTimedOut("Command timed out after 120s: x", "ps: not found\n"))
            .orElseThrow();

    assertTrue(
        sentence.startsWith(
            "At the timeout the step's processes could not be listed; getent passwd sail"),
        sentence);
    assertTrue(sentence.contains("; this diagnosis could not be written to " + log), sentence);
  }

  private static String readString(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The box's answers, except that the command naming {@code program} hangs until its bound. */
  private record HangingOn(String program, ShellExec box) implements ShellExec {
    @Override
    public Result exec(List<String> command)
        throws IOException, InterruptedException, TimeoutException {
      return exec(command, null, Duration.ofSeconds(120));
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout)
        throws IOException, InterruptedException, TimeoutException {
      return command.getFirst().equals(program)
          ? new ShellExecutor(false).exec(List.of("sleep", "30"), null, timeout)
          : box.exec(command, workDir, timeout);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
