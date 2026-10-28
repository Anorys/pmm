---
title: Roadmap
description: What has been built, and what could come next.
---

| Phase | Content | Status |
|---|---|---|
| 0 | Skeleton, configuration, `init`, `config`, native build, CI on Linux and Windows | Done |
| 1 | Workspace model: POM scanning, repository and module graph, `pmm graph` | Done |
| 2 | Git status: fetch, ahead/behind, merge-base age, `pmm status` | Done |
| 3 | Dependency checks with Nexus, `pmm deps`, `pmm doctor` | Done |
| 4 | Parallel builds, blocker analysis, fingerprint cache, `pmm build`, `pmm doctor --build` | Done |
| 5 | Conflict prediction and safe rebase, `pmm sync`, `pmm rebase` | Done |
| 6 | JSON, JUnit XML and HTML reports from `pmm doctor` | Done |
| | The `pmm ui` dashboard, update notice and `pmm self-update` | Done |

## Possible next steps

Not started:

- **Integration builds**: building consumers against the sources of their upstream repositories rather
  than their last release, before releasing.
- **`pmm release-order`**: which repositories to release, in which order, to unblock the others.
