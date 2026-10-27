package com.anorys.pmm.core.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs small Java source-file programs, so the tests behave the same on Linux and Windows. */
class SystemProcessRunnerTest {

    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    @TempDir
    Path tmp;

    private final SystemProcessRunner runner = new SystemProcessRunner();

    @Test
    void capturesOutputErrorStreamAndExitCode() throws IOException {
        Path program = program("Hello", """
                System.out.println("hello " + args[0]);
                System.err.println("on stderr");
                System.exit(3);
                """);

        ProcessResult result = runner.run(ProcessSpec.of(JAVA, program.toString(), "pmm").in(tmp).build());

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.succeeded()).isFalse();
        assertThat(result.lines()).contains("hello pmm", "on stderr");
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void passesEnvironmentAndWritesLogFile() throws IOException {
        Path program = program("Env", "System.out.println(System.getenv(\"PMM_TEST\"));");
        Path log = tmp.resolve("logs/run.log");

        ProcessResult result = runner.run(ProcessSpec.of(JAVA, program.toString())
                .env("PMM_TEST", "value-42")
                .logTo(log)
                .build());

        assertThat(result.succeeded()).isTrue();
        assertThat(result.output()).contains("value-42");
        assertThat(Files.readString(log)).contains("value-42");
    }

    @Test
    void closesStandardInput() throws IOException {
        Path program = program("Stdin", "System.out.println(System.in.read());");

        ProcessResult result = runner.run(ProcessSpec.of(JAVA, program.toString())
                .timeout(Duration.ofSeconds(60))
                .build());

        assertThat(result.timedOut()).isFalse();
        assertThat(result.lines()).contains("-1");
    }

    @Test
    void killsProcessOnTimeout() throws IOException {
        Path program = program("Sleep", "Thread.sleep(60_000);");

        ProcessResult result = runner.run(ProcessSpec.of(JAVA, program.toString())
                .timeout(Duration.ofSeconds(3))
                .build());

        assertThat(result.timedOut()).isTrue();
        assertThat(result.succeeded()).isFalse();
        assertThat(result.duration()).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void reportsMissingExecutable() {
        assertThatThrownBy(() -> runner.run(ProcessSpec.of("pmm-no-such-tool-xyz").build()))
                .isInstanceOf(ProcessException.class)
                .hasMessageContaining("pmm-no-such-tool-xyz")
                .hasMessageContaining("not found");
    }

    private Path program(String name, String body) throws IOException {
        String source = "public class " + name + " { public static void main(String[] args) throws Exception {\n"
                + body + "\n} }\n";
        return Files.writeString(tmp.resolve(name + ".java"), source);
    }
}
