/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.engine.BoxCredentialFile;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.SailPaths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SequencedMap;
import java.util.stream.Collectors;

/** Claude Code: {@code claude}, launched headless with sail's own settings file of hooks. */
final class ClaudeCode implements Harness {

  private static final String BINARY = "claude";
  private static final String RESULT = "\"type\":\"result\"";

  private static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh");

  /**
   * The sail-owned settings file, passed to {@code claude} via {@code --settings} only when sail
   * launches the agent: engineer SSH sessions that run bare {@code claude} never see these hooks,
   * so their {@code Stop} events do not leak into the spec event bus.
   */
  private static final String SETTINGS_PATH =
      ContainerExec.DEV_HOME + "/.sail/claude-settings.json";

  /**
   * The settings file of a live member, passed via {@code --settings} to the one process sail reads
   * instead of hooking: the read-deny rules and nothing that runs a script.
   */
  private static final String LIVE_SETTINGS_PATH =
      ContainerExec.DEV_HOME + "/.sail/claude-live.json";

  /**
   * What makes a session read-only, as argv: ambient settings and MCP configs excluded, the tool
   * set cut, the allow-rules naming only the {@code spec} subcommands a room member uses (it reads
   * and it comments) and {@code cd}, and deny-rules refusing either file-reading flag of {@code
   * spec} anywhere in a command, so no glob or trailing flag hands it a file such as {@code
   * /proc/self/environ}. Every read-only lane is built from this one list.
   */
  private static final List<String> READ_ONLY_ARGS =
      List.of(
          "--setting-sources",
          "",
          "--strict-mcp-config",
          "--tools",
          "Bash,Read,Grep,Glob",
          "--allowedTools",
          "Bash(spec show:*)",
          "Bash(spec list:*)",
          "Bash(spec board:*)",
          "Bash(spec comments:*)",
          "Bash(spec comment:*)",
          "Bash(spec whoami:*)",
          "Bash(cd:*)",
          "--disallowedTools",
          "Bash(spec *--body-file*)",
          "Bash(spec *--plan-file*)");

  @Override
  public String yamlName() {
    return "claude-code";
  }

  @Override
  public String binaryName() {
    return BINARY;
  }

  @Override
  public String displayName() {
    return "Claude Code";
  }

  @Override
  public String installCommand() {
    return "curl -fsSL https://claude.ai/install.sh | bash";
  }

  @Override
  public String skillsDir() {
    return ".claude/skills/";
  }

  /**
   * {@code claude --print}, with {@code --output-format stream-json --verbose} when the launch
   * streams so {@code agent.log} fills live during a long-running dispatch with newline-delimited
   * JSON events instead of a single final result. Streaming must stay scoped to the background
   * dispatch path: the review/foreground paths parse the agent's final {@code json} block and would
   * break under streaming output.
   */
  @Override
  public String headless(Launch launch) {
    var resume = resumeOption(launch);
    var perm = launch.fullPermissions() ? " --dangerously-skip-permissions" : "";
    return invocation(launch)
        + perm
        + modelOption(launch.model())
        + effortOption(launch.reasoningEffort())
        + resume
        + " -p "
        + launch.task();
  }

