# SAIL Security Audit

## Status
Audit refreshed against current `main` after the SAIL rename. The previous findings still mostly
apply, but the document needed to reflect the current `sail.yaml`-only posture and the current module
names: `sail-core`, `sail-harness`, and `sail-infra`.

No intentional backwards compatibility remains for `sing.yaml` or the `sing` CLI alias. Remaining
`standardapplied/sail` references are GitHub repository slugs, not runtime branding or config aliases.

## Scope
This audit covers the current `sail` CLI and runtime trust boundaries:

- Engineer workstation invoking `sail` locally or through SSH.
- Bare-metal Ubuntu host running Incus and project containers.
- Project containers running developer tooling and agent CLIs as the `dev` user.
- Local API server used by Chorus through an SSH tunnel.
- Spec directories, generated project files, agent session files, logs, webhooks, GitHub tokens,
  API bearer tokens, release assets, installer scripts, and generated handoff/report artifacts.

## Threat Model

### Assets
- Host root and Incus administration access.
- Project container filesystems, repos, specs, snapshots, and agent session state.
- SSH keys, GitHub tokens, webhook URLs, API bearer tokens, and generated logs/reports.
- The engineer's ability to approve and dispatch autonomous agents safely.

### Trust Boundaries
- CLI arguments and `sail.yaml` configuration cross from the engineer workstation into host and
  container command execution.
- Spec content crosses from project data into agent prompts and dispatch flows.
- Local API requests cross from Chorus into `sail` host operations through bearer-token
  authentication.
- Agent output crosses from untrusted model/tool execution into PR, handoff, report, and review
  workflows.
- Release artifacts cross from GitHub Releases into local binaries through `install.sh`,
  `sail upgrade`, and the silent auto-upgrader.

### Primary Risks
- Command injection through interpolated paths, branches, spec IDs, repo paths, repo hosts, or
  generated task content.
- Exposing the local API beyond loopback without intentional operator opt-in.
- Token disclosure through permissive token-file permissions, symlinks, logs, errors, process
  arguments, git credential stores, or command output.
- Cross-project access through path traversal, invalid container names, unsafe generated SSH
  configuration, or ambiguous Incus matches.
- Prompt injection in specs or generated handoff content causing unsafe automation or misleading
  reviewer agents.
- Supply-chain drift in Maven dependencies, GitHub Actions, installer scripts, release artifacts,
  and agent CLI bootstrap commands.

## Findings Fixed In This Audit

### Process timeouts now apply while stdout is open
`ShellExecutor` previously read stdout to EOF before waiting for the process timeout. A process that
kept stdout open could bypass the timeout and hang the caller. Stdout and stderr are now drained
asynchronously while the process timeout is enforced first.

### Webhook URLs are redacted from warning logs
Webhook failure paths no longer print the full webhook URL. Slack and Discord webhook paths are
secret-bearing, so logs now include only the scheme and host.

### Incus container names are validated at the command builder
`ContainerExec.asDevUser` now validates container names before constructing `incus exec` commands.
This makes the shared command builder defensive even when a future caller forgets to validate input.

### Repo hosts are validated before SSH known-host scanning
The provisioning path no longer interpolates repository hosts into a `bash -c` `ssh-keyscan`
command. Hosts are validated and passed as positional arguments.

### Installer checksum verification is mandatory
`install.sh` now refuses to install a release asset when the matching `.sha256` asset is missing.
The previous behavior skipped verification if the checksum could not be fetched.

### CI workflow permissions are read-only
The CI workflow now declares `contents: read`, reducing default GitHub token permissions for normal
verification and dependency scanning.

## Existing Controls Still Valid

### API bind address requires explicit remote opt-in
`sail server start` refuses to bind to non-loopback addresses unless `--allow-remote` is supplied.
This keeps the default local API posture local-first even when an operator mistypes `--host 0.0.0.0`.

### API token file hardening
The API token store rejects non-regular token paths, reapplies owner-only permissions before reusing
existing tokens, and creates new token files with restrictive POSIX permissions when the filesystem
supports them.

