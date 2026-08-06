# Contributing to sail

Build and validate with the same command CI runs:

```bash
mvn clean verify
```

Coverage and quality gates are ratchets — see [QUALITY.md](QUALITY.md). Design context
lives in [ARCHITECTURE.md](ARCHITECTURE.md), and coding conventions in
[CLAUDE.md](CLAUDE.md).

## Database migration policy

The SQLite schema is versioned by `SchemaManager` in `sail-core`. The rules every change
inherits:

- **Append-only within a major version.** New migrations are added after the current
  baseline and are never reordered, edited, or removed once released. If a migration is
  wrong, append another one that fixes it.
- **Every migration ships with a seeded-data test.** The test stages a database at the
  prior version, seeds representative rows, migrates, and asserts the rows survived.
- **Baselining happens only at a major version with a published floor.** The floor's
  final schema and the new baseline must be structurally identical — proven by a
  `sqlite_master` diff test against the captured floor fixture (`FloorSchema`) — the
  on-ramp from the floor is a single version stamp, and anything below the floor is
  refused with an error naming the release that can still carry it forward. The v1
  baseline's schema floor is v118, produced by sail 0.14.x. Fleet sync requires sail
  0.15.0 or later.
- **No downgrade path.** Schema changes are forward-only; the floor mechanism above is
  the only supported way to cross a baseline.
- **One-shot data fix-ups are not schema migrations.** They belong in the
  `DataMigration` framework, run exactly once, and are tracked by name in
  `data_migrations`.
