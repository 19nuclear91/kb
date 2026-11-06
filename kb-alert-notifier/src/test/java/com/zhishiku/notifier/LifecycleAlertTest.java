package com.zhishiku.notifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleAlertTest extends TestSupport {
    @ParameterizedTest
    @ValueSource(strings = {"KbLifecycleDisconnected", "KbLifecycleBacklogOld", "KbLifecycleEmergencyUnavailable",
            "KbLifecycleInputRejected", "KbLifecycleEventConflict", "KbLifecycleRevocationSlow",
            "KbOutputPermitOverdue", "KbLifecycleDependencyFailures", "KbRevocationTargetUnconfirmed"})
    void fixedCatalogProducesOnlyControlledSummaryAndLink(String name) throws Exception {
        // 解析实际运行配置并生成消息，不做说明文档关键词断言。
        Path source = Path.of("../ops/monitoring/alert-catalog.json");
        if (!Files.exists(source)) {
            source = Path.of("ops/monitoring/alert-catalog.json");
        }
        byte[] bytes = Files.readAllBytes(source);
        ArrayNode catalog = (ArrayNode) BoundedJson.parse(bytes, 64 * 1024);
        JsonNode entry = null;
        for (JsonNode item : catalog) {
            if (item.path("name").asText().equals(name)) {
                entry = item;
            }
        }
        assertThat(entry).isNotNull();
        Files.write(directory.resolve("catalog.json"), bytes);
        var configured = new NotifierSettings(properties());
        ObjectNode input = webhook(1);
        String service = entry.path("services").get(0).asText();
        for (ObjectNode labels : List.of((ObjectNode) input.get("groupLabels"), (ObjectNode) input.get("commonLabels"),
                (ObjectNode) alert(input, 0).get("labels"))) {
            labels.put("alertname", name).put("service", service).put("severity", entry.path("severity").asText());
            labels.put("employee_id", "private-employee-canary").put("event_id", "private-event-canary");
        }
        String message = Markdown.render(Webhook.decode(BoundedJson.encode(input), configured), configured).text();
        assertThat(message).contains("https://admin.corp.internal/admin/identity/lifecycle", "通知不会自动恢复账号")
                .doesNotContain("private-employee-canary", "private-event-canary", "question-password-body-canary", "evil.invalid");
        ((ObjectNode) input.get("groupLabels")).put("alertname", "KbLifecycleUnknown");
        assertThatThrownBy(() -> Webhook.decode(BoundedJson.encode(input), configured)).isInstanceOf(Rejection.class);
    }
}
