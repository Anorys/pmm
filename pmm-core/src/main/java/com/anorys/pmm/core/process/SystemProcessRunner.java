package com.anorys.pmm.core.process;

import com.anorys.pmm.core.Platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs processes with {@link ProcessBuilder}. Standard error is merged into standard output,
 * standard input is closed immediately (so a command waiting for input fails instead of
 * hanging), and on timeout the whole process tree is killed.
 */
public final class SystemProcessRunner implements ProcessRunner {

    private final ExecutableResolver resolver;
    private final boolean windows;
    private final Charset outputCharset;

    public SystemProcessRunner() {
        this(ExecutableResolver.fromEnvironment(), Platform.isWindows(), nativeCharset());
    }

    public SystemProcessRunner(ExecutableResolver resolver, boolean windows, Charset outputCharset) {
        this.resolver = resolver;
        this.windows = windows;
        this.outputCharset = outputCharset;
    }

    @Override
    public ProcessResult run(ProcessSpec spec) {
        List<String> command = commandLine(spec);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (spec.workingDir() != null) {
            builder.directory(spec.workingDir().toFile());
        }
        builder.environment().putAll(spec.environment());

        long start = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ProcessException("Cannot start " + String.join(" ", command) + ": " + e.getMessage(), e);
        }
        closeQuietly(process);

        StringBuilder output = new StringBuilder();
        // A platform thread: virtual threads crash process handling in the Windows native image.
        Thread reader = Thread.ofPlatform().daemon().name("pmm-output").start(() -> pump(process, output, spec.logFile()));
        boolean timedOut = false;
        try {
            if (spec.timeout() == null) {
                process.waitFor();
            } else if (!process.waitFor(spec.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor();
            }
            reader.join();
        } catch (InterruptedException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ProcessException("Interrupted while waiting for " + spec.executable(), e);
        }
        Duration duration = Duration.ofNanos(System.nanoTime() - start);
        synchronized (output) {
            return new ProcessResult(process.exitValue(), output.toString(), duration, timedOut);
        }
    }

    List<String> commandLine(ProcessSpec spec) {
        Path executable = resolver.resolve(spec.executable())
                .orElseThrow(() -> new ProcessException("'" + spec.executable() + "' was not found on the PATH"));
        List<String> command = new ArrayList<>();
        if (windows && ExecutableResolver.isBatchFile(executable)) {
            // .cmd/.bat files (mvn.cmd, mvnw.cmd) cannot be started directly by CreateProcess.
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add(executable.toString());
        command.addAll(spec.arguments());
        return command;
    }

    private void pump(Process process, StringBuilder output, Path logFile) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), outputCharset));
             Writer log = openLog(logFile)) {
            String line;
            while ((line = in.readLine()) != null) {
                synchronized (output) {
                    output.append(line).append('\n');
                }
                if (log != null) {
                    log.write(line);
                    log.write(System.lineSeparator());
                    // Flushed line by line so that the dashboard can follow a running build in its log.
                    log.flush();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Writer openLog(Path logFile) throws IOException {
        if (logFile == null) {
            return null;
        }
        Path parent = logFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);
    }

    private static void closeQuietly(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // The process may already have exited.
        }
    }

    private static Charset nativeCharset() {
        String name = System.getProperty("native.encoding");
        try {
            return name == null ? Charset.defaultCharset() : Charset.forName(name);
        } catch (IllegalArgumentException e) {
            return Charset.defaultCharset();
        }
    }
}
