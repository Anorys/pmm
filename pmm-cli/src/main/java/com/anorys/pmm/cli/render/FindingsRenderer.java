package com.anorys.pmm.cli.render;

import com.anorys.pmm.core.Severity;
import com.anorys.pmm.core.check.Finding;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Lists findings, most severe first:
 * <pre>
 *   warn   core     3 commits behind origin/develop
 *                   -> rebase feature/PMM-142 onto origin/develop
 * </pre>
 */
public final class FindingsRenderer {

    private FindingsRenderer() {
    }

    /**
     * @param ansi turns picocli markup such as {@code @|red error|@} into terminal colors, or strips it
     */
    public static String render(List<Finding> findings, UnaryOperator<String> ansi) {
        if (findings.isEmpty()) {
            return "No findings." + System.lineSeparator();
        }
        int repoWidth = findings.stream().mapToInt(f -> Objects.toString(f.repository(), "-").length()).max().orElse(1);
        String indent = " ".repeat(2 + 5 + 2 + repoWidth + 2);
        StringBuilder out = new StringBuilder();
        findings.stream()
                .sorted(Comparator.comparing(Finding::severity).reversed())
                .forEach(f -> {
                    String repo = Objects.toString(f.repository(), "-");
                    out.append("  ").append(ansi.apply(label(f.severity())))
                            .append("  ").append(repo).append(" ".repeat(repoWidth - repo.length()))
                            .append("  ").append(f.message()).append(System.lineSeparator());
                    if (f.suggestion() != null) {
                        out.append(indent).append("-> ").append(f.suggestion()).append(System.lineSeparator());
                    }
                });
        return out.toString();
    }

    /** Fixed width (5) so the columns line up once colors are added. */
    static String label(Severity severity) {
        return switch (severity) {
            case ERROR -> "@|bold,red error|@";
            case WARN -> "@|yellow warn |@";
            case INFO -> "@|cyan info |@";
            case OFF -> "off  ";
        };
    }

    /** Summary line: "1 error, 2 warnings, 3 info". */
    public static String summary(List<Finding> findings) {
        long errors = findings.stream().filter(f -> f.severity() == Severity.ERROR).count();
        long warnings = findings.stream().filter(f -> f.severity() == Severity.WARN).count();
        long infos = findings.stream().filter(f -> f.severity() == Severity.INFO).count();
        return errors + (errors == 1 ? " error, " : " errors, ")
                + warnings + (warnings == 1 ? " warning, " : " warnings, ")
                + infos + " info";
    }
}
