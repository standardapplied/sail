/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.LoginOutcome;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The two directions of Claude Code's streaming mode, as a live member and a chat speak it: the
 * JSON line that prompts it, and what each JSON line it writes says happened. Everything a line
 * carries is the agent's and so untrusted: a session id is taken only when it is safe to hand back
 * as an argument, a name (a tool's, a line's type) and a summary are clipped where they are read, a
 * line too long to be worth parsing (a large tool result) says nothing, and a line of a type this
 * reader does not read is an {@link Step.Other} naming its type, so a harness that adds a line type
 * breaks no reader. A chat's own lines, its answers and requests, are {@link LiveChat}'s.
 *
 * <p>A subagent's lines carry the tool call that started it: they are a {@link Step.Subagent},
 * never the agent's own steps, since what a subagent writes is not what the agent says.
 */
public final class LiveStream {

  /** A longer line is skipped unparsed: the frame ceiling, in characters. */
  public static final int MAX_LINE_CHARS = 1 << 20;

  /** How much of a tool's first string argument a reader of the room is shown. */
  public static final int SUMMARY_CODE_POINTS = 120;

  private static final int NAME_CODE_POINTS = 64;

  private static final String SUCCESS = "success";

  /** How a turn a person stopped ends: in its text, or while a tool waited or ran. */
  private static final Set<String> INTERRUPTED = Set.of("aborted_streaming", "aborted_tools");

  /** The origin of a turn Claude Code began itself, when a background task it ran finished. */
  private static final String TASK_NOTIFICATION = "task-notification";

  /** A result that says the login's account is out of quota, and when it resets, if it says. */
  private static final Pattern LIMIT =
      Pattern.compile("You['’]ve hit your .*?limit(?:\\s*·\\s*resets\\s+(.+))?");

  private LiveStream() {}

  /** What one line of Claude Code's stdout says. */
  public sealed interface Step {

    /**
     * A turn began, in the conversation {@code sessionId}: the model answering it, the permission
     * mode it is in, its tools, and the commands and skills a person can name. Said at every turn.
     */
    record Init(
        String sessionId,
        String model,
        String mode,
        List<String> tools,
        List<String> commands,
        List<String> skills)
        implements Step {}

    /** A block of the agent's text begins: what follows is not glued to what came before. */
    record TextStarted() implements Step {}

    /** The agent wrote {@code text}, a fragment of what it is saying. */
    record Text(String text) implements Step {}

    /** A block of text said whole: a subagent's, which streams no fragments. */
    record Said(String text) implements Step {}

    /** The agent called tools. */
    record ToolCalls(List<ToolCall> calls) implements Step {}

    /** Tool calls came back. */
    record ToolResults(List<ToolResult> results) implements Step {}

    /**
     * A line of the subagent the tool call {@code callId} started, of type {@code agentType}, given
     * {@code task}: {@code step} is what it says, read as the agent's own lines are.
     */
    record Subagent(String callId, String agentId, String agentType, String task, Step step)
        implements Step {}

    /**
     * Claude Code asks before the call {@code callId} of {@code tool}: with the call's {@code
     * input}, its own {@code description} of it and why it asks ({@code reason}), when it says, and
     * the rules it would add to allow it always ({@code suggestions}, to hand back as they are).
     */
    record Permission(
        String requestId,
        String callId,
        String tool,
        String displayName,
        Map<String, Object> input,
        String description,
        String reason,
        List<Object> suggestions)
        implements Step {}

    /** The agent asks a person {@code questions}, as a request to allow its question tool. */
    record Question(
        String requestId, String callId, Map<String, Object> input, List<ChatItem.Asked> questions)
        implements Step {}

    /** The agent asks a person to approve its {@code plan}, as a request to leave plan mode. */
    record Plan(String requestId, String callId, Map<String, Object> input, String plan)
        implements Step {}

    /** Claude Code withdrew the request {@code requestId}: it takes no answer now. */
    record Withdrawn(String requestId) implements Step {}

    /** A request to the model failed with {@code status} and is tried again after a delay. */
    record Retry(int attempt, int attempts, long delayMs, int status, String error)
        implements Step {}

    /** The conversation was compacted from {@code tokensBefore} tokens to {@code tokensAfter}. */
    record Compaction(String trigger, long tokensBefore, long tokensAfter) implements Step {}

