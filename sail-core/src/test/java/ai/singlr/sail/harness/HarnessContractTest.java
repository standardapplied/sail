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
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** What every harness sail knows must answer, run over {@link Harnesses#all}. */
class HarnessContractTest {

  private static final String TASK = "/home/dev/.sail/agent-task.txt";
  private static final String MALFORMED_ID =
      "Malformed session id; refusing to build a resume command from replicated data.";

  static Stream<Harness> harnesses() {
    return Harnesses.all().stream();
  }

  /** I6: a harness's forms are read from its chat form alone, never from which harness it is. */
  @ParameterizedTest
  @MethodSource("harnesses")
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aHarnessChatsExactlyWhenItHasAChatForm(Harness harness) {
    assertEquals(
        harness.chat().isPresent()
            ? List.of(SessionForm.TERMINAL, SessionForm.CHAT)
            : List.of(SessionForm.TERMINAL),
        harness.forms(),
        harness.yamlName());
  }

  /** I6, I7: Claude Code chats, signing in inside at the owner's default; Codex not until S4. */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void claudeCodeChatsAndSignsInInsideAndCodexOnlyRunsInATerminal() {
    var claude = Harnesses.of("claude-code");

    assertEquals(List.of(SessionForm.TERMINAL, SessionForm.CHAT), claude.forms());
    assertTrue(claude.chat().orElseThrow().signsInInside());
    assertEquals(List.of(SessionForm.TERMINAL), Harnesses.of("codex").forms());
    assertEquals(
        List.of(SessionForm.TERMINAL),
        StubHarness.loggingInThrough("claude-code", OptionalInt.empty()).forms(),
        "a harness named claude-code with no chat form takes no chat");
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
            () -> harness.headless(new Launch(TASK, true, null, null, "a; rm -rf /", true, null)));

    assertEquals(MALFORMED_ID, ex.getMessage());
  }

  @ParameterizedTest
  @MethodSource("harnesses")
  void readOnlyWithAMalformedSessionIdThrowsItOrTheRefusal(Harness harness) {
    var launch = new Launch(TASK, false, null, null, "a; rm -rf /", true, null);

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

  @ParameterizedTest
  @ValueSource(
      strings = {
        "a b", "a;b", "a$b", "a/b", "a|b", "a&b", "a`b", "a'b", "a\"b", "a(b", "a>b", "a\nb",
        "abc\n", "a\\b", "a*b", "a~b", "a=b", "a,b", "a:b", "a\tb"
      })
  void eachCharacterOutsideTheSafeSetIsRejectedOnItsOwn(String sessionId) {
    assertFalse(Harness.isSafeSessionId(sessionId), sessionId);
  }

  @ParameterizedTest
  @MethodSource("harnesses")
  void attachRefusesAMalformedSessionIdAndStartsAFreshConversationForNone(Harness harness) {
    var ex = assertThrows(IllegalArgumentException.class, () -> harness.attach("a; rm -rf /"));

    assertEquals(MALFORMED_ID, ex.getMessage());
    assertEquals(harness.binaryName(), harness.attach(null));
  }

  @ParameterizedTest
  @MethodSource("harnesses")
  void everyPathAHarnessNamesUnderTheHomeDirectoryIsRelativeToIt(Harness harness) {
    assertFalse(harness.skillsDir().startsWith("/"), harness.skillsDir());
    assertTrue(harness.skillsDir().endsWith("/"), "a skill lives at <skillsDir><name>/SKILL.md");
    assertEquals(harness.skillsDir() + "mine", harness.skillFolder("mine"));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "  "})
  void aLaunchNamesTheTaskFileItReads(String taskFile) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Launch(taskFile, true, null, null, null, true, null));
  }

  @ParameterizedTest
  @MethodSource("harnesses")
  void everyHarnessDeclaresExactlyOneKey(Harness harness) {
    var kinds = harness.loginKinds();

    assertEquals(
        1,
        kinds.size(),
        harness.yamlName() + " has two ways in, its own sign-in or one stored key: " + kinds);
    for (var kind : kinds) {
      assertTrue(kind.variable().matches("[A-Z][A-Z0-9_]*"), kind.variable());
      assertFalse(kind.howToMake().isEmpty(), kind.kind());
      assertEquals(
          List.of(),
          kind.howToMake().commands(),
          "a kind is a key its service issues for programs, made where the service says, never by"
              + " running a harness that is signed in: "
              + kind.howToMake());
      assertTrue(
          kind.fixed().keySet().stream()
              .noneMatch(
                  fixed ->
                      harness.loginVariables().contains(fixed)
                          || harness.credentialSwitches().contains(fixed)),
          "a fixed value is never a credential the run's lines set empty: " + kind.fixed());
      assertEquals(kind, harness.loginKind(kind.kind()));
    }
    assertEquals(kinds.getFirst(), harness.loginKind(null));
    assertTrue(
        harness.loginVariables().containsAll(kinds.stream().map(LoginKind::variable).toList()),
        "a run on a stored login sets empty every other variable its harness reads");
    assertEquals(kinds.getFirst(), harness.loginKind(" "));
  }

  @ParameterizedTest
  @MethodSource("harnesses")
  void everyHarnessNamesItsOwnSignInInWordsAndTheFileItKeepsItIn(Harness harness) {
    var signIn = harness.signIn();

    assertFalse(signIn.name().isBlank(), harness.yamlName());
    assertFalse(signIn.how().isEmpty(), harness.yamlName());
    assertEquals(
        List.of(),
        signIn.how().commands(),
        "the sign-in's sentence is words a person reads in the panel: " + signIn.how());
    assertTrue(
        harness.storedLogins().contains(signIn.file()),
        "the file that holds the sign-in is one a workspace loses when it leaves its person: "
            + signIn.file()
            + " in "
            + harness.storedLogins());
  }

  @ParameterizedTest
  @MethodSource("harnesses")
  void aKindTheHarnessDoesNotDeclareIsRefusedNamingTheOnesItDoes(Harness harness) {
    var ex = assertThrows(IllegalArgumentException.class, () -> harness.loginKind("session"));

    assertEquals(
        harness.displayName()
            + " takes no login of kind 'session'; it takes "
            + String.join(", ", harness.loginKinds().stream().map(LoginKind::kind).toList())
            + ".",
        ex.getMessage());
  }
}
