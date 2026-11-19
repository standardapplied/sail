/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.engine.ContainerExec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/** Codex CLI: {@code codex exec}, whose hooks sail reaches through Codex's hook trust model. */
final class Codex implements Harness {

  private static final String BINARY = "codex";
  private static final String TOKEN_COUNT = "\"type\":\"token_count\"";
  private static final String TURN_CONTEXT = "\"type\":\"turn_context\"";
  private static final String TASK_COMPLETE = "\"type\":\"task_complete\"";

  /** When a usage limit resets, as its message says it. */
  private static final Pattern TRY_AGAIN = Pattern.compile("(?i)try again at ([^.]+)");

  /**
   * Codex takes whatever level the selected model advertises ({@code model_reasoning_effort}), so
   * every Sail level is passed as it is.
   */
  private static final List<String> EFFORTS = List.of("none", "low", "medium", "high", "xhigh");

  /**
   * Codex does not expose a {@code --settings <path>} flag the way Claude Code does: its discovery
   * is fixed to {@code ~/.codex/hooks.json} (plus {@code config.toml} and project-scoped variants),
   * so session scoping runs through Codex's hook trust model instead. Codex records trust per hook
   * against a content hash ({@code [hooks.state]} in the engineer's {@code config.toml}) and
   * <em>silently skips</em> new or changed hooks until they are trusted, including in headless
   * {@code codex exec} (verified against codex-cli 0.144.0). Sail cannot pre-seed those hashes,
   * since the format is internal and {@code config.toml} belongs to the engineer, so
   * sail-dispatched sessions pass {@code --dangerously-bypass-hook-trust} instead (see {@link
   * #headless}). Engineer-run interactive {@code codex} sessions never get that flag, so this layer
   * stays inert for them unless they trust it via {@code /hooks}, and even then sail's scripts
   * self-gate on the run's environment, so nothing leaks into the spec event bus and no interactive
   * stop is gated.
   */
  private static final String HOOKS_PATH = ContainerExec.DEV_HOME + "/.codex/hooks.json";

  @Override
  public String yamlName() {
    return "codex";
  }

  @Override
  public String binaryName() {
    return BINARY;
  }

  @Override
  public String displayName() {
    return "Codex CLI";
  }

  @Override
  public String installCommand() {
    return "curl -fsSL https://chatgpt.com/codex/install.sh | CODEX_NON_INTERACTIVE=1 sh";
  }

  @Override
  public String skillsDir() {
    return ".agents/skills/";
  }

  /**
   * {@code codex exec}, or {@code codex exec resume <id>} for a recorded conversation. Codex
   * already streams a readable transcript, so a streaming launch is the same command. A
   * full-permission session additionally passes {@code --dangerously-bypass-hook-trust}: Codex
   * silently skips untrusted hooks even in headless {@code exec}, and sail cannot pre-seed trust
   * hashes for the hooks file it owns and rewrites, so without the flag the sail hooks layer would
   * never fire. It is tied to full permissions because hooks run outside the Codex sandbox: in a
   * sandboxed session auto-trusting hooks would grant an escape hatch, while an unsandboxed session
   * gains nothing it did not already have. Every sail dispatch path runs with full permissions, and
   * interactive engineer sessions never get the flag.
   */
  @Override
  public String headless(Launch launch) {
    var resume = launch.resume();
    var perm =
        launch.fullPermissions()
            ? " --dangerously-bypass-approvals-and-sandbox --dangerously-bypass-hook-trust"
            : "";
    return BINARY
        + " exec"
        + resume.map(id -> " resume").orElse("")
        + perm
        + modelOptions(launch.model(), launch.reasoningEffort())
        + resume.map(id -> " " + id).orElse("")
        + " "
        + launch.task();
  }

  /**
   * Codex cannot run the room lane's read-only session at all: its only enforcement layer is the
   * bubblewrap sandbox, which needs user namespaces, blocked inside incus containers ({@code bwrap:
   * setting up uid map: Permission denied}), so its sole executing mode is the full bypass flag the
   * room forbids.
   */
  @Override
  public Optional<String> readOnlyRefusal() {
    return Optional.of(
        displayName()
            + " has no harness-enforced read-only session inside a sail container: its bubblewrap"
            + " sandbox needs user namespaces, which incus containers block, so its only working mode"
            + " bypasses all restrictions. Add it with full access instead — the per-turn repo"
            + " reservation guards that lane.");
  }

