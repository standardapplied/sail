One file per change: a pull request adds changes/<short-slug>.md (its spec id, or a short phrase), directly in this folder, and never edits CHANGELOG.md.
The file holds the change's entry as it would read under the version heading: one or more `- **…** …` bullets, with LF line ends.
The release folds every file into CHANGELOG.md with `ci/changelog X.Y.Z` in the version bump's commit, on a full clone, oldest first by the commit that added each (renaming a file re-dates it).