### Duplicate bearer headers are rejected
The API authenticator rejects requests with multiple `Authorization` headers instead of relying on
the first header value.

### API request bodies are bounded
JSON request bodies are capped at 64 KiB and must use `application/json` when a content type is
present.

### Project, spec, repo path, branch, service, snapshot, runtime, and SSH-user inputs are validated
The shared validators reject traversal, absolute paths, invalid names, and unsafe branch/path
characters before values reach Incus, Git, or filesystem operations.

### Shell interpolation has been reduced in write and launch paths
Spec writes, task writes, session writes, and agent launch commands pass dynamic values as
positional arguments to `bash -c` instead of concatenating paths or content into shell scripts.

### Git credentials are not embedded in clone command arguments
Project provisioning writes `.git-credentials` inside the container with `0600` mode and configures
Git's credential helper, avoiding token-bearing clone URLs in process arguments.

### Webhook configuration blocks private network targets
Notification URLs parsed from `sail.yaml` are restricted to HTTP(S) and reject private, loopback,
link-local, and unresolvable hosts.

### Dependency vulnerability scanning runs in CI
CI runs OWASP Dependency-Check with the repository `NVD_API_KEY` secret, fails builds for CVSS 7+
findings, skips test-scope dependencies, installs reactor modules before the aggregate scan, caches
NVD data, and uploads HTML reports.

## Accepted Risks

### Agent install commands remain enum-controlled shell snippets
Agent CLI install commands still run through shell because npm-based global installs and vendor
install scripts are shell-oriented. The command strings are enum-controlled, not user-provided. If
agent installation becomes user-extensible, move to typed installers before accepting arbitrary
commands.

### Agent dispatch intentionally gives coding agents broad power
Foreground/background launch paths can grant full agent permissions. This is an operational trust
decision, not a parser boundary. Dispatch must remain an explicit engineer action, and model output
must be treated as untrusted.

### Spec content remains untrusted prompt input
Spec markdown is intentionally passed to coding agents. Reviewer, audit, handoff, and report flows
must not treat spec text or agent output as authoritative instructions.

### API transport is local/tunnel-only by design
The API uses bearer-token authentication but does not provide TLS itself. The intended deployment is
loopback plus SSH tunnel. Remote binding requires explicit `--allow-remote` and should sit behind a
trusted transport.

### Some secrets can still be supplied as process arguments
Flags such as `--git-token`, `--github-token`, and `sail api --token` are explicit operator
escape hatches. Prefer environment variables, interactive prompts with echo disabled, or token files
for sensitive values.

### Release assets are checksum-verified but not signed
`install.sh`, `sail upgrade`, and the auto-upgrader verify SHA-256 checksums, but the checksum is
fetched from the same GitHub release channel as the binary. This protects against corruption and
partial tampering, not a compromised release account or workflow.

### GitHub Actions are version-pinned by major tag, not commit SHA
Actions currently use major-version tags such as `actions/checkout@v4`. This is conventional, but
not a maximum supply-chain posture.

## Hardening pass — 2026-06-12 (0.13.59)

A six-dimension audit (secret leakage, authn/authz, injection, hand-rolled WebAuthn crypto,
web/file-perms/SSRF, credential lifecycle) over the auth surface. Confirmed clean: SQL fully
parameterized, command execution argv-based (no shell injection), authorized_keys fail-closed,
tokens/sessions/tickets stored only as SHA-256 of 256-bit `SecureRandom` values, WebAuthn
signatures genuinely verified with constant-time challenge/rpId compares, algorithm-confusion
blocked, signCount anti-clone correct, `redirect_uri` loopback validation not bypassable, no
reflected/DOM XSS. Fixed:

- **Authorization fails safe.** `Role.fromAttribute` mapped a missing/blank role to ADMIN; now
  every unknown value resolves to VIEWER. `TokenStore.create` validates the role. (Both role
  columns are already `NOT NULL`+CHECK, so this is defense in depth.)