    /**
     * What Claude Code is doing ({@code requesting}, {@code compacting}, or null for neither) and,
     * when it changed, the permission mode it is in now.
     */
    record Status(String status, String mode) implements Step {}

    /**
     * The turn is over. {@code reason} is why it failed, {@code null} for a turn that did not or
     * whose harness named no failure; {@code text} is the turn's final text as the harness reports
     * it; a turn a person stopped is {@code interrupted}; one Claude Code began itself, with no
     * message, is {@code selfStarted}; {@code login} is what came of the login when that is why it
     * failed; and {@code turn} is what this turn alone spent.
     */
    record Result(
        boolean error,
        String reason,
        String text,
        boolean interrupted,
        boolean selfStarted,
        LoginOutcome login,
        ChatItem.TurnEnded turn)
        implements Step {}

    /** The agent is at work on its turn, with nothing to show for this line. */
    record Working() implements Step {}

    /** A line this reader does not read: of {@code type}, empty for a line that names none. */
    record Other(String type) implements Step {}
  }

  /** One tool call: its id, the tool, its first string argument clipped, and its whole input. */
  public record ToolCall(String id, String tool, String summary, Map<String, Object> input) {}

  /** One call come back: its id, its output as text, and whether it failed. */
  public record ToolResult(String callId, String output, boolean error) {}

  /** An image sent with a message: its media type, one the model reads, and its base64 data. */
  public record Image(String mediaType, String data) {

    private static final Set<String> READ =
        Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    public Image {
      if (mediaType == null || !READ.contains(mediaType)) {
        throw new IllegalArgumentException(
            "An image is image/png, image/jpeg, image/gif or image/webp, not '" + mediaType + "'.");
      }
      Strings.requireNonBlank(data, "data");
    }

    /** The image as a block of a message's content. */
    Map<String, Object> block() {
      return ordered(
          "type",
          "image",
          "source",
          ordered("type", "base64", "media_type", mediaType, "data", data));
    }
  }

  /** The stdin line that says {@code text} to Claude Code, with {@code images} shown before it. */
  public static String userMessage(String text, Image... images) {
    var content = new ArrayList<Object>();
    for (var image : images) {
      content.add(image.block());
    }
    content.add(ordered("type", "text", "text", text));
    return YamlUtil.dumpJson(
        ordered("type", "user", "message", ordered("role", "user", "content", content)));
  }

  /**
   * What came of a login, from a failed turn's text ({@code said}) and the API's status: a 401 is a
   * refusal of the login; "Not logged in" is no login delivered at all (only a harness given no
   * credential, or an empty one, prints it: 2.1.322, recorded); "You've hit your … limit" is a
   * limit, with the time it resets. Empty for anything else.
   */
  public static Optional<LoginOutcome> loginOutcome(String said, Long apiErrorStatus) {
    var words = said.strip();
    var limit = LIMIT.matcher(words);
    if (Long.valueOf(401).equals(apiErrorStatus)) {
      return Optional.of(new LoginOutcome.Refused());
    }
    if (words.startsWith("Not logged in")) {
      return Optional.of(new LoginOutcome.Undelivered());
    }
    if (limit.matches()) {
      return Optional.of(new LoginOutcome.Limited(LoginOutcome.resetTime(limit.group(1))));
    }
    return Optional.empty();
  }

  /** What {@code line} says; {@link Step.Other} for a line that is too long or is not JSON. */
  public static Step read(String line) {
    Map<String, Object> event;
    try {
      event = YamlUtil.parseJsonLine(line, MAX_LINE_CHARS);
    } catch (RuntimeException unreadable) {
      return new Step.Other("");
    }
    var type = name(event.get("type"));
    return switch (type) {
      case "result" -> result(event);
      case "system" -> system(event);
      case "control_request" -> request(event);
      case "control_cancel_request" ->
          event.get("request_id") instanceof String id
              ? new Step.Withdrawn(id)
              : new Step.Other(type);
      default -> event.get("parent_tool_use_id") == null ? own(type, event) : subagent(type, event);
    };
  }

  private static Step own(String type, Map<String, Object> event) {
    return switch (type) {
      case "stream_event" -> streamed(map(event.get("event")));
      case "assistant" -> called(blocks(event), new Step.Working());
      case "user" -> returned(blocks(event));
      default -> new Step.Other(type);
    };
  }

