---
title: Using pmm in CI
description: pmm doctor as the entry point of the CI factory, with exit codes and reports.
---

`pmm doctor` is meant for the CI factory: one command, an exit code, and reports to archive.

```shell
pmm doctor --build --strict --json target/pmm.json --junit target/pmm-junit.xml --html target/pmm.html
```

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Healthy |
| 1 | Warnings only, when `--strict` is given |
| 2 | At least one check reported an error |
| 3 | The tool could not run: invalid configuration, bad arguments, missing executable |

`--strict` makes warnings fail the job too.

## Reports

- **JUnit XML**: one test suite per category of checks (`pmm.graph`, `pmm.git`, `pmm.deps`,
  `pmm.build`) and one test case per repository, which fails when that repository has an error in that
  category. Warnings and information are in the test case's output. Any CI server that reads JUnit
  results (Jenkins, GitLab, Azure DevOps, TeamCity...) shows them without a plugin.
- **JSON**: everything the run found (repositories, dependencies, builds, blockers, findings, notes)
  for scripts and dashboards. Fields are only ever added, never renamed.
- **HTML**: one file with no script and no external resource, readable from a build artifact or an
  e-mail, in light or dark mode.

## Good to know

- The update notice is never shown in CI (`CI` is set) nor when the output is not a terminal.
- `ask` is the default push policy of `pmm rebase`, and CI has no terminal to ask in, so CI never
  pushes unless `rebase.push: always` or `--push`.
- Credential prompts are disabled: give the CI agent git credentials that work without a prompt,
  and Nexus credentials through `settings.xml` or `PMM_NEXUS_USERNAME` / `PMM_NEXUS_PASSWORD`.
