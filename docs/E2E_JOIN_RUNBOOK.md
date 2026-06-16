# E2E Runbook — Main ↔ Node Join & Sync

A hand-driven test of the db-sync star: one **main** devbox (the sync authority) and
one **node** that joins it, reconciles specs and files, and survives conflicts and
outages. Drive it once by hand to feel the flow before we automate it.

> **Why two boxes.** There is exactly one `/var/lib/sail` and one `sail` OS user per
> machine, so you cannot fake two mains on one host. Use two real boxes: two Incus
> containers on the bare-metal host, or two VMs. Anything with its own `~/.sail`,
> its own `/var/lib/sail`, and SSH reachability to the other works.

Throughout: **MAIN** = the authority, **NODE** = the joiner. Run each block on the
box named in its heading. Replace `<main-ip>` with MAIN's address and `bob` with the
node engineer's handle.

> **Security model — it's SSH keys, end to end.** A node syncs by running `ssh
> sail@<main> sail _sync` over the locked `sail` user's forced-command gateway
> (`command="sail _gateway --fde <handle>",restrict` — no shell, no port-forward).
> `sail join` generates a dedicated, sail-managed key (`~/.sail/sync_ed25519`) and the
> sync lane pins it with `ssh -i … -o IdentitiesOnly=yes`. The **only** thing that
> crosses out of band is a *public* key — never a secret — so there is no network
> enrolment surface to attack.

---

## 1. MAIN — become the authority

```bash
# MAIN
sudo sail host init                  # one-time: control plane + container host
sudo sail host ssh-identity          # provision the locked `sail` user + /var/lib/sail
sudo sail host sync --as-main        # role = main (the sync authority)
sail host sync                       # verify: "This box is main."
```

---

## 2. NODE — one-command join

```bash
# NODE
sudo sail host init                  # one-time control plane (no ssh-identity needed —
                                     # a node only syncs outbound to main)
sail join sail@<main-ip>             # generates the sync key, points this box at main,
                                     # and prints the exact line for MAIN to authorize
```

`sail join` prints something like:

```
✓ This box is now a node syncing to sail@<main-ip>.

  Send this to the operator of sail@<main-ip> — they run it once:

    sail fde add bob --role member --key "ssh-ed25519 AAAA… sail-sync@node"

  Once they have, finish here with sail sync. Only a public key leaves this box.
```

(`--handle <name>` overrides the suggested handle; `--json` emits `public_key` +
`authorize_command` for scripting.)

## 3. MAIN — authorize the node (one command)

Paste the line `sail join` printed. `fde add --key` registers the key **and** refreshes
`authorized_keys` in one step (`member`+ can push; a `viewer` can only pull):

```bash
# MAIN
sail fde add bob --role member --key "ssh-ed25519 AAAA… sail-sync@node"
sail fde list                        # verify: bob, role member, status active
```

## 4. NODE — prove the transport, then reconcile

```bash
# NODE — gate: should authenticate as `sail` and start the RPC server (it hangs
# waiting for bytes; Ctrl-C is fine). Must NOT see a password prompt,
# "Permission denied (publickey)", or a shell.
ssh -o PasswordAuthentication=no -o IdentitiesOnly=yes -i ~/.sail/sync_ed25519 \
    sail@<main-ip> sail _sync
# Ctrl-C to exit.

sail sync                            # first reconcile
# expect: "Synced with main: N pulled, 0 pushed, 0 merged." (N = specs already on main)
sail sync                            # run again
# expect: "Already in sync with main."   ← idempotency (assertion 8)
```

---

## 5. The assertion matrix

Each row is a distinct code path. Run the action on the box named, then `sail sync`
**on NODE**, then check. `sail sync --json` prints `{pulled, pushed, merged, conflicts}`
if you'd rather assert on numbers.

### 1. Pull (main → node)
```bash
# MAIN
sail spec create pull-demo --title "Pull works"
# NODE
sail sync                            # expect pulled ≥ 1
sail spec list                       # expect pull-demo present
```

### 2. Push (node → main), member can write
```bash
# NODE
sail spec create push-demo --title "Push works"
sail sync                            # expect pushed ≥ 1
# MAIN
sail spec list                       # expect push-demo present
```

### 3. Auto-merge (disjoint fields)
```bash
# MAIN
sail spec edit pull-demo --assignee alice
# NODE  (edit a DIFFERENT field on the same spec, before syncing)
sail spec edit pull-demo --title "Pull works (node-edited)"
sail sync                            # expect merged ≥ 1, conflicts = 0
sail spec show pull-demo             # expect BOTH: assignee=alice AND the node title
```

### 4. Conflict (same field), node row not clobbered
```bash
# MAIN
sail spec edit push-demo --title "Main's title"
# NODE  (same field, different value, before syncing)
sail spec edit push-demo --title "Node's title"
sail sync                            # expect conflicts ≥ 1
sail spec show push-demo             # expect STILL "Node's title" — local not clobbered
sail conflicts                       # expect push-demo listed; resolve it (take main or local)
sail sync                            # expect convergence; conflicts = 0 afterward
```

### 5. Files materialize; local edits are kept
```bash
# MAIN
echo "shared config v1" > /tmp/shared.env
sail project files add --project <proj> /tmp/shared.env --as config/shared.env
# NODE
sail sync
sail project files ls --project <proj>          # expect config/shared.env
cat ~/.sail/projects/<proj>/config/shared.env   # expect "shared config v1"

