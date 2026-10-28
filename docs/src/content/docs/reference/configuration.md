---
title: Configuration
description: Every key of pmm.yaml and pmm.local.yaml, with its default.
---

`pmm.yaml` is meant to be shared by the team. Each developer can add a `pmm.local.yaml` next to it for
personal overrides; it is merged on top of `pmm.yaml`:

- mappings key by key,
- `repositories` by `name`,
- other lists replaced.

Unknown keys are rejected, so typos are reported instead of silently ignored. `pmm config` prints the
effective configuration after the merge.

Every key below is optional except `repositories[].name`. The values shown are the defaults unless
noted.

```yaml title="pmm.yaml"
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
  backupRefs: true                 # refs/pmm/backup/<branch>/<timestamp> before each rebase
  push: ask                        # never, ask, always (always --force-with-lease)

branches:
  pushProtected: [develop, main, master, "release/*"]   # never pushed, published commits never rewritten
  updateProtected: []              # never changed locally (no fast-forward, merge, rebase or reset)
  strategies:                      # default of 'pmm update', first matching glob wins
    "release/**": merge            # (default: ff on push-protected branches, rebase elsewhere)
  stashOnSwitch: ask               # ask, park, carry or never: uncommitted changes on 'pmm switch'

build:
  parallelRepos: 0                 # 0 = automatic: min(4, cores / 2)
  memoryPerBuild: 1g
  timeoutMinutes: 15
  cache: true                      # skip repositories whose sources did not change
```

## A personal override

```yaml title="pmm.local.yaml"
maven:
  executable: mvnd
build:
  parallelRepos: 2
repositories:
  - name: legacy-adapter
    enabled: true                  # I work on this one; the team does not
rebase:
  push: always
```

Add `pmm.local.yaml` and `.pmm/` to the workspace's ignore file if the workspace folder is under
version control.
