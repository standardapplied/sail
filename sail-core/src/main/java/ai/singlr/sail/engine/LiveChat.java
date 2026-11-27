/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.LiveStream.Step;
import ai.singlr.sail.harness.Harness;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A chat's side of Claude Code's live stream ({@link LiveStream}): what a person is shown of each
 * step it reads, as {@link ChatItem}s, and the lines a chat writes on Claude Code's stdin besides a
 * message: its answers to Claude Code's requests and its own requests to Claude Code. Each line is
 * one Claude Code 2.1.332 was written and acted on (the S0 probe's recordings), and each request
 * names an id the caller chooses, which Claude Code's answer names again.
 */
public final class LiveChat {

  private LiveChat() {}

  /** What a person is shown of {@code step}, of a process of {@code harness}; none for many. */
  public static List<ChatItem> items(Step step, Harness harness) {
    return items(step, harness, null);
  }

  private static List<ChatItem> items(Step step, Harness harness, String under) {
    return switch (step) {
      case Step.Init init ->
          List.of(new ChatItem.Session(init.model(), init.mode(), init.commands(), init.skills()));
      case Step.TextStarted _ -> List.of(new ChatItem.Text(under, "", true));
      case Step.Text text -> List.of(new ChatItem.Text(under, text.text(), false));
      case Step.Said said -> List.of(new ChatItem.Text(under, said.text(), true));
      case Step.ToolCalls called ->
          called.calls().stream().<ChatItem>map(call -> row(call, harness, under)).toList();
      case Step.ToolResults returned ->
          returned.results().stream()
              .<ChatItem>map(
                  result ->
                      new ChatItem.ToolDone(
                          under, result.callId(), result.output(), result.error()))
              .toList();
      case Step.Subagent subagent -> items(subagent.step(), harness, subagent.callId());
      case Step.Permission asked -> List.of(permission(asked, harness));
      case Step.Question asked ->
          List.of(new ChatItem.Question(asked.requestId(), asked.callId(), asked.questions()));
      case Step.Plan asked ->
          List.of(new ChatItem.Plan(asked.requestId(), asked.callId(), asked.plan()));
      case Step.Withdrawn withdrawn -> List.of(new ChatItem.Withdrawn(withdrawn.requestId()));
      case Step.Retry retry ->
          List.of(
              new ChatItem.Retrying(
                  retry.attempt(), retry.attempts(), retry.delayMs(), retry.error()));
      case Step.Compaction compaction ->
          List.of(
              new ChatItem.Compacted(
                  compaction.trigger(), compaction.tokensBefore(), compaction.tokensAfter()));
      case Step.Status status ->
          status.mode() == null ? List.of() : List.of(new ChatItem.ModeChanged(status.mode()));
      case Step.Result result -> ended(result);
      case Step.Working _, Step.Other _ -> List.of();
    };
  }

  /** A call's row: in the words Claude Code gives it ({@code description}), else its summary. */
  private static ChatItem row(LiveStream.ToolCall call, Harness harness, String under) {
    var words =
        call.input().get("description") instanceof String described && !described.isBlank()
            ? Strings.clip(described, LiveStream.SUMMARY_CODE_POINTS)
            : call.summary();
    return new ChatItem.Tool(under, call.id(), harness.toolKind(call.tool()), words, call.input());
  }

  private static ChatItem permission(Step.Permission asked, Harness harness) {
    return new ChatItem.Permission(
        asked.requestId(),
        asked.callId(),
        harness.toolKind(asked.tool()),
        Strings.isNotBlank(asked.description()) ? asked.description() : asked.displayName(),
        asked.reason(),
        asked.input(),
        !asked.suggestions().isEmpty());
  }

  /**
   * How a turn ended: begun by Claude Code itself, stopped by a person or failed, then what it
   * cost. A stopped turn is flagged an error by Claude Code, and is no failure.
   */
  private static List<ChatItem> ended(Step.Result result) {
    var items = new ArrayList<ChatItem>();
    if (result.selfStarted()) {
      items.add(new ChatItem.SelfStarted());
    }
    if (result.interrupted()) {
      items.add(new ChatItem.Interrupted());
    } else if (result.error()) {
      items.add(
          new ChatItem.Failed(
              result.text().isBlank()
                  ? Objects.requireNonNullElse(result.reason(), "")
                  : result.text(),
              result.login()));
    }
    items.add(result.turn());
    return items;
  }

  /** Allows the call Claude Code asked about, run with {@code input}: its own, or a changed one. */
  public static String allow(String requestId, Map<String, Object> input) {
    return respond(
        requestId,
        LiveStream.ordered("behavior", "allow", "updatedInput", Objects.requireNonNull(input)));
  }

  /**
   * Allows the call and every one like it from now on: with {@code permissions}, the request's own
   * suggestions handed back as they came. Claude Code keeps a rule it suggests for {@code
   * localSettings} in {@code ~/workspace/.claude/settings.local.json}, a file it makes inside the
   * workspace, and a suggested mode switches the session's mode at once (2.1.332, recorded).
   *
   * @throws IllegalArgumentException when Claude Code suggested nothing to keep
   */
  public static String allowAlways(
      String requestId, Map<String, Object> input, List<Object> permissions) {
    if (permissions.isEmpty()) {
      throw new IllegalArgumentException(
          "Claude Code offered no way to allow this always; allow it once instead.");
    }
    return respond(
        requestId,
        LiveStream.ordered(
            "behavior",
            "allow",
            "updatedInput",
            Objects.requireNonNull(input),
            "updatedPermissions",
            permissions));
  }

  /**
   * Answers a question: its {@code input}, the questions as asked, with {@code answers}, each
   * question's text to the label chosen.
   */
  public static String answer(
      String requestId, Map<String, Object> input, Map<String, String> answers) {
    var answered = new LinkedHashMap<>(input);
    answered.put("answers", Objects.requireNonNull(answers));
    return allow(requestId, answered);
  }

  /** Refuses the call, a question or a plan, saying why to the agent in {@code message}. */
  public static String deny(String requestId, String message) {
    return respond(
        requestId,
        LiveStream.ordered(
            "behavior", "deny", "message", Strings.requireNonBlank(message, "message")));
  }

  /**
   * Stops the turn in progress. Claude Code withdraws a request it is waiting on ({@link
   * Step.Withdrawn}), and the turn ends interrupted; the process stays, and takes the next message
   * as a new turn (2.1.332, recorded).
   */
  public static String interrupt(String requestId) {
    return ask(requestId, LiveStream.ordered("subtype", "interrupt"));
  }

  /**
   * Puts the session in {@code mode}, one of the harness's chat modes by name; Claude Code says so
   * in a {@link Step.Status} (2.1.332, recorded).
   */
  public static String setMode(String requestId, String mode) {
    return ask(
        requestId,
        LiveStream.ordered(
            "subtype", "set_permission_mode", "mode", Strings.requireNonBlank(mode, "mode")));
  }

  private static String respond(String requestId, Map<String, Object> response) {
    return YamlUtil.dumpJson(
        LiveStream.ordered(
            "type",
            "control_response",
            "response",
            LiveStream.ordered(
                "subtype",
                "success",
                "request_id",
                Strings.requireNonBlank(requestId, "requestId"),
                "response",
                response)));
  }

  private static String ask(String requestId, Map<String, Object> request) {
    return YamlUtil.dumpJson(
        LiveStream.ordered(
            "type",
            "control_request",
            "request_id",
            Strings.requireNonBlank(requestId, "requestId"),
            "request",
            request));
  }
}
