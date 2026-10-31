/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.NameValidator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Root model for {@code sail.yaml} project descriptor. */
public record SailYaml(
    String name,
    String description,
    Resources resources,
    String image,
    List<String> packages,
    Runtimes runtimes,
    Git git,
    List<Repo> repos,
    Map<String, Service> services,
    Map<String, Process> processes,
    Agent agent,
    Ssh ssh) {

  /** The keys sail no longer reads, each with the one sentence saying where its text now goes. */
  public static final Map<String, Remedy> DELETED_KEYS =
      Map.of(
          "agent_context",
          Remedy.of(
              "agent_context is no longer read: sail writes no context file. Put what it said in"
                  + " the repo's AGENTS.md, and a rule in a project skill (",
              Remedy.sail("project skills add"),
              ")."),
          "agent.methodology",
          Remedy.of(
              "agent.methodology is no longer read: sail's work prompt tells the agent to run the"
                  + " project's verification. Put the commands in the repo's AGENTS.md."),
          "agent.build_skill",
          Remedy.of(
              "agent.build_skill is no longer read: sail's work prompt says how a build runs. Put"
                  + " what the skill said in the repo's AGENTS.md, or keep it as a project skill (",
              Remedy.sail("project skills add"),
              ") the agent invokes when relevant."),
          "agent.review_pipeline.fix_skill",
          Remedy.of(
              "agent.review_pipeline.fix_skill is no longer read: a fix run reads the build's work"
                  + " prompt. Put what the skill said in the repo's AGENTS.md, or keep it as a"
                  + " project skill (",
              Remedy.sail("project skills add"),
              ")."));

  /** Refuses a definition that still sets {@code key}, one of {@link #DELETED_KEYS}. */
  static void refuseDeleted(Map<String, Object> map, String key, String descriptor) {
    var leaf = key.substring(key.lastIndexOf('.') + 1);
    if (map.containsKey(leaf)) {
      throw Remedied.argument(
          "", DELETED_KEYS.get(key).then(" Then remove `" + key + "` from " + descriptor + "."));
    }
  }

  @SuppressWarnings("unchecked")
  public static SailYaml fromMap(Map<String, Object> map) {
    var name = (String) map.get("name");
    refuseDeleted(map, "agent_context", descriptor(name));
    var resourcesRaw = (Map<String, Object>) map.get("resources");
    var runtimesRaw = (Map<String, Object>) map.get("runtimes");
    var gitRaw = (Map<String, Object>) map.get("git");
    var reposRaw = (List<Map<String, Object>>) map.get("repos");
    var servicesRaw = (Map<String, Object>) map.get("services");
    var processesRaw = (Map<String, Object>) map.get("processes");
    var agentRaw = (Map<String, Object>) map.get("agent");
    var sshRaw = (Map<String, Object>) map.get("ssh");

    var agent = agentRaw != null ? Agent.fromMap(agentRaw, descriptor(name)) : null;
    var config =
        new SailYaml(
            name,
            (String) map.get("description"),
            resourcesRaw != null ? Resources.fromMap(resourcesRaw) : null,
            (String) map.get("image"),
            (List<String>) map.get("packages"),
            runtimesRaw != null ? Runtimes.fromMap(runtimesRaw) : null,
            gitRaw != null ? Git.fromMap(gitRaw) : null,
            reposRaw != null ? reposRaw.stream().map(Repo::fromMap).toList() : null,
            servicesRaw != null
                ? servicesRaw.entrySet().stream()
                    .collect(
                        Collectors.toMap(
                            Map.Entry::getKey,
                            e -> Service.fromMap((Map<String, Object>) e.getValue()),
                            (a, b) -> a,
                            LinkedHashMap::new))
                : null,
            processesRaw != null
                ? processesRaw.entrySet().stream()
                    .collect(
                        Collectors.toMap(
                            Map.Entry::getKey,
                            e -> Process.fromMap(e.getKey(), (Map<String, Object>) e.getValue()),
                            (a, b) -> a,
                            LinkedHashMap::new))
                : null,
            agent,
            sshRaw != null ? Ssh.fromMap(sshRaw) : null);
    config.requirePortsClaimedOnce();
    return config;
  }

  /**
   * Refuses a definition where two processes, or a process and a service, claim one port: the
   * portal forwards a declared port to exactly one app.
   */
  private void requirePortsClaimedOnce() {
    var claimed = new LinkedHashMap<Integer, String>();
    if (services != null) {
      services.forEach(
          (service, declared) -> {
            if (declared != null && declared.ports() != null) {
              declared
                  .ports()
                  .forEach(port -> claimed.putIfAbsent(port, "service '" + service + "'"));
            }
          });
    }
    if (processes != null) {
      processes.forEach(
          (process, declared) -> {
            if (declared.port() == null) {
              return;
            }
            var other = claimed.putIfAbsent(declared.port(), "process '" + process + "'");
            if (other != null) {
              throw new IllegalArgumentException(
                  "Port "
                      + declared.port()
                      + " is claimed by both "
                      + other
                      + " and process '"
                      + process
                      + "'. Give one of them another port.");
            }
          });
    }
  }

  private static String descriptor(String name) {
    return Objects.requireNonNullElse(name, "project") + "/sail.yaml";
  }

  /** Converts this config to a map suitable for YAML serialization. */
  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("name", name);
    if (description != null) map.put("description", description);
    if (resources != null) map.put("resources", resources.toMap());
    if (image != null) map.put("image", image);
    if (packages != null) map.put("packages", packages);
    if (runtimes != null) map.put("runtimes", runtimes.toMap());
    if (git != null) map.put("git", git.toMap());
    if (repos != null) {
      map.put("repos", repos.stream().map(Repo::toMap).toList());
    }
    if (services != null) {
      var svcs = new LinkedHashMap<String, Object>();
      for (var entry : services.entrySet()) {
        svcs.put(entry.getKey(), entry.getValue().toMap());
      }
      map.put("services", svcs);
    }
    if (processes != null) {
      var procs = new LinkedHashMap<String, Object>();
      for (var entry : processes.entrySet()) {
        procs.put(entry.getKey(), entry.getValue().toMap());
      }
      map.put("processes", procs);
    }
    if (agent != null) map.put("agent", agent.toMap());
    if (ssh != null) map.put("ssh", ssh.toMap());
    return map;
  }

  public record Resources(int cpu, String memory, String disk) {
    public static Resources fromMap(Map<String, Object> map) {
      var cpu = map.get("cpu");
      if (!(cpu instanceof Number)) {
        throw new IllegalArgumentException("resources.cpu is required and must be a number");
      }
      var memory = map.get("memory");
      if (memory == null) {
        throw new IllegalArgumentException("resources.memory is required (e.g. \"8GB\")");
      }
      var disk = map.get("disk");
      if (disk == null) {
        throw new IllegalArgumentException("resources.disk is required (e.g. \"50GB\")");
      }
      return new Resources(((Number) cpu).intValue(), normalizeSize(memory), normalizeSize(disk));
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("cpu", cpu);
      map.put("memory", memory);
      map.put("disk", disk);
      return map;
    }
  }

  public record Runtimes(int jdk, String node, String maven) {
    public static Runtimes fromMap(Map<String, Object> map) {
      var jdk = map.get("jdk");
      var nodeVal = map.get("node");
      var node = toVersionString(nodeVal);
      var maven = toVersionString(map.get("maven"));
      if (node != null) {
        NameValidator.requireValidVersion(node, "runtimes.node");
      }
      if (maven != null) {
        NameValidator.requireValidVersion(maven, "runtimes.maven");
      }
      return new Runtimes(jdk instanceof Number n ? n.intValue() : 0, node, maven);
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      if (jdk > 0) map.put("jdk", jdk);
      if (node != null) map.put("node", node);
      if (maven != null) map.put("maven", maven);
      return map;
    }

    /** Converts a YAML value (Integer, Double, or String) to a version string. */
    private static String toVersionString(Object val) {
      if (val instanceof Integer n) return String.valueOf(n);
      if (val instanceof Double d)
        return d % 1 == 0 ? String.valueOf(d.intValue()) : String.valueOf(d);
      if (val instanceof String s && !s.isBlank()) return s;
      return null;
    }
  }

  /**
   * Git identity and authentication configuration for the dev user. The {@code sshKey} field is a
   * path to a private key file on the host, used only when {@code auth} is {@code "ssh"}.
   */
  public record Git(String name, String email, String auth, String sshKey) {
    public static Git fromMap(Map<String, Object> map) {
      var name = map.get("name");
      if (!(name instanceof String)) {
        throw new IllegalArgumentException("git.name is required");
      }
      var email = map.get("email");
      if (!(email instanceof String)) {
        throw new IllegalArgumentException("git.email is required");
      }
      var authValue = Objects.requireNonNullElse((String) map.get("auth"), "token");
      if (!"token".equals(authValue) && !"ssh".equals(authValue)) {
        throw new IllegalArgumentException("git.auth must be 'token' or 'ssh', got: " + authValue);
      }
      var sshKey = (String) map.get("ssh_key");
      return new Git((String) name, (String) email, authValue, sshKey);
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("email", email);
      map.put("auth", auth);
      if (sshKey != null) map.put("ssh_key", sshKey);
      return map;
    }
  }

  /**
   * A source repository to clone into {@code ~/workspace/<path>}.
   *
   * @param forge {@code github} for a GitHub host that is not {@code github.com} (a GitHub
   *     Enterprise host), so the forge CLI is logged in for it; null otherwise
   */
  public record Repo(String url, String path, String branch, String forge) {

    /** The one forge a repo may be marked as: a GitHub host that is not {@code github.com}. */
    public static final String GITHUB = "github";

    public Repo {
      if (forge != null && !forge.equals(GITHUB)) {
        throw new IllegalArgumentException(
            "repos[].forge must be '" + GITHUB + "' when set, got: " + forge);
      }
    }

    public Repo(String url, String path, String branch) {
      this(url, path, branch, null);
    }

    public static Repo fromMap(Map<String, Object> map) {
      var url = map.get("url");
      if (!(url instanceof String)) {
        throw new IllegalArgumentException("repos[].url is required");
      }
      NameValidator.requireValidGitUrl((String) url, "repos[].url");
      var path = map.get("path");
      if (!(path instanceof String)) {
        throw new IllegalArgumentException("repos[].path is required");
      }
      NameValidator.requireSafePath((String) path, "repos[].path");
      var branch = (String) map.get("branch");
      if (branch != null) {
        NameValidator.requireValidGitRef(branch, "repos[].branch");
      }
      var forge = map.get("forge");
      if (forge != null && !(forge instanceof String)) {
        throw new IllegalArgumentException("repos[].forge must be text when set.");
      }
      return new Repo((String) url, (String) path, branch, (String) forge);
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("url", url);
      map.put("path", path);
      if (branch != null) map.put("branch", branch);
      if (forge != null) map.put("forge", forge);
      return map;
    }
  }

  public record Service(
      String image,
      List<Integer> ports,
      Map<String, String> environment,
      String command,
      List<String> volumes) {
    @SuppressWarnings("unchecked")
    public static Service fromMap(Map<String, Object> map) {
      var envRaw = (Map<String, Object>) map.get("environment");
      Map<String, String> env = null;
      if (envRaw != null) {
        var envMap = new LinkedHashMap<String, String>();
        for (var entry : envRaw.entrySet()) {
          envMap.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        env = envMap;
      }
      return new Service(
          (String) map.get("image"),
          (List<Integer>) map.get("ports"),
          env,
          (String) map.get("command"),
          (List<String>) map.get("volumes"));
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("image", image);
      if (ports != null) map.put("ports", ports);
      if (environment != null) map.put("environment", new LinkedHashMap<>(environment));
      if (command != null) map.put("command", command);
      if (volumes != null) map.put("volumes", volumes);
      return map;
    }
  }

  /**
   * An app the project runs in its container as the dev user, from its working directory.
   *
   * @param workdir relative to the workspace, {@code .} for the workspace itself; null for the
   *     first repo's path
   * @param port the port it listens on, which the portal forwards and opens; null for none
   * @param env what it adds to its environment, by name; empty for nothing
   */
  public record Process(String command, String workdir, Integer port, Map<String, String> env) {

    /** The lowest port a process may claim: below it a port needs root. */
    public static final int MIN_PORT = 1024;

    /** The highest port there is. */
    public static final int MAX_PORT = 65535;

    /** The workdir that is the workspace itself. */
    public static final String WORKSPACE = ".";

    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public Process {
      env = env == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(env));
    }

    /** The process named {@code name} as {@code processes.<name>} declares it, checked whole. */
    @SuppressWarnings("unchecked")
    public static Process fromMap(String name, Map<String, Object> map) {
      NameValidator.requireValidProcessName(name);
      var field = "processes." + name;
      if (map == null || !(map.get("command") instanceof String command) || command.isBlank()) {
        throw new IllegalArgumentException(
            field + ".command is required: the command that starts it.");
      }
      var workdir = map.get("workdir");
      if (workdir != null && !(workdir instanceof String)) {
        throw new IllegalArgumentException(field + ".workdir must be text when set.");
      }
      if (workdir != null && !inWorkspace((String) workdir).isEmpty()) {
        NameValidator.requireSafePath(inWorkspace((String) workdir), field + ".workdir");
      }
      var port = map.get("port");
      if (port != null
          && (!(port instanceof Integer number) || number < MIN_PORT || number > MAX_PORT)) {
        throw new IllegalArgumentException(
            field
                + ".port must be a whole number from "
                + MIN_PORT
                + " to "
                + MAX_PORT
                + ", got: "
                + port);
      }
      var env = new LinkedHashMap<String, String>();
      var envRaw = map.get("env");
      if (envRaw != null && !(envRaw instanceof Map<?, ?>)) {
        throw new IllegalArgumentException(field + ".env must be a map of names to values.");
      }
      if (envRaw != null) {
        for (var entry : ((Map<Object, Object>) envRaw).entrySet()) {
          if (!(entry.getKey() instanceof String key) || !ENV_NAME.matcher(key).matches()) {
            throw new IllegalArgumentException(
                field
                    + ".env has the name '"
                    + entry.getKey()
                    + "'; a name is letters, digits and _, not starting with a digit.");
          }
          if (entry.getValue() == null
              || entry.getValue() instanceof Map
              || entry.getValue() instanceof List) {
            throw new IllegalArgumentException(field + ".env." + key + " must be a single value.");
          }
          env.put(key, String.valueOf(entry.getValue()));
        }
      }
      return new Process(command, (String) workdir, (Integer) port, env);
    }

    /**
     * The path {@code workdir} names under the workspace, without the {@code ./} it may start with;
     * empty for the workspace itself.
     */
    public static String inWorkspace(String workdir) {
      var path = workdir.replaceFirst("^(\\./)+", "");
      return WORKSPACE.equals(path) ? "" : path;
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      if (command != null) map.put("command", command);
      if (workdir != null) map.put("workdir", workdir);
      if (port != null) map.put("port", port);
      if (!env.isEmpty()) map.put("env", new LinkedHashMap<>(env));
      return map;
    }
  }

  @SuppressWarnings("unchecked")
  public record Agent(
      String type,
      boolean autoBranch,
      String branchPrefix,
      boolean autoSnapshot,
      List<String> install,
      Map<String, String> config,
      Guardrails guardrails,
      Notifications notifications,
      ReviewPipelineConfig reviewPipeline,
      Member member) {

    public Agent(
        String type,
        boolean autoBranch,
        String branchPrefix,
        boolean autoSnapshot,
        List<String> install,
        Map<String, String> config,
        Guardrails guardrails,
        Notifications notifications) {
      this(
          type,
          autoBranch,
          branchPrefix,
          autoSnapshot,
          install,
          config,
          guardrails,
          notifications,
          null,
          null);
    }

    @SuppressWarnings("unchecked")
    public static Agent fromMap(Map<String, Object> map) {
      return fromMap(map, "sail.yaml");
    }

    @SuppressWarnings("unchecked")
    static Agent fromMap(Map<String, Object> map, String descriptor) {
      if (map.containsKey("specs_dir")) {
        throw new IllegalArgumentException(
            "Unknown agent key `specs_dir` in "
                + descriptor
                + "; remove `specs_dir` from "
                + descriptor
                + " because specs live in the Sail database.");
      }
      refuseDeleted(map, "agent.methodology", descriptor);
      refuseDeleted(map, "agent.build_skill", descriptor);
      var notificationsRaw = (Map<String, Object>) map.get("notifications");
      var reviewPipelineRaw = (Map<String, Object>) map.get("review_pipeline");
      var memberRaw = (Map<String, Object>) map.get("member");
      var agent =
          new Agent(
              (String) map.get("type"),
              Boolean.TRUE.equals(map.get("auto_branch")),
              (String) map.get("branch_prefix"),
              Boolean.TRUE.equals(map.get("auto_snapshot")),
              (List<String>) map.get("install"),
              (Map<String, String>) map.get("config"),
              Guardrails.fromBlock(map.get("guardrails"), Guardrails.BUILD_BLOCK, descriptor)
                  .orElse(null),
              notificationsRaw != null ? Notifications.fromMap(notificationsRaw, descriptor) : null,
              reviewPipelineRaw != null
                  ? ReviewPipelineConfig.fromMap(reviewPipelineRaw, descriptor)
                  : null,
              memberRaw != null ? Member.fromMap(memberRaw) : null);
      agent.requireSeatableMember(descriptor);
      return agent;
    }

    /** The harnesses the project installs: {@code install}, else {@code type}. */
    public List<String> installs() {
      if (install != null && !install.isEmpty()) {
        return install.stream().distinct().toList();
      }
      return type != null ? List.of(type) : List.of();
    }

    /**
     * The member a home room opens with: {@code member} with its blanks filled, the agent from
     * {@code type}, the agent's own default model, {@code full} access. Null when neither names an
     * agent.
     */
    public Member projectMember() {
      var declared = declaredMember();
      var agent = memberAgent();
      if (Strings.isBlank(agent)) {
        return null;
      }
      return new Member(
          agent,
          Strings.isBlank(declared.model()) ? null : declared.model(),
          Strings.isBlank(declared.mode())
              ? EngagementMode.FULL.wire()
              : EngagementMode.of(declared.mode()).wire());
    }

    private Member declaredMember() {
      return member != null ? member : new Member(null, null, null);
    }

    private String memberAgent() {
      var declared = declaredMember().agent();
      return Strings.isBlank(declared) ? type : declared;
    }

    /**
     * Refuses a member that could never be seated, declared or the default {@code type} makes: a
     * harness the project does not install, a mode no engagement knows, or a model that is not one
     * shell-safe token. A definition naming no agent at all has no member to refuse.
     */
    private void requireSeatableMember(String descriptor) {
      var declared = declaredMember();
      var agent = memberAgent();
      if (Strings.isBlank(agent)) {
        if (member == null) {
          return;
        }
        throw new IllegalArgumentException(
            "agent.member.agent in "
                + descriptor
                + " is unset and agent.type names no agent. Set one of them.");
      }
      if (!installs().contains(agent)) {
        throw new IllegalArgumentException(
            "agent.member.agent '"
                + agent
                + "' is not a harness "
                + descriptor
                + " installs ("
                + String.join(", ", installs())
                + "). Add it to agent.install, or name one of those.");
      }
      try {
        if (Strings.isNotBlank(declared.mode())) {
          EngagementMode.of(declared.mode());
        }
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "agent.member.mode in " + descriptor + ": " + e.getMessage(), e);
      }
      try {
        Spec.validatedModel(declared.model());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "agent.member.model '"
                + declared.model()
                + "' in "
                + descriptor
                + " is not a model id. Use one without spaces or shell syntax.",
            e);
      }
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("type", type);
      map.put("auto_branch", autoBranch);
      if (branchPrefix != null) map.put("branch_prefix", branchPrefix);
      map.put("auto_snapshot", autoSnapshot);
      if (install != null) map.put("install", new ArrayList<>(install));
      if (config != null) map.put("config", new LinkedHashMap<>(config));
      if (guardrails != null) map.put("guardrails", guardrails.toMap());
      if (notifications != null) map.put("notifications", notifications.toMap());
      if (reviewPipeline != null) map.put("review_pipeline", reviewPipeline.toMap());
      if (member != null) map.put("member", member.toMap());
      return map;
    }

    /**
     * The limits a run in {@code lane} is held to: {@code review_pipeline.guardrails} for a
     * reviewer, {@code guardrails} for every other lane, a fix run included, since it runs the
     * project's verification and waits on CI exactly as a build does; each lane's own defaults when
     * its block is absent. The one place a lane is mapped to its limits.
     */
    public Guardrails guardrailsFor(Lane lane) {
      if (lane == Lane.REVIEW) {
        return reviewPipeline != null ? reviewPipeline.guardrails() : Guardrails.reviewDefaults();
      }
      return guardrails != null ? guardrails : Guardrails.defaults();
    }

    /**
     * The hard lifetime the project sets for a run in {@code lane}, which bounds that run's
     * credential, or null when none does. A reviewer has its lane's — its default when the project
     * writes no block — and every other lane, a fix run included, one only from an {@code
     * agent.guardrails} block the project wrote; a block that names no {@code max_duration} sets
     * none. A run nothing bounds must not lose its credential to a clock mid-work.
     */
    public Duration lifetimeFor(Lane lane) {
      var bounded = guardrails != null || lane == Lane.REVIEW;
      return bounded ? Guardrails.parseDuration(guardrailsFor(lane).maxDuration()) : null;
    }
  }

  /**
   * The engagement {@code agent.member} names for a project's home room: each field blank for its
   * default, which {@link Agent#projectMember} fills.
   */
  public record Member(String agent, String model, String mode) {
    static Member fromMap(Map<String, Object> map) {
      return new Member(
          (String) map.get("agent"), (String) map.get("model"), (String) map.get("mode"));
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      if (agent != null) map.put("agent", agent);
      if (model != null) map.put("model", model);
      if (mode != null) map.put("mode", mode);
      return map;
    }
  }

  @SuppressWarnings("unchecked")
  public record Ssh(String user, List<String> authorizedKeys) {
    public static Ssh fromMap(Map<String, Object> map) {
      var user = (String) map.get("user");
      if (user != null) {
        NameValidator.requireValidSshUser(user);
      }
      return new Ssh(user, (List<String>) map.get("authorized_keys"));
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      if (user != null) map.put("user", user);
      if (authorizedKeys != null) map.put("authorized_keys", new ArrayList<>(authorizedKeys));
      return map;
    }
  }

  /** Returns a copy of this config with Node.js added to the runtimes. */
  public SailYaml withNodeRuntime(String nodeVersion) {
    var newRuntimes =
        runtimes != null
            ? new Runtimes(runtimes.jdk(), nodeVersion, runtimes.maven())
            : new Runtimes(0, nodeVersion, null);
    return new SailYaml(
        name,
        description,
        resources,
        image,
        packages,
        newRuntimes,
        git,
        repos,
        services,
        processes,
        agent,
        ssh);
  }

  /** Returns a copy of this config with the agent install list replaced. */
  public SailYaml withAgentInstall(List<String> install) {
    var newAgent =
        new Agent(
            agent.type(),
            agent.autoBranch(),
            agent.branchPrefix(),
            agent.autoSnapshot(),
            install,
            agent.config(),
            agent.guardrails(),
            agent.notifications(),
            agent.reviewPipeline(),
            agent.member());
    return new SailYaml(
        name,
        description,
        resources,
        image,
        packages,
        runtimes,
        git,
        repos,
        services,
        processes,
        newAgent,
        ssh);
  }

  /** Returns the SSH username from config, defaulting to "dev". */
  public String sshUser() {
    return ssh != null && ssh.user() != null ? ssh.user() : "dev";
  }

  /** Returns absolute repo paths inside the container for the configured repos. */
  public List<String> repoPaths() {
    var base = "/home/" + sshUser() + "/workspace";
    if (repos != null && !repos.isEmpty()) {
      return repos.stream().map(r -> base + "/" + r.path()).toList();
    }
    return List.of(base);
  }

  /** Normalizes a size value: bare numbers like {@code 4} become {@code "4GB"}. */
  static String normalizeSize(Object val) {
    var s = String.valueOf(val).strip().replaceAll("\\s+", "");
    if (s.matches("^\\d+$")) {
      return s + "GB";
    }
    return s;
  }
}
