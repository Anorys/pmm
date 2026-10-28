---
title: Commands
description: Every pmm command and option.
---

## Global options

These work with every command.

| Option | Effect |
|---|---|
| `-c, --config <file>` | Configuration file. Default: `pmm.yaml` in the current directory or its parents. |
| `-v, --verbose` | Print more details. |
| `--no-update-check` | Do not look for a newer pmm release (also `PMM_NO_UPDATE_CHECK=1`). |
| `-h, --help` | Help for the command. |
| `-V, --version` | Print the version. |

Every command that checks something accepts `--strict`: exit with code 1 when there are warnings.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Healthy |
| 1 | Warnings only, when `--strict` is given |
| 2 | At least one check reported an error |
| 3 | The tool could not run: invalid configuration, bad arguments, missing executable |

## pmm init

```text
pmm init [<workspace>] [--base-branch <branch>] [--force]
```

Finds the git repositories with a `pom.xml` in the workspace folder (default: the current directory)
and writes a commented starter `pmm.yaml` there. `--base-branch` sets the default base branch
(`develop`); `--force` overwrites an existing `pmm.yaml`.

## pmm config

```text
pmm config [--validate] [--strict]
```

Validates the configuration (all problems at once) and prints the effective values, after merging
`pmm.local.yaml`. `--validate` only validates, and warns about repositories that are not cloned.

## pmm graph

```text
pmm graph [--repo <name>] [-f text|mermaid|dot] [--modules] [--strict]
```

Reads the POMs and shows the build order, the artifacts and versions each repository uses from the
others, and cycles. `--repo` shows what one repository depends on and what depends on it (text
format). `--modules` draws modules instead of repositories (Mermaid and DOT).
See [Dependencies and Nexus](../../guides/dependencies/).

## pmm status

```text
pmm status [--repo <name>]... [--no-fetch] [--strict]
```

Fetches all repositories in parallel, then shows each branch, how far it is ahead of and behind its
base branch, local changes and any rebase or merge in progress, with a suggestion (fast-forward or
rebase) for each branch that is behind. `--no-fetch` compares with the last fetched state.

## pmm deps

```text
pmm deps [--offline] [--strict]
```

Lists every version a repository uses from another one, with the latest release in Nexus, and reports
unreleased versions (what blocks whom), SNAPSHOTs, outdated releases, external versions Nexus does not
have, divergent external versions, and upstream repositories with commits since their last release.
`--offline` skips Nexus; the checks that need no Nexus still run.

## pmm build

```text
pmm build [--repo <name>]... [-j <n>] [--no-cache] [--offline] [--strict]
```

Runs `maven.compileGoal` in every repository, `build.parallelRepos` (or `-j`) at a time, skips
repositories whose sources did not change since their last successful build, and explains each
failure: the first compilation error, or the artifact missing from Nexus and the repository that must
release it. See [Builds and blockers](../../guides/builds/).

## pmm sync

```text
pmm sync [--repo <name>]... [--no-fetch] [--fast-forward]
```

Fetches, then says for each repository whether its branch is up to date, can be fast-forwarded, or
needs a rebase, and which files a rebase would conflict on. `--fast-forward` fast-forwards the branches
that have no commit of their own and a clean working tree.

## pmm rebase

```text
pmm rebase <repo>... [--dry-run] [--no-fetch] [--autostash] [--keep-conflicts] [--[no-]push]
```

Rebases the current branch of each repository onto its base branch, safely, then pushes it with
`--force-with-lease` if `rebase.push` or `--push` says so. See
[Branches and rebasing](../../guides/rebasing/).

| Option | Effect |
|---|---|
| `--dry-run` | Only say what would happen, including the predicted conflicts |
| `--no-fetch` | Rebase onto the last fetched base branch |
| `--autostash` | Stash uncommitted changes before, and restore them after |
| `--keep-conflicts` | On conflicts, leave the rebase in progress to resolve by hand |
| `--push`, `--no-push` | Override `rebase.push` |

## pmm doctor

```text
pmm doctor [--no-fetch] [--offline] [--build] [--strict] [--json <file>] [--junit <file>] [--html <file>]
```

Runs every enabled check: cycles, git status after a fetch, dependencies, and the builds with
`--build`. The entry point for CI; it can also write the result as JSON, JUnit XML and a
self-contained HTML page. `--offline` implies `--no-fetch`. See [Using pmm in CI](../../guides/ci/).

## pmm ui

```text
pmm ui [--demo]
```

Opens the interactive [dashboard](../../guides/dashboard/); also what `pmm` alone does in a terminal.
`--demo` opens it on a made-up workspace, with simulated git, Nexus and builds.

## pmm self-update

```text
pmm self-update [--check] [--to <version>] [--force]
```

Replaces the executable with the latest release, after checking its SHA-256. See
[Updating](../../getting-started/installation/#updating).
