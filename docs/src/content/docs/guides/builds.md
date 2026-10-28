---
title: Builds and blockers
description: Parallel Maven builds, failure analysis and the fingerprint cache.
---

`pmm build` compiles every monitored repository, several at a time, and explains each failure.

## How builds run

In each repository, `pmm` runs:

```text
<maven> -B -ntp [-o] [-s settings] [-P profiles] [-T threads] [-pl :module,... -am] <compileGoal> <extraArgs>
```

with `MAVEN_OPTS=-Xmx<memoryPerBuild>`, and stops it after `timeoutMinutes`.

- With `maven.executable: auto` it uses `mvnd` when it is on the `PATH`, else the repository's own
  Maven wrapper (`mvnw` / `mvnw.cmd`), else `mvn`.
- Repositories depend on each other through released versions, so they all build at the same time;
  `build.parallelRepos` (or `-j`) limits how many, to keep the machine usable. The default, `0`, means
  `min(4, cores / 2)`.
- The full output of each build goes to `.pmm/logs/<repo>.log` under the workspace root.

```shell
pmm build                       # everything, from the cache when possible
pmm build --repo billing -j 2   # one repository, two builds at a time
pmm build --no-cache --offline  # build everything, without Nexus
```

## What blocks what

When Maven cannot download an artifact (a dependency, a parent, a BOM or a plugin), `pmm` looks up
which monitored repository produces it and reports a blocker:

```text
platform-core blocks billing, reporting (to release: core-api 3.1.5)
```

Other failures show the first compilation error, or the first Maven error, with the path of the log.

## The cache

After a successful build, `pmm` stores a fingerprint of the repository in `.pmm/build-cache.json`:

- the commit's tree,
- the uncommitted changes,
- the untracked (not ignored) files,
- the Maven command line.

The next `pmm build` skips the repository while the fingerprint is unchanged, so after the first run
only what you changed is built again. Failures are never cached, and a repository that uses a
SNAPSHOT from elsewhere is always built, since its result can change without any local change.
`--no-cache` or `build.cache: false` builds everything.

:::note
Add `.pmm/` to the ignore file of the workspace if the workspace folder is itself under version
control.
:::
