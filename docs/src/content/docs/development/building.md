---
title: Building from source
description: Build the jar and the GraalVM native executable, and the rules that keep the native build working.
---

## The jar

Requirements: JDK 21. The Maven wrapper downloads Maven 3.9.11; set `MVNW_REPOURL` to your Nexus mirror
of Maven Central if the internet is not reachable.

```shell
./mvnw verify                  # Linux / macOS / Git Bash
mvnw.cmd verify                # Windows cmd or PowerShell
java -jar pmm-cli/target/pmm-cli-0.1.0-SNAPSHOT-all.jar --help
```

## The native executable

Requirements: [GraalVM for JDK 21](https://www.graalvm.org/downloads/) with `JAVA_HOME` pointing to it.
On Windows, native-image also needs the Visual Studio Build Tools with the *Desktop development with
C++* workload, and the build must run from an *x64 Native Tools Command Prompt*.

```shell
./mvnw -Pnative -DskipTests package
pmm-cli/target/pmm --version   # pmm.exe on Windows
```

CI builds the native executable on Linux and Windows for every pull request and publishes it as a
build artifact (`pmm-Linux`, `pmm-Windows`).

## Rules that keep the native build working

GraalVM native images only see the classes and resources that are reachable at build time. To keep
this cheap, the code follows these rules:

- **No reflection-based data binding.** The configuration is read with Jackson's tree model and mapped
  by hand (`ConfigParser`), which also gives precise error messages.
- **picocli metadata is generated.** `picocli-codegen` runs as an annotation processor and writes the
  reflection configuration for the commands.
- **Prefer libraries without dependency injection or dynamic class loading.** POMs and `settings.xml`
  are read with `maven-model` and `maven-settings` (plain XML parsing), versions are compared with
  `maven-artifact`, and Nexus is queried with the JDK HTTP client (`maven-metadata.xml`), rather than
  with Maven Resolver and its Sisu/Guice container. The image is built with
  `--enable-http --enable-https`.
- **No virtual threads.** They crash the Windows executable when it starts processes.
- **The dashboard uses TamboUI's immediate-mode layers only** (`tamboui-tui`, `tamboui-widgets` and the
  JLine 3 backend, created directly rather than discovered), not its toolkit, CSS or annotation-driven
  bindings. JLine ships its own native-image metadata, including the JNI terminal provider used on
  Windows; it is pinned to 3.30.6 because the 3.25.1 that TamboUI brings has broken JNI metadata,
  which left `pmm ui` on a dumb terminal on Windows. The dashboard opens the terminal itself and
  refuses a dumb one, and `pmm ui --check-terminal` (hidden, used by the native smoke test) checks
  that the native library loads in the image. The image has no Flight Recorder, so TamboUI's JFR draw event is initialized at build time
  and reported as disabled by a substitution (`pmm-tui/.../nativeimage/`).

Resources loaded at runtime must be listed in
`pmm-cli/src/main/resources/META-INF/native-image/com.anorys.pmm/pmm-cli/resource-config.json` (the
version file, and TamboUI's default key bindings).

## This documentation

The site lives in `docs/` and is built with [Astro Starlight](https://starlight.astro.build/). It
needs Node.js 24 (or 22.23.4 or later).

```shell
cd docs
npm ci
npm run dev        # http://localhost:4321/pmm/
npm run build      # static site in docs/dist
```

Pages are Markdown files under `docs/src/content/docs/`; the sidebar is in `docs/astro.config.mjs`.
The *Docs* workflow publishes the site to GitHub Pages on every push to `main` that touches `docs/`.
