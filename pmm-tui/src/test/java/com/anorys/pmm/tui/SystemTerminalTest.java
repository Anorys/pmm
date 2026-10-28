package com.anorys.pmm.tui;

import com.anorys.pmm.tui.SystemTerminal.TerminalUnavailableException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemTerminalTest {

    @Test
    void loadsTheNativeTerminalSupport() throws Exception {
        assertThat(SystemTerminal.check()).startsWith("jni terminal support loaded");
    }

    @Test
    void refusesADumbTerminal() {
        // The tests run without a console: JLine would fall back to a dumb terminal, with a warning.
        assertThatThrownBy(SystemTerminal::open)
                .isInstanceOf(TerminalUnavailableException.class)
                .hasMessageStartingWith("pmm ui could not open the terminal");
    }
}
