# pmm — project modules manager

`pmm` is a command-line tool that checks the health of a workspace made of several Maven repositories:

- are the Maven dependencies consistent, and do the internal releases they need exist in Nexus?
- does every monitored project compile?
- which project blocks which?
- is each branch up to date with its base branch (`origin/develop` by default), and can it be rebased safely?

It ships as a single native executable (`pmm.exe` on Windows, `pmm` on Linux and macOS) built with GraalVM, so it starts instantly and needs no installed JVM.

> **Status: phase 6.** Every command of the plan works: the configuration (`init`, `config`), the
> dependency graph (`graph`), the git status (`status`), the dependency checks against Nexus (`deps`),
> parallel builds (`build`), conflict prediction and safe rebasing (`sync`, `rebase`), and `doctor` with
> JSON, JUnit XML and HTML reports for CI. See the [roadmap](#roadmap) for what could come next.

## Download

Each [release](https://github.com/aureg/project-modules-manager/releases) has the native executables:
`pmm-<version>-windows-x64.exe` and `pmm-<version>-linux-x64`, with their SHA-256 in `SHA256SUMS`.
Rename the one for your system to `pmm.exe` (or `pmm`) and put it on your `PATH`. There is no macOS
build yet: [build it from source](#building).

To publish a release, push a tag `vX.Y.Z` on `main`, or run the Release workflow from the Actions tab
with version X.Y.Z: it builds the executables at that version and attaches them to a new GitHub release
`vX.Y.Z` (creating the tag if needed).

## Quick start

```shell
# In the folder that contains your repositories (for example C:\work\projects\anorys)
pmm init                  # finds the git repositories with a pom.xml and writes pmm.yaml
pmm config                # validates pmm.yaml and prints the effective configuration
pmm config --validate     # validates only; warns about repositories that are not cloned
pmm graph                 # build order, who uses what from whom (with versions), cycles
pmm status                # fetches every repository, then branch, ahead/behind origin/develop, local changes
pmm deps                  # internal versions against Nexus: unreleased (blockers), SNAPSHOTs, outdated
pmm build                 # compiles every repository, several at a time; says which one blocks which
pmm sync                  # what each branch needs: nothing, a fast-forward, or a rebase (with predicted conflicts)
pmm rebase billing        # rebases billing's branch onto origin/develop, safely (backup, abort on conflict)
pmm doctor                # graph, status and deps (add --build for the builds); the command for CI
```

`pmm` looks for `pmm.yaml` in the current directory and its parents; use `--config <file>` to point to another one.

## Commands

| Command | Status | What it does |
|---|---|---|
| `pmm init [<workspace>] [--base-branch <branch>] [--force]` | available | Writes a commented starter `pmm.yaml` listing the repositories found in the folder. |
| `pmm config [--validate] [--strict]` | available | Validates the configuration (all problems at once) and prints the effective values. |
| `pmm graph [--repo <name>] [-f text\|mermaid\|dot] [--modules] [--strict]` | available | Reads the POMs and shows the build order, the artifacts and versions each repository uses from the others, and cycles. `--repo` shows what one repository depends on and what depends on it. |
| `pmm status [--repo <name>]... [--no-fetch] [--strict]` | available | Fetches all repositories in parallel, then shows each branch, how far it is ahead of and behind its base branch, local changes and any rebase or merge in progress, with a suggestion (fast-forward or rebase) for each branch that is behind. |
| `pmm deps [--offline] [--strict]` | available | Lists every version a repository uses from another one, with the latest release in Nexus, and reports unreleased versions (what blocks whom), SNAPSHOTs, outdated releases, external versions Nexus does not have, divergent external versions, and upstream repositories with commits since their last release. |
| `pmm build [--repo <name>]... [-j <n>] [--no-cache] [--offline] [--strict]` | available | Runs `maven.compileGoal` in every repository, `build.parallelRepos` at a time, skips repositories whose sources did not change since their last successful build, and explains each failure: the first compilation error, or the artifact missing from Nexus and the repository that must release it. |
| `pmm sync [--repo <name>]... [--no-fetch] [--fast-forward]` | available | Fetches, then says for each repository whether its branch is up to date, can be fast-forwarded, or needs a rebase, and which files a rebase would conflict on. `--fast-forward` fast-forwards the branches that have no commit of their own and no local change. |
| `pmm rebase <repo>... [--dry-run] [--no-fetch] [--autostash] [--keep-conflicts] [--[no-]push]` | available | Rebases the current branch onto its base branch, safely (see below), then pushes it with `--force-with-lease` if `rebase.push` or `--push` says so. |
| `pmm doctor [--no-fetch] [--offline] [--build] [--strict] [--json <file>] [--junit <file>] [--html <file>]` | available | Runs every enabled check (cycles, git status after a fetch, dependencies, and the builds with `--build`); the entry point for CI. Can also write the result as JSON, JUnit XML and a self-contained HTML page. |

Global options: `-c, --config <file>`, `-v, --verbose`, `-h, --help`, `-V, --version`.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Healthy |
| 1 | Warnings only, when `--strict` is given |
| 2 | At least one check reported an error |
| 3 | The tool could not run: invalid configuration, bad arguments, missing executable |

### Using pmm in CI

`pmm doctor` is meant for the CI factory: one command, an exit code, and reports to archive.

```shell
pmm doctor --build --strict --json target/pmm.json --junit target/pmm-junit.xml --html target/pmm.html
```

- **Exit code**: see above; `--strict` makes warnings fail the job too.
- **JUnit XML**: one test suite per category of checks (`pmm.graph`, `pmm.git`, `pmm.deps`, `pmm.build`)
  and one test case per repository, which fails when that repository has an error in that category.
  Warnings and information are in the test case's output. Any CI server that reads JUnit results
  (Jenkins, GitLab, Azure DevOps, TeamCity...) shows them without a plugin.
- **JSON**: everything the run found (repositories, dependencies, builds, blockers, findings, notes) for
  scripts and dashboards. Fields are only ever added, never renamed.
- **HTML**: one file with no script and no external resource, readable from a build artifact or an
  e-mail, in light or dark mode.

`ask` is the default push policy of `pmm rebase`, and CI has no terminal to ask in, so CI never pushes
unless `rebase.push: always` or `--push`.

## Configuration

`pmm.yaml` is meant to be shared by the team. Each developer can add a `pmm.local.yaml` next to it for
personal overrides; it is merged on top of `pmm.yaml` (mappings key by key, `repositories` by `name`,
other lists replaced). Unknown keys are rejected, so typos are reported instead of silently ignored.

Every key below is optional except `repositories[].name`. The values shown are the defaults unless noted.

```yaml
workspace:
  root: .                          # relative to this file; repository paths are relative to the root
  baseBranch: develop              # default base branch of every repository
  remote: origin

repositories:
  - name: platform-core            # required; letters, digits, '.', '_' and '-'
    path: platform-core            # default: the name
    url: git@github.com:anorys/platform-core.git   # example; optional
  - name: billing
    baseBranch: release/2.4        # example: overrides workspace.baseBranch
    modules: [billing-api, billing-service]        # default: ["*"] = all modules
    releaseTagPattern: "billing-{version}"         # example; default: auto-detected
  - name: legacy-adapter
    enabled: false                 # listed but not monitored; default: true

internalGroups: [com.anorys.*]     # example; group ids produced by these repositories

maven:
  executable: auto                 # auto (mvnd when installed, else mvn), mvn, mvnd, or a path
  settings: ~/.m2/settings.xml     # example; default: Maven's own default
  profiles: []
  offline: false
  threads: 1C
  extraArgs: []                    # for example ["-Dmaven.javadoc.skip", "-Denforcer.skip"]
  compileGoal: test-compile        # compile or test-compile

nexus:
  repositoryId: nexus-releases     # example; server id in settings.xml (URL and credentials)
  url: https://nexus.example.com/repository/releases   # example; overrides the URL from settings.xml

checks:                            # severity: off, info, warn, error
  deps.release-missing: error
  deps.internal-outdated: { severity: warn, allowedLag: patch }   # tolerated lag: none, patch, minor, major
  deps.unreleased-upstream: info
  deps.unresolvable: error
  deps.convergence: warn
  deps.internal-snapshot: error
  deps.undeclared-internal: warn
  deps.nexus-unavailable: warn     # Nexus could not be queried; release checks skipped
  git.behind: { severity: warn, maxCommits: 20, maxDays: 7 }
  git.dirty: info                  # uncommitted changes, or a rebase/merge in progress
  git.not-cloned: warn
  git.fetch-failed: warn
  git.base-missing: error          # the base branch does not exist on the remote
  graph.cycle: error
  build.failed: error              # does not compile, or another Maven failure
  build.blocked: error             # a dependency, parent, BOM or plugin could not be downloaded

rebase:
  requireCleanTree: true
  autostash: false
  backupRefs: true                 # refs/pmm/backup/<branch>-<timestamp> before each rebase
  push: ask                        # never, ask, always (always --force-with-lease)
  protectedBranches: [develop, main, master, "release/*"]   # never rebased nor force-pushed

build:
  parallelRepos: 0                 # 0 = automatic: min(4, cores / 2)
  memoryPerBuild: 1g
  timeoutMinutes: 15
  cache: true                      # skip repositories whose sources did not change
```

## Building

Requirements: JDK 21. The Maven wrapper downloads Maven 3.9.11; set `MVNW_REPOURL` to your Nexus mirror
of Maven Central if the internet is not reachable.

```shell
./mvnw verify                  # Linux / macOS / Git Bash
mvnw.cmd verify                # Windows cmd or PowerShell
java -jar pmm-cli/target/pmm-cli-0.1.0-SNAPSHOT-all.jar --help
```

### Native executable

Requirements: [GraalVM for JDK 21](https://www.graalvm.org/downloads/) with `JAVA_HOME` pointing to it.
On Windows, native-image also needs the Visual Studio Build Tools with the *Desktop development with C++*
workload, and the build must run from an *x64 Native Tools Command Prompt*.

```shell
./mvnw -Pnative -DskipTests package
pmm-cli/target/pmm --version   # pmm.exe on Windows
```

CI builds the native executable on Linux and Windows for every pull request and publishes it as a
build artifact (`pmm-Linux`, `pmm-Windows`).

### Rules that keep the native build working

GraalVM native images only see the classes and resources that are reachable at build time. To keep
this cheap, the code follows three rules:

- **No reflection-based data binding.** The configuration is read with Jackson's tree model and mapped
  by hand (`ConfigParser`), which also gives precise error messages.
- **picocli metadata is generated.** `picocli-codegen` runs as an annotation processor and writes the
  reflection configuration for the commands.
- **Prefer libraries without dependency injection or dynamic class loading.** POMs and `settings.xml`
  are read with `maven-model` and `maven-settings` (plain XML parsing), versions are compared with
  `maven-artifact`, and Nexus is queried with the JDK HTTP client (`maven-metadata.xml`), rather than
  with Maven Resolver and its Sisu/Guice container. The image is built with `--enable-http --enable-https`.

Resources loaded at runtime must be listed in
`pmm-cli/src/main/resources/META-INF/native-image/com.anorys.pmm/pmm-cli/resource-config.json`.

## Project layout

```
pmm-core/     severities, platform helpers, process execution (Windows-aware: mvn.cmd, PATHEXT)
pmm-config/   pmm.yaml model, loading and merging, validation, starter template
pmm-maven/    reads the POMs into the workspace model (no Maven run); dependency checks against Nexus
pmm-git/      git through the git command line: fetch, status, freshness, unreleased commits, safe rebase
pmm-build/    Maven builds in parallel, failure analysis (blockers), fingerprint cache
pmm-cli/      picocli commands, renderers, exit codes, executable jar and native image
```

`pmm-core` also holds the workspace model (repositories, modules, references between artifacts) and a
small directed-graph implementation (build layers, cycles, transitive dependents).
Reports are added in phase 6.

### How the git status is read

`pmm` runs the `git` executable, so your credentials, SSH keys, proxy and Git Credential Manager settings
apply. Credential prompts are disabled (`GIT_TERMINAL_PROMPT=0`): a fetch that needs a password fails
with a `git.fetch-failed` finding instead of hanging, and the status then uses the last fetched state.
"Behind" counts the commits of the base branch missing from `HEAD`; its age is the commit date of the
oldest missing one. A branch is flagged (`git.behind` severity) once it is `maxCommits` behind or that
commit is `maxDays` old; a smaller lag is reported as information only.

### How dependencies are checked

`pmm deps` reads the versions from the POMs (see below) and asks Nexus which versions exist, by
downloading `<group>/<artifact>/maven-metadata.xml`: one small HTTP request per artifact, eight at a
time, no Maven run and no local repository involved.

| Check | Reports |
|---|---|
| `deps.release-missing` | A repository uses an internal version that Nexus does not have: its build fails until the producer releases it. The suggestion names who must release what (`release platform-core 3.1.5 (it is at 3.1.5-SNAPSHOT)`). |
| `deps.internal-snapshot` | A repository uses a SNAPSHOT of another one. |
| `deps.internal-outdated` | A newer internal release exists and the gap is larger than `allowedLag` (`patch` by default: 3.1.4 → 3.1.6 is tolerated, 3.1.4 → 3.2.0 is reported). |
| `deps.unreleased-upstream` | A repository that others use has commits on its base branch since its latest release. |
| `deps.undeclared-internal` | An artifact of an `internalGroups` group that no monitored repository produces. |
| `deps.unresolvable` | An external version that Nexus does not have, for artifacts Nexus knows. Point `nexus` at a group repository that proxies Maven Central for this check to cover third-party libraries. |
| `deps.convergence` | Repositories declare different versions of the same external artifact. |

**Nexus URL and credentials.** `nexus.url` gives the repository URL directly. Otherwise
`nexus.repositoryId` names a `<mirror>` (or a repository in a `<profile>`) of your `settings.xml`, and
the `<server>` with the same id gives the credentials (`${env.NAME}` values are supported). Passwords
encrypted with `mvn --encrypt-password` cannot be read: set `PMM_NEXUS_USERNAME` and
`PMM_NEXUS_PASSWORD` instead, which also override `settings.xml`. `--offline` (or `maven.offline: true`)
skips Nexus; the checks that do not need it still run.

If Nexus uses a certificate signed by an internal CA, the native executable needs that CA's trust
store at run time: `pmm -Djavax.net.ssl.trustStore=C:\path\to\cacerts -Djavax.net.ssl.trustStorePassword=changeit deps`.

**Finding the latest release in git.** For `deps.unreleased-upstream`, the release commit of the
latest release is found from `releaseTagPattern` when set, else from a tag that ends with the version
(`v3.1.4`, `platform-core-3.1.4`), else from the oldest commit whose root `pom.xml` gained
`<version>3.1.4</version>`. Commits whose message contains `[maven-release-plugin]` and merge commits
are not counted. `pmm deps` uses the last fetched state; `pmm doctor` and `pmm status` fetch first.

### How rebasing stays safe

`pmm rebase` refuses to run on a protected branch (`rebase.protectedBranches`; `*` matches within one
path segment, `**` across), on a detached HEAD, while a rebase or merge is in progress, or with
uncommitted changes (unless `--autostash` or `rebase.autostash`). Before rebasing it saves the branch as
`refs/pmm/backup/<branch>/<timestamp>` and prints the command that undoes the rebase. If the rebase
stops on conflicts, it is aborted and the branch is left exactly as it was; `--keep-conflicts` leaves it
in progress instead, to resolve by hand. The conflicts are predicted beforehand with `git merge-tree`
(git 2.38 or later), which does not touch the working tree: `pmm sync` and `pmm rebase --dry-run` show
them.

Pushing a rebased branch rewrites its remote history, so it is never done silently: `rebase.push` is
`never`, `ask` (the default: a yes/no question in a terminal, no push when there is none, as in CI) or
`always`; `--push` and `--no-push` override it. The push uses `--force-with-lease` with the remote state
read before the rebase: if anyone pushed to the branch since, the push is refused instead of overwriting
their commits.

### How builds run

`pmm build` runs, in each repository, `<maven> -B -ntp [-o] [-s settings] [-P profiles] [-T threads]
[-pl :module,... -am] <compileGoal> <extraArgs>` with `MAVEN_OPTS=-Xmx<memoryPerBuild>` and stops it
after `timeoutMinutes`. With `maven.executable: auto` it uses `mvnd` when it is on the PATH, else the
repository's own Maven wrapper (`mvnw`/`mvnw.cmd`), else `mvn`. Repositories depend on each other
through released versions, so they all build at the same time; `build.parallelRepos` (or `-j`) limits
how many, to keep the machine usable. The full output of each build goes to `.pmm/logs/<repo>.log`
under the workspace root.

**What blocks what.** When Maven cannot download an artifact (a dependency, a parent, a BOM or a
plugin), `pmm` looks up which monitored repository produces it and reports a blocker:
`platform-core blocks billing, reporting (to release: core-api 3.1.5)`. Other failures show the first
compilation error, or the first Maven error, with the path of the log.

**The cache.** After a successful build, `pmm` stores a fingerprint of the repository in
`.pmm/build-cache.json`: the commit's tree, the uncommitted changes, the untracked (not ignored)
files and the Maven command line. The next `pmm build` skips the repository while the fingerprint is
unchanged, so after the first run only what you changed is built again. Failures are never cached,
and a repository that uses a SNAPSHOT from elsewhere is always built, since its result can change
without any local change. `--no-cache` or `build.cache: false` builds everything. Add `.pmm/` to the
ignore file of the workspace if the workspace folder is itself under version control.

### How the POMs are read

`pmm` does not run Maven to build the graph, so `pmm graph` takes about a second for the whole workspace.
It reads each repository's root `pom.xml` and its modules (including modules declared in profiles),
looks up parents anywhere in the workspace by `groupId:artifactId`, and inherits properties and
`dependencyManagement` along that chain. Parents, dependencies, imported BOMs and build plugins all
count as references. A parent or BOM that lives only in Nexus (a corporate parent, for example) is not
read: a version that only it defines is reported as a warning instead of being guessed.

## Roadmap

| Phase | Content |
|---|---|
| 0 | Skeleton, configuration, `init`, `config`, native build, CI on Linux and Windows |
| 1 | Workspace model: POM scanning, repository and module graph, `pmm graph` |
| 2 | Git status: fetch, ahead/behind, merge-base age, `pmm status` |
| 3 | Dependency checks with Nexus, `pmm deps`, `pmm doctor` |
| 4 | Parallel builds, blocker analysis, fingerprint cache, `pmm build`, `pmm doctor --build` |
| 5 | Conflict prediction and safe rebase, `pmm sync`, `pmm rebase` |
| 6 | JSON, JUnit XML and HTML reports from `pmm doctor` |

Possible next steps, not started: building consumers against the sources of their upstream
repositories rather than their last release (an "integration" build before releasing), and a
`pmm release-order` that lists which repositories to release, in which order, to unblock the others.
