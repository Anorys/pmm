---
title: Environment variables
description: The environment variables and JVM properties pmm reads.
---

| Variable | Effect |
|---|---|
| `PMM_NEXUS_USERNAME`, `PMM_NEXUS_PASSWORD` | Nexus credentials; override `settings.xml` (needed when its password is encrypted) |
| `PMM_GITHUB_TOKEN`, `GH_TOKEN`, `GITHUB_TOKEN` | Token for the update check and `pmm self-update`, in this order; otherwise `git credential fill` |
| `PMM_UPDATE_URL` | A mirror of the GitHub releases API for the update check |
| `PMM_NO_UPDATE_CHECK=1` | Never look for a newer release (same as `--no-update-check`) |
| `CI` | When set, no update notice |
| `PMM_ASCII=1` | ASCII symbols in the dashboard, for old consoles |
| `NO_COLOR` | No colors |
| `MAVEN_OPTS` | Passed to Maven, with `-Xmx<build.memoryPerBuild>` added |
| `HTTPS_PROXY` | Proxy for the update check and `pmm self-update` (unless `-Dhttps.proxyHost` is given) |

## JVM properties

The native executable accepts `-D` properties before the command, for example:

```shell
pmm -Djavax.net.ssl.trustStore=C:\path\to\cacerts -Djavax.net.ssl.trustStorePassword=changeit deps
pmm -Dhttps.proxyHost=proxy.example.com -Dhttps.proxyPort=3128 self-update
```

## Files

| Path | Content |
|---|---|
| `pmm.yaml`, `pmm.local.yaml` | Configuration, shared and personal |
| `.pmm/build-cache.json` | Fingerprints of the last successful builds |
| `.pmm/logs/<repo>.log` | Full output of the last build of each repository |
| `%LOCALAPPDATA%\pmm\update-check.properties` (`~/.cache/pmm/` elsewhere) | Last update check |
