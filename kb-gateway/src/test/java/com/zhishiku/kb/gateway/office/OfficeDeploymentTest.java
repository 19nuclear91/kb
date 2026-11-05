package com.zhishiku.kb.gateway.office;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.office.OfficeProtocol;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 解析可执行配置，不加载应用上下文，不启动编排，也不读取说明文档。 */
class OfficeDeploymentTest {
    @Test
    @SuppressWarnings("unchecked")
    void optionalComposeBindsDistinctSecretsWithoutPublishingSupplierPorts() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.isDirectory(root.resolve("ops/office"))) {
            root = root.getParent();
            if (root == null) {
                throw new IllegalStateException("找不到编排配置");
            }
        }
        try (var input = Files.newInputStream(root.resolve("ops/office/compose.yml"))) {
            Map<String, Object> compose = new Yaml().load(input);
            Map<String, Map<String, Object>> services = (Map<String, Map<String, Object>>) compose.get("services");
            for (var service : services.values()) {
                assertThat(service.get("profiles")).isEqualTo(List.of("office"));
                assertThat(service).doesNotContainKey("ports");
            }
            var supplier = services.get("office-documents");
            assertThat(supplier.get("image")).isEqualTo(OfficeProtocol.IMAGE);
            Map<String, String> environment = (Map<String, String>) supplier.get("environment");
            var config = new ObjectMapper().readTree(environment.get("NODE_CONFIG"));
            assertThat(config.at("/services/CoAuthoring/secret/inbox/file").asText())
                    .isNotEqualTo(config.at("/services/CoAuthoring/secret/outbox/file").asText());
            assertThat(config.at("/services/CoAuthoring/secret/outbox/string").asText()).isEmpty();
            assertThat(config.at("/externalRequest/action/allow").asBoolean(true)).isFalse();
            assertThat(config.at("/externalRequest/directIfIn/jwtToken").asBoolean(true)).isFalse();
            assertThat(config.at("/services/CoAuthoring/expire/files").asInt()).isBetween(1, 3600);
            Map<String, Map<String, Object>> networks = (Map<String, Map<String, Object>>) compose.get("networks");
            assertThat(networks.get("office-private").get("internal")).isEqualTo(true);
        }
    }
}
