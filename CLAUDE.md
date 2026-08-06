# CLAUDE.md

## Why `sail` Exists

Engineers working on multiple projects simultaneously need fully isolated dev environments: separate JDKs, databases, search engines, message brokers, and AI coding agents — with hard isolation so a runaway agent in one project can't affect another.

`sail` provisions a bare-metal server with Incus system containers, one per project. Each container gets a complete Ubuntu 24.04 userspace with its own filesystem, network stack, and rootless Podman runtime. The CLI manages container lifecycle, runs AI agents inside them with guardrails and rollback safety, and orchestrates spec-driven autonomous workflows.

One binary, zero dependencies, fully declarative. Download it, run `sail host init`, and you're operational.

## Tech Stack

- **Java 25** with virtual threads where applicable
- **picocli** for CLI framework (GraalVM native-image ready)
- **SnakeYAML Engine 3.0.1** for YAML + JSON parsing (YAML 1.2 is a JSON superset). Zero transitive dependencies.
- **GraalVM native-image** for AOT compilation to a static Linux binary (<1ms startup)
- No Spring. No Lombok. No annotation magic beyond picocli's `@Command`/`@Option`/`@Parameters`.
- Minimal dependencies — two libraries total (picocli, SnakeYAML Engine). No reflection metadata needed.
- File generation (agent context files, ssh-config) uses plain Java string building — no templating library.

## Infrastructure Inside Containers

- **Podman** (rootless, daemonless) runs infrastructure services. No Docker, no Docker Compose.
- Each service in `sail.yaml` becomes a `podman run -d --restart=always` command. `loginctl enable-linger` keeps services alive across reboots. No systemd unit generation needed.
- Testcontainers works via Podman socket (`DOCKER_HOST`, `TESTCONTAINERS_RYUK_DISABLED=true`).
- Developer processes (`java -jar`, `npm run dev`) are interactive — run in editor terminal tabs, not managed by `sail`.

## Conventions

- Records for all data models / DTOs
- Sealed interfaces for domain types and algebraic data modeling
- Virtual threads for all I/O, never platform threads
- `ProcessBuilder` for shell execution — no runtime exec shortcuts
- Every command must be idempotent — run it twice, same result
- `--dry-run` support on every command that modifies state (prints underlying shell commands instead of executing)
- `--json` flag on every command for machine-parseable output
- Human-readable ANSI-colored output by default, degrade gracefully when not a TTY
- No magic. Every `sail` command maps to a small number of `incus` / `podman` / `systemd` calls that the user could run themselves
- Error messages must explain what happened AND what to do about it

## Code Comments Policy

**No inline comments in code, ever.** This applies to all Java source files and test files:
- No standalone comment lines (`// explanation`) inside method bodies or test methods
- No end-of-line comments (`code; // explanation`)
- No section separator comments (`// ── section name ──`) in test files — use method naming and class structure for organization instead
- The only acceptable comments are Javadoc on public APIs and the license header
- If code needs a comment to be understood, the code is too complex — simplify it, rename variables, or extract a method

## Java Coding Style

Always write modern Java — leverage JDK 25 features, never write old-style code.

- **`var` everywhere:** Use `var` for all method-scoped local variables with immediate initialization. Only use explicit types for late initialization (declared on one line, assigned later) or when the type is ambiguous from the RHS.
- **Text blocks:** Use `"""` for any multi-line string (YAML, JSON, SQL, shell scripts). Never use `String.join("\n", ...)` or `+` concatenation for multi-line content.
- **Immutable collections:** Use `List.of()`, `Set.of()`, `Map.of()` for constants and returns. Only use `new ArrayList<>()` / `new HashMap<>()` for mutable accumulators.
- **Records:** Use records for all value types — DTOs, results, events, config objects. No getters/setters boilerplate.
- **Pattern matching:** Use `instanceof` pattern matching (`if (x instanceof Foo f)`), switch expressions with patterns where applicable.
- **`Objects` utility:** Prefer `Objects.requireNonNullElse(x, default)` over manual null ternaries. Prefer `Objects.toString(x, "")` over `x != null ? x : ""`.
- **Streams:** Use streams for transforms, filters, and aggregations. Keep imperative loops for simple iteration with side effects or early returns.
- **Try-with-resources:** Always use for anything `AutoCloseable`. Never manual try/finally for resource cleanup.
- **No old APIs:** No `Date`/`Calendar` (use `java.time`). No `Vector`/`Hashtable`. No `StringBuffer` (use `StringBuilder` or text blocks). No `Runtime.exec()` (use `ProcessBuilder`).

