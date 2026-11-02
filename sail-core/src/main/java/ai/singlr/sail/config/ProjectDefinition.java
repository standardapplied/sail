/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.NameValidator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A project's definition: what its container is made of and how its agents work, in named sections.
 * Each section is the unit of storage, editing, sync and merge ({@link Section}); {@link
 * ProjectDefinitionJson} is its one codec. Nothing personal is in it: the git identity and the key
 * a container trusts are this box's, read when a container is provisioned, and every agent, shell
 * and hook runs as {@link #USER}.
 */
public record ProjectDefinition(
    String name,
    String description,
    Resources resources,
    String image,
    List<String> packages,
    Runtimes runtimes,
    List<Repo> repos,
    Map<String, Service> services,
    Map<String, Process> processes,
    Agent agent,
    ReviewPipelineConfig pipeline) {

  /** The account every agent, shell and hook in a project's container runs as (uid 1000). */
  public static final String USER = "dev";

  /** The keys a definition holds: its name and one per {@link Section}. */
  static final List<String> KEYS =
      Stream.concat(Stream.of("name"), Arrays.stream(Section.values()).map(Section::key)).toList();

  /** A definition's sections, each written, compared and merged on its own. */
  public enum Section {
    DESCRIPTION("description"),
    IMAGE("image"),
    RESOURCES("resources"),
    RUNTIMES("runtimes"),
    REPOS("repos"),
    SERVICES("services"),
    PROCESSES("processes"),
    PACKAGES("packages"),
    AGENT("agent"),
    PIPELINE("pipeline");

    private final String key;

    Section(String key) {
      this.key = key;
    }

    /** The section's key in a definition document, and its name on every door. */
    public String key() {
      return key;
    }

    /** The section a door names, or a refusal listing the ones there are. */
    public static Section of(String key) {
      for (var section : values()) {
        if (section.key.equals(key)) {
          return section;
        }
      }
      throw new IllegalArgumentException(
          "'"
              + key
              + "' is not a section of a project definition. The sections are: "
              + String.join(", ", Arrays.stream(values()).map(Section::key).toList())
              + ".");
    }
  }

  @SuppressWarnings("unchecked")
  static ProjectDefinition fromMap(Map<String, Object> map) {
    Fields.only(map, "", KEYS);
    var name = Fields.text(map, "name", "name");
    if (name == null) {
      throw new IllegalArgumentException("name is required: the project's name.");
    }
    NameValidator.requireValidProjectName(name);
    var resourcesRaw = Fields.map(map, "resources", "resources");
    var runtimesRaw = Fields.map(map, "runtimes", "runtimes");
    var reposRaw = Fields.list(map, "repos", "repos");
    var servicesRaw = Fields.map(map, "services", "services");
    var processesRaw = Fields.map(map, "processes", "processes");
    var agentRaw = Fields.map(map, "agent", "agent");
    var pipelineRaw = Fields.map(map, "pipeline", "pipeline");
    var config =
        new ProjectDefinition(
            name,
            Fields.text(map, "description", "description"),
            resourcesRaw != null ? Resources.fromMap(resourcesRaw) : null,
            Fields.text(map, "image", "image"),
            Fields.textList(map, "packages", "packages"),
            runtimesRaw != null ? Runtimes.fromMap(runtimesRaw) : null,
            reposRaw != null ? repos(reposRaw) : null,
            servicesRaw != null ? services(servicesRaw) : null,
            processesRaw != null ? processes(processesRaw) : null,
            agentRaw != null ? Agent.fromMap(agentRaw) : null,
            pipelineRaw != null ? ReviewPipelineConfig.fromMap(pipelineRaw) : null);
    return config;
  }

  private static List<Repo> repos(List<Object> raw) {
    var repos = new ArrayList<Repo>();
    for (var i = 0; i < raw.size(); i++) {
      repos.add(Repo.fromMap(Fields.element(raw, i, "repos")));
    }
    return List.copyOf(repos);
  }

  private static Map<String, Service> services(Map<String, Object> raw) {
    var services = new LinkedHashMap<String, Service>();
    raw.forEach(
        (service, declared) ->
            services.put(
                service, Service.fromMap(service, Fields.asMap(declared, "services." + service))));
    return services;
  }

  private static Map<String, Process> processes(Map<String, Object> raw) {
    var processes = new LinkedHashMap<String, Process>();
    raw.forEach(
        (process, declared) ->
            processes.put(
                process, Process.fromMap(process, Fields.asMap(declared, "processes." + process))));
    return processes;
  }

  /**
   * Refuses a definition where two processes, or a process and a service, claim one port: the
   * portal forwards a declared port to exactly one app. The one rule that spans sections, so a
   * definition is read without it: a sync merge of two boxes' sections, each sound alone, stays
   * readable and replicates, and the clash is refused where it is written and where it is served.
   */
  public ProjectDefinition requirePortsClaimedOnce() {
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
    return this;
  }

  /** This definition as its document's map, a key for each section it sets. */
  Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("name", name);
    if (description != null) map.put("description", description);
    if (resources != null) map.put("resources", resources.toMap());
    if (image != null) map.put("image", image);
    if (packages != null) map.put("packages", packages);
    if (runtimes != null) map.put("runtimes", runtimes.toMap());
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
    if (pipeline != null) map.put("pipeline", pipeline.toMap());
    return map;
  }

  public record Resources(int cpu, String memory, String disk) {
    static Resources fromMap(Map<String, Object> map) {
      Fields.only(map, "resources", List.of("cpu", "memory", "disk"));
      var cpu = Fields.integer(map, "cpu", "resources.cpu");
      if (cpu == null || cpu < 1) {
        throw new IllegalArgumentException(
            "resources.cpu is required: a whole number of cores, 1 or more.");
      }
      return new Resources(cpu, size(map, "memory", "\"8GB\""), size(map, "disk", "\"50GB\""));
    }

    private static String size(Map<String, Object> map, String key, String example) {
      var value = map.get(key);
      if (!(value instanceof String) && !(value instanceof Integer)) {
        throw new IllegalArgumentException(
            "resources." + key + " is required: a size such as " + example + ".");
      }
      return normalizeSize(value);
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
    static Runtimes fromMap(Map<String, Object> map) {
      Fields.only(map, "runtimes", List.of("jdk", "node", "maven"));
      var jdk = Fields.integer(map, "jdk", "runtimes.jdk");
      if (jdk != null && jdk < 1) {
        throw new IllegalArgumentException(
            "runtimes.jdk must be a JDK major version such as 25, got: " + jdk);
      }
      var node = toVersionString(map.get("node"), "runtimes.node");
      var maven = toVersionString(map.get("maven"), "runtimes.maven");
      if (node != null) {
        NameValidator.requireValidVersion(node, "runtimes.node");
      }
      if (maven != null) {
        NameValidator.requireValidVersion(maven, "runtimes.maven");
      }
      return new Runtimes(jdk == null ? 0 : jdk, node, maven);
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      if (jdk > 0) map.put("jdk", jdk);
      if (node != null) map.put("node", node);
      if (maven != null) map.put("maven", maven);
      return map;
    }

    /** A version written as a number or as text, as text; null when unset. */
    private static String toVersionString(Object val, String field) {
      return switch (val) {
        case null -> null;
        case Integer n -> String.valueOf(n);
        case Double d -> d % 1 == 0 ? String.valueOf(d.intValue()) : String.valueOf(d);
        case String s when !s.isBlank() -> s;
        default -> throw new IllegalArgumentException(field + " must be a version such as 24.");
      };
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

    private static final Pattern SCP_URL = Pattern.compile("^[^@/]+@([^:/]+):(.+)$");
    private static final Pattern SSH_URL =
        Pattern.compile("^ssh://(?:[^@/]+@)?([^:/]+)(?::\\d+)?/(.+)$");

    public Repo {
      if (forge != null && !forge.equals(GITHUB)) {
        throw new IllegalArgumentException(
            "repos[].forge must be '" + GITHUB + "' when set, got: " + forge);
      }
    }

    public Repo(String url, String path, String branch) {
      this(url, path, branch, null);
    }

    /** The path a repo is cloned to when none is named: its URL's last part, without .git. */
    public static String pathOf(String url) {
      var name = url.substring(url.lastIndexOf('/') + 1);
      return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
    }

    static Repo fromMap(Map<String, Object> map) {
      Fields.only(map, "repos[]", List.of("url", "path", "branch", "forge"));
      var url = Fields.text(map, "url", "repos[].url");
      if (url == null) {
        throw new IllegalArgumentException("repos[].url is required: the repository to clone.");
      }
      requireHttps(url);
      NameValidator.requireValidGitUrl(url, "repos[].url");
      var path = Fields.text(map, "path", "repos[].path");
      if (path == null) {
        throw new IllegalArgumentException(
            "repos[].path is required: where it is cloned under the workspace.");
      }
      NameValidator.requireSafePath(path, "repos[].path");
      var branch = Fields.text(map, "branch", "repos[].branch");
      if (branch != null) {
        NameValidator.requireValidGitRef(branch, "repos[].branch");
      }
      return new Repo(url, path, branch, Fields.text(map, "forge", "repos[].forge"));
    }

    /**
     * Refuses a repository that is not cloned over https: sail clones with the box's forge token,
     * and a container holds no private key to clone over ssh with.
     */
    private static void requireHttps(String url) {
      if (url.startsWith("https://")) {
        return;
      }
      var https = httpsForm(url);
      throw new IllegalArgumentException(
          "repos[].url '"
              + url
              + "' is not an https URL: sail clones over https with the box's forge token."
              + (https == null ? " Use the repository's https URL." : " Use " + https + "."));
    }

    /** The https URL an ssh-style {@code url} names, or null when it names none. */
    private static String httpsForm(String url) {
      var scp = SCP_URL.matcher(url);
      if (scp.matches()) {
        return "https://" + scp.group(1) + "/" + scp.group(2);
      }
      var ssh = SSH_URL.matcher(url);
      return ssh.matches() ? "https://" + ssh.group(1) + "/" + ssh.group(2) : null;
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
    /** The service named {@code name} as {@code services.<name>} declares it, checked whole. */
    static Service fromMap(String name, Map<String, Object> map) {
      NameValidator.requireValidServiceName(name);
      var field = "services." + name;
      Fields.only(map, field, List.of("image", "ports", "environment", "command", "volumes"));
      var image = Fields.text(map, "image", field + ".image");
      if (Strings.isBlank(image)) {
        throw new IllegalArgumentException(field + ".image is required: the image it runs.");
      }
      return new Service(
          image,
          Fields.integerList(map, "ports", field + ".ports"),
          Fields.textMap(map, "environment", field + ".environment"),
          Fields.text(map, "command", field + ".command"),
          Fields.textList(map, "volumes", field + ".volumes"));
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
    static Process fromMap(String name, Map<String, Object> map) {
      NameValidator.requireValidProcessName(name);
      var field = "processes." + name;
      Fields.only(map, field, List.of("command", "workdir", "port", "env"));
      if (!(map.get("command") instanceof String command) || command.isBlank()) {
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

  /**
   * How the project's agents work: the harness, its branches and snapshots, what it installs, the
   * limits a run is held to and where its events are sent.
   */
  public record Agent(
      String type,
      boolean autoBranch,
      String branchPrefix,
      boolean autoSnapshot,
      List<String> install,
      Map<String, String> config,
      Guardrails guardrails,
      Notifications notifications,
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
          null);
    }

    static Agent fromMap(Map<String, Object> map) {
      Fields.only(
          map,
          "agent",
          List.of(
              "type",
              "auto_branch",
              "branch_prefix",
              "auto_snapshot",
              "install",
              "config",
              "guardrails",
              "notifications",
              "member"));
      var notificationsRaw = Fields.map(map, "notifications", "agent.notifications");
      var memberRaw = Fields.map(map, "member", "agent.member");
      var agent =
          new Agent(
              Fields.text(map, "type", "agent.type"),
              Fields.bool(map, "auto_branch", "agent.auto_branch"),
              Fields.text(map, "branch_prefix", "agent.branch_prefix"),
              Fields.bool(map, "auto_snapshot", "agent.auto_snapshot"),
              Fields.textList(map, "install", "agent.install"),
              Fields.textMap(map, "config", "agent.config"),
              Guardrails.fromBlock(map.get("guardrails"), Guardrails.BUILD_BLOCK).orElse(null),
              notificationsRaw != null ? Notifications.fromMap(notificationsRaw) : null,
              memberRaw != null ? Member.fromMap(memberRaw) : null);
      agent.requireSeatableMember();
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
    private void requireSeatableMember() {
      var declared = declaredMember();
      var agent = memberAgent();
      if (Strings.isBlank(agent)) {
        if (member == null) {
          return;
        }
        throw new IllegalArgumentException(
            "agent.member.agent is unset and agent.type names no agent. Set one of them.");
      }
      if (!installs().contains(agent)) {
        throw new IllegalArgumentException(
            "agent.member.agent '"
                + agent
                + "' is not a harness the project installs ("
                + String.join(", ", installs())
                + "). Add it to agent.install, or name one of those.");
      }
      try {
        if (Strings.isNotBlank(declared.mode())) {
          EngagementMode.of(declared.mode());
        }
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("agent.member.mode: " + e.getMessage(), e);
      }
      try {
        Spec.validatedModel(declared.model());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "agent.member.model '"
                + declared.model()
                + "' is not a model id. Use one without spaces or shell syntax.",
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
      if (member != null) map.put("member", member.toMap());
      return map;
    }
  }

  /**
   * The engagement {@code agent.member} names for a project's home room: each field blank for its
   * default, which {@link Agent#projectMember} fills.
   */
  public record Member(String agent, String model, String mode) {
    static Member fromMap(Map<String, Object> map) {
      Fields.only(map, "agent.member", List.of("agent", "model", "mode"));
      return new Member(
          Fields.text(map, "agent", "agent.member.agent"),
          Fields.text(map, "model", "agent.member.model"),
          Fields.text(map, "mode", "agent.member.mode"));
    }

    public Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      if (agent != null) map.put("agent", agent);
      if (model != null) map.put("model", model);
      if (mode != null) map.put("mode", mode);
      return map;
    }
  }

  /**
   * The limits a run in {@code lane} is held to: {@code pipeline.guardrails} for a reviewer, {@code
   * guardrails} for every other lane, a fix run included, since it runs the project's verification
   * and waits on CI exactly as a build does; each lane's own defaults when its block is absent. The
   * one place a lane is mapped to its limits.
   */
  public Guardrails guardrailsFor(Lane lane) {
    if (lane == Lane.REVIEW) {
      return pipeline != null ? pipeline.guardrails() : Guardrails.reviewDefaults();
    }
    return agent != null && agent.guardrails() != null ? agent.guardrails() : Guardrails.defaults();
  }

  /**
   * The hard lifetime the project sets for a run in {@code lane}, which bounds that run's
   * credential, or null when none does. A reviewer has its lane's — its default when the project
   * writes no block — and every other lane, a fix run included, one only from an {@code
   * agent.guardrails} block the project wrote; a block that names no {@code max_duration} sets
   * none. A run nothing bounds must not lose its credential to a clock mid-work.
   */
  public Duration lifetimeFor(Lane lane) {
    var bounded = agent != null && agent.guardrails() != null || lane == Lane.REVIEW;
    return bounded ? Guardrails.parseDuration(guardrailsFor(lane).maxDuration()) : null;
  }

  /** This definition with {@code services} as its services section. */
  public ProjectDefinition withServices(Map<String, Service> services) {
    return new ProjectDefinition(
        name,
        description,
        resources,
        image,
        packages,
        runtimes,
        repos,
        services,
        processes,
        agent,
        pipeline);
  }

  /** This definition with {@code repos} as its repos section. */
  public ProjectDefinition withRepos(List<Repo> repos) {
    return new ProjectDefinition(
        name,
        description,
        resources,
        image,
        packages,
        runtimes,
        repos,
        services,
        processes,
        agent,
        pipeline);
  }

  /** This definition under the name {@code name}, every section as it is. */
  public ProjectDefinition withName(String name) {
    return new ProjectDefinition(
        name,
        description,
        resources,
        image,
        packages,
        runtimes,
        repos,
        services,
        processes,
        agent,
        pipeline);
  }

  /** This definition with {@code resources} as its resources section. */
  public ProjectDefinition withResources(Resources resources) {
    return new ProjectDefinition(
        name,
        description,
        resources,
        image,
        packages,
        runtimes,
        repos,
        services,
        processes,
        agent,
        pipeline);
  }

  /** Returns absolute repo paths inside the container for the configured repos. */
  public List<String> repoPaths() {
    var base = "/home/" + USER + "/workspace";
    if (repos != null && !repos.isEmpty()) {
      return repos.stream().map(r -> base + "/" + r.path()).toList();
    }
    return List.of(base);
  }

  /** A size as a definition holds it: {@code 4} and {@code "4 GB"} are {@code "4GB"}. */
  public static String normalizeSize(Object val) {
    var s = String.valueOf(val).strip().replaceAll("\\s+", "");
    if (s.matches("^\\d+$")) {
      return s + "GB";
    }
    return s;
  }
}
