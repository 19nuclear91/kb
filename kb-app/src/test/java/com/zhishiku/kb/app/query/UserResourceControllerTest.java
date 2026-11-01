package com.zhishiku.kb.app.query;

import com.zhishiku.kb.app.document.model.DocumentCapabilities;
import com.zhishiku.kb.app.document.service.DocumentCapabilitiesService;
import com.zhishiku.kb.app.document.web.DocumentCapabilitiesController;
import com.zhishiku.kb.app.query.web.AdminQueryAdvice;
import com.zhishiku.kb.app.space.model.UploadSpaceCandidate;
import com.zhishiku.kb.app.space.service.UploadSpaceQueryService;
import com.zhishiku.kb.app.space.web.UploadSpaceController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 独立 controller 单测，服务均为 Mock，不监听 HTTP 或组装业务链路。
 */
class UserResourceControllerTest {
    @Test
    void minimumProjectionNoStoreAndRawParameterBinding() throws Exception {
        var spaces = mock(UploadSpaceQueryService.class);
        var docs = mock(DocumentCapabilitiesService.class);
        when(spaces.list(any())).thenReturn(new QueryPage<>(List.of(new UploadSpaceCandidate(3, "空间")), false, null));
        when(docs.get(anyString(), any())).thenReturn(new DocumentCapabilities(9, true, false, false, false, false));
        var mvc = MockMvcBuilders.standaloneSetup(new UploadSpaceController(spaces), new DocumentCapabilitiesController(docs))
                .setControllerAdvice(new AdminQueryAdvice()).build();
        mvc.perform(get("/api/auth/upload-spaces").param("limit", "1", "2")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data.items[0].length()").value(2));
        verify(spaces).list(Map.of("limit", List.of("1", "2")));
        mvc.perform(get("/api/documents/9/capabilities")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(6)).andExpect(jsonPath("$.data.canDownload").value(true))
                .andExpect(jsonPath("$.data.canDelete").value(false)).andExpect(jsonPath("$.data.canEditMetadata").value(false));
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 403, 404, 503})
    void errorsHaveSafeStatusAndNoProjection(int code) throws Exception {
        var spaces = mock(UploadSpaceQueryService.class);
        var docs = mock(DocumentCapabilitiesService.class);
        when(spaces.list(any())).thenThrow(new QueryException(code, "private"));
        when(docs.get(anyString(), any())).thenThrow(new QueryException(code, "private"));
        var mvc = MockMvcBuilders.standaloneSetup(new UploadSpaceController(spaces), new DocumentCapabilitiesController(docs))
                .setControllerAdvice(new AdminQueryAdvice()).build();
        for (var path : List.of("/api/auth/upload-spaces", "/api/documents/9/capabilities")) {
            mvc.perform(get(path)).andExpect(status().is(code)).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.data").doesNotExist()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
        }
    }
}