  /** Only full: its sandbox needs user namespaces, which Incus containers block. */
  @Override
  public List<String> modes() {
    return List.of(FULL_MODE);
  }

  /**
   * Codex's built-in provider takes an API key in an interactive session only from its own store
   * ({@code auth.json}), where it would rest after the session. A provider of its own config,
   * declared on the command line ({@code model_providers}), reads the key from the environment
   * instead ({@code env_key}), so nothing of it is stored: the OpenAI API under the id {@code
   * sail}, which Codex does not reserve.
   */
  @Override
  public InteractiveLogin interactiveLogin(LoginKind kind) {
    return new InteractiveLogin(
        "",
        " -c model_provider=sail -c 'model_providers.sail={name=\"OpenAI\","
            + " base_url=\"https://api.openai.com/v1\", env_key=\""
            + kind.variable()
            + "\", wire_api=\"responses\"}'");
  }

  /** Its own store, where {@code codex login} keeps a ChatGPT sign-in or a key. */
  @Override
  public List<String> storedLogins() {
    return List.of(".codex/auth.json");
  }

  @Override
  public String interactive(boolean fullPermissions) {
    return fullPermissions ? BINARY + " --dangerously-bypass-approvals-and-sandbox" : BINARY;
  }

  @Override
  public String attach(String sessionId) {
    return sessionId != null
        ? BINARY + " resume " + Harness.requireSafeSessionId(sessionId)
        : BINARY;
  }

  @Override
  public List<String> efforts() {
    return EFFORTS;
  }

  @Override
  public ReportFile reportFile() {
    return ReportFile.TRANSCRIPT;
  }

  @Override
  public List<String> reportMarkers() {
    return List.of(TOKEN_COUNT, TURN_CONTEXT, TASK_COMPLETE);
  }

  /**
   * The transcript's last {@code token_count}: the thread's running totals, with no dollars, and
   * the model of its last {@code turn_context}. Codex's input count includes its cached part, which
   * is taken out, so the run's input is what it read uncached. A last {@code token_count} line that
   * does not parse gives null.
   */
  @Override
  public RunUsage usage(String report) {
    var found = RunUsage.last(report, TOKEN_COUNT, Codex::counts);
    if (found.unreadable()) {
      return null;
    }
    if (found.line() == null) {
      return RunUsage.NONE;
    }
    var total =
        RunUsage.map(
            RunUsage.map(RunUsage.map(found.line(), "payload"), "info"), "total_token_usage");
    var input = RunUsage.count(total, "input_tokens");
    var cached = RunUsage.count(total, "cached_input_tokens");
    var context = RunUsage.last(report, TURN_CONTEXT, Codex::isTurnContext).line();
    var model = context == null ? null : RunUsage.map(context, "payload").get("model");
    return new RunUsage(
        model instanceof String named ? named : null,
        input == null || cached == null ? input : input - cached,
        RunUsage.count(total, "output_tokens"),
        cached,
        RunUsage.count(total, "cache_write_input_tokens"),
        null,
        RunUsage.Source.TRANSCRIPT);
  }

  /**
   * The {@code task_complete} of the transcript's last turn, the one whose {@code turn_id} its last
   * {@code turn_context} names: a resumed session's rollout holds earlier runs' turns, and an end
   * of one of theirs says nothing of this run. Its error's {@code codex_error_info} naming an HTTP
   * 401 is a refusal, and {@code usage_limit_exceeded} a limit, with the time its message says to
   * try again at. {@code codex exec} without {@code --json}, as Sail launches it, writes no {@code
   * turn.failed}; this is where its end is recorded (codex-cli 0.163.0).
   */
  @Override
  public Optional<LoginOutcome> loginOutcome(String report, LoginKind given) {
    var context = RunUsage.last(report, TURN_CONTEXT, Codex::isTurnContext).line();
    var turn = context == null ? null : RunUsage.map(context, "payload").get("turn_id");
    var done =
        RunUsage.last(
                report,
                TASK_COMPLETE,
                line -> "task_complete".equals(RunUsage.map(line, "payload").get("type")))
            .line();
    if (turn == null
        || done == null
        || !turn.equals(RunUsage.map(done, "payload").get("turn_id"))) {
      return Optional.empty();
    }
    var error = RunUsage.map(RunUsage.map(done, "payload"), "error");
    var failed = RunUsage.map(RunUsage.map(error, "codex_error_info"), "http_connection_failed");
    if (Long.valueOf(401).equals(RunUsage.count(failed, "http_status_code"))) {
      return Optional.of(new LoginOutcome.Refused());
    }
    if (!"usage_limit_exceeded".equals(error.get("codex_error_info"))) {
      return Optional.empty();
    }
    var message = error.get("message") instanceof String said ? said : "";
    var resets =
        TRY_AGAIN
            .matcher(message)
            .results()
            .map(found -> found.group(1))
            .reduce((first, last) -> last)
            .map(LoginOutcome::resetTime);
    return Optional.of(new LoginOutcome.Limited(resets.orElse(null)));
  }

