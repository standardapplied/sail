/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.BoxCredentialFile;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.LiveStream;
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

  /** Where Claude Code sends its requests, which its stored key fixes to the Anthropic API. */
  private static final String BASE_URL = "ANTHROPIC_BASE_URL";

  /**
   * A unix socket Claude Code sends every request through instead of its address, whatever host
   * that names (2.1.327, measured: undocumented, and read from the environment alone, never from a
   * settings file): its stored key sets it empty, which Claude Code takes as unset.
   */
  private static final String UNIX_SOCKET = "ANTHROPIC_UNIX_SOCKET";

  /**
   * Set, Claude Code takes its address, provider and credential from its environment alone and
   * ignores those any settings file names, a managed one included (2.1.327, measured): what makes a
   * key's address hold whatever the container's settings say. It also ignores an {@code
   * apiKeyHelper}.
   */
  private static final String HOST_MANAGED = "CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST";

  private static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh");

  /**
   * How sail runs Claude Code as one process it talks to: JSON lines both ways, text as written.
   */
  static final List<String> STREAMING =
      List.of(
          BINARY,
          "--print",
          "--input-format",
          "stream-json",
          "--output-format",
          "stream-json",
          "--verbose",
          "--include-partial-messages");

  /** Where, under the dev user's home, Claude Code keeps its own sign-in. */
  private static final String CREDENTIALS = ".claude/.credentials.json";

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
  static final String LIVE_SETTINGS_PATH = ContainerExec.DEV_HOME + "/.sail/claude-live.json";

  /**
   * What makes a session read-only, as argv: ambient settings and MCP configs excluded, the tool
   * set cut, the allow-rules naming only the {@code spec} subcommands a room member uses (it reads
   * and it comments) and {@code cd}, and deny-rules refusing either file-reading flag of {@code
   * spec} anywhere in a command, so no glob or trailing flag hands it a file such as {@code
   * /proc/self/environ}, and refusing the shell's environment readers and every read under {@code
   * /proc}, which hold the run's login: Claude Code's own checks refuse those too, and these rules
   * keep them refused whatever a later release of it approves. Every read-only lane is built from
   * this one list.
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
          "Bash(spec *--plan-file*)",
          "Bash(printenv:*)",
          "Bash(env:*)",
          "Bash(set:*)",
          "Bash(export:*)",
          "Bash(declare:*)",
          "Read(//proc/**)");

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
    var command = new ArrayList<>(STREAMING);
    command.addAll(List.of("--permission-prompts", "none", "--settings", LIVE_SETTINGS_PATH));
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
  public Optional<HarnessChat> chat() {
    return Optional.of(new ClaudeCodeChat());
  }

  @Override
  public Optional<String> readOnlyRefusal() {
    return Optional.empty();
  }

  /**
   * An API key in {@code ANTHROPIC_API_KEY} makes an interactive session ask the person to approve
   * it, showing its first 7 and last 20 characters, and keep those last 20 in {@code
   * ~/.claude.json} once approved (2.1.327, seen with a throwaway key, with or without {@link
   * #HOST_MANAGED}). A key a session's own {@code apiKeyHelper} prints is neither asked about nor
   * kept, so the key moves to a variable of Sail's own, which the helper prints. {@link
   * #HOST_MANAGED} ignores a helper, so this session goes without it, and a user's or a project's
   * settings file's {@code env} then outranks the session's environment: a cloud switch there with
   * its skip-auth sent the helper's key to that cloud's address (measured). So the same {@code
   * --settings}, which outranks the user's and the project's settings but not managed ones, sets
   * everything the session's lines pin ({@link #pinned}) and its key's variable empty.
   */
  @Override
  public InteractiveLogin interactiveLogin(LoginKind kind) {
    var env = new LinkedHashMap<String, Object>(pinned(kind));
    env.remove(HOST_MANAGED);
    env.put(kind.variable(), "");
    var settings = new LinkedHashMap<String, Object>();
    settings.put("apiKeyHelper", "printenv SAIL_ANTHROPIC_API_KEY");
    settings.put("env", env);
    return new InteractiveLogin(
        "export SAIL_ANTHROPIC_API_KEY=\"$ANTHROPIC_API_KEY\"; unset ANTHROPIC_API_KEY "
            + HOST_MANAGED,
        " --settings '" + YamlUtil.dumpJsonValue(settings) + "'");
  }

  /**
   * Claude Code's own sign-in, which it asks for when it starts signed out, with a Claude
   * subscription or an Anthropic Console account. It keeps it in {@code .credentials.json}; {@code
   * .claude.json} is its own state, which also holds an approved key's last 20 characters, so that
   * file says nothing of a sign-in.
   */
  @Override
  public SignIn signIn() {
    return new SignIn(
        "Your Claude account",
        Remedy.of(
            "Claude Code asks you to sign in with your Claude account when it starts, once in each"
                + " workspace."),
        CREDENTIALS);
  }

  /**
   * Its sign-in ({@link #signIn}) and its own state, which holds an approved key's last 20
   * characters and the signed-in account, with the copies of that state it keeps in {@code backups}
   * each time it writes it: a start that finds a copy and no state says so before its welcome, so a
   * home holds both or neither.
   */
  @Override
  public List<String> storedLogins() {
    return List.of(CREDENTIALS, ".claude.json", ".claude/backups");
  }

  @Override
  public String interactive(boolean fullPermissions) {
    return fullPermissions ? BINARY + " --dangerously-skip-permissions" : BINARY;
  }

  /**
   * Claude Code's own start, with no flag, then each of its permission modes once, passed as it
   * names them ({@code --permission-mode}): its {@code default} is Manual, offered as {@code
   * manual}, its alias, since a start with no flag is its own start.
   */
  @Override
  public List<Mode> modes() {
    return List.of(
        new Mode(
            "default",
            "Claude Code's own",
            "Starts as Claude Code starts by itself: the project's default mode, else Auto where it"
                + " is available, else Manual.",
            "",
            false),
        permissionMode(
            "manual", "Manual", "Asks before edits, shell commands and network actions.", false),
        permissionMode(
            "acceptEdits",
            "Accept edits",
            "Makes file edits and common filesystem commands without asking.",
            false),
        permissionMode(
            "plan",
            "Plan",
            "Researches and proposes changes, and edits nothing until you approve the plan.",
            false),
        permissionMode(
            "auto",
            "Auto",
            "Runs everything, with a classifier reviewing each action first; Manual where auto is"
                + " not available.",
            false),
        permissionMode(
            "dontAsk",
            "Don't ask",
            "Refuses anything that would ask, and runs only what is approved beforehand.",
            false),
        permissionMode(
            "bypassPermissions",
            "Bypass permissions",
            "Skips every permission prompt and safety check.",
            true));
  }

  static Mode permissionMode(String name, String label, String about, boolean risky) {
    return new Mode(name, label, about, " --permission-mode " + name, risky);
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

  /**
   * The last {@code result}, when it is an error, read as {@link LiveStream#loginOutcome} reads a
   * failed turn of a chat: a 401, "Not logged in" or a limit. No other credential is ever used in
   * the login's place: under {@link #HOST_MANAGED} no settings file's {@code apiKeyHelper} or key,
   * a user's, a project's or a managed one, no key kept by {@code /login} and no signed-in
   * subscription outranks the key's own variable, and its {@code init} line names that variable
   * (2.1.327, measured).
   */
  @Override
  public Optional<LoginOutcome> loginOutcome(String report) {
    var result = RunUsage.last(report, RESULT, line -> "result".equals(line.get("type"))).line();
    if (result == null || !Boolean.TRUE.equals(result.get("is_error"))) {
      return Optional.empty();
    }
    return LiveStream.loginOutcome(
        result.get("result") instanceof String text ? text : "",
        RunUsage.count(result, "api_error_status"));
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
   * A Console API key, read from {@code ANTHROPIC_API_KEY} and sent to the Anthropic API alone
   * ({@link #HOST_MANAGED}). It outranks a sign-in made inside the session ({@link #signIn}), which
   * Sail never holds.
   */
  @Override
  public List<LoginKind> loginKinds() {
    var fixed = new LinkedHashMap<String, String>();
    fixed.put(BASE_URL, "https://api.anthropic.com");
    fixed.put(UNIX_SOCKET, "");
    fixed.put(HOST_MANAGED, "1");
    return List.of(
        new LoginKind(
            "api-key",
            "ANTHROPIC_API_KEY",
            "API key",
            Remedy.of("Create an API key in the Claude Console"),
            fixed));
  }

  /**
   * Its key's variable, the gateway token outranking it, {@code CLAUDE_CODE_OAUTH_TOKEN}, a
   * subscription's token a container may export, the credentials a host hands it by file
   * descriptor, and {@code ANTHROPIC_CUSTOM_HEADERS}, whose header replaces the key's own (2.1.327,
   * measured: a key by descriptor is sent beside an {@code ANTHROPIC_AUTH_TOKEN}, and an {@code
   * x-api-key} header in place of the stored key). Claude Code takes an empty variable as unset.
   */
  @Override
  public List<String> loginVariables() {
    return List.of(
        "ANTHROPIC_AUTH_TOKEN",
        "ANTHROPIC_API_KEY",
        "CLAUDE_CODE_OAUTH_TOKEN",
        "CLAUDE_CODE_API_KEY_FILE_DESCRIPTOR",
        "CLAUDE_CODE_OAUTH_TOKEN_FILE_DESCRIPTOR",
        "CLAUDE_CODE_GATEWAY_TOKEN_FILE_DESCRIPTOR",
        "CLAUDE_CODE_WEBSOCKET_AUTH_FILE_DESCRIPTOR",
        "ANTHROPIC_CUSTOM_HEADERS");
  }

  /**
   * The switches of every cloud it reaches Claude through: set, Claude Code sends its requests to
   * that cloud on the container's own account, whatever login it holds (Mantle and Claude on Google
   * Cloud to whatever address the container names, measured); empty, it is off.
   */
  @Override
  public List<String> credentialSwitches() {
    return List.of(
        "CLAUDE_CODE_USE_BEDROCK",
        "CLAUDE_CODE_USE_VERTEX",
        "CLAUDE_CODE_USE_FOUNDRY",
        "CLAUDE_CODE_USE_MANTLE",
        "CLAUDE_CODE_USE_ANTHROPIC_AWS",
        "CLAUDE_CODE_USE_ANTHROPIC_GOOGLE_CLOUD");
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
   * ~/.config/gh}, a run's {@code launch.env}, which holds its credential and its person's login
   * until its wrapper reads it, and a session's login file under {@code ~/.sail/sessions}, which
   * holds its person's login until its shell reads it, explicitly on top of that, so the protection
   * does not rest solely on the cwd heuristic. Claude Code applies a {@code Read(path)} deny to a
   * Bash command that reads that path (verified), deny outranks every allow rule, and the room
   * invocation pins {@code --setting-sources ""} so no ambient settings file can shadow these. A
   * full (YOLO) agent skips permission rules by design: it is the trusted member lane. Spec-CLI
   * auth is untouched: the helper reads the credential at the OS level, not through a tool.
   * Residual (a secret committed inside the workspace, a kernel escape, a harness-enforcement bug)
   * is owned by the room-lane hardening follow-up, a read-only-disk sidecar, not this denylist.
   */
  private static List<String> readDenyRules() {
    return List.of(
        boxCredentialReadDeny(),
        "Read(" + ContainerExec.DEV_HOME + "/.ssh/**)",
        "Read(" + ContainerExec.DEV_HOME + "/.git-credentials)",
        "Read(" + ContainerExec.DEV_HOME + "/.config/gh/**)",
        "Read(" + ContainerExec.DEV_HOME + "/.sail/runs/**/launch.env)",
        "Read(~/.sail/sessions/**)");
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
