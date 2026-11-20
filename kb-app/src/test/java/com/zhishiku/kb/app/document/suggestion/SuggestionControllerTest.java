package com.zhishiku.kb.app.document.suggestion;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.document.metadata.web.MetadataAdvice;
import com.zhishiku.kb.app.document.metadata.web.MetadataResponseFilter;
import com.zhishiku.kb.app.document.metadata.web.MetadataSuggestionController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SuggestionControllerTest {
    @Test
    void acceptedAndTerminalResponsesAreDistinctFromReadFailures() throws Exception {
        var requests = mock(SuggestionRequests.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MetadataSuggestionController(requests))
                .setControllerAdvice(new MetadataAdvice()).addFilters(new MetadataResponseFilter()).build();
        UUID id = UUID.randomUUID();
        String base = "/api/documents/4/metadata-suggestions";
        var start = new SuggestionJob.View(id, 6, 7, "RUNNING", null, null, null, null, null, null, null,
                "NOT_REQUIRED", null, null);
        var failed = new SuggestionJob.View(id, 6, 7, "FAILED", "SNAPSHOT_TRUNCATED", "NOT_INVOKED", 0L, 0L,
                null, 0L, null, "NOT_REQUIRED", null, null);
        var rejected = new SuggestionJob.View(id, 6, 7, "REJECTED", "QUOTA_REJECTED", "NOT_INVOKED", 0L, 0L,
                10L, 0L, null, "PENDING", null, null);
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getTokenValue).thenReturn("session");
            when(requests.create("session", 4, 6L, 7, id)).thenReturn(start);
            mvc.perform(post(base).header("X-Request-Id", id).contentType("application/json")
                            .content("{\"sourceVersionId\":6,\"expectedMetadataVersion\":7}"))
                    .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.data.requestId").value(id.toString()));
            mvc.perform(post(base)).andExpect(status().isBadRequest());
            when(requests.get("session", 4, id)).thenReturn(rejected, failed).thenThrow(new IllegalStateException("私有错误"));
            mvc.perform(get(base + "/" + id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("REJECTED"))
                    .andExpect(jsonPath("$.data.reasonCode").value("QUOTA_REJECTED"));
            mvc.perform(get(base + "/" + id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reasonCode").value("SNAPSHOT_TRUNCATED"))
                    .andExpect(jsonPath("$.data.expiresAt").doesNotExist());
            mvc.perform(get(base + "/" + id)).andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.reasonCode").value("UNAVAILABLE"));
            var expires = java.time.OffsetDateTime.now().plusMinutes(30);
            var completed = new SuggestionJob.View(id, 6, 7, "COMPLETED", null, "KNOWN", 4L, 1L, 8L, 5L, 5L,
                    "SETTLED", expires, null);
            org.mockito.Mockito.doReturn(completed).when(requests).get("session", 4, id);
            mvc.perform(get(base + "/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.data.expiresAt").exists());
        }
    }
}
