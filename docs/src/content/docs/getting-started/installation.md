---
title: Installation
description: Download the native executable, keep it up to date, or build it from source.
---

## Download

Each [release](https://github.com/anorys/pmm/releases) has the native executables:

| File | System |
|---|---|
| `pmm-<version>-windows-x64.exe` | Windows x64 |
| `pmm-<version>-linux-x64` | Linux x64 |
| `SHA256SUMS` | SHA-256 of both |

Rename the one for your system to `pmm.exe` (or `pmm`) and put it on your `PATH`:

```powershell
# Windows (PowerShell), for example in C:\tools
Rename-Item pmm-0.1.1-windows-x64.exe pmm.exe
pmm --version
```

```shell
# Linux
mv pmm-0.1.1-linux-x64 ~/.local/bin/pmm && chmod +x ~/.local/bin/pmm
pmm --version
```

There is no macOS build yet: [build it from source](../../development/building/).

### Requirements

- `git` 2.38 or later on the `PATH` (`pmm` runs git for the status, conflict prediction and rebasing).
- Maven (`mvn`), the Maven daemon (`mvnd`) or each repository's Maven wrapper, for `pmm build`.
- A terminal of at least 80 x 24 for the dashboard; Windows Terminal is recommended on Windows.

## Updating

When a newer release is published, any `pmm` command run in a terminal ends with a notice, the way
pip does:

```text
A new release of pmm is available: 0.1.0 -> 0.2.0
To update, run: pmm self-update
```

`pmm self-update` downloads the executable of the latest release for your system, checks its SHA-256
against the release's `SHA256SUMS`, runs it once (`--version`), and only then puts it in place of the
running one. On Windows a running `.exe` cannot be overwritten but can be renamed, so the old one
becomes `pmm.exe.old` and the next run deletes it.

| Command | Effect |
|---|---|
| `pmm self-update` | Installs the latest release |
| `pmm self-update --check` | Only reports whether a newer release exists |
| `pmm self-update --to 0.1.1` | Installs a given version, also an older one |
| `pmm self-update --force` | Reinstalls even when already up to date |

It works with the native executable, not with the jar.

- **When it looks**: at most once a day, in the background while the command runs, with a 3-second
  timeout. A failed lookup (offline, proxy) is silent and retried the next day. The answer is cached
  in `%LOCALAPPDATA%\pmm\update-check.properties` (`~/.cache/pmm/` elsewhere).
- **When it does not**: in CI (`CI` is set), when the output is not a terminal (scripts, pipes), for
  development builds (`-SNAPSHOT`), with `--no-update-check`, or with `PMM_NO_UPDATE_CHECK=1`.
- **GitHub credentials**: none are needed, the repository is public. When `PMM_GITHUB_TOKEN`, `GH_TOKEN`
  or `GITHUB_TOKEN` is set, or git already stores credentials for `github.com` (`git credential fill`, for
  example the Git Credential Manager on Windows), `pmm` uses them, which raises GitHub's rate limit; it
  never prompts. The token is only sent to `api.github.com`, never to the storage host downloads are
  redirected to.
- **Corporate network**: set `HTTPS_PROXY`, or pass `-Dhttps.proxyHost=... -Dhttps.proxyPort=...` to
  the executable; an internal certificate authority goes in a trust store given with
  `-Djavax.net.ssl.trustStore=...`. `PMM_UPDATE_URL` points to a mirror of the GitHub releases API.

See [environment variables](../../reference/environment/) for the full list.
