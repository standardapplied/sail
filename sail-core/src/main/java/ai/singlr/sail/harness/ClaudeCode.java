/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.engine.BoxCredentialFile;
import ai.singlr.sail.engine.SailPaths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/** Claude Code: {@code claude}, launched headless with sail's own settings file of hooks. */
final class ClaudeCode implements Harness {

  private static final String BINARY = "claude";

  private static final String DEV_HOME = "/home/dev";

  /**
   * The sail-owned settings file, passed to {@code claude} via {@code --settings} only when sail
   * launches the agent: engineer SSH sessions that run bare {@code claude} never see these hooks,
   * so their {@code Stop} events do not leak into the spec event bus.
   */
  private static final String SETTINGS_PATH = DEV_HOME + "/.sail/claude-settings.json";

  private static final String ROOM_TOOLS = " --tools \"Bash,Read,Grep,Glob\"";

  private static final String ROOM_ALLOWED_TOOLS =
      " --allowedTools \"Bash(spec:*)\" \"Bash(cd:*)\"";

  private static final String ROOM_ISOLATION = " --setting-sources \"\" --strict-mcp-config";

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
  public String homeContextPath() {
    return ".claude/CLAUDE.md";
  }

  @Override
  public String skillsDir() {
    return ".claude/skills/";
  }

  /**
   * A path-scoped rule {@code ~/.claude/rules/<name>.md} whose {@code paths:} frontmatter loads it
   * only when a matching file enters context, or a session-start rule when the project gives no
   * globs.
   */
  @Override
  public String languageRulePath(String name) {
    return ".claude/rules/" + name + ".md";
  }

  @Override
  public String languageRule(String name, List<String> paths, String body) {
    if (paths.isEmpty()) {
      return body;
    }
    var sb = new StringBuilder("---\npaths:\n");
    for (var glob : paths) {
      sb.append("  - \"").append(glob).append("\"\n");
    }
    return sb.append("---\n\n").append(body).toString();
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
   * {@link #roomReadDenyRules}, which Claude applies to a Bash read of a denied path too,
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
   * Codex cannot run this lane at all: its only enforcement layer is the bubblewrap sandbox, which
   * needs user namespaces, blocked inside incus containers ({@code bwrap: setting up uid map:
   * Permission denied}), so its sole executing mode is the full bypass flag the room forbids.
   */
  @Override
  public String readOnly(Launch launch) {
    var resume = resumeOption(launch);
    return invocation(launch)
        + ROOM_ISOLATION
        + ROOM_TOOLS
        + ROOM_ALLOWED_TOOLS
        + modelOption(launch.model())
        + resume
        + " -p "
        + launch.task();
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
    return sessionId != null ? BINARY + " --resume " + sessionId : BINARY;
  }

  @Override
  public boolean honoursReasoningEffort() {
    return false;
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
   * Besides hooks, the settings file carries the {@link #roomReadDenyRules} permission rules and
   * turns commit co-author attribution off. {@code SessionStart} runs the session-started event for
   * the {@code startup} source only, and the session report in its own matcher-less group beside
   * it: the event announces a session's beginning exactly once, but the report must fire on every
   * start source, since a resume, clear or compact restart mints a new conversation whose identity
   * must overwrite the row. {@code PostToolUse} fires only for a call that succeeded and {@code
   * PostToolUseFailure} for one that failed, so both tell the watcher a call that ran is over; a
   * call Claude Code denies before running it fires neither, and {@code PostToolBatch}, fired once
   * every call of a batch has resolved, is what closes it. {@code Stop} runs the stop gate alone:
   * hooks in a group run in parallel, so a bare publisher beside the gate would announce a stop
   * that the gate then cancels.
   */
  @Override
  public HookFile hooks() {
    var settings = new LinkedHashMap<String, Object>();
    settings.put("includeCoAuthoredBy", false);
    settings.put("permissions", Map.of("deny", roomReadDenyRules()));
    return new HookFile(
        SETTINGS_PATH,
        settings,
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

  /**
   * The Read-deny rules that belt-and-suspenders the room lane's highest-value credentials. The
   * primary boundary is elsewhere: Claude Code auto-approves read commands ({@code cat}/{@code
   * head}/{@code tail}/{@code grep}) only inside the working directory (the workspace), and refuses
   * a read of any path outside it (verified empirically), so the container's secrets, all of which
   * live outside {@code ~/workspace}, are unreadable by default even without a rule. These denies
   * harden the box FDE {@code box.credential}, the box SSH identity ({@code ~/.ssh}, the Sail CLI
   * identity), and the {@code ~/.git-credentials} token explicitly on top of that, so the
   * protection does not rest solely on the cwd heuristic. Claude Code applies a {@code Read(path)}
   * deny to a Bash command that reads that path (verified), deny outranks every allow rule, and the
   * room invocation pins {@code --setting-sources ""} so no ambient settings file can shadow these.
   * A full (YOLO) agent skips permission rules by design: it is the trusted member lane. Spec-CLI
   * auth is untouched: the helper reads the credential at the OS level, not through a tool.
   * Residual (a secret committed inside the workspace, a kernel escape, a harness-enforcement bug)
   * is owned by the room-lane hardening follow-up, a read-only-disk sidecar, not this denylist.
   */
  private static List<String> roomReadDenyRules() {
    return List.of(
        boxCredentialReadDeny(),
        "Read(" + DEV_HOME + "/.ssh/**)",
        "Read(" + DEV_HOME + "/.git-credentials)");
  }

  private static String boxCredentialReadDeny() {
    var credential = SailPaths.apiSocketContainerDir().resolve(BoxCredentialFile.FILE_NAME);
    return "Read(/" + credential + ")";
  }

  private static String invocation(Launch launch) {
    var streamFormat = launch.stream() ? " --output-format stream-json --verbose" : "";
    return BINARY + " --print" + streamFormat + " --settings " + SETTINGS_PATH;
  }

  private static String resumeOption(Launch launch) {
    return launch.resume().map(id -> " --resume " + id).orElse("");
  }

  private static String modelOption(String model) {
    return model == null ? "" : " --model " + model;
  }
}
