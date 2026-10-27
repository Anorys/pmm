package com.anorys.pmm.cli.report;

import com.anorys.pmm.build.BuildChecks.Blocker;
import com.anorys.pmm.build.BuildResult;
import com.anorys.pmm.core.Severity;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.git.RepositoryStatus;
import com.anorys.pmm.maven.DependencyChecks.InternalUsage;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;

/**
 * The report as a single HTML file: no script, no external resource, so it opens from a CI artifact,
 * an e-mail or a network share, and prints well. Light and dark themes follow the system.
 */
public final class HtmlReport {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private static final String STYLE = """
            :root { --bg:#ffffff; --fg:#1f2328; --muted:#59636e; --line:#d1d9e0; --head:#f6f8fa;
                    --error:#cf222e; --warn:#9a6700; --info:#0969da; --ok:#1a7f37; }
            @media (prefers-color-scheme: dark) {
              :root { --bg:#0d1117; --fg:#e6edf3; --muted:#9198a1; --line:#3d444d; --head:#151b23;
                      --error:#ff7b72; --warn:#d29922; --info:#4493f8; --ok:#3fb950; } }
            * { box-sizing: border-box; }
            body { margin: 0 auto; max-width: 1200px; padding: 24px 16px; background: var(--bg); color: var(--fg);
                   font: 14px/1.5 -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
            h1 { font-size: 22px; margin: 0 0 4px; } h2 { font-size: 17px; margin: 32px 0 8px; }
            .meta, .muted { color: var(--muted); }
            .summary { display: flex; gap: 12px; flex-wrap: wrap; margin: 16px 0; }
            .tile { border: 1px solid var(--line); border-radius: 8px; padding: 8px 16px; min-width: 120px; }
            .tile b { display: block; font-size: 24px; }
            .scroll { overflow-x: auto; }
            table { border-collapse: collapse; width: 100%; }
            th, td { text-align: left; padding: 6px 10px; border-bottom: 1px solid var(--line); vertical-align: top; }
            th { background: var(--head); font-weight: 600; white-space: nowrap; }
            td.num { text-align: right; font-variant-numeric: tabular-nums; }
            code { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12px; }
            .error { color: var(--error); font-weight: 600; } .warn { color: var(--warn); font-weight: 600; }
            .info { color: var(--info); } .ok { color: var(--ok); }
            .suggestion { color: var(--muted); }
            ul { margin: 4px 0; padding-left: 20px; }
            """;

    private HtmlReport() {
    }

    public static String render(DoctorReport report) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>pmm doctor</title>\n<style>\n").append(STYLE).append("</style>\n</head>\n<body>\n");
        html.append("<h1>pmm doctor</h1>\n<div class=\"meta\">").append(e(report.workspace().toString()))
                .append(" &middot; ").append(DATE.format(report.generatedAt().atZone(ZoneId.systemDefault())))
                .append(" &middot; pmm ").append(e(report.version()))
                .append(report.fetched() ? "" : " &middot; not fetched").append("</div>\n");

        html.append("<div class=\"summary\">\n")
                .append(tile("error", report.count(Severity.ERROR), "errors"))
                .append(tile("warn", report.count(Severity.WARN), "warnings"))
                .append(tile("info", report.count(Severity.INFO), "info"))
                .append(tile(report.exitCode() == 0 ? "ok" : "error", report.exitCode(), "exit code"))
                .append("</div>\n");

        if (!report.blockers().isEmpty()) {
            html.append("<h2>Blockers</h2>\n<ul>\n");
            for (Blocker blocker : report.blockers()) {
                html.append("<li><b>").append(e(blocker.producer())).append("</b> blocks ")
                        .append(e(String.join(", ", blocker.blocked().stream().sorted().toList())))
                        .append(" <span class=\"muted\">(to release: ")
                        .append(e(String.join(", ", blocker.artifacts().stream().sorted().toList()))).append(")</span></li>\n");
            }
            html.append("</ul>\n");
        }

        html.append("<h2>Findings</h2>\n");
        if (report.findings().isEmpty()) {
            html.append("<p class=\"ok\">No findings.</p>\n");
        } else {
            html.append(tableStart("Severity", "Repository", "Check", "Message"));
            report.findings().stream().sorted(Comparator.comparing(Finding::severity).reversed()).forEach(f -> html
                    .append("<tr><td class=\"").append(f.severity().id()).append("\">").append(f.severity().id())
                    .append("</td><td>").append(e(DoctorReport.repositoryOf(f))).append("</td><td><code>")
                    .append(e(f.checkId())).append("</code></td><td>").append(e(f.message()))
                    .append(f.suggestion() == null ? "" : "<div class=\"suggestion\">&rarr; " + e(f.suggestion()) + "</div>")
                    .append("</td></tr>\n"));
            html.append(tableEnd());
        }

