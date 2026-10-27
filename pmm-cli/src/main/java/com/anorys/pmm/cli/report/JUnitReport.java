package com.anorys.pmm.cli.report;

import com.anorys.pmm.core.Severity;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.git.RepositoryStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The report as JUnit XML, which almost every CI server can display. One test suite per category of
 * checks (graph, git, deps, build), one test case per repository: it fails when that repository has an
 * error finding in that category; warnings and information go to the test case's output.
 */
public final class JUnitReport {

    private static final List<String> CATEGORIES = List.of("graph", "git", "deps", "build");

    private JUnitReport() {
    }

    public static String render(DoctorReport report) {
        Set<String> repositories = new LinkedHashSet<>();
        report.repositories().stream().map(RepositoryStatus::name).forEach(repositories::add);
        report.findings().stream().map(DoctorReport::repositoryOf).forEach(repositories::add);

        Map<String, Map<String, List<Finding>>> byCategory = new LinkedHashMap<>();
        for (String category : CATEGORIES) {
            byCategory.put(category, new LinkedHashMap<>());
        }
        for (Finding finding : report.findings()) {
            byCategory.computeIfAbsent(DoctorReport.category(finding), k -> new LinkedHashMap<>())
                    .computeIfAbsent(DoctorReport.repositoryOf(finding), k -> new ArrayList<>()).add(finding);
        }

        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        int tests = 0;
        int failures = 0;
        StringBuilder suites = new StringBuilder();
        for (Map.Entry<String, Map<String, List<Finding>>> category : byCategory.entrySet()) {
            if (category.getKey().equals("build") && report.builds().isEmpty() && category.getValue().isEmpty()) {
                continue;
            }
            List<String> names = new ArrayList<>(repositories);
            if (category.getKey().equals("graph")) {
                names = List.of(DoctorReport.WORKSPACE);
            }
            int suiteFailures = 0;
            StringBuilder cases = new StringBuilder();
            for (String repository : names) {
                List<Finding> findings = category.getValue().getOrDefault(repository, List.of());
                List<Finding> errors = findings.stream().filter(f -> f.severity() == Severity.ERROR).toList();
                cases.append("    <testcase classname=\"pmm.").append(Xml.escape(category.getKey()))
                        .append("\" name=\"").append(Xml.escape(repository)).append("\" time=\"0\">\n");
                if (!errors.isEmpty()) {
                    suiteFailures++;
                    cases.append("      <failure type=\"").append(Xml.escape(errors.get(0).checkId()))
                            .append("\" message=\"").append(Xml.escape(errors.get(0).message())).append("\">")
                            .append(Xml.escape(describe(errors))).append("</failure>\n");
                }
                List<Finding> others = findings.stream().filter(f -> f.severity() != Severity.ERROR).toList();
                if (!others.isEmpty()) {
                    cases.append("      <system-out>").append(Xml.escape(describe(others))).append("</system-out>\n");
                }
                cases.append("    </testcase>\n");
            }
            tests += names.size();
            failures += suiteFailures;
            suites.append("  <testsuite name=\"pmm.").append(Xml.escape(category.getKey()))
                    .append("\" tests=\"").append(names.size()).append("\" failures=\"").append(suiteFailures)
                    .append("\" errors=\"0\" skipped=\"0\" timestamp=\"").append(report.generatedAt()).append("\">\n")
                    .append(cases).append("  </testsuite>\n");
        }
        xml.append("<testsuites name=\"pmm doctor\" tests=\"").append(tests).append("\" failures=\"").append(failures)
                .append("\" errors=\"0\">\n").append(suites).append("</testsuites>\n");
        return xml.toString();
    }

    private static String describe(List<Finding> findings) {
        StringBuilder text = new StringBuilder();
        for (Finding finding : findings) {
            text.append(finding.severity().id()).append(" [").append(finding.checkId()).append("] ")
                    .append(finding.message()).append('\n');
            if (finding.suggestion() != null) {
                text.append("  -> ").append(finding.suggestion()).append('\n');
            }
        }
        return text.toString();
    }
}
