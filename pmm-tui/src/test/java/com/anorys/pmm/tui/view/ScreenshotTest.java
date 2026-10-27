package com.anorys.pmm.tui.view;

import com.anorys.pmm.tui.demo.DemoWorkspace;
import com.anorys.pmm.tui.model.DashboardState;
import com.anorys.pmm.tui.model.DashboardState.Tab;
import com.anorys.pmm.tui.theme.Theme;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.export.ExportRequest;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders every screen of the demo workspace. With {@code -Dpmm.screenshots=<dir>} it also writes them
 * as SVG files, which is how the screenshots of the documentation are made.
 */
class ScreenshotTest {

    private static final Instant NOW = Instant.parse("2026-10-27T18:30:00Z");

    @Test
    void workspace() throws IOException {
        String text = shoot("workspace", DemoWorkspace::state, Tab.WORKSPACE, 150, 40);
        assertThat(text).contains("platform-core", "feature/PMM-142-cache", "REPOSITORY", "FINDINGS");
    }

    @Test
    void building() throws IOException {
        String text = shoot("build", DemoWorkspace::building, Tab.BUILD, 150, 36, 4);
        assertThat(text).contains("billing");
    }

    @Test
    void dependencies() throws IOException {
        String text = shoot("dependencies", DemoWorkspace::state, Tab.DEPENDENCIES, 150, 36);
        assertThat(text).contains("invoicing");
    }

    @Test
    void graph() throws IOException {
        String text = shoot("graph", DemoWorkspace::state, Tab.GRAPH, 150, 36);
        assertThat(text).contains("customer-portal");
    }

    @Test
    void smallestTerminal() throws IOException {
        String text = shoot("small", DemoWorkspace::state, Tab.WORKSPACE, 80, 24);
        assertThat(text).contains("platform-core");
    }

    @Test
    void tooSmall() throws IOException {
        assertThat(shoot(null, DemoWorkspace::state, Tab.WORKSPACE, 60, 20)).contains("Enlarge the terminal");
    }

    static String shoot(String name, Function<Instant, DashboardState> demo, Tab tab, int width, int height) throws IOException {
        return shoot(name, demo, tab, width, height, 1);
    }

    static String shoot(String name, Function<Instant, DashboardState> demo, Tab tab, int width, int height, int selected)
            throws IOException {
        DashboardState state = demo.apply(NOW);
        state.tab(tab);
        state.select(selected);
        Buffer buffer = Buffer.empty(Rect.of(width, height));
        new DashboardView(Theme.standard()).render(Frame.forTesting(buffer), state, NOW);
        String dir = System.getProperty("pmm.screenshots");
        if (dir != null && name != null) {
            Path out = Path.of(dir);
            Files.createDirectories(out);
            ExportRequest.export(buffer).svg().toFile(out.resolve(name + ".svg"));
        }
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                text.append(buffer.get(x, y).symbol());
            }
            text.append('\n');
        }
        return text.toString();
    }
}
