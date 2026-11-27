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
 * carries is the agent's and so untrusted: a session id, a request's id and a tool call's are taken
 * only when they are safe to hand back (as {@link Harness#isSafeSessionId} judges a session id), a
 * name that picks how a line is read (a line's type) and a call's summary are clipped where they
 * are read, what a person is shown is clipped by the {@link ChatItem} it is shown in, and what a
 * host hands back to the agent (a call's input) is never clipped, a line too long to be worth
 * parsing (a large tool result) says nothing, and a line of a type this reader does not read is an
 * {@link Step.Other} naming its type, so a harness that adds a line type breaks no reader. A chat's
 * own lines, its answers and requests, are {@link LiveChat}'s.
 *
 * <p>A subagent's lines carry the tool call that started it: they are a {@link Step.Subagent},
 * never the agent's own steps, since what a subagent writes is not what the agent says.
 */
public final class LiveStream {

  /**
   * A longer line is skipped unparsed: the frame ceiling, in UTF-16 characters, not bytes. What
   * such a line held is lost: a call's row (an {@code assistant} line holds a call's input twice),
   * whose result then names a row never shown, or a call's result, whose row a host closes at the
   * turn's end; and a request, which then waits unseen until a person stops the turn.
   */
  public static final int MAX_LINE_CHARS = 1 << 20;

  /** How much of a name the agent gave, a mode's, a model's, a command's, a person is shown. */
  public static final int NAME_CODE_POINTS = 64;

  /**
   * How much of a short line, a row's words, a reason, a label, a person is shown, and of a tool's
   * first string argument a reader of the room is.
   */
  public static final int SUMMARY_CODE_POINTS = 120;

  /** How much of what the agent wrote at length, a text, a call's input or output, is shown. */
  public static final int CONTENT_CODE_POINTS = 1 << 16;

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

    /**
     * A line that is what a person is shown, whole: a request withdrawn ({@code
     * control_cancel_request}), a request of the host's refused ({@code control_response} that is
     * no success), a retry ({@code system/api_retry}) or a compaction ({@code compact_boundary}).
     */
    record Shown(ChatItem item) implements Step {}

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

    /**
     * A request Claude Code made of the host that could not be read whole (an id that is not safe
     * to hand back, no input, no tool): no person is asked it, so the host refuses it at once
     * ({@link LiveChat#refuseUnreadable}), or, when {@code requestId} is null because it named none
     * that is safe, stops the turn, which would otherwise wait on it.
     */
    record Unreadable(String requestId) implements Step {}

    /**
     * A line this reader does not read: of {@code type}, empty for a line that names none, and a
     * {@code system} line or a request of the host other than {@code can_use_tool} named with its
     * subtype ({@code system/task_started}, {@code control_request/elicitation}).
     */
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

  /**
   * What {@code line} says; {@link Step.Other} for a line that is too long, is not JSON, or holds
   * what this reader cannot take, naming its type when it names one. No line is fatal, whatever it
   * holds: one that throws while it is read (a {@code null} where a value was, an id that is not
   * safe) says nothing, and so does one nested so deep that parsing it overflows the stack, a depth
   * the agent chooses (an MCP server's structured result), not the host.
   */
  public static Step read(String line) {
    var type = "";
    try {
      var event = YamlUtil.parseJsonLine(line, MAX_LINE_CHARS);
      type = name(event.get("type"));
      return switch (type) {
        case "result" -> result(event);
        case "system" -> system(event);
        case "control_request" -> request(event);
        case "control_cancel_request" ->
            new Step.Shown(new ChatItem.Withdrawn(id(event, "request_id")));
        case "control_response" -> {
          var response = map(event.get("response"));
          yield SUCCESS.equals(response.get("subtype"))
              ? new Step.Other(type)
              : new Step.Shown(
                  new ChatItem.Refused(
                      safeId(response.get("request_id")),
                      Objects.toString(text(response, "error"), ""),
                      false));
        }
        default -> turn(type, event);
      };
    } catch (RuntimeException | StackOverflowError malformed) {
      return new Step.Other(type);
    }
  }

  /**
   * A line of a turn: the agent's own, or, when it names the tool call that started a subagent
   * ({@code parent_tool_use_id}), that subagent's, read as the agent's are.
   */
  private static Step turn(String type, Map<String, Object> event) {
    var subagent = event.get("parent_tool_use_id") != null;
    var blocks =
        list(map(event.get("message")).get("content")).stream().map(LiveStream::map).toList();
    var step =
        switch (type) {
          case "stream_event" ->
              subagent ? new Step.Other(type) : streamed(map(event.get("event")));
          case "assistant" -> called(blocks, subagent ? said(blocks, type) : new Step.Working());
          case "user" -> returned(blocks);
          default -> new Step.Other(type);
        };
    return subagent
        ? new Step.Subagent(
            id(event, "parent_tool_use_id"),
            text(event, "agent_id"),
            text(event, "subagent_type"),
            text(event, "task_description"),
            step)
        : step;
  }

  /**
   * A subagent's text, said whole, its blocks as paragraphs; {@link Step.Other} for a line of none.
   * A line holding text beside a tool call was never recorded: its calls are read, not its text.
   */
  private static Step said(List<Map<String, Object>> blocks, String type) {
    return blocks.stream()
        .filter(block -> "text".equals(block.get("type")))
        .map(block -> text(block, "text"))
        .filter(Objects::nonNull)
        .reduce((first, next) -> first + "\n\n" + next)
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

  /** What a turn spent: its cost only when a model names one, since none is not "$0.00". */
  private static ChatItem.TurnEnded spent(Map<String, Object> models, long durationMs) {
    Double cost = null;
    long in = 0;
    long out = 0;
    for (var model : models.values()) {
      var figures = map(model);
      if (figures.get("costUSD") instanceof Number usd) {
        cost = (cost == null ? 0 : cost) + usd.doubleValue();
      }
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
          new Step.Shown(
              new ChatItem.Retrying(
                  (int) count(event, "attempt"),
                  (int) count(event, "max_retries"),
                  count(event, "retry_delay_ms"),
                  text(event, "error"),
                  false));
      case "compact_boundary" ->
          new Step.Shown(
              new ChatItem.Compacted(
                  text(metadata, "trigger"),
                  count(metadata, "pre_tokens"),
                  count(metadata, "post_tokens"),
                  false));
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
   * A request Claude Code makes of the host: only {@code can_use_tool} is read, which asks before a
   * call, and asks a question or a plan's approval as a call of its question or plan tool; one that
   * cannot be read whole is {@link Step.Unreadable}, so the turn never waits on it unseen. A
   * request is its process's: it dies with it, and a resumed conversation replays none (recorded).
   */
  private static Step request(Map<String, Object> event) {
    var request = map(event.get("request"));
    var subtype = name(request.get("subtype"));
    if (!"can_use_tool".equals(subtype)) {
      return new Step.Other("control_request/" + subtype);
    }
    try {
      return asked(id(event, "request_id"), request);
    } catch (RuntimeException unreadable) {
      return new Step.Unreadable(safeId(event.get("request_id")));
    }
  }

  /**
   * A {@code can_use_tool} read whole. Its suggestions are the harness's own, to hand back as they
   * came: none when there are none, or when one is missing ({@code null}), since what is left of
   * them is no longer what it offered to keep.
   */
  private static Step asked(String id, Map<String, Object> request) {
    var call = id(request, "tool_use_id");
    var tool = Objects.toString(text(request, "tool_name"), "");
    if ("".equals(tool) || !(request.get("input") instanceof Map<?, ?>)) {
      throw new IllegalArgumentException("Not a call: " + tool);
    }
    var input = map(request.get("input"));
    return switch (tool) {
      case "AskUserQuestion" -> new Step.Question(id, call, input, questions(input));
      case "ExitPlanMode" ->
          new Step.Plan(id, call, input, Objects.toString(text(input, "plan"), ""));
      default ->
          new Step.Permission(
              id,
              call,
              tool,
              Objects.toString(text(request, "display_name"), ""),
              input,
              text(request, "description"),
              text(request, "decision_reason"),
              request.get("permission_suggestions") instanceof List<?> suggestions
                      && !suggestions.contains(null)
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
                                    text(option, "label"), text(option, "description"), false))
                        .toList(),
                    Boolean.TRUE.equals(asked.get("multiSelect")),
                    false))
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
                      id(block, "id"),
                      Objects.toString(text(block, "name"), ""),
                      Strings.clip(first(input), SUMMARY_CODE_POINTS),
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
                        id(block, "tool_use_id"),
                        block.get("content") instanceof String text
                            ? text
                            : list(block.get("content")).stream()
                                .map(part -> text(map(part), "text"))
                                .filter(Objects::nonNull)
                                .collect(Collectors.joining("\n")),
                        Boolean.TRUE.equals(block.get("is_error"))))
            .toList();
    return results.isEmpty() ? new Step.Other("user") : new Step.ToolResults(results);
  }

  /** A call's first string argument, whole; empty when it has none. */
  static String first(Map<String, Object> input) {
    return input.values().stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .findFirst()
        .orElse("");
  }

  /** {@code value} as a name that picks how a line is read, clipped; empty for what is no text. */
  private static String name(Object value) {
    return value instanceof String named ? Strings.clip(named, NAME_CODE_POINTS) : "";
  }

  private static List<String> names(Object value) {
    return list(value).stream().filter(String.class::isInstance).map(String.class::cast).toList();
  }

  /**
   * The id {@code from} holds at {@code key}, one safe to hand back.
   *
   * @throws IllegalArgumentException for any other, which makes its line say nothing
   */
  private static String id(Map<String, Object> from, String key) {
    if (from.get(key) instanceof String id && Harness.isSafeSessionId(id)) {
      return id;
    }
    throw new IllegalArgumentException("Not an id: " + key);
  }

  /** {@code value} when it is an id safe to hand back, else null. */
  private static String safeId(Object value) {
    return value instanceof String id && Harness.isSafeSessionId(id) ? id : null;
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
