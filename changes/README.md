One file per change: a pull request adds changes/<short-slug>.md (its spec id, or a short phrase) and never adds to CHANGELOG.md.
The file holds the change's entry as it would read under the version heading: one or more `- **…** …` bullets.
The release folds every file into CHANGELOG.md under its version with `ci/changelog X.Y.Z`, in the version bump's commit.
