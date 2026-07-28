# Changelog

## Unreleased

One identity model: every agent session is a run.

- `sail agent run --task` and `sail agent sweep` now mint a first-class run
  (`role='adhoc'`, no spec) with a run-scoped unit, log, and pid file, and reserve the whole
  container through the same atomic transaction as dispatch — an ad-hoc session and a
  dispatched agent can no longer run beside each other, in either order.
- The fixed `sail-agent` unit and `~/.sail/agent.*` files are gone. Stop any running agent
  session before upgrading: a fixed-unit ad-hoc session launched by an older binary is
  invisible to this version's run-scoped stop, status, and log commands and must be stopped
  by that older binary (or by hand) first.
- `sail agent stop` on an ad-hoc session now reports its run id; `sail agent status`,
  `agent logs`, and `agent report` resolve ad-hoc sessions through their run rows like any
  dispatch. The `run_not_active` stop reason no longer occurs: run-scoped identities made
  the pid-identity-theft guards obsolete.
- Foreground sessions write their pid to the run's pid file, record the same run-scoped
  unit name on their run row, and persist their process identity, so they are probeable,
  stoppable, and reconcilable through the same identity as background ones — a crashed
  foreground launcher no longer strands its whole-container reservation, and a launch
  failure never frees the reservation while the agent still probes live.
- Runs persist the agent process's `/proc` start-time fingerprint at launch; a stop refuses
  to signal a pid whose fingerprint no longer matches, so an in-container PID reused after
  the agent exits can never be killed by a stale run.
- The watcher re-armer walks live session runs (build and ad-hoc alike) instead of
  in-progress specs, so a crashed watcher over an ad-hoc background session is replaced
  within a pass; its probe is systemd-strict, so a foreground session is never armed with
  guardrails it was not launched with.
- `sail agent run` regenerates the shared home-level agent context only after the
  whole-container reservation is won, so a refused launch leaves the running agent's
  instructions and skills untouched.
- Review runs record their real execution identity (`sail-review-<id>`) instead of a blank
  unit.
- The missed-stop sweep releases a spec-less run only on probed evidence of death and never
  releases any reservation while its agent's identity still probes live.

## 0.14.0

Version 0.14.0 is the v1 upgrade floor. Upgrade every box in a fleet to 0.14.0 before
installing a later v1 release.

- Legacy runs, specs, projects, and reviews are carried to the current data shape by `sail
  migrate`.
- Legacy build runs without a run-scoped unit are stopped; foreground review runs are unchanged.
- Synced entities receive content-addressed baseline revisions once.
- API tokens owned by an FDE now cascade when that FDE is removed.
- Sync refuses a peer below 0.14.0 before exchanging data.
