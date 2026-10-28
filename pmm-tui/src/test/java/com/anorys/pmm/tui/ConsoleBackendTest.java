package com.anorys.pmm.tui;

import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleBackendTest {

    @Test
    void sendsAFrameOnlyWhenFlushed() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        // A dumb terminal writes synchronously, unlike a pseudo-terminal and its pump thread.
        try (Terminal terminal = new DumbTerminal("test", "xterm-256color",
                new ByteArrayInputStream(new byte[0]), output, StandardCharsets.UTF_8)) {
            ConsoleBackend backend = new ConsoleBackend(terminal);
            backend.beginSynchronizedUpdate();
            backend.writeRaw("\033[1;1Hpmm");
            backend.hideCursor();
            backend.endSynchronizedUpdate();
            assertThat(output.size()).isZero();

            backend.flush();
            assertThat(output.toString(StandardCharsets.UTF_8))
                    .isEqualTo("\033[?2026h\033[1;1Hpmm\033[?25l\033[?2026l");
        }
    }
}