  /**
   * The room lane's headless command: like {@link #headless} but harness-restricted instead of
   * full-permission. The tool set is cut to {@code Bash,Read,Grep,Glob}: {@code Write} and {@code
   * Edit} do not exist in the session, and the {@code --tools} cut is CLI-authoritative, so no
   * on-disk settings file can re-add them. The explicit allow-rules cover {@code spec} (the lane's
   * one write, posting the answer, and the room credential is viewer-role so even {@code spec}
   * cannot mutate a spec) and {@code cd}. Beyond those, {@code --print} refuses every
   * <em>mutating</em> Bash command and every arbitrary interpreter, and it auto-permits recognized
   * <em>read</em> commands ({@code cat}/{@code head}/{@code tail}/{@code grep}) only for paths
   * <em>inside the working directory</em>: a read of a path outside {@code ~/workspace} is refused
   * (verified empirically). So the session reads the code it is consulting on and nothing else:
   * every container secret lives outside the workspace ({@code ~/.ssh}, {@code ~/.sail}, {@code
   * ~/.claude}, {@code /var/lib/sail}) and is unreadable by default. The {@code Read}-deny rules in
   * {@link #readDenyRules}, which Claude applies to a Bash read of a denied path too,
   * belt-and-suspenders the highest-value credentials on top of that; they are not the primary
   * boundary. Git is deliberately absent: {@code git diff --output=<path>} writes through a prefix
   * allow-rule, and git's external-diff and pager config are command-execution surfaces, which a
   * read-only lane must not expose.
   *
   * <p>The invocation is pinned closed against ambient configuration: {@code --setting-sources ""}
   * excludes every user/project/local settings file, so a {@code .claude/settings.json} in the
   * workspace or home directory cannot merge an additional {@code Bash(...)} allow-rule into the
   * session (Claude Code merges permission rules additively across settings sources; the flag
   * removes those sources while the sail-owned {@code --settings} file, hooks plus the
   * credential/key read-denies, still applies), and {@code --strict-mcp-config} keeps a workspace
   * {@code .mcp.json} from launching MCP server processes into the session.
   *
   * <p>This is the harness-enforced boundary the platform can express, not a kernel one. It is
   * exact about what it is: {@code Write}/{@code Edit} are structurally gone and Bash writes and
   * interpreters are refused, so the session cannot change code; reads are auto-approved only
   * inside the workspace, so out-of-tree secrets ({@code box.credential}, {@code ~/.ssh}, {@code
   * ~/.sail}, {@code ~/.claude}) are unreadable by default, with {@code Read}-denies
   * belt-and-bracing the top credentials; ambient settings and MCP configs are excluded; the room
   * credential is viewer-role; and a host-side content guard ({@code
   * DispatchOperations#guardRoomRun}) surfaces any worktree change as a loud guardrail event. What
   * it is not: hermetic against a kernel-level escape, a harness-enforcement bug, or a secret
   * committed <em>inside</em> the workspace (which the consultant reads by design, as any build
   * agent does). That boundary is owned by the room-lane hardening follow-up spec (a sidecar
   * container with a read-only disk device), which incus does not give a same-container process.
   */
  @Override
  public String readOnly(Launch launch) {
    var resume = resumeOption(launch);
    return invocation(launch)
        + readOnlyOptions()
        + modelOption(launch.model())
        + effortOption(launch.reasoningEffort())
        + resume
        + " -p "
        + launch.task();
  }

  /**
   * Claude Code's streaming mode: prompts are JSON lines on stdin, and every step of a turn is a
   * JSON line on stdout, text as it is written ({@code --include-partial-messages}). A read-only
   * member runs under exactly the isolation, tool cut and allow-rules of {@link #readOnly}; a full
   * one skips permissions as {@link #headless} does. {@code --permission-prompts none} denies every
   * prompt either way: nobody is there to answer one.
   */
  @Override
  public Optional<List<String>> live(LiveLaunch launch) {
    var command =
        new ArrayList<>(
            List.of(
                BINARY,
                "--print",
                "--input-format",
                "stream-json",
                "--output-format",
                "stream-json",
                "--verbose",
                "--include-partial-messages",
                "--permission-prompts",
                "none",
                "--settings",
                LIVE_SETTINGS_PATH));
    command.addAll(
        launch.fullPermissions() ? List.of("--dangerously-skip-permissions") : READ_ONLY_ARGS);
    if (launch.model() != null) {
      command.addAll(List.of("--model", launch.model()));
    }
    launch.resume().ifPresent(id -> command.addAll(List.of("--resume", id)));
    return Optional.of(List.copyOf(command));
  }

  @Override
  public Optional<LiveSettings> liveSettings() {
    return Optional.of(new LiveSettings(LIVE_SETTINGS_PATH, settings()));
  }

  @Override
  public Optional<String> readOnlyRefusal() {
    return Optional.empty();
  }

  @Override
  public String interactive(boolean fullPermissions) {
    return fullPermissions ? BINARY + " --dangerously-skip-permissions" : BINARY;
  }

  @Override
  public String attach(String sessionId) {
    return sessionId != null
        ? BINARY + " --resume " + Harness.requireSafeSessionId(sessionId)
        : BINARY;
  }

  /**
   * The levels of {@code --effort} that are Sail's own: {@code max} and {@code ultracode} are not.
   */
  @Override
  public List<String> efforts() {
    return EFFORTS;
  }

  @Override
  public ReportFile reportFile() {
    return ReportFile.AGENT_LOG;
  }

  @Override
  public List<String> reportMarkers() {
    return List.of(RESULT);
  }