        html.append("<h2>Branches</h2>\n").append(tableStart("Repository", "Branch", "Base", "Ahead", "Behind", "Local"));
        for (RepositoryStatus status : report.repositories()) {
            if (!status.readable()) {
                html.append(row(status.name(), "-", status.baseRef(), "-", "-", status.error()));
                continue;
            }
            boolean known = !status.baseMissing();
            html.append("<tr><td>").append(e(status.name())).append("</td><td>")
                    .append(e(Objects.toString(status.tree().branch(), "(detached)"))).append("</td><td>")
                    .append(e(status.baseRef())).append("</td><td class=\"num\">").append(known ? status.ahead() : "-")
                    .append("</td><td class=\"num\">").append(known ? status.behind() : "-").append("</td><td>")
                    .append(status.tree().clean() ? "clean" : "local changes").append("</td></tr>\n");
        }
        html.append(tableEnd());

        html.append("<h2>Dependencies between repositories</h2>\n");
        if (report.dependencies().isEmpty()) {
            html.append("<p class=\"muted\">No repository uses an artifact of another monitored repository.</p>\n");
        } else {
            html.append(tableStart("Repository", "Uses", "From", "Version", "Latest", "State"));
            for (InternalUsage usage : report.dependencies()) {
                html.append(row(usage.consumer(), usage.artifact().artifactId(), Objects.toString(usage.producer(), "-"),
                        usage.version(), Objects.toString(usage.latest(), "-"), usage.state().label()));
            }
            html.append(tableEnd());
        }

        if (!report.builds().isEmpty()) {
            html.append("<h2>Builds</h2>\n").append(tableStart("Repository", "Result", "Time", "Detail"));
            for (BuildResult result : report.builds()) {
                String detail = result.reason() != null ? result.reason()
                        : result.failure() == null ? "" : Objects.toString(result.failure().detail(), "");
                String css = result.status().ok() ? "ok" : result.status() == BuildResult.Status.SKIPPED ? "muted" : "error";
                html.append("<tr><td>").append(e(result.repository())).append("</td><td class=\"").append(css).append("\">")
                        .append(result.status().name().toLowerCase(Locale.ROOT).replace('_', ' ')).append("</td><td class=\"num\">")
                        .append(result.duration().toSeconds()).append(" s</td><td>").append(e(detail))
                        .append(result.log() == null ? "" : "<div class=\"muted\"><code>" + e(relative(report, result.log())) + "</code></div>")
                        .append("</td></tr>\n");
            }
            html.append(tableEnd());
        }

        if (!report.notes().isEmpty() || !report.warnings().isEmpty()) {
            html.append("<h2>Notes</h2>\n<ul>\n");
            report.notes().forEach(n -> html.append("<li>").append(e(n)).append("</li>\n"));
            report.warnings().forEach(w -> html.append("<li class=\"warn\">").append(e(w)).append("</li>\n"));
            html.append("</ul>\n");
        }
        return html.append("</body>\n</html>\n").toString();
    }

    private static String tile(String css, long value, String label) {
        return "<div class=\"tile\"><b class=\"" + css + "\">" + value + "</b>" + label + "</div>\n";
    }

    private static String tableStart(String... headers) {
        StringBuilder html = new StringBuilder("<div class=\"scroll\"><table>\n<tr>");
        for (String header : headers) {
            html.append("<th>").append(header).append("</th>");
        }
        return html.append("</tr>\n").toString();
    }

    private static String tableEnd() {
        return "</table></div>\n";
    }

    private static String row(String... cells) {
        StringBuilder html = new StringBuilder("<tr>");
        for (String cell : cells) {
            html.append("<td>").append(e(cell)).append("</td>");
        }
        return html.append("</tr>\n").toString();
    }

    /** Logs live under the workspace; the relative path is shorter and the same on every machine. */
    private static String relative(DoctorReport report, java.nio.file.Path log) {
        return log.startsWith(report.workspace()) ? report.workspace().relativize(log).toString() : log.toString();
    }

    private static String e(String text) {
        return Xml.escape(text);
    }
}
