package com.anorys.pmm.tui;

import com.anorys.pmm.tui.demo.DemoWorkspace;
import com.anorys.pmm.tui.model.DashboardState;
import com.anorys.pmm.tui.model.RepoState;
import com.anorys.pmm.tui.service.DemoService;
import com.anorys.pmm.tui.theme.Theme;
import com.anorys.pmm.tui.view.DashboardView;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.export.ExportRequest;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the branch actions of the dashboard with keys, against the demo workspace, with the
 * background work run inline. With {@code -Dpmm.screenshots=<dir>} the screens are written as SVG.
 */
class BranchActionsTest {

    private static final Instant NOW = Instant.parse("2026-10-27T18:30:00Z");

    private PmmDashboard dashboard;

    @BeforeEach
    void setUp() {
        dashboard = new PmmDashboard(new DemoService(), DemoWorkspace.state(NOW), Theme.standard(), true);
    }

    private DashboardState state() {
        return dashboard.state();
    }

    private void select(String repository) {
        state().select(DemoWorkspace.names().indexOf(repository));
    }

    private void press(char c) {
        dashboard.handle(KeyEvent.ofChar(c), null);
    }

    private void press(KeyCode code) {
        dashboard.handle(KeyEvent.ofKey(code), null);
    }

    private RepoState repo(String name) {
        return state().repository(name).orElseThrow();
    }

    @Test
    void developIsFastForwardedFromItsRemote() throws IOException {
        select("billing");
        press('u');

        String screen = shoot("update-develop");
        assertThat(state().menu().title()).isEqualTo("Update billing");
        assertThat(state().menu().selected().id()).isEqualTo("ff");
        assertThat(screen).contains("develop  ←  origin/develop", "push-protected", "fast-forward", "reset --hard", "suggested");

        press(KeyCode.ENTER);

        assertThat(state().menu()).isNull();
        assertThat(state().toast().message()).isEqualTo("billing: develop fast-forwarded by 12 commits of origin/develop");
        assertThat(repo("billing").git().behind()).isZero();
    }

    @Test
    void aFeatureBranchPicksItsTargetAutostashAndStrategy() throws IOException {
        select("platform-core");
        press('r');
        assertThat(state().menu().selected().id()).isEqualTo("rebase");
        assertThat(state().menu().description().get(0).text()).contains("uncommitted change");

        press('a');
        assertThat(state().menu().header().get(1).text()).contains("autostash on");
        assertThat(state().menu().selected().id()).isEqualTo("rebase");
        String screen = shoot("update-feature");
        assertThat(screen).contains("● origin/develop", "○ origin/feature/PMM-142-cache", "needs a force push");

        press('t');
        assertThat(state().menu().header().get(2).text()).startsWith("from   ● origin/feature/PMM-142-cache");
        press('t');
        press('j');
        press('j');
        press('j');
        assertThat(state().menu().selected().id()).isEqualTo("reset-hard");
        press(KeyCode.ENTER);
        assertThat(state().dialog().title()).isEqualTo("reset --hard feature/PMM-142-cache?");
        shoot("update-reset-hard");
        press('y');
        assertThat(repo("platform-core").git().ahead()).isZero();
        assertThat(repo("platform-core").git().tree().changed()).isZero();
    }

    @Test
    void switchingParksTheChangesAndBringsThemBack() throws IOException {
        select("platform-core");
        press('s');
        assertThat(state().menu().title()).isEqualTo("Switch platform-core");
        for (char c : "api".toCharArray()) {
            press(c);
        }
        String screen = shoot("switch");
        assertThat(state().menu().visible()).extracting(i -> i.id()).containsExactly("feature/PMM-170-api-v2");
        assertThat(screen).contains("remote only");

        press(KeyCode.ENTER);
        assertThat(state().menu().title()).isEqualTo("Uncommitted changes");
        shoot("switch-stash");
        press(KeyCode.ENTER);
        assertThat(repo("platform-core").git().tree().branch()).isEqualTo("feature/PMM-170-api-v2");
        assertThat(state().toast().message()).contains("4 changes parked with feature/PMM-142-cache");

        press('s');
        assertThat(state().menu().visible()).anyMatch(i -> i.id().equals("feature/PMM-142-cache") && i.note().equals("parked changes"));
        state().menu().select("feature/PMM-142-cache");
        press(KeyCode.ENTER);
        assertThat(state().toast().message()).contains("are back");
        assertThat(repo("platform-core").git().tree().changed()).isEqualTo(4);
    }

    @Test
    void everyRepositorySwitchesAtOnceAndBehindBranchesFastForwardTogether() throws IOException {
        press('U');
        String screen = shoot("update-all");
        assertThat(state().dialog().title()).isEqualTo("Fast-forward 1 repository?");
        assertThat(screen).contains("billing", "+12 from origin/develop", "platform-core", "skipped: 4 uncommitted changes");
        press('y');
        assertThat(state().toast().message()).startsWith("1 fast-forwarded, 12 commits brought in");

        select("billing");
        press('s');
        press(KeyCode.TAB);
        assertThat(state().menu().title()).isEqualTo("Switch every repository");
        state().menu().select("release/3.1");
        press(KeyCode.ENTER);
        assertThat(state().menu().title()).isEqualTo("Uncommitted changes");
        press(KeyCode.ENTER);
        assertThat(state().repositories()).allMatch(r -> r.git().tree().branch().equals("release/3.1"));
    }

    private String shoot(String name) throws IOException {
        int width = 150;
        int height = 40;
        Buffer buffer = Buffer.empty(Rect.of(width, height));
        new DashboardView(Theme.standard()).render(Frame.forTesting(buffer), state(), NOW);
        String dir = System.getProperty("pmm.screenshots");
        if (dir != null) {
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