  /**
   * The last {@code result} line of the log that reports anything: a crashed session's last result
   * may carry every figure zeroed, and the running totals of the one before it are the run's.
   * Tokens are {@code modelUsage}'s, summed over the models it names, since {@code usage} leaves
   * out what subagents spent; the dollars are {@code total_cost_usd}; the model is the one that
   * cost most. A result with no {@code modelUsage} gives its {@code usage}, and no model. A last
   * result line that does not parse gives null.
   */
  @Override
  public RunUsage usage(String report) {
    var found = RunUsage.last(report, RESULT, ClaudeCode::reports);
    if (found.unreadable()) {
      return null;
    }
    return found.line() == null ? RunUsage.NONE : usageOf(found.line());
  }

  private static boolean reports(Map<String, Object> line) {
    if (!"result".equals(line.get("type"))) {
      return false;
    }
    var usage = usageOf(line);
    return (usage.costUsd() != null && usage.costUsd() > 0)
        || (usage.tokens() != null && usage.tokens() > 0);
  }

  private static RunUsage usageOf(Map<String, Object> result) {
    var cost = result.get("total_cost_usd") instanceof Number usd ? usd.doubleValue() : null;
    var models = RunUsage.map(result, "modelUsage");
    if (models.isEmpty()) {
      var usage = RunUsage.map(result, "usage");
      return new RunUsage(
          null,
          RunUsage.count(usage, "input_tokens"),
          RunUsage.count(usage, "output_tokens"),
          RunUsage.count(usage, "cache_read_input_tokens"),
          RunUsage.count(usage, "cache_creation_input_tokens"),
          cost,
          RunUsage.Source.RESULT);
    }
    String model = null;
    var most = -1.0;
    long in = 0;
    long out = 0;
    long read = 0;
    long write = 0;
    for (var entry : models.entrySet()) {
      var figures = RunUsage.map(models, entry.getKey());
      in += figure(figures, "inputTokens");
      out += figure(figures, "outputTokens");
      read += figure(figures, "cacheReadInputTokens");
      write += figure(figures, "cacheCreationInputTokens");
      var spent = figures.get("costUSD") instanceof Number usd ? usd.doubleValue() : 0;
      if (spent > most) {
        most = spent;
        model = entry.getKey();
      }
    }
    return new RunUsage(model, in, out, read, write, cost, RunUsage.Source.RESULT);
  }

  private static long figure(Map<String, Object> figures, String key) {
    var count = RunUsage.count(figures, key);
    return count == null ? 0 : count;
  }

  /**
   * A token {@code claude setup-token} makes, which lasts a year and is read from {@code
   * CLAUDE_CODE_OAUTH_TOKEN}; or a Console API key, read from {@code ANTHROPIC_API_KEY}. Either
   * outranks a login made by hand with {@code /login}, which the harness refreshes and so is never
   * stored.
   */
  @Override
  public List<LoginKind> loginKinds() {
    return List.of(
        new LoginKind(
            "token",
            "CLAUDE_CODE_OAUTH_TOKEN",
            Remedy.of("Run claude setup-token on a machine where Claude Code is signed in")),
        new LoginKind(
            "api-key", "ANTHROPIC_API_KEY", Remedy.of("Create an API key in the Claude Console")));
  }

  /**
   * Its kinds' variables and {@code ANTHROPIC_AUTH_TOKEN}, a gateway's token, which outranks both.
   * Claude Code takes an empty variable as unset.
   */
  @Override
  public List<String> loginVariables() {
    return List.of("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN");
  }

  /**
   * The Bedrock, Vertex and Foundry switches: set, Claude Code sends its requests to that cloud on
   * the container's own account, whatever login it holds; empty, it is off.
   */
  @Override
  public List<String> credentialSwitches() {
    return List.of("CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY");
  }

  @Override
  public OptionalInt loginTunnelPort() {
    return OptionalInt.of(3000);
  }

  @Override
  public Optional<String> interactiveTip() {
    return Optional.of(
        "Type /rc inside Claude Code to connect from your phone via Remote Control.");
  }