# Now prove a local edit is preserved, not overwritten:
# NODE
echo "node local change" >> ~/.sail/projects/<proj>/config/shared.env
# MAIN
echo "shared config v2" > /tmp/shared.env
sail project files add --project <proj> /tmp/shared.env --as config/shared.env
# NODE
sail sync     # expect a "Kept 1 locally-modified file(s)" warning; your edit survives
```

### 6. FDE roster replication
```bash
# NODE
sail fde list                        # expect main's FDEs (incl. bob) mirrored here
```

### 7. Role enforcement (viewer is pull-only)
```bash
# MAIN
sail fde add viewer1 --role viewer --email v@example.com
sail fde key add viewer1 "<a second NODE key, or a throwaway box's key>"
sudo sail host keys sync
# From the box holding viewer1's key:
sail spec create blocked --title "should not push"
sail sync                            # expect: pull works, but the push is REFUSED
```

### 8. Idempotency — already verified in §4 ("Already in sync").

### 9. Resilience across a main outage
```bash
# NODE
sail sync --watch --interval 10      # leave running
# MAIN — take it offline (stop sshd, or pause the container)
# MAIN — meanwhile create a spec:  sail spec create after-outage --title "post outage"
# MAIN — bring it back
# NODE — watch loop should retry, resume from checkpoint, pull after-outage, no dupes
#        (Ctrl-C the watch when satisfied)
sail spec list                       # expect after-outage present exactly once
```

### 10. Attribution
```bash
# After any pull, on NODE:
sail spec history pull-demo          # expect updates attributed to the real author,
                                     # NOT to "sync"
```

### 11. board_updated notification
```bash
# NODE — in a second shell, before a pull that brings remote work:
sail events stream                   # leave running
# Trigger a pull (do assertion 1 again with a new spec). Expect a board_updated
# event on the stream, and the CLI to surface an "updates available" hint.
```

---

## Reset between runs

```bash
# On EACH box, to start clean (DESTRUCTIVE — wipes the control-plane DB):
sail spec list                       # note what exists first if you care
rm -f /var/lib/sail/sail.db          # or ~/.sail/sail.db on a non-provisioned box
# Re-run host init / host sync / sail join as in sections 1–4.
```

To change a box's role without wiping: `sudo sail host sync --as-main` makes it the
authority, `sail join sail@<other>` (or `sudo sail host sync --main sail@<other>`)
re-points it at a main; `sail host sync` with no flags prints the current role.

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `Permission denied (publickey)` on the gate (§4) | The operator hasn't run the `sail fde add … --key` line `sail join` printed (or ran it against a different handle). `fde add --key` auto-syncs `authorized_keys`, so no separate step is needed. |
| Gate gives a password prompt | The `sail` user isn't the one answering — check `sudo sail host ssh-identity` ran on MAIN and `authorized_keys` has the `command="sail _gateway --fde …"` line. |
| `sail sync` says "Single devbox — nothing to sync" | `sail join …` wasn't run on the node (no main configured). |
| Push silently does nothing on a node | The FDE's role is `viewer`. Bump it: re-run the authorize line with `--role member`. |
| Conflicts never clear | Resolve them: `sail conflicts` then re-`sail sync`. A round only converges once same-field disputes are decided. |

---

## What "exceptional" looks like before we announce

- All 11 rows pass on two real boxes, twice (cold join + reset-and-rejoin).
- The gate (§4) and the role-refusal (§7) fail **loudly and clearly** — those are the
  two errors a new community node-runner will hit first.
- Then we codify §5 as an automated harness (provision two throwaway boxes → run matrix
  → assert → tear down) so it becomes a release gate and a demo.
