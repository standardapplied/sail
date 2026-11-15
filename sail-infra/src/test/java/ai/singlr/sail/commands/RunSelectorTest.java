/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.RunStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Medium 7: one selector for the verbs that read, attach to or stop a run, and the one form every
 * printed command for a run takes, so it reaches that run however many are live.
 */
class RunSelectorTest {

  private static final String AUTH_RUN = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String ADHOC_RUN = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

  private static RunStore.RunRow run(String id, String specId) {
    return new RunStore.RunRow(
        id,
        "acme",
        specId,
        "eng",
        specId == null ? "adhoc" : "build",
        "claude-code",
        null,
        "t",
        null,
        null,
        "running",
        null,
        "l",
        "u",
        Instant.EPOCH,
        null,
        List.of(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** A verb that takes the selector, as every run verb does. */
  @CommandLine.Command(name = "verb")
  static final class Verb {
    @CommandLine.Parameters(index = "0")
    String project;

    @CommandLine.Mixin RunSelector selector = new RunSelector();
  }

  private static RunSelector parsed(String... args) {
    var verb = new Verb();
    new CommandLine(verb).parseArgs(args);
    return verb.selector;
  }

  @Test
  void aSpecPicksItsRunsARunItselfAndNeitherAnyRun() {
    var auth = run(AUTH_RUN, "auth");
    var adhoc = run(ADHOC_RUN, null);

    assertTrue(parsed("acme", "--spec", "auth").picks(auth));
    assertFalse(parsed("acme", "--spec", "auth").picks(adhoc));
    assertTrue(parsed("acme", "--run", ADHOC_RUN).picks(adhoc));
    assertFalse(parsed("acme", "--run", ADHOC_RUN).picks(auth));
    assertTrue(parsed("acme").picks(auth) && parsed("acme").picks(adhoc));
    assertFalse(parsed("acme").names());
  }

  @Test
  void aPrintedCommandNamesASpecsRunByItsSpecAndAnyOtherByItsId() {
    assertEquals(" --spec auth", RunSelector.of(run(AUTH_RUN, "auth")));
    assertEquals(" --run " + ADHOC_RUN, RunSelector.of(run(ADHOC_RUN, null)));
  }
}
