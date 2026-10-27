package com.zhishiku.kb.app.space.web;

import com.zhishiku.kb.app.space.web.dto.DocumentRelocationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PatchMapping;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证搬迁接口的 OpenAPI 元数据契约，不约束说明文案或源码布局。
 */
class DocumentRelocationControllerDocsTest {

    @Test
    void openApiAnnotationsExposeTheEndpointAndErrorResponses() throws Exception {
        var controller = DocumentRelocationController.class;
        var handler = controller.getDeclaredMethod("relocate", long.class,
                DocumentRelocationRequest.class, String.class);

        assertThat(controller.getAnnotation(Tag.class)).isNotNull();
        assertThat(handler.getAnnotation(Operation.class)).isNotNull();
        assertThat(handler.getAnnotation(PatchMapping.class)).isNotNull();
        var responses = handler.getAnnotation(ApiResponses.class);
        assertThat(responses).isNotNull();
        assertThat(Arrays.stream(responses.value()).map(ApiResponse::responseCode).toList())
                .contains("400", "403", "404", "409");
    }
}
