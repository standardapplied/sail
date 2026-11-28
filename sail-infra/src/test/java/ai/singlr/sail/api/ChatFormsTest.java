/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.HarnessChat;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.store.ProjectStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the agents route says of each harness's forms and chat, read from {@link Harness#chat}, the
 * seam a chat's open reads too; and the one sign-in switch, {@link HarnessChat#signsInInside},
 * turned off through a stand-in of Claude Code's chat, at an open and at each start of a chat's
 * process.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ChatFormsTest {

  private static final Harness CLAUDE = Harnesses.of("claude-code");

  /** L10: Claude Code offers a chat in its chat modes, signing in inside; Codex offers none. */
  @Test
  void eachOfferSaysTheFormsItsHarnessTakesAndHowItChats() {
    var claude = new AgentOffer(CLAUDE, null, true, null, null, true).toMap();
    var codex = new AgentOffer(Harnesses.of("codex"), null, true, null, null, true).toMap();

    assertEquals(List.of("terminal", "chat"), claude.get("forms"));
    assertEquals(
        Map.of(
            "mode_default",
            "default",
            "inside",
            true,
            "modes",
            CLAUDE.chat().orElseThrow().modes().stream()
                .map(
                    mode ->
                        Map.of(
                            "mode",
                            mode.name(),
                            "label",
                            mode.label(),
                            "about",
                            mode.about(),
                            "risky",
                            mode.risky()))
                .toList()),
        claude.get("chat"));
    assertEquals(
        List.of("Asks first", "Edits freely", "Plans first"),
        modeLabels(claude),
        "the chat's modes in S1's words, beside the terminal's");
    assertEquals(List.of("terminal"), codex.get("forms"));
    assertFalse(codex.containsKey("chat"));
  }

  /** A harness the workspace does not let start inside offers no inside chat either. */
  @Test
  void anOfferThatDoesNotStartInsideOffersNoInsideChat() {
    var offered = new AgentOffer(CLAUDE, null, false, null, null, true).toMap();

    assertEquals(false, ((Map<?, ?>) offered.get("chat")).get("inside"));
  }

  /**
   * Each offer holds only the forms this runner opens now: with a terminal door, a terminal and,
   * for a harness that chats, a chat; with none, Claude Code a chat alone and Codex, which has no
   * chat, nothing.
   */
  @Test
  void anOfferHoldsOnlyTheFormsThisRunnerOpens() {
    var codex = Harnesses.of("codex");

    assertEquals(
        List.of(List.of("terminal", "chat"), List.of("chat"), List.of("terminal"), List.of()),
        List.of(
            offer(CLAUDE, true).get("forms"),
            offer(CLAUDE, false).get("forms"),
            offer(codex, true).get("forms"),
            offer(codex, false).get("forms")));
    assertEquals(
        offer(CLAUDE, true).get("chat"),
        offer(CLAUDE, false).get("chat"),
        "a chat is offered alike with or without a terminal door");
  }

  /**
   * An offer left with no form is not offered, and an answer left with none says once why nothing
   * starts here, in the ticket route's words; an answer with a form left refuses nothing.
   */
  @Test
  void anOfferWithNoFormIsNotOfferedAndAnAnswerLeftWithNoneSaysWhyOnce() {
    var codex = Harnesses.of("codex");
    var some =
        AgentOffersResponse.opening(
                "acme", "try", "sam", true, List.of(offered(CLAUDE, false), offered(codex, false)))
            .toMap();
    var none =
        AgentOffersResponse.opening("acme", "try", "sam", true, List.of(offered(codex, false)))
            .toMap();
    var both =
        AgentOffersResponse.opening(
                "acme", "try", "sam", true, List.of(offered(CLAUDE, true), offered(codex, true)))
            .toMap();

    assertEquals(List.of("claude-code"), harnesses(some));
    assertFalse(some.containsKey("refusal"));
    assertEquals(List.of("claude-code", "codex"), harnesses(both));
    assertFalse(both.containsKey("refusal"));
    assertEquals(List.of(), none.get("offered"));
    var refusal = (Map<?, ?>) none.get("refusal");
    assertEquals(
        List.of(
            "terminal_not_offered",
            "This runner offers no terminal over the network; ask an admin."),
        List.of(refusal.get("code"), refusal.get("message")));
    assertEquals(
        List.of("project", "workspace", "owner", "running", "refusal", "offered"),
        List.copyOf(none.keySet()),
        "said once, on the answer");
  }

  private static AgentOffer offered(Harness harness, boolean terminal) {
    return new AgentOffer(harness, null, true, null, null, terminal);
  }

  private static Map<String, Object> offer(Harness harness, boolean terminal) {
    return offered(harness, terminal).toMap();
  }

  private static List<Object> harnesses(Map<String, Object> answer) {
    return ((List<?>) answer.get("offered"))
        .stream().<Object>map(offer -> ((Map<?, ?>) offer).get("harness")).toList();
  }

  /** L11, I7: the switch off says so on the route and refuses an inside open; a key open works. */
  @Test
  void withTheSwitchOffAChatTakesTheStoredKeyAndNotTheSignInInside() {
    var outside = signingInOutside();
    var offered = new AgentOffer(outside, null, true, null, null, true).toMap();
    var claude = new AgentOffer(CLAUDE, null, true, null, null, true).toMap();

    assertEquals(false, ((Map<?, ?>) offered.get("chat")).get("inside"));
    assertEquals(true, offered.get("inside"), "a terminal still starts signed in inside");
    assertEquals(claude.get("kinds"), offered.get("kinds"), "an API key is always offered");
    var refused =
        assertThrows(IllegalArgumentException.class, () -> ChatLaunch.form(outside, true));
    assertEquals(
        "Claude Code's chat takes your stored API key here, not the sign-in made in the workspace.",
        refused.getMessage());
    assertSame(outside.chat().orElseThrow(), ChatLaunch.form(outside, false));
    assertEquals(
        CLAUDE.chat().orElseThrow().modes(),
        ChatLaunch.form(CLAUDE, true).modes(),
        "Claude Code's own chat signs in inside");
  }

  /**
   * The switch holds at each start of a chat's process, not at its open alone: a chat opened signed
   * in inside is not started on that sign-in once the switch is off, refused in the open's words,
   * and nothing starts.
   */
  @Test
  void aChatOpenedInsideIsNotStartedInsideOnceTheSwitchIsOff(@TempDir Path dir) {
    try (var bench = new ChatBench(dir)) {
      var session =
          ChatBench.as("tom", () -> bench.host.open("acme", "tom", "claude-code", "default", true))
              .session();
      var chat = bench.store.find(session).orElseThrow();
      var switchedOff =
          new ChatLaunch(
              bench.door,
              bench.fdes,
              ProjectReader.ofCatalog(new ProjectStore(bench.db)),
              bench.processes,
              bench.processes.container(),
              TestAuth.roles(bench.db),
              name -> signingInOutside());
      var launched = new ArrayList<Boolean>();

      var refused =
          assertThrows(
              IllegalArgumentException.class,
              () -> ChatBench.as("tom", () -> switchedOff.start(chat, launched::add)));

      assertEquals(
          "Claude Code's chat takes your stored API key here, not the sign-in made in the workspace.",
          refused.getMessage());
      assertEquals(List.of(), launched, "no process was about to start");
      assertEquals(0, bench.processes.starts.size());
    }
  }

  private static List<Object> modeLabels(Map<String, Object> offer) {
    return ((List<?>) ((Map<?, ?>) offer.get("chat")).get("modes"))
        .stream().<Object>map(mode -> ((Map<?, ?>) mode).get("label")).toList();
  }

  /** Claude Code, whose chat form is its own but for a switch that is off. */
  private static Harness signingInOutside() {
    var chat = CLAUDE.chat().orElseThrow();
    var outside = standIn(HarnessChat.class, chat, "signsInInside", false);
    return standIn(Harness.class, CLAUDE, "chat", Optional.of(outside));
  }

  /** {@code real}, as {@code type}, but answering {@code answer} to {@code method}. */
  private static <T> T standIn(Class<T> type, T real, String method, Object answer) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, called, args) -> {
              if (called.getName().equals(method)) {
                return answer;
              }
              try {
                return called.invoke(real, args);
              } catch (InvocationTargetException thrown) {
                throw thrown.getCause();
              }
            }));
  }
}