  private static Step subagent(String type, Map<String, Object> event) {
    if (!(event.get("parent_tool_use_id") instanceof String callId)) {
      return new Step.Other(type);
    }
    var blocks = blocks(event);
    var step =
        switch (type) {
          case "assistant" -> called(blocks, said(blocks, type));
          case "user" -> returned(blocks);
          default -> new Step.Other(type);
        };
    return new Step.Subagent(
        callId,
        text(event, "agent_id"),
        text(event, "subagent_type"),
        text(event, "task_description"),
        step);
  }

  /** A subagent's text, said whole; {@link Step.Other} for a line of none. */
  private static Step said(List<Map<String, Object>> blocks, String type) {
    return blocks.stream()
        .filter(block -> "text".equals(block.get("type")))
        .map(block -> text(block, "text"))
        .filter(Objects::nonNull)
        .reduce(String::concat)
        .<Step>map(Step.Said::new)
        .orElseGet(() -> new Step.Other(type));
  }

  /**
   * A turn's result: it failed when the harness flags it an error, whatever its subtype says, and
   * the subtype is its reason only when it names a failure. A harness can flag a turn failed under
   * the subtype {@value #SUCCESS}, and a failure is never told as a success. What it cost is this
   * turn's alone ({@code model_usage_since_last_result}): its {@code total_cost_usd} is the
   * process's running total (2.1.332, recorded).
   */
  private static Step result(Map<String, Object> event) {
    var error = Boolean.TRUE.equals(event.get("is_error"));
    var subtype = event.get("subtype") instanceof String named ? named : "";
    var said = event.get("result") instanceof String text ? text : "";
    var status = event.get("api_error_status") instanceof Number number ? number.longValue() : null;
    return new Step.Result(
        error,
        error && !subtype.isBlank() && !SUCCESS.equals(subtype) ? subtype : null,
        said,
        INTERRUPTED.contains(name(event.get("terminal_reason"))),
        TASK_NOTIFICATION.equals(map(event.get("origin")).get("kind")),
        error ? loginOutcome(said, status).orElse(null) : null,
        spent(map(event.get("model_usage_since_last_result")), count(event, "duration_ms")));
  }

  private static ChatItem.TurnEnded spent(Map<String, Object> models, long durationMs) {
    var cost = 0.0;
    long in = 0;
    long out = 0;
    for (var model : models.values()) {
      var figures = map(model);
      cost += figures.get("costUSD") instanceof Number usd ? usd.doubleValue() : 0;
      in +=
          count(figures, "inputTokens")
              + count(figures, "cacheReadInputTokens")
              + count(figures, "cacheCreationInputTokens");
      out += count(figures, "outputTokens");
    }
    return new ChatItem.TurnEnded(cost, in, out, durationMs);
  }

  private static Step system(Map<String, Object> event) {
    var subtype = name(event.get("subtype"));
    var metadata = map(event.get("compact_metadata"));
    return switch (subtype) {
      case "init" -> init(event);
      case "status" -> new Step.Status(text(event, "status"), text(event, "permissionMode"));
      case "api_retry" ->
          new Step.Retry(
              (int) count(event, "attempt"),
              (int) count(event, "max_retries"),
              count(event, "retry_delay_ms"),
              (int) count(event, "error_status"),
              text(event, "error"));
      case "compact_boundary" ->
          new Step.Compaction(
              text(metadata, "trigger"),
              count(metadata, "pre_tokens"),
              count(metadata, "post_tokens"));
      default -> new Step.Other("system/" + subtype);
    };
  }

  private static Step init(Map<String, Object> event) {
    return event.get("session_id") instanceof String id && Harness.isSafeSessionId(id)
        ? new Step.Init(
            id,
            text(event, "model"),
            text(event, "permissionMode"),
            names(event.get("tools")),
            names(event.get("slash_commands")),
            names(event.get("skills")))
        : new Step.Other("system/init");
  }