  /**
   * Besides hooks, the settings file carries the {@link #readDenyRules} permission rules and turns
   * commit co-author attribution off. {@code SessionStart} runs the session-started event for the
   * {@code startup} source only, and the session report in its own matcher-less group beside it:
   * the event announces a session's beginning exactly once, but the report must fire on every start
   * source, since a resume, clear or compact restart mints a new conversation whose identity must
   * overwrite the row. {@code PostToolUse} fires only for a call that succeeded and {@code
   * PostToolUseFailure} for one that failed, so both tell the watcher a call that ran is over; a
   * call Claude Code denies before running it fires neither, and {@code PostToolBatch}, fired once
   * every call of a batch has resolved, is what closes it. {@code Stop} runs the stop gate alone:
   * hooks in a group run in parallel, so a bare publisher beside the gate would announce a stop
   * that the gate then cancels.
   */
  @Override
  public HookFile hooks() {
    return new HookFile(
        SETTINGS_PATH,
        settings(),
        List.of(
            new HookFile.Group("SessionStart", "startup", List.of(SailHook.SESSION_STARTED)),
            new HookFile.Group("SessionStart", null, List.of(SailHook.SESSION_REPORT)),
            new HookFile.Group("PreToolUse", null, List.of(SailHook.TOOL_STARTED)),
            new HookFile.Group(
                "PostToolUse", null, List.of(SailHook.TOOL_FINISHED, SailHook.ROOM_RELAY)),
            new HookFile.Group("PostToolUseFailure", null, List.of(SailHook.TOOL_FINISHED)),
            new HookFile.Group("PostToolBatch", null, List.of(SailHook.BATCH_RESOLVED)),
            new HookFile.Group("Stop", null, List.of(SailHook.STOP_GATE)),
            new HookFile.Group("SessionEnd", null, List.of(SailHook.SESSION_ENDED))));
  }

  /** What every settings file sail hands Claude Code carries, whichever lane reads it. */
  private static SequencedMap<String, Object> settings() {
    var settings = new LinkedHashMap<String, Object>();
    settings.put("includeCoAuthoredBy", false);
    settings.put("permissions", Map.of("deny", readDenyRules()));
    return settings;
  }

  /**
   * The Read-deny rules that belt-and-suspenders the room lane's highest-value credentials. The
   * primary boundary is elsewhere: Claude Code auto-approves read commands ({@code cat}/{@code
   * head}/{@code tail}/{@code grep}) only inside the working directory (the workspace), and refuses
   * a read of any path outside it (verified empirically), so the container's secrets, all of which
   * live outside {@code ~/workspace}, are unreadable by default even without a rule. These denies
   * harden the box FDE {@code box.credential}, the box SSH identity ({@code ~/.ssh}, the Sail CLI
   * identity), the {@code ~/.git-credentials} token, the {@code gh} token under {@code
   * ~/.config/gh} and a run's {@code launch.env}, which holds its credential and its person's login
   * until its wrapper reads it, explicitly on top of that, so the protection does not rest solely
   * on the cwd heuristic. Claude Code applies a {@code Read(path)} deny to a Bash command that
   * reads that path (verified), deny outranks every allow rule, and the room invocation pins {@code
   * --setting-sources ""} so no ambient settings file can shadow these. A full (YOLO) agent skips
   * permission rules by design: it is the trusted member lane. Spec-CLI auth is untouched: the
   * helper reads the credential at the OS level, not through a tool. Residual (a secret committed
   * inside the workspace, a kernel escape, a harness-enforcement bug) is owned by the room-lane
   * hardening follow-up, a read-only-disk sidecar, not this denylist.
   */
  private static List<String> readDenyRules() {
    return List.of(
        boxCredentialReadDeny(),
        "Read(" + ContainerExec.DEV_HOME + "/.ssh/**)",
        "Read(" + ContainerExec.DEV_HOME + "/.git-credentials)",
        "Read(" + ContainerExec.DEV_HOME + "/.config/gh/**)",
        "Read(" + ContainerExec.DEV_HOME + "/.sail/runs/**/launch.env)");
  }

  private static String boxCredentialReadDeny() {
    var credential = SailPaths.apiSocketContainerDir().resolve(BoxCredentialFile.FILE_NAME);
    return "Read(/" + credential + ")";
  }

  private static String invocation(Launch launch) {
    var streamFormat = launch.stream() ? " --output-format stream-json --verbose" : "";
    return BINARY + " --print" + streamFormat + " --settings " + SETTINGS_PATH;
  }

  /** {@link #READ_ONLY_ARGS} as shell text: each option as it is, each value double-quoted. */
  private static String readOnlyOptions() {
    return READ_ONLY_ARGS.stream()
        .map(arg -> arg.startsWith("--") ? " " + arg : " \"" + arg + "\"")
        .collect(Collectors.joining());
  }

  private static String resumeOption(Launch launch) {
    return launch.resume().map(id -> " --resume " + id).orElse("");
  }

  private static String modelOption(String model) {
    return model == null ? "" : " --model " + model;
  }

  private static String effortOption(String effort) {
    return effort == null ? "" : " --effort " + effort;
  }
}