## Key Design Decisions to Maintain

1. **No tmux.** Developer processes are interactive (editor terminal tabs). Infrastructure services use Podman `--restart=always` + linger. SSH remote dev provides persistent terminals.
2. **Dir default, ZFS opt-in.** `dir` backend works on any server (no spare disk needed). ZFS `refquota` provides hard disk limits when a spare disk is available (`--storage zfs --disk /dev/sdX`). Dir quotas are advisory — warned in output.
3. **Idempotent everything.** Every command checks current state before acting. `sail up` on an already-running container is a no-op, not an error.
4. **Declarative config.** `sail.yaml` is the source of truth. The container is derived state that can be destroyed and recreated.
5. **Zero network assumptions.** `sail host init` detects disks, network interfaces, and available resources. No hardcoded device paths or IPs.
6. **Agent context: sail owns the home layer, the engineer owns the workspace.** Both Claude Code and Codex natively merge a home-level context file with project-level files, so sail writes its generated layer to the home namespace (`~/.claude/CLAUDE.md`, `~/.codex/AGENTS.md`) and overwrites it every run, while the engineer owns `~/workspace/CLAUDE.md`/`AGENTS.md` outright — sail never creates or touches it, and (being "closer") it overrides sail's layer on conflict. No `@import` pointer, no `--force`. `sail agent context regen` re-overwrites only sail's home layer.
7. **Database-backed project sync.** The main box stores project descriptors and shared workspace files in SQLite. Node boxes reconcile that state through the sync protocol; project configuration does not travel through a Git repository or a merged global descriptor.

## GraalVM Native Image Build

- Build command: `JAVA_HOME=/path/to/graalvm-jdk-25 mvn clean package -Pnative -DskipTests`
- The `native` Maven profile uses `native-maven-plugin` 0.11.4 with `compile-no-fork` goal
- Binary output: `sail-infra/target/sail` (<1ms startup)
- **No manual reflection metadata needed.** SnakeYAML Engine parses to `Map<String, Object>`, and records use explicit `fromMap()`/`toMap()` factory methods — no reflection involved. picocli's annotation processor (`picocli-codegen`) auto-generates its own native-image metadata at compile time for command classes.
- Maven plugin guide: https://graalvm.github.io/native-build-tools/latest/end-to-end-maven-guide.html

## Dependency Versions (pinned)

| Dependency | Version | Group ID |
|-----------|---------|----------|
| picocli | 4.7.7 | info.picocli |
| snakeyaml-engine | 3.0.1 | org.snakeyaml |
| JUnit | 6.0.2 | org.junit |
| native-maven-plugin | 0.11.4 | org.graalvm.buildtools |

## How It Works Under the Hood

`sail` is an orchestrator. It does not implement container management or virtualization — it drives `incus`, `podman`, `systemd`, and `ssh` via shell commands through `ShellExecutor`.

- **Containers:** Incus system containers (not VMs). Each project is a full Ubuntu 24.04 userspace with its own filesystem, network, and Podman runtime. Storage backend is `dir` (default, host filesystem) or `zfs` (hard quotas via `refquota`).
- **Services:** Postgres, Meilisearch, Redpanda run as rootless Podman containers inside the Incus container with `--restart=always`. `loginctl enable-linger` keeps them alive across reboots.
- **Developer processes:** Application JVMs and Node dev servers are interactive — developers run them in editor terminal tabs. Not managed by `sail` or systemd.
- **Editor:** SSH remote dev into the container. No tmux needed — editor terminal tabs persist across reconnects.
- **AI Agent:** Agent-agnostic design. Supports Claude Code and Codex. Container snapshots provide rollback safety. The `agent` block in `sail.yaml` declares the agent type and its config. Spec-driven workflows via `sail dispatch`.
