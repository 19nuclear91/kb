package com.zhishiku.kb.app.aiusage;

import com.zhishiku.kb.app.aiusage.service.AiUsageReadService;
import com.zhishiku.kb.app.aiusage.web.AiUsageQueryController;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.web.AdminQueryAdvice;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

class AiUsageControllerTest {
    @Test
    void fourMappingsPreserveExactStringsNullAndNoStore() throws Exception {
        var service = mock(AiUsageReadService.class);
        var metrics = new HashMap<String, Object>(); metrics.put("promptTokens", null); metrics.put("callCount", "9007199254740993");
        when(service.usage(any(), anyBoolean())).thenReturn(Map.of("metrics", metrics));
        when(service.audits(any())).thenReturn(Map.of("items", List.of()));
        when(service.detail(anyString())).thenReturn(Map.of("eventId", "fixed"));
        var mvc = MockMvcBuilders.standaloneSetup(new AiUsageQueryController(service)).setControllerAdvice(new AdminQueryAdvice()).build();
        for (String path : List.of("usage", "usage/groups")) {
            mvc.perform(get("/api/admin/ai/" + path).param("limit", "1", "2")).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.data.metrics.callCount").value("9007199254740993"))
                    .andExpect(jsonPath("$.data.metrics.promptTokens").value(nullValue()));
        }
        mvc.perform(get("/api/admin/ai/audits")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/admin/ai/audits/fixed")).andExpect(status().isOk()).andExpect(jsonPath("$.data.eventId").value("fixed"));
        verify(service).usage(eq(Map.of("limit", List.of("1", "2"))), eq(false));
        verify(service).usage(eq(Map.of("limit", List.of("1", "2"))), eq(true));
    }

    @Test
    void safeFailuresKeepHttpStatusAndNeverExposeParametersOrDependencyDetails() throws Exception {
        var service = mock(AiUsageReadService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AiUsageQueryController(service)).setControllerAdvice(new AdminQueryAdvice()).build();
        for (var failure : Map.of(400, QueryException.invalid(), 401, com.zhishiku.kb.common.security.IdentityException.unauthorized("private-token"),
                403, QueryException.forbidden(), 404, QueryException.missing(), 503, new DataAccessResourceFailureException("private-db")).entrySet()) {
            doThrow(failure.getValue()).when(service).usage(any(), anyBoolean());
            doThrow(failure.getValue()).when(service).audits(any());
            doThrow(failure.getValue()).when(service).detail(any());
            for (String path : List.of("usage", "usage/groups", "audits", "audits/private-id"))
                mvc.perform(get("/api/admin/ai/" + path).param("cursor", "private-cursor"))
                        .andExpect(status().is(failure.getKey())).andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(content().string(not(containsString("private-"))));
        }
    }
}