  /**
   * A request Claude Code makes of the host: only {@code can_use_tool}, which asks before a call,
   * and asks a question or a plan's approval as a call of its question or plan tool.
   */
  private static Step request(Map<String, Object> event) {
    var request = map(event.get("request"));
    if (!(event.get("request_id") instanceof String id)
        || !"can_use_tool".equals(request.get("subtype"))) {
      return new Step.Other("control_request");
    }
    var call = text(request, "tool_use_id");
    var input = map(request.get("input"));
    var tool = name(request.get("tool_name"));
    return switch (tool) {
      case "AskUserQuestion" -> new Step.Question(id, call, input, questions(input));
      case "ExitPlanMode" -> new Step.Plan(id, call, input, text(input, "plan"));
      default ->
          new Step.Permission(
              id,
              call,
              tool,
              name(request.get("display_name")),
              input,
              text(request, "description"),
              text(request, "decision_reason"),
              request.get("permission_suggestions") instanceof List<?> suggestions
                  ? List.copyOf(suggestions)
                  : List.of());
    };
  }

  private static List<ChatItem.Asked> questions(Map<String, Object> input) {
    return list(input.get("questions")).stream()
        .map(LiveStream::map)
        .map(
            asked ->
                new ChatItem.Asked(
                    text(asked, "question"),
                    text(asked, "header"),
                    list(asked.get("options")).stream()
                        .map(LiveStream::map)
                        .map(
                            option ->
                                new ChatItem.Choice(
                                    text(option, "label"), text(option, "description")))
                        .toList(),
                    Boolean.TRUE.equals(asked.get("multiSelect"))))
        .toList();
  }

  private static Step streamed(Map<String, Object> event) {
    var type = Objects.toString(event.get("type"), "");
    if ("content_block_start".equals(type)
        && "text".equals(map(event.get("content_block")).get("type"))) {
      return new Step.TextStarted();
    }
    var delta = map(event.get("delta"));
    return "content_block_delta".equals(type)
            && "text_delta".equals(delta.get("type"))
            && delta.get("text") instanceof String text
            && !text.isEmpty()
        ? new Step.Text(text)
        : new Step.Working();
  }

  private static Step called(List<Map<String, Object>> blocks, Step otherwise) {
    var calls =
        blocks.stream()
            .filter(block -> "tool_use".equals(block.get("type")))
            .map(
                block -> {
                  var input = map(block.get("input"));
                  return new ToolCall(
                      Objects.toString(block.get("id"), ""),
                      Strings.clip(Objects.toString(block.get("name"), ""), NAME_CODE_POINTS),
                      summary(input),
                      input);
                })
            .toList();
    return calls.isEmpty() ? otherwise : new Step.ToolCalls(calls);
  }

  private static Step returned(List<Map<String, Object>> blocks) {
    var results =
        blocks.stream()
            .filter(block -> "tool_result".equals(block.get("type")))
            .map(
                block ->
                    new ToolResult(
                        Objects.toString(block.get("tool_use_id"), ""),
                        output(block.get("content")),
                        Boolean.TRUE.equals(block.get("is_error"))))
            .toList();
    return results.isEmpty() ? new Step.Other("user") : new Step.ToolResults(results);
  }

  /** A call's output: its text, or the text of the blocks it came back as. */
  private static String output(Object content) {
    return content instanceof String text
        ? text
        : list(content).stream()
            .map(block -> text(map(block), "text"))
            .filter(Objects::nonNull)
            .collect(Collectors.joining("\n"));
  }

  private static String summary(Map<String, Object> input) {
    return input.values().stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .findFirst()
        .map(first -> Strings.clip(first, SUMMARY_CODE_POINTS))
        .orElse("");
  }

  private static List<Map<String, Object>> blocks(Map<String, Object> event) {
    return list(map(event.get("message")).get("content")).stream().map(LiveStream::map).toList();
  }

  /** {@code value} as a name the agent gave, clipped; empty for anything that is not text. */
  private static String name(Object value) {
    return value instanceof String named ? Strings.clip(named, NAME_CODE_POINTS) : "";
  }

  private static List<String> names(Object value) {
    return list(value).stream().filter(String.class::isInstance).map(LiveStream::name).toList();
  }

  private static String text(Map<String, Object> from, String key) {
    return from.get(key) instanceof String text ? text : null;
  }

  private static long count(Map<String, Object> from, String key) {
    return from.get(key) instanceof Number number ? number.longValue() : 0;
  }

  private static List<?> list(Object value) {
    return value instanceof List<?> list ? list : List.of();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  /** {@code keysAndValues} as an ordered map, as Claude Code's own lines order their keys. */
  static Map<String, Object> ordered(Object... keysAndValues) {
    var map = new LinkedHashMap<String, Object>();
    for (var i = 0; i < keysAndValues.length; i += 2) {
      map.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return map;
  }
}
