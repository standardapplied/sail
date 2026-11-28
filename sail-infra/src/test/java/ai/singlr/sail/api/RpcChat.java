/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ChatItem;
import ai.singlr.sail.engine.ChatWire;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.HarnessChat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A second harness, for tests alone, whose wire is JSON-RPC 2.0 a message a line, as an ACP agent's
 * or Codex's app server's is, kept to what a chat needs: {@code initialize} first, whose response
 * names the session a prompt must name, so a message said before it is held until it answers; a
 * turn is a {@code prompt} request, which its response ends; what the agent does comes as {@code
 * update} notifications, and a call it asks before as a {@code permission} request the client
 * answers by its id. Nothing of it is the host's: it shows the host speaks a harness through its
 * wire alone.
 */
final class RpcChat implements HarnessChat {

  /** The id of the opening request, whose response names the session. */
  static final String OPENING = "open";

  @Override
  public List<String> argv(Launch launch) {
    return List.of("rpc-agent", "--stdio");
  }

  @Override
  public Wire wire(Launch launch) {
    return new Rpc(launch);
  }

  @Override
  public List<Harness.Mode> modes() {
    return List.of(
        new Harness.Mode("default", "Asks first", "Asks before it runs a command.", "", false));
  }

  @Override
  public boolean signsInInside() {
    return false;
  }

  @Override
  public ChatItem.ToolKind kind(String tool) {
    return "run".equals(tool) ? ChatItem.ToolKind.COMMAND : ChatItem.ToolKind.OTHER;
  }

  @Override
  public ChatItem.Detail detail(String tool, Map<String, Object> input) {
    return new ChatItem.Detail.Command((String) input.get("command"), false, false);
  }

  @Override
  public List<ChatItem.Always> always(
      String tool, Map<String, Object> input, List<Object> suggestions) {
    return List.of();
  }

  @Override
  public String noAlways(String tool, Map<String, Object> input, List<Object> suggestions) {
    return "This agent keeps no rule; allow it once instead.";
  }

  /** {@code pairs}, a key then its value, as one JSON object in their order. */
  static Map<String, Object> object(Object... pairs) {
    var object = new LinkedHashMap<String, Object>();
    for (var index = 0; index < pairs.length; index += 2) {
      object.put((String) pairs[index], pairs[index + 1]);
    }
    return object;
  }

  /** A request of the client's: {@code method} with {@code params}, as {@code id}. */
  static String request(String id, String method, Map<String, Object> params) {
    return YamlUtil.dumpJson(
        object("jsonrpc", "2.0", "id", id, "method", method, "params", params));
  }

  /** A notification: {@code method} with {@code params}, answered by nothing. */
  static String notification(String method, Map<String, Object> params) {
    return YamlUtil.dumpJson(object("jsonrpc", "2.0", "method", method, "params", params));
  }

  /** The response to {@code id}: {@code result}. */
  static String response(String id, Map<String, Object> result) {
    return YamlUtil.dumpJson(object("jsonrpc", "2.0", "id", id, "result", result));
  }

  /** One process's exchange: its session once opened, the messages held until then, its turn. */
  private final class Rpc implements Wire {

    private final Launch launch;
    private final List<String> held = new ArrayList<>();
    private String session;
    private String turn;
    private int turns;

    private Rpc(Launch launch) {
      this.launch = launch;
    }

    @Override
    public List<String> opening() {
      return List.of(
          request(
              OPENING,
              "initialize",
              launch.resume().isPresent()
                  ? object("protocol", 1, "resume", launch.resumeSessionId())
                  : object("protocol", 1)));
    }

    @Override
    public Heard read(String line) {
      var message = YamlUtil.parseJsonLine(line, 1 << 20);
      var id = message.get("id");
      if (OPENING.equals(id) && message.get("result") instanceof Map<?, ?> result) {
        session = (String) result.get("session");
        var mode = (String) result.get("mode");
        var prompts = held.stream().map(this::prompt).toList();
        held.clear();
        return new Heard(
            List.of(new ChatItem.Session(null, mode, List.of(), List.of(), false)),
            null,
            new Said(session, mode),
            false,
            false,
            prompts);
      }
      if (id != null && id.equals(turn) && message.containsKey("result")) {
        turn = null;
        return Heard.of(List.of(new ChatItem.TurnEnded(null, 0, 0, 0)));
      }
      if (!(message.get("params") instanceof Map<?, ?> params)) {
        return Heard.of(List.of());
      }
      return switch ((String) message.get("method")) {
        case "permission" -> asked((String) id, params);
        case "update" -> Heard.of(List.of(update(params)));
        default -> Heard.of(List.of());
      };
    }

    private Heard asked(String id, Map<?, ?> params) {
      @SuppressWarnings("unchecked")
      var input = (Map<String, Object>) params.get("input");
      var tool = (String) params.get("tool");
      return new Heard(
          List.of(
              new ChatItem.Permission(
                  id,
                  (String) params.get("call"),
                  null,
                  kind(tool),
                  (String) input.get("command"),
                  null,
                  detail(tool, input),
                  List.of(),
                  false)),
          reply -> answer(id, reply),
          null,
          false,
          false,
          List.of());
    }

    private String answer(String id, ChatWire.Reply reply) {
      return switch (reply.decision()) {
        case ALLOW -> response(id, object("outcome", "allow"));
        case DENY ->
            response(
                id,
                reply.message() == null
                    ? object("outcome", "deny")
                    : object("outcome", "deny", "message", reply.message()));
        default ->
            throw new IllegalArgumentException(
                "This agent asks to make this call: allow it, or deny it.");
      };
    }

    private ChatItem update(Map<?, ?> params) {
      var call = (String) params.get("call");
      return switch ((String) params.get("kind")) {
        case "tool" -> {
          var command = (String) params.get("command");
          yield new ChatItem.Tool(
              null,
              call,
              ChatItem.ToolKind.COMMAND,
              command,
              new ChatItem.Detail.Command(command, false, false),
              false);
        }
        case "done" ->
            new ChatItem.ToolDone(null, call, (String) params.get("output"), false, false);
        default -> new ChatItem.Text(null, (String) params.get("text"), true, false);
      };
    }

    @Override
    public List<String> message(String text) {
      if (session == null) {
        held.add(text);
        return List.of();
      }
      return List.of(prompt(text));
    }

    private String prompt(String text) {
      turn = "turn-" + ++turns;
      return request(turn, "prompt", object("session", session, "text", text));
    }

    @Override
    public String interrupt(String id) {
      return notification("cancel", object("session", session));
    }

    @Override
    public String mode(String id, String mode) {
      return request(id, "mode", object("session", session, "mode", mode));
    }
  }
}
