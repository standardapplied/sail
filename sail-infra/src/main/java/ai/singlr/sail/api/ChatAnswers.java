/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.ChatWire;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChatStore;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What a chat's person tells its agent besides a message: an answer to a request it made, a stop of
 * its turn, and a change of its mode. Each reaches the chat only through its host's {@link Held}
 * seam, the chat's lock and the process its host holds for it, if any, so nothing here starts a
 * process or waits on one; each decides under the process's monitor, inside the chat's lock, as the
 * host's own writers do, and waits for what it wrote once the lock is let go. An answer reaches its
 * request once: only a request its process holds is answered, and every other is refused from what
 * the log says of it.
 */
final class ChatAnswers {

  /** The fields an answer names. */
  private static final Set<String> ANSWER = Set.of("decision", "message", "answers");

  /** The fields one answer to a question names. */
  private static final Set<String> ONE_ANSWER = Set.of("chosen", "own");

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

  /** What a route here reaches of a chat's host: nothing that starts a process. */
  @FunctionalInterface
  interface Held {

    /**
     * {@code work} on the caller's chat {@code session}, once it is theirs (refused as no chat of
     * theirs otherwise), under the chat's lock, with its log and the process its host holds for it,
     * null for none; then what it answers, once the lock is let go.
     */
    Mappable under(String session, Work work);
  }

  /**
   * What a route does with a chat, under its lock: it decides, and answers what is then said
   * outside the lock, once what it wrote is waited for.
   */
  @FunctionalInterface
  interface Work {
    Supplier<Mappable> apply(ChatStore.Chat chat, ChatLog log, ChatProcess process);
  }

  /** What a route logged: its seq. */
  record Logged(long seq) implements Mappable {
    @Override
    public Map<String, Object> toMap() {
      return Map.of("seq", seq);
    }
  }

  /** Whether a turn is stopping. */
  record Stopping(boolean stopping) implements Mappable {
    @Override
    public Map<String, Object> toMap() {
      return Map.of("stopping", stopping);
    }
  }

  private final Held held;

  /** The routes over the chats {@code held} reaches. */
  ChatAnswers(Held held) {
    this.held = held;
  }

  /**
   * Answers the request {@code request} of the caller's chat {@code session} with {@code body}
   * ({@code decision}, the words a denial carries in {@code message}, a question's {@code
   * answers}): logged as answered, then sent to the agent. Answers the seq it was logged as, or is
   * refused as withdrawn when the agent did not take it.
   */
  Mappable answer(String session, String request, Map<String, Object> body) {
    if (!Harness.isSafeSessionId(request)) {
      throw new IllegalArgumentException("A request's id is the one its item in the chat names.");
    }
    var reply = reply(body);
    var by = Actor.current().handle();
    return held.under(
        session,
        (chat, log, process) -> {
          if (chat.ended()) {
            throw ended(chat, "answer");
          }
          var answered =
              (process == null
                      ? Optional.<ChatProcess.Answered>empty()
                      : process.answer(request, reply, by, withdrawn(chat)))
                  .orElseThrow(() -> unanswerable(chat, log.about(request), request));
          return () -> {
            if (!process.wrote(answered.line())) {
              throw withdrawn(chat);
            }
            return new Logged(answered.seq());
          };
        });
  }

  /**
   * Stops the open turn of the caller's chat {@code session}: an interrupt is written once, and
   * answered {@code stopping} while that stop is in flight; with no turn open or no process,
   * nothing is written.
   */
  Mappable interrupt(String session) {
    return held.under(
        session,
        (chat, log, process) -> {
          var stopping = new Stopping(process != null && process.stop());
          return () -> written(process, stopping);
        });
  }

  /**
   * Puts the caller's chat {@code session} in the mode {@code body} names, one of its harness's
   * chat modes, between turns: asked of the process that holds it, or, with none, kept as the mode
   * the next one starts in. Answers the seq it was logged as.
   */
  Mappable mode(String session, Map<String, Object> body) {
    if (!Set.of("mode").containsAll(body.keySet())) {
      throw new IllegalArgumentException("A change of mode names only its mode.");
    }
    if (!(body.get("mode") instanceof String mode)) {
      throw new IllegalArgumentException("mode is the name of one of the chat's modes.");
    }
    var by = Actor.current().handle();
    return held.under(
        session,
        (chat, log, process) -> {
          if (chat.ended()) {
            throw ended(chat, "change of mode");
          }
          var harness = Harnesses.of(chat.harness());
          Harness.Mode.named(harness.chat().orElseThrow().modes(), mode, harness.displayName());
          if (process != null) {
            var logged = new Logged(process.setMode(mode, by, turnOpen(harness)));
            return () -> written(process, logged);
          }
          log.said(null, mode);
          var logged = new Logged(log.log(new ChatWire.ModeSet(mode, null, by)));
          return () -> logged;
        });
  }