  private static boolean isTurnContext(Map<String, Object> line) {
    return "turn_context".equals(line.get("type"));
  }

  private static boolean counts(Map<String, Object> line) {
    var payload = RunUsage.map(line, "payload");
    return "token_count".equals(payload.get("type"))
        && !RunUsage.map(RunUsage.map(payload, "info"), "total_token_usage").isEmpty();
  }

  /**
   * An API key, read from {@code CODEX_API_KEY}, which {@code codex exec} (every Sail launch of
   * Codex) reads before any other login. Codex does not read {@code OPENAI_API_KEY} for its own
   * login, and its "Sign in with ChatGPT" refreshes, so it is never stored.
   */
  @Override
  public List<LoginKind> loginKinds() {
    return List.of(
        new LoginKind(
            "api-key",
            "CODEX_API_KEY",
            "API key",
            Remedy.of("Create an API key at platform.openai.com/api-keys"),
            null));
  }

  /**
   * Its key's variable and {@code CODEX_ACCESS_TOKEN}, which Codex reads as an agent identity: no
   * run is started on one, but a value the container exports must not outrank the stored key.
   */
  @Override
  public List<String> loginVariables() {
    return List.of("CODEX_API_KEY", "CODEX_ACCESS_TOKEN");
  }

  @Override
  public OptionalInt loginTunnelPort() {
    return OptionalInt.empty();
  }

  @Override
  public Optional<String> interactiveTip() {
    return Optional.empty();
  }

  /**
   * Codex's {@code SessionStart} payload carries {@code session_id} and {@code transcript_path}
   * under the same names as Claude Code's, so the one report script records the resumable
   * conversation beside the session-started event. Its {@code PostToolUse} honors {@code
   * hookSpecificOutput.additionalContext} exactly as Claude Code does, so the one relay script
   * gives both the same mid-run room delivery; the relay must not move to {@code PreToolUse}, where
   * Codex parses but rejects {@code additionalContext}. Codex fires {@code PostToolUse} for a call
   * that failed as for one that succeeded, so it needs no counterpart of Claude Code's {@code
   * PostToolUseFailure}, and it has no batch hook. {@code Stop} carries the same {@code
   * stop_hook_active} loop guard and honors the same block decision as Claude Code, so the one gate
   * script serves both unmodified, and it must be the only {@code Stop} hook. Codex has no analogue
   * of {@code SessionEnd}, so no session-completed event is emitted for it; the spec lifecycle
   * already moves on the stop.
   */
  @Override
  public HookFile hooks() {
    return new HookFile(
        HOOKS_PATH,
        new LinkedHashMap<>(),
        List.of(
            new HookFile.Group(
                "SessionStart", null, List.of(SailHook.SESSION_STARTED, SailHook.SESSION_REPORT)),
            new HookFile.Group("PreToolUse", null, List.of(SailHook.TOOL_STARTED)),
            new HookFile.Group(
                "PostToolUse", null, List.of(SailHook.TOOL_FINISHED, SailHook.ROOM_RELAY)),
            new HookFile.Group("Stop", null, List.of(SailHook.STOP_GATE))));
  }

  private static String modelOptions(String model, String reasoningEffort) {
    var options = new StringBuilder();
    if (model != null) {
      options.append(" --model ").append(model);
    }
    if (reasoningEffort != null) {
      options.append(" --config model_reasoning_effort='\"").append(reasoningEffort).append("\"'");
    }
    return options.toString();
  }
}