- **Session auth requires an active FDE.** `SessionAwareAuth` now rejects a session whose FDE is
  not `active`, matching the SSH gateway.
- **WebAuthn parser hardening.** CBOR rejects an array whose declared count exceeds the remaining
  input (allocation-bomb DoS); RSA COSE keys are bounded to 2048–8192-bit moduli with an odd
  exponent ≥ 3; `clientDataJSON` is parsed strictly (duplicate keys rejected) to remove a
  parser-divergence avenue on the signed fields.
- **Enrollment tickets are atomically single-use.** `EnrollmentTicketStore.consume` uses
  `UPDATE … WHERE consumed_at IS NULL RETURNING`, and the handler consumes *before* registering,
  closing a race that could enroll multiple passkeys from one ticket.
- **Token DB unreachable to other local users.** The per-user `~/.sail` data directory is created
  `0700` (`SailPaths.ensureDataDir`); the group-shared `/var/lib/sail` is left to provisioning.
- **FDE handles validated at creation** (`NameValidator.requireValidFdeHandle`), so a malformed
  handle can never reach the `authorized_keys` renderer (where it would fail-closed the whole-file
  regeneration and lock out key sync).
- **Webhook SSRF resolves and range-checks bytes.** `WebhookUrlSafety` now resolves the host and
  checks each address (incl. IPv4-mapped IPv6 like `::ffff:169.254.169.254`, full 169.254/16,
  CGNAT 100.64/10, ULA fc00::/7) instead of string prefixes; fail-closed on resolution failure.
- **No-store on passkey responses** so a session-token body is never cached.

Open items deliberately deferred (need a product decision or larger change, tracked separately):
- **Route privilege tiers.** The main router maps mutating verbs to WRITE, so a `member` can
  dispatch agents and approve reviews. Whether dispatch/review-approve/spec-delete should require
  ADMIN is a product decision, not a safe unilateral change.
- **API token expiry/rotation** and a **background sweep** of expired sessions/tickets/challenges
  (currently enforced on read, pruned lazily).
- **WebAuthn `userHandle` binding** at finish (defense in depth; route-layer authz already
  prevents takeover).
- `--token` on the CLI is visible via `/proc`/shell history; prefer `SAIL_TOKEN`/config.

## Recommended Follow-Ups
- Add release signing with cosign or minisign and verify signatures in `install.sh`, `sail upgrade`,
  and the auto-upgrader.
- Pin GitHub Actions by commit SHA once the release process stabilizes.
- Add a small API request-rate guard if the API is expected to stay open for long-lived Chorus
  sessions.
- Consider replacing npm/global shell install snippets with typed installer implementations.
- Add a periodic secret-scan job for generated examples, scripts, and release artifacts.

## Verification
- Focused regression suite:
  `mvn -pl sail-core,sail-infra,sail-harness -am -Dtest=ShellExecutorTest,ContainerExecTest,WebhookNotifierTest,ProjectProvisionerTest -Dsurefire.failIfNoSpecifiedTests=false test`
- Existing focused API/session suite:
  `mvn -pl sail-infra,sail-harness -am -Dtest=ApiTokenStoreTest,ApiRouterTest,ApiCommandTest,SpecWorkspaceTest,AgentSessionTest -Dsurefire.failIfNoSpecifiedTests=false test`
- Full verification:
  `mvn clean verify`
- CI dependency scan:
  `mvn install org.owasp:dependency-check-maven:12.1.8:aggregate -DskipTests -Djacoco.skip=true -Dformat=HTML -DfailBuildOnCVSS=7 -DskipTestScope=true -DnvdApiKey=${{ secrets.NVD_API_KEY }} -DdataDirectory=${{ runner.temp }}/dependency-check-data`
- Native packaging:
  `mvn package -Pnative -DskipTests` requires GraalVM locally; CI remains the authoritative
  native-image check when the local shell uses Temurin.
