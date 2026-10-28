---
title: Branches, updates and switching
description: Branch freshness, protected branches, the update strategies (fast-forward, merge, rebase, reset), switching branches with your changes parked or carried, and the safety rules behind them.
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

## Protected branches: push and update

Protection has two sides, set by globs (`*` matches within one path segment, `**` across):

| Setting | Default | What pmm never does on these branches |
|---|---|---|
| `branches.pushProtected` | `develop`, `main`, `master`, `release/*` | Push them, or rewrite a commit that is already on their remote |
| `branches.updateProtected` | none | Change them locally: no fast-forward, merge, rebase or reset |

So the local `develop` can always take the new commits of `origin/develop`: a fast-forward, or a rebase
of commits you have not pushed yet, rewrites nothing that is published. What stays refused on a
push-protected branch is a rebase or reset that would rewrite commits already on its upstream, and any
push. `rebase.protectedBranches`, the older setting, is still read as `branches.pushProtected`.

## Updating a branch

`pmm update` (the `u` key of the dashboard) brings the commits of another branch into the current one,
with the strategy you choose. The target is the branch's **upstream** (`origin/develop` for `develop`,
`origin/feature/x` for `feature/x`) or, with `--from base` (`t` in the dashboard), its **base branch**.

| Strategy | What it does |
|---|---|
| `ff` | Moves the branch forward; refused if it has commits of its own |
| `merge` | A merge commit bringing the target's commits (a fast-forward when there is nothing to merge) |
| `rebase` | Replays the branch's own commits on top of the target |
| `reset-soft` | Points the branch at the target; the changes of its own commits stay staged (to squash them) |
| `reset-mixed` | The same, with the changes left unstaged in the working tree |
| `reset-hard` | Makes the branch exactly the target: its own commits and uncommitted changes are dropped |

Without `--strategy`, pmm uses the first glob of `branches.strategies` that matches the branch, else
`ff` on push-protected branches and `rebase` elsewhere; when that one cannot run, the first of `ff`,
`rebase` and `merge` that can. `pmm update --dry-run` (and the dashboard's menu) shows every strategy
with what it would do, or why it is refused.

Before anything but a fast-forward, the branch is saved as `refs/pmm/backup/<branch>/<timestamp>`. A
hard reset also saves the uncommitted changes of tracked files as `refs/pmm/wip/<branch>/<timestamp>`
(`git stash apply <ref>` brings them back), and asks first (`--yes` on the command line). A merge or
rebase that stops on conflicts is aborted, so the branch is left as it was. Uncommitted changes are
stashed around a fast-forward, merge or rebase with `--autostash` (`a` in the dashboard).

```shell
pmm update                                  # every repository from its upstream, default strategy
pmm update billing --dry-run                # what each strategy would do
pmm update core --from base --strategy merge
pmm update core --strategy reset-hard --yes # back to origin/<branch>, the rest kept in refs/pmm/
```

The dashboard's `U` fast-forwards, after one confirmation, every repository whose branch only lacks
commits of its upstream.

## Switching branches

`pmm switch <branch>` (the `s` key) switches every repository that has the branch, or only those of
`--repo`. A remote branch with no local copy gets a local branch tracking it; `--create` creates a
missing branch from the base branch. `pmm switch` alone lists the branches.

Uncommitted changes are handled one of two ways (`--stash`, else `branches.stashOnSwitch`, which asks by
default):

- **park**: they are stashed with the branch you leave, and restored when you come back to it, as if
  each branch had its own working tree;
- **carry**: they are stashed, then restored on the new branch.

```shell
pmm switch feature/PMM-142                  # every repository that has it
pmm switch develop --repo billing --stash park
pmm switch --repo billing                   # the branches, with the ones holding parked changes
```

## How rebasing stays safe

`pmm rebase` is `pmm update --from base --strategy rebase` with the options of a rebase. It refuses to
run:

- on an update-protected branch, or on a push-protected one when the rebase would rewrite commits
  already on its upstream,
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
their commits. A push-protected branch is never pushed.
