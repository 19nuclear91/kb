package com.zhishiku.notifier;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class MarkdownTest extends TestSupport {
    @Test
    void mixedGroupRetainsFiringAndCountsWithoutSensitiveMetadata() {
        ObjectNode input = webhook(3);
        alert(input, 1).put("status", "resolved").put("endsAt", "2025-01-02T00:00:00Z");
        input.put("truncatedAlerts", 19);
        Markdown.Message message = Markdown.render(Webhook.decode(BoundedJson.encode(input), settings), settings);
        assertThat(message.text()).contains("告警触发", "firing：2", "resolved：1", "truncatedAlerts：19", "本地省略详情：0",
                "https://grafana.corp.internal/d/kb-parse-index").doesNotContain("question-password-body-canary",
                "sensitive-common-canary", "evil.invalid", "private-fingerprint-canary", "untrusted-group-canary");
        JsonNode payload = BoundedJson.parse(message.payload(), BoundedJson.RESPONSE_BYTES);
        assertThat(payload.get("msgtype").textValue()).isEqualTo("markdown");
        assertThat(payload.get("markdown").get("text").textValue()).isEqualTo(message.text());
    }

    @Test
    void resolvedGroupDoesNotPromiseRecoveryEvenWhenUpstreamTruncates() {
        ObjectNode input = webhook(1);
        input.put("status", "resolved").put("truncatedAlerts", Long.MAX_VALUE);
        alert(input, 0).put("status", "resolved").put("endsAt", "2025-01-02T00:00:00Z");
        Markdown.Message result = Markdown.render(Webhook.decode(BoundedJson.encode(input), settings), settings);
        assertThat(result.text()).contains("告警条件已解除，请核对采样和当前状态", "firing：0", "resolved：1", Long.toString(Long.MAX_VALUE))
                .doesNotContain("全部恢复", "业务已恢复");
    }

    @Test
    void escapingByteBudgetAndLocalOmissionsPreserveUnicodeAndFixedSummary() throws Exception {
        ArrayNode catalog = catalog();
        for (JsonNode item : catalog) {
            ((ObjectNode) item).put("summary", "😀[<&*".repeat(40));
        }
        Files.write(directory.resolve("catalog.json"), BoundedJson.encode(catalog));
        NotifierSettings large = new NotifierSettings(properties());
        ObjectNode input = webhook(100);
        input.put("truncatedAlerts", 8);
        Markdown.Message message = Markdown.render(Webhook.decode(BoundedJson.encode(input), large), large);
        assertThat(Markdown.bytes(message.text())).isLessThanOrEqualTo(3500);
        assertThat(message.title().codePointCount(0, message.title().length())).isLessThanOrEqualTo(80);
        assertThat(message.text()).contains("😀\\[&lt;&amp;\\*", "firing：100", "truncatedAlerts：8", "[管理入口]");
        long included = message.text().lines().filter(line -> line.startsWith("- firing")).count();
        assertThat(included).isLessThan(100);
        assertThat(message.text()).contains("本地省略详情：" + (100 - included));
        assertThat(BoundedJson.utf8(message.text().getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(message.text());
        BoundedJson.validateString(Markdown.truncate("😀".repeat(81), 80));
        assertThat(Markdown.truncate("😀".repeat(81), 80).codePointCount(0, 160)).isEqualTo(80);
    }

    @Test
    void settlementLinksExplainObservationCoverage() {
        ObjectNode input = webhook(1);
        ((ObjectNode) input.get("groupLabels")).put("alertname", "KbSettlementReview");
        ((ObjectNode) input.get("commonLabels")).put("alertname", "KbSettlementReview");
        ((ObjectNode) alert(input, 0).get("labels")).put("alertname", "KbSettlementReview");
        Markdown.Message message = Markdown.render(Webhook.decode(BoundedJson.encode(input), settings), settings);
        assertThat(message.text()).contains("结算以指标面板为准", "AI 审计仅覆盖启用采集后的记录");
    }
}
