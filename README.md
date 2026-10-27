# pmm — project modules manager

`pmm` is a command-line tool that checks the health of a workspace made of several Maven repositories:

- are the Maven dependencies consistent, and do the internal releases they need exist in Nexus?
- does every monitored project compile?
- which project blocks which?
- is each branch up to date with its base branch (`origin/develop` by default), and can it be rebased safely?

It ships as a single native executable (`pmm.exe` on Windows, `pmm` on Linux and macOS) built with GraalVM, so it starts instantly and needs no installed JVM.

> **Status: phase 2.** The configuration, `init`, `config`, the dependency graph (`graph`) and the git
> status of every repository (`status`) work. The other checks (`deps`, `build`, `sync`, `rebase`,
> `doctor`) come in the next phases; see the [roadmap](#roadmap).

## Quick start

```shell
# In the folder that contains your repositories (for example C:\work\projects\anorys)
pmm init                  # finds the git repositories with a pom.xml and writes pmm.yaml
pmm config                # validates pmm.yaml and prints the effective configuration
pmm config --validate     # validates only; warns about repositories that are not cloned
pmm graph                 # build order, who uses what from whom (with versions), cycles
pmm status                # fetches every repository, then branch, ahead/behind origin/develop, local changes
```

`pmm` looks for `pmm.yaml` in the current directory and its parents; use `--config <file>` to point to another one.

## Commands

| Command | Status | What it does |
|---|---|---|
| `pmm init [<workspace>] [--base-branch <branch>] [--force]` | available | Writes a commented starter `pmm.yaml` listing the repositories found in the folder. |
| `pmm config [--validate] [--strict]` | available | Validates the configuration (all problems at once) and prints the effective values. |
| `pmm graph [--repo <name>] [-f text\|mermaid\|dot] [--modules] [--strict]` | available | Reads the POMs and shows the build order, the artifacts and versions each repository uses from the others, and cycles. `--repo` shows what one repository depends on and what depends on it. |
| `pmm status [--repo <name>]... [--no-fetch] [--strict]` | available | Fetches all repositories in parallel, then shows each branch, how far it is ahead of and behind its base branch, local changes and any rebase or merge in progress, with a suggestion (fast-forward or rebase) for each branch that is behind. |
| `pmm deps` | phase 3 | Dependency checks against the POMs and Nexus. |
| `pmm build [--changed]` | phase 4 | Compiles repositories in parallel and explains what blocks what. |
| `pmm sync`, `pmm rebase <repo>` | phase 5 | Predicts conflicts with the base branch and rebases safely. |
| `pmm doctor` | phase 3+ | Runs every enabled check; the entry point for CI. |

Global options: `-c, --config <file>`, `-v, --verbose`, `-h, --help`, `-V, --version`.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Healthy |
| 1 | Warnings only, when `--strict` is given |
| 2 | At least one check reported an error |
| 3 | The tool could not run: invalid configuration, bad arguments, missing executable |

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
  deps.internal-outdated: { severity: warn, allowedLag: minor }
  deps.unreleased-upstream: info
  deps.unresolvable: error
  deps.convergence: warn
  deps.internal-snapshot: error
  deps.undeclared-internal: warn
  git.behind: { severity: warn, maxCommits: 20, maxDays: 7 }
  git.dirty: info                  # uncommitted changes, or a rebase/merge in progress
  git.not-cloned: warn
  git.fetch-failed: warn
  git.base-missing: error          # the base branch does not exist on the remote
  graph.cycle: error

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
- **Prefer libraries without dependency injection or dynamic class loading.** For the next phases, POMs
  will be read with `maven-model` (plain XML parsing) and Nexus queried over HTTP (`maven-metadata.xml`),
  rather than with Maven Resolver and its Sisu/Guice container.

Resources loaded at runtime must be listed in
`pmm-cli/src/main/resources/META-INF/native-image/com.anorys.pmm/pmm-cli/resource-config.json`.

## Project layout

```
pmm-core/     severities, platform helpers, process execution (Windows-aware: mvn.cmd, PATHEXT)
pmm-config/   pmm.yaml model, loading and merging, validation, starter template
pmm-maven/    reads the POMs of the workspace into the workspace model (no Maven run)
pmm-git/      git through the git command line: fetch, status, ahead/behind, freshness checks
pmm-cli/      picocli commands, renderers, exit codes, executable jar and native image
```

`pmm-core` also holds the workspace model (repositories, modules, references between artifacts) and a
small directed-graph implementation (build layers, cycles, transitive dependents).
Modules for builds and reports are added in their phases.

### How the git status is read

`pmm` runs the `git` executable, so your credentials, SSH keys, proxy and Git Credential Manager settings
apply. Credential prompts are disabled (`GIT_TERMINAL_PROMPT=0`): a fetch that needs a password fails
with a `git.fetch-failed` finding instead of hanging, and the status then uses the last fetched state.
"Behind" counts the commits of the base branch missing from `HEAD`; its age is the commit date of the
oldest missing one. A branch is flagged (`git.behind` severity) once it is `maxCommits` behind or that
commit is `maxDays` old; a smaller lag is reported as information only.

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
| 3 | Dependency checks with Nexus, `pmm deps`, first `pmm doctor` |
| 4 | Parallel builds, blocker analysis, fingerprint cache, `pmm build` |
| 5 | Conflict prediction and safe rebase, `pmm sync`, `pmm rebase` |
| 6 | HTML, JSON and JUnit XML reports; integration builds against upstream sources |
