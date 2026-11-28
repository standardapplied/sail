# Git Merge vs. Git Rebase

Both `git merge` and `git rebase` integrate changes from one branch into another, but they do it differently. Merge preserves history exactly as it happened, while rebase rewrites it to look linear.

| Aspect | Merge | Rebase |
|:-------|:-----:|------:|
| History | Preserves true branch history | Rewrites into a linear sequence |
| Commits | Creates a new merge commit | Replays commits onto new base |
| Safety | Safe on shared branches | Avoid on shared, already-pushed branches |

---

Typical usage:

```bash
git checkout feature
git rebase main
```

Steps to integrate with merge, instead:

3) Check out the target branch, such as `main`.
4) Run `git merge feature` to create a merge commit that joins both histories.
5) Resolve any conflicts, then commit the result.
