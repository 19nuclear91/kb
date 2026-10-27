package com.zhishiku.kb.eval.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.eval.metrics.MetricModel.RunMetrics;
import com.zhishiku.kb.eval.model.InputModel.Dataset;
import com.zhishiku.kb.eval.model.InputModel.LabelCase;
import com.zhishiku.kb.eval.model.InputModel.ResultCase;
import com.zhishiku.kb.eval.model.InputModel.Run;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonReportWriterTest {
    @Test
    void roundTripsPrecisionNullDigestsProvenanceAndCompleteFailedGate() throws Exception {
        EvaluationReport report = ReportFixture.report();
        JsonNode node = new ObjectMapper().readTree(new JsonReportWriter().serialize(report));
        assertThat(node.get("schemaVersion").intValue()).isEqualTo(1);
        assertThat(node.get("metricDefinitionVersion").intValue()).isEqualTo(1);
        assertThat(node.at("/dataset/labelsSha256").textValue()).isEqualTo("b".repeat(64));
        assertThat(node.get("thresholdsSha256").textValue()).isEqualTo("e".repeat(64));
        assertThat(node.at("/runs/0/sourceType").textValue()).isEqualTo("SYNTHETIC");
        assertThat(node.at("/runs/1/sourceType").textValue()).isEqualTo("RECORDED");
        assertThat(node.at("/runs/1/resultsSha256").textValue()).isEqualTo("d".repeat(64));
        assertThat(node.at("/runs/1/overall/metrics/0/value").doubleValue()).isEqualTo(2.0 / 3);
        assertThat(node.at("/runs/1/overall/metrics/0/eligibleCases").intValue()).isEqualTo(1);
        assertThat(node.at("/runs/1/overall/metrics/1/value").isNull()).isTrue();
        assertThat(node.at("/runs/1/cases").size()).isEqualTo(2);
        assertThat(node.at("/comparisons/0/regressions/0/caseIds/0").textValue()).isEqualTo("c1");
        assertThat(node.at("/gate/passed").booleanValue()).isFalse();
        assertThat(node.at("/gate/rules/0/actual").doubleValue()).isEqualTo(2.0 / 3);
        assertThat(node.at("/scope/mode").textValue()).isEqualTo("OFFLINE_FILES_ONLY");
        assertThat(node.at("/scope/algorithmsVerified").booleanValue()).isFalse();
        assertThat(node.at("/scope/livePermissionsVerified").booleanValue()).isFalse();
        assertThat(node.at("/scope/answerQualityVerified").booleanValue()).isFalse();
    }

    @Test
    void safeDtoOutputIsIndependentOfRunOrderingAndContainsNoRawBusinessFields() throws Exception {
        EvaluationReport report = ReportFixture.report();
        List<EvaluationReport.RunSummary> reversed = new ArrayList<>(report.runs());
        Collections.reverse(reversed);
        var reordered = new EvaluationReport(report.schemaVersion(), report.metricDefinitionVersion(), report.scope()
                , report.dataset(),
                report.ks(), report.baselineId(), report.thresholdsSha256(), reversed, report.comparisons(),
                report.gate());
        JsonReportWriter writer = new JsonReportWriter();
        String json = writer.serialize(report);
        assertThat(writer.serialize(reordered)).isEqualTo(json);
        assertThat(json).doesNotContain("query", "content", "docTitle", "token", "password", "http://", "resultsFile"
                , "labelsFile");
        JsonNode node = new ObjectMapper().readTree(json);
        assertThat(node.at("/runs/0/cases/0/caseId").textValue()).isEqualTo("c1");
        assertThat(node.at("/runs/0/groups/0/tag").textValue()).isEqualTo("a_tag");
    }

    @Test
    void projectionNeverSerializesAttachedInputRecordsOrTheirSentinels() {
        var fixture = ReportFixture.report();
        var safe = fixture.runs().getFirst();
        String sentinel = "CONTENT_SENTINEL_QUERY_SENTINEL_TOKEN_SENTINEL";
        var attachedInput = new Run(safe.runId(), safe.configurationId(), safe.sourceType(), safe.manifestSha256(),
                safe.resultsSha256(),
                Map.of(sentinel, new ResultCase(sentinel, List.of(), 0, null)));
        var computation = new RunMetrics(attachedInput, safe.overall(), safe.groups(), safe.cases(), safe.latency());
        var dataset = new Dataset(fixture.dataset().identity(), fixture.dataset().manifestSha256(),
                fixture.dataset().labelsSha256(),
                Map.of("c1", new LabelCase("c1", List.of(), true, Map.of())));
        var report = new ReportFactory().create(dataset, fixture.ks(), List.of(computation), null, List.of(), null,
                fixture.gate());
        assertThat(attachedInput.toString()).contains(sentinel);
        assertThat(new JsonReportWriter().serialize(report)).doesNotContain("CONTENT_SENTINEL", "QUERY_SENTINEL",
                "TOKEN_SENTINEL");
    }
}
