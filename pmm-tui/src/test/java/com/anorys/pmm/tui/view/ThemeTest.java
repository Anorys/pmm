package com.anorys.pmm.tui.view;

import com.anorys.pmm.tui.demo.DemoWorkspace;
import com.anorys.pmm.tui.model.DashboardState;
import com.anorys.pmm.tui.theme.Glyphs;
import com.anorys.pmm.tui.theme.Theme;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeTest {

    private static final Instant NOW = Instant.parse("2026-10-27T18:30:00Z");

    @Test
    void asciiFallbackUsesNoUnicodeSymbol() {
        String text = render(new Theme(true, Glyphs.ASCII), DashboardState.Tab.WORKSPACE);
        assertThat(text).contains("platform-core").doesNotContain("✔", "✘", "▲", "╭", "━");
    }

    @Test
    void withoutColorsEveryStateStillHasAWord() {
        DashboardState state = DemoWorkspace.state(NOW);
        Buffer buffer = Buffer.empty(Rect.of(150, 36));
        new DashboardView(new Theme(false, Glyphs.UNICODE)).render(Frame.forTesting(buffer), state, NOW);
        boolean colored = false;
        for (int y = 0; y < 36; y++) {
            for (int x = 0; x < 150; x++) {
                colored |= buffer.get(x, y).style().fg().isPresent();
            }
        }
        assertThat(colored).isFalse();
        assertThat(render(new Theme(false, Glyphs.UNICODE), DashboardState.Tab.WORKSPACE)).contains("blocked", "failed", "cached");
    }

    @Test
    void theBrandGradientLoops() {
        assertThat(Theme.brand(0).toRgb()).isEqualTo(Theme.brand(1).toRgb());
        assertThat(Theme.brand(0.25).toRgb()).isEqualTo(Theme.FACET_LIGHT.toRgb());
    }

    private static String render(Theme theme, DashboardState.Tab tab) {
        DashboardState state = DemoWorkspace.state(NOW);
        state.tab(tab);
        Buffer buffer = Buffer.empty(Rect.of(150, 36));
        new DashboardView(theme).render(Frame.forTesting(buffer), state, NOW);
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < 36; y++) {
            for (int x = 0; x < 150; x++) {
                text.append(buffer.get(x, y).symbol());
            }
            text.append('\n');
        }
        return text.toString();
    }
}