  /** {@code answer}, once what {@code process} was handed is waited for; at once with none. */
  private static Mappable written(ChatProcess process, Mappable answer) {
    if (process != null) {
      process.written();
    }
    return answer;
  }

  /**
   * The person's reply, as {@code body} says it.
   *
   * @throws IllegalArgumentException for a field it does not name, or one not of its shape
   */
  private static ChatWire.Reply reply(Map<String, Object> body) {
    if (!ANSWER.containsAll(body.keySet())) {
      throw new IllegalArgumentException("An answer names only decision, message and answers.");
    }
    if (!(body.get("decision") instanceof String decision)) {
      throw new IllegalArgumentException("decision is allow, allow_always, deny or answer.");
    }
    var message = body.get("message");
    if (message != null && !(message instanceof String)) {
      throw new IllegalArgumentException("message is the person's own words, as text.");
    }
    return new ChatWire.Reply(
        ChatWire.Decision.named(decision), (String) message, answers(body.get("answers")));
  }

  /** Each question's answer, as {@code answers} names them, in its order. */
  private static List<ChatWire.Answer> answers(Object answers) {
    if (answers == null) {
      return List.of();
    }
    if (!(answers instanceof Map<?, ?> named)) {
      throw new IllegalArgumentException(
          "answers name each question by its text, each with what was chosen or said.");
    }
    var given = new ArrayList<ChatWire.Answer>();
    named.forEach((question, answer) -> given.add(answer((String) question, answer)));
    return given;
  }

  /** The answer {@code answer} gives {@code question}: its labels chosen, or own words. */
  private static ChatWire.Answer answer(String question, Object answer) {
    if (!(answer instanceof Map<?, ?> one)
        || !ONE_ANSWER.containsAll(one.keySet())
        || !(one.get("own") == null || one.get("own") instanceof String)
        || !(one.get("chosen") == null || one.get("chosen") instanceof List<?>)) {
      throw new IllegalArgumentException(
          "An answer to a question names only chosen, its labels, and own, the person's words.");
    }
    var chosen = one.get("chosen") instanceof List<?> labels ? labels : List.of();
    if (!chosen.stream().allMatch(String.class::isInstance)) {
      throw new IllegalArgumentException("Each label chosen is text, as its choice names it.");
    }
    return new ChatWire.Answer(
        question, chosen.stream().map(String.class::cast).toList(), (String) one.get("own"));
  }

  /**
   * The refusal of an answer to {@code request} of {@code chat} that no process takes, as the log
   * says of it ({@code about}): none asked, answered already, or no longer waited for.
   */
  private static ApiException unanswerable(
      ChatStore.Chat chat, List<ChatStore.Item> about, String request) {
    if (about.stream().noneMatch(ChatLog::asks)) {
      return new ApiException(
          ErrorCode.NOT_FOUND, "There is no request " + request + " in this chat.");
    }
    return about.stream()
        .filter(ChatLog::answers)
        .findFirst()
        .map(
            answered ->
                new ApiException(
                    ErrorCode.REQUEST_ANSWERED,
                    "You answered this at "
                        + TIME.format(answered.at())
                        + " UTC: "
                        + ChatLog.fields(answered).get("decision")
                        + "."))
        .orElseGet(() -> withdrawn(chat));
  }

  private static ApiException withdrawn(ChatStore.Chat chat) {
    return new ApiException(
        ErrorCode.REQUEST_WITHDRAWN,
        Harnesses.of(chat.harness()).displayName()
            + " no longer waits for this answer, so it was not sent.",
        "Answer what it asks next, or send a message.");
  }

  /** What refuses a {@code what} to {@code chat}, which has ended: a message's and an answer's. */
  static ApiException ended(ChatStore.Chat chat, String what) {
    return new ApiException(
        ErrorCode.CONFLICT,
        "This chat has ended, so it takes no " + what + ".",
        "Open a new chat in workspace " + chat.workspace() + ".");
  }

  private static ApiException turnOpen(Harness harness) {
    return new ApiException(
        ErrorCode.TURN_OPEN,
        harness.displayName()
            + " is working on a turn; change its mode when the turn ends, or stop it first.");
  }
}
