package com.anorys.pmm.cli.report;

import com.anorys.pmm.build.BuildChecks.Blocker;
import com.anorys.pmm.build.BuildResult;
import com.anorys.pmm.build.Failure.MissingArtifact;
import com.anorys.pmm.core.Severity;
import com.anorys.pmm.core.check.Finding;
import com.anorys.pmm.git.RepositoryStatus;
import com.anorys.pmm.maven.DependencyChecks.InternalUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;

/**
 * The report as JSON, for scripts and dashboards. Built with Jackson's tree model (no data binding,
 * so nothing to register for the native image). Field names are part of the contract: add, never rename.
 */
public final class JsonReport {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private JsonReport() {
    }

    public static String render(DoctorReport report) {
        ObjectNode root = JSON.createObjectNode();
        root.put("tool", "pmm");
        root.put("version", report.version());
        root.put("generatedAt", report.generatedAt().toString());
        root.put("workspace", report.workspace().toString());
        root.put("fetched", report.fetched());
        root.put("exitCode", report.exitCode());
        ObjectNode summary = root.putObject("summary");
        summary.put("errors", report.count(Severity.ERROR));
        summary.put("warnings", report.count(Severity.WARN));
        summary.put("info", report.count(Severity.INFO));

        ArrayNode repositories = root.putArray("repositories");
        for (RepositoryStatus status : report.repositories()) {
            ObjectNode node = repositories.addObject();
            node.put("name", status.name());
            node.put("base", status.baseRef());
            if (!status.readable()) {
                node.put("error", status.error());
                continue;
            }
            node.put("branch", status.tree().branch());
            node.put("baseMissing", status.baseMissing());
            node.put("ahead", status.ahead());
            node.put("behind", status.behind());
            node.put("clean", status.tree().clean());
            node.put("operation", status.tree().operation().name().toLowerCase(Locale.ROOT));
            if (status.fetchError() != null) {
                node.put("fetchError", status.fetchError());
            }
        }

        ArrayNode dependencies = root.putArray("dependencies");
        for (InternalUsage usage : report.dependencies()) {
            ObjectNode node = dependencies.addObject();
            node.put("consumer", usage.consumer());
            node.put("artifact", usage.artifact().toString());
            node.put("producer", usage.producer());
            node.put("version", usage.version());
            node.put("latest", usage.latest());
            node.put("state", usage.state().name().toLowerCase(Locale.ROOT));
            usage.modules().forEach(node.putArray("modules")::add);
        }

        ArrayNode builds = root.putArray("builds");
        for (BuildResult result : report.builds()) {
            ObjectNode node = builds.addObject();
            node.put("repository", result.repository());
            node.put("status", result.status().name().toLowerCase(Locale.ROOT));
            node.put("durationMillis", result.duration().toMillis());
            node.put("log", result.log() == null ? null : result.log().toString());
            if (result.reason() != null) {
                node.put("reason", result.reason());
            }
            if (result.failure() != null) {
                ObjectNode failure = node.putObject("failure");
                failure.put("kind", result.failure().kind().name().toLowerCase(Locale.ROOT));
                failure.put("module", result.failure().module());
                failure.put("detail", result.failure().detail());
                ArrayNode missing = failure.putArray("missing");
                for (MissingArtifact artifact : result.failure().missing()) {
                    ObjectNode m = missing.addObject();
                    m.put("artifact", artifact.artifact().toString());
                    m.put("version", artifact.version());
                    m.put("producer", artifact.producer());
                }
            }
        }

        ArrayNode blockers = root.putArray("blockers");
        for (Blocker blocker : report.blockers()) {
            ObjectNode node = blockers.addObject();
            node.put("producer", blocker.producer());
            blocker.blocked().stream().sorted().forEach(node.putArray("blocked")::add);
            blocker.artifacts().stream().sorted().forEach(node.putArray("toRelease")::add);
        }

        ArrayNode findings = root.putArray("findings");
        for (Finding finding : report.findings()) {
            ObjectNode node = findings.addObject();
            node.put("check", finding.checkId());
            node.put("severity", finding.severity().id());
            node.put("repository", finding.repository());
            node.put("message", finding.message());
            node.put("suggestion", finding.suggestion());
        }
        report.notes().forEach(root.putArray("notes")::add);
        report.warnings().forEach(root.putArray("warnings")::add);
        try {
            return JSON.writeValueAsString(root) + System.lineSeparator();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
