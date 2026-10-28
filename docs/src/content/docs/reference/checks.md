---
title: Checks
description: Every finding pmm can report, what it means and its default severity.
---

Each check has a severity, set under `checks` in [`pmm.yaml`](../configuration/): `off`, `info`,
`warn` or `error`. Errors make `pmm doctor` exit with code 2; warnings make it exit with code 1 when
`--strict` is given.

## Graph

| Check | Default | Reports |
|---|---|---|
| `graph.cycle` | error | Repositories that depend on each other in a cycle |

## Git

| Check | Default | Reports |
|---|---|---|
| `git.behind` | warn | The branch is `maxCommits` (20) behind its base branch, or the oldest missing commit is `maxDays` (7) old; a smaller lag is information |
| `git.dirty` | info | Uncommitted changes, or a rebase or merge in progress |
| `git.not-cloned` | warn | A configured repository is not in the workspace |
| `git.fetch-failed` | warn | The fetch failed (often credentials); the last fetched state is used |
| `git.base-missing` | error | The base branch does not exist on the remote |

## Dependencies

| Check | Default | Reports |
|---|---|---|
| `deps.release-missing` | error | An internal version that Nexus does not have; names who must release what |
| `deps.internal-snapshot` | error | A SNAPSHOT of another monitored repository |
| `deps.internal-outdated` | warn | A newer internal release, beyond `allowedLag` (`patch`) |
| `deps.unreleased-upstream` | info | An upstream repository has commits since its latest release |
| `deps.undeclared-internal` | warn | An internal-group artifact no monitored repository produces |
| `deps.unresolvable` | error | An external version Nexus does not have, for an artifact it knows |
| `deps.convergence` | warn | Different versions of the same external artifact across repositories |
| `deps.nexus-unavailable` | warn | Nexus could not be queried; release checks were skipped |

Details in [Dependencies and Nexus](../../guides/dependencies/).

## Build

| Check | Default | Reports |
|---|---|---|
| `build.failed` | error | Does not compile, or another Maven failure; shows the first error and the log path |
| `build.blocked` | error | A dependency, parent, BOM or plugin could not be downloaded; names the repository that must release it |

Details in [Builds and blockers](../../guides/builds/).
