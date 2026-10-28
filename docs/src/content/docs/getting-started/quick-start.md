---
title: Quick start
description: From an empty folder to a full health report in ten commands.
---

Open a terminal in the folder that contains your repositories (for example
`C:\work\projects\anorys`).

## 1. Describe the workspace

```shell
pmm init                  # finds the git repositories with a pom.xml and writes pmm.yaml
pmm config                # validates pmm.yaml and prints the effective configuration
pmm config --validate     # validates only; warns about repositories that are not cloned
```

`pmm init` writes a commented starter `pmm.yaml` listing the repositories it found. Edit it to set the
base branch, the Nexus repository and the internal group ids, then commit it where the team can share
it. `pmm` looks for `pmm.yaml` in the current directory and its parents; `--config <file>` points to
another one.

## 2. Look at the workspace

```shell
pmm graph                 # build order, who uses what from whom (with versions), cycles
pmm status                # fetches every repository, then branch, ahead/behind origin/develop, local changes
pmm deps                  # internal versions against Nexus: unreleased (blockers), SNAPSHOTs, outdated
```

## 3. Build and fix

```shell
pmm build                 # compiles every repository, several at a time; says which one blocks which
pmm sync                  # what each branch needs: nothing, a fast-forward, or a rebase (with predicted conflicts)
pmm rebase billing        # rebases billing's branch onto origin/develop, safely (backup, abort on conflict)
```

## 4. Everything at once

```shell
pmm doctor                # graph, status and deps (add --build for the builds); the command for CI
pmm ui                    # the interactive dashboard (pmm alone does the same)
```

![A build in progress in the dashboard](../../../assets/screenshots/tui-build.png)

Want to try it without a workspace? `pmm ui --demo` opens the dashboard on a made-up workspace of
nine repositories with simulated git, Nexus and builds.

## Next

- [The dashboard](../../guides/dashboard/)
- [All commands](../../reference/commands/)
- [Using pmm in CI](../../guides/ci/)
