---
title: Introduction
description: What pmm is for, and the workspace it expects.
---

`pmm` (project modules manager) is a command-line tool that checks the health of a workspace made of
several Maven repositories. It answers the questions you ask yourself every morning in such a
workspace:

- Are the Maven dependencies consistent, and do the internal releases they need exist in Nexus?
- Does every monitored project compile?
- Which project blocks which?
- Is each branch up to date with its base branch (`origin/develop` by default), and can it be rebased
  safely?

It ships as a single native executable (`pmm.exe` on Windows, `pmm` on Linux and macOS) built with
GraalVM, so it starts instantly and needs no installed JVM.

## The workspace pmm expects

- **One folder, several git repositories.** Each repository has its own `pom.xml` (often a
  multi-module build) and its own remote. There is no aggregating parent repository.
- **Repositories depend on each other through released versions.** `billing` uses
  `platform-core` 3.1.5 as published in Nexus, not its sources. So every repository builds on its own,
  and a repository is *blocked* when the release it needs does not exist yet.
- **Long builds.** A build takes minutes, so `pmm` builds repositories in parallel and caches the
  ones whose sources did not change.
- **Personal feature branches based on `develop`.** Rebasing is the preferred way to stay up to date,
  and force-pushing your own branch with `--force-with-lease` is acceptable after a confirmation.

Everything is configurable in one shared file, `pmm.yaml`, with personal overrides in
`pmm.local.yaml`: see the [configuration reference](../../reference/configuration/).

## What it does not do

- It does not release anything: it tells you what to release, and in which order.
- It does not resolve dependencies with Maven: POMs are read directly and Nexus is queried over
  HTTP, which takes about a second for the whole workspace.
- It never rewrites a remote branch without asking, and never touches a protected branch.

## Next

- [Install pmm](../installation/)
- [Quick start](../quick-start/)
