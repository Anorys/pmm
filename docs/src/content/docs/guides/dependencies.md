---
title: Dependencies and Nexus
description: How pmm reads the POMs and checks every internal version against Nexus.
---

`pmm deps` (and `pmm doctor`) list every version a repository uses from another one, with the latest
release in Nexus, and report what is wrong.

## How the POMs are read

`pmm` does not run Maven to build the graph, so `pmm graph` takes about a second for the whole
workspace. It reads each repository's root `pom.xml` and its modules (including modules declared in
profiles), looks up parents anywhere in the workspace by `groupId:artifactId`, and inherits properties
and `dependencyManagement` along that chain. Parents, dependencies, imported BOMs and build plugins all
count as references.

A parent or BOM that lives only in Nexus (a corporate parent, for example) is not read: a version that
only it defines is reported as a warning instead of being guessed.

```shell
pmm graph                       # build layers, references with versions, cycles
pmm graph --repo billing        # what billing depends on, and what depends on it
pmm graph -f mermaid > graph.md # also: dot
pmm graph --modules             # module-level graph
```

## How Nexus is queried

`pmm deps` asks Nexus which versions exist by downloading
`<group>/<artifact>/maven-metadata.xml`: one small HTTP request per artifact, eight at a time, no Maven
run and no local repository involved.

| Check | Reports |
|---|---|
| `deps.release-missing` | A repository uses an internal version that Nexus does not have: its build fails until the producer releases it. The suggestion names who must release what (`release platform-core 3.1.5 (it is at 3.1.5-SNAPSHOT)`). |
| `deps.internal-snapshot` | A repository uses a SNAPSHOT of another one. |
| `deps.internal-outdated` | A newer internal release exists and the gap is larger than `allowedLag` (`patch` by default: 3.1.4 → 3.1.6 is tolerated, 3.1.4 → 3.2.0 is reported). |
| `deps.unreleased-upstream` | A repository that others use has commits on its base branch since its latest release. |
| `deps.undeclared-internal` | An artifact of an `internalGroups` group that no monitored repository produces. |
| `deps.unresolvable` | An external version that Nexus does not have, for artifacts Nexus knows. Point `nexus` at a group repository that proxies Maven Central for this check to cover third-party libraries. |
| `deps.convergence` | Repositories declare different versions of the same external artifact. |

## Nexus URL and credentials

`nexus.url` gives the repository URL directly. Otherwise `nexus.repositoryId` names a `<mirror>` (or a
repository in a `<profile>`) of your `settings.xml`, and the `<server>` with the same id gives the
credentials (`${env.NAME}` values are supported).

Passwords encrypted with `mvn --encrypt-password` cannot be read: set `PMM_NEXUS_USERNAME` and
`PMM_NEXUS_PASSWORD` instead, which also override `settings.xml`. `--offline` (or
`maven.offline: true`) skips Nexus; the checks that do not need it still run.

:::tip[Internal certificate authority]
If Nexus uses a certificate signed by an internal CA, the native executable needs that CA's trust
store at run time:

```shell
pmm -Djavax.net.ssl.trustStore=C:\path\to\cacerts -Djavax.net.ssl.trustStorePassword=changeit deps
```
:::

## Finding the latest release in git

For `deps.unreleased-upstream`, the release commit of the latest release is found:

1. from `releaseTagPattern` when the repository sets one (`billing-{version}`);
2. else from a tag that ends with the version (`v3.1.4`, `platform-core-3.1.4`);
3. else from the oldest commit whose root `pom.xml` gained `<version>3.1.4</version>`.

Commits whose message contains `[maven-release-plugin]` and merge commits are not counted. `pmm deps`
uses the last fetched state; `pmm doctor` and `pmm status` fetch first.
