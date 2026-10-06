/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.harness.Harness.Launch;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** What every harness sail knows must answer, run over {@link Harnesses#all}. */
class HarnessContractTest {

  private static final String TASK = "/home/dev/.sail/agent-task.txt";
  private static final String MALFORMED_ID =
      "Malformed session id; refusing to build a resume command from replicated data.";

  static Stream<Harness> harnesses() {
    return Harnesses.all().stream();
  }

  @ParameterizedTest
  @MethodSource("harnesses")
  void identityIsNotBlank(Harness harness) {
    for (var identity :
        List.of(
            harness.yamlName(),
            harness.binaryName(),
            harness.displayName(),
            harness.installCommand(),
            harness.homeContextPath(),
            harness.skillsDir())) {
      assertFalse(identity.isBlank(), harness.yamlName());
    }
    assertEquals(harness, Harnesses.of(harness.yamlName()));
  }

  @ParameterizedTest
  @MethodSource("harnesses")
  void theHookFileNamesEveryRequiredHook(Harness harness) {
    var declared =
        harness.hooks().groups().stream().flatMap(group -> group.hooks().stream()).toList();

    for (var required : SailHook.required()) {
      assertTrue(declared.contains(required), harness.yamlName() + " runs " + required);
    }
    assertTrue(harness.hooks().path().startsWith("/home/dev/"), harness.hooks().path());
  }

  @ParameterizedTest
  @MethodSource("harnesses")
  void headlessWithAMalformedSessionIdThrows(Harness harness) {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> harness.headless(new Launch(TASK, true, null, null, "a; rm -rf /", true)));

    assertEquals(MALFORMED_ID, ex.getMessage());
  }

  @ParameterizedTest
  @MethodSource("harnesses")
  void readOnlyWithAMalformedSessionIdThrowsItOrTheRefusal(Harness harness) {
    var launch = new Launch(TASK, false, null, null, "a; rm -rf /", true);

    if (harness.readOnlyRefusal().isPresent()) {
      var ex = assertThrows(IllegalStateException.class, () -> harness.readOnly(launch));
      assertEquals(
          harness.displayName()
              + " has no harness-enforced read-only session inside a sail container; the room"
              + " lane refuses to launch it.",
          ex.getMessage());
      return;
    }
    var ex = assertThrows(IllegalArgumentException.class, () -> harness.readOnly(launch));
    assertEquals(MALFORMED_ID, ex.getMessage());
  }

  @Test
  void ordinarySessionIdShapesAreSafe() {
    assertTrue(Harness.isSafeSessionId("0198f00d-1234-7000-8000-abcdefabcdef"));
    assertTrue(Harness.isSafeSessionId("abc-123"));
    assertTrue(Harness.isSafeSessionId("a"));
    assertTrue(Harness.isSafeSessionId("9session.name_x"));
    assertTrue(Harness.isSafeSessionId("a".repeat(128)));
  }

  @Test
  void aSessionIdStartingWithADashIsRejectedAsOptionInjection() {
    assertFalse(
        Harness.isSafeSessionId("--dangerously-bypass-approvals-and-sandbox"),
        "a leading '-' would be parsed by the harness as an option, not a session id");
    assertFalse(Harness.isSafeSessionId("-r"));
    assertFalse(Harness.isSafeSessionId(".hidden"));
    assertFalse(Harness.isSafeSessionId("_x"));
  }

  @Test
  void aSessionIdWithShellMetacharactersOrOversizeOrNoneIsRejected() {
    assertFalse(Harness.isSafeSessionId("abc; rm -rf /"));
    assertFalse(Harness.isSafeSessionId("abc$(id)"));
    assertFalse(Harness.isSafeSessionId(""));
    assertFalse(Harness.isSafeSessionId(null));
    assertFalse(Harness.isSafeSessionId("a".repeat(129)));
  }
}
