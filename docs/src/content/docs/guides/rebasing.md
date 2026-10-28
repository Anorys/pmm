---
title: Branches and rebasing
description: Branch freshness, conflict prediction and the safety rules of pmm rebase.
---

## How the git status is read

`pmm` runs the `git` executable, so your credentials, SSH keys, proxy and Git Credential Manager
settings apply. Credential prompts are disabled (`GIT_TERMINAL_PROMPT=0`): a fetch that needs a
password fails with a `git.fetch-failed` finding instead of hanging, and the status then uses the last
fetched state.

"Behind" counts the commits of the base branch missing from `HEAD`; its age is the commit date of the
oldest missing one. A branch is flagged (`git.behind` severity) once it is `maxCommits` behind or that
commit is `maxDays` old; a smaller lag is reported as information only.

```shell
pmm status                  # fetch, then branch, ahead/behind, local changes
pmm sync                    # nothing to do, fast-forward, or rebase (with predicted conflicts)
pmm sync --fast-forward     # fast-forwards branches with no commit of their own and no local change
```

## How rebasing stays safe

`pmm rebase` refuses to run:

- on a protected branch (`rebase.protectedBranches`; `*` matches within one path segment, `**`
  across),
- on a detached HEAD,
- while a rebase or merge is in progress,
- with uncommitted changes, unless `--autostash` or `rebase.autostash`.

Before rebasing it saves the branch as `refs/pmm/backup/<branch>/<timestamp>` and prints the command
that undoes the rebase. If the rebase stops on conflicts, it is aborted and the branch is left exactly
as it was; `--keep-conflicts` leaves it in progress instead, to resolve by hand.

The conflicts are predicted beforehand with `git merge-tree` (git 2.38 or later), which does not touch
the working tree: `pmm sync` and `pmm rebase --dry-run` show them.

```shell
pmm rebase billing --dry-run   # the plan and the predicted conflicts, nothing changed
pmm rebase billing             # backup, rebase, then ask before pushing
pmm rebase billing reporting --push
```

## Pushing

Pushing a rebased branch rewrites its remote history, so it is never done silently. `rebase.push` is:

| Value | Behaviour |
|---|---|
| `never` | Never pushes |
| `ask` (default) | A yes/no question in a terminal; no push when there is no terminal, as in CI |
| `always` | Always pushes |

`--push` and `--no-push` override it. The push uses `--force-with-lease` with the remote state read
before the rebase: if anyone pushed to the branch since, the push is refused instead of overwriting
their commits.
