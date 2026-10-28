---
title: Releasing
description: How a pmm release is published.
---

A release is a GitHub release `vX.Y.Z` with the native executables attached:

- `pmm-X.Y.Z-windows-x64.exe`
- `pmm-X.Y.Z-linux-x64`
- `SHA256SUMS`

To publish one, either push a tag `vX.Y.Z` on `main`, or run the **Release** workflow from the Actions
tab with version `X.Y.Z`. It builds the executables at that version and attaches them to a new GitHub
release `vX.Y.Z`, creating the tag if needed.

:::caution[Asset names]
`pmm self-update` looks for these exact asset names. Changing them means changing
`SelfUpdater.assetName` in the same release.
:::

Once the release exists, every `pmm` run in a terminal shows the update notice within a day, and
`pmm self-update` installs it.
