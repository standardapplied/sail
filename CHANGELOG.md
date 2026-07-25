# Changelog

## 0.14.0

Version 0.14.0 is the v1 upgrade floor. Upgrade every box in a fleet to 0.14.0 before
installing a later v1 release.

- Legacy runs, specs, projects, and reviews are carried to the current data shape by `sail
  migrate`.
- Legacy build runs without a run-scoped unit are stopped; foreground review runs are unchanged.
- Synced entities receive content-addressed baseline revisions once.
- API tokens owned by an FDE now cascade when that FDE is removed.
- Sync refuses a peer below 0.14.0 before exchanging data.
