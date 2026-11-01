package com.zhishiku.kb.app.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.document.model.DocumentCapabilities;
import com.zhishiku.kb.common.api.R;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 直接序列化 DTO，不启动容器或连接外部服务。 */
class DocumentCapabilitiesSerializationTest {

    @Test
    void serializedProjectionContainsDeleteAndPreservesExistingFields() throws Exception {
        var json = new ObjectMapper();
        var capabilities = new DocumentCapabilities(Long.MAX_VALUE, false, true, true, true, true);
        var data = json.readTree(json.writeValueAsString(R.ok(capabilities))).get("data");
        assertThat(data.size()).isEqualTo(6);
        assertThat(data.get("documentId").longValue()).isEqualTo(Long.MAX_VALUE);
        assertThat(data.get("canDownload").booleanValue()).isFalse();
        assertThat(data.get("canUploadVersion").booleanValue()).isTrue();
        assertThat(data.get("canRetry").booleanValue()).isTrue();
        assertThat(data.get("canDelete").booleanValue()).isTrue();
        assertThat(data.get("canEditMetadata").booleanValue()).isTrue();
    }

    @Test
    void deniedDeleteIsAnExplicitBoolean() throws Exception {
        var json = new ObjectMapper();
        var data = json.readTree(json.writeValueAsString(new DocumentCapabilities(9, true, false, false, false, false)));
        assertThat(data.get("canDelete").isBoolean()).isTrue();
        assertThat(data.get("canDelete").booleanValue()).isFalse();
    }
}
