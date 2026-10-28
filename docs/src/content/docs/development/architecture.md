---
title: Architecture
description: The Maven modules of pmm and what each one does.
---

pmm is a Java 21 multi-module Maven build. Each module has one job, and only `pmm-cli` knows about
the command line.

```text
pmm-core/     severities, platform helpers, process execution (Windows-aware: mvn.cmd, PATHEXT),
              the workspace model and a small directed graph (build layers, cycles, dependents)
pmm-config/   pmm.yaml model, loading and merging, validation, starter template
pmm-maven/    reads the POMs into the workspace model (no Maven run); dependency checks against Nexus
pmm-git/      git through the git command line: fetch, status, freshness, unreleased commits, safe rebase
pmm-build/    Maven builds in parallel, failure analysis (blockers), fingerprint cache
pmm-tui/      the interactive dashboard, drawn with TamboUI (immediate mode, JLine backend)
pmm-cli/      picocli commands, renderers, reports, update check, executable jar and native image
docs/         this documentation site (Astro Starlight)
```

## Design choices

- **The git command line, not a git library.** Users' credentials, SSH keys, proxies and the Git
  Credential Manager apply as they do in their terminal, and `git merge-tree` predicts conflicts
  without touching the working tree.
- **POMs read directly.** `maven-model` parses them; parents, properties and `dependencyManagement`
  are resolved inside the workspace. `pmm graph` takes about a second, with no Maven run.
- **Nexus over plain HTTP.** One `maven-metadata.xml` per artifact, eight requests at a time.
- **Findings, not exceptions.** Every check produces findings with a severity, a message and a
  suggestion; renderers (terminal, JSON, JUnit XML, HTML, dashboard) only present them.
- **One native executable.** Everything is chosen to work in a GraalVM native image (see
  [Building from source](../building/)).
