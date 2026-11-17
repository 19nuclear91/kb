package com.zhishiku.kb.app.document.diff;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.document.diff.model.DiffViews.Response;
import com.zhishiku.kb.app.document.diff.service.DiffException;
import com.zhishiku.kb.app.document.diff.service.DocumentDiffService;
import com.zhishiku.kb.app.document.diff.web.DiffAdvice;
import com.zhishiku.kb.app.document.diff.web.DiffResponseFilter;
import com.zhishiku.kb.app.document.diff.web.DocumentDiffController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentDiffControllerTest {
    private static final String PATH = "/api/documents/10/versions/2/diff";

    @Test
    void rejectsInvalidParametersWithoutCallingServiceAndAlwaysNoStore() throws Exception {
        var service = mock(DocumentDiffService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DocumentDiffController(service))
                .setControllerAdvice(new DiffAdvice()).addFilters(new DiffResponseFilter()).build();
        for (String query : List.of("", "?baseVersionId=1&baseVersionId=3", "?baseVersionId=x", "?baseVersionId=2",
                "?baseVersionId=1&unknown=1", "?baseVersionId=0", "?baseVersionId=-1", "?baseVersionId=1.0")) {
            mvc.perform(get(PATH + query)).andExpect(status().isBadRequest())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.reasonCode").value("INVALID_INPUT"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void eachDomainStatusIs200AndCapacityAndDependenciesHaveSafeErrors() throws Exception {
        var service = mock(DocumentDiffService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DocumentDiffController(service))
                .setControllerAdvice(new DiffAdvice()).addFilters(new DiffResponseFilter()).build();
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            for (String outcome : List.of("AVAILABLE", "SNAPSHOT_MISSING", "TOO_LARGE", "TIMEOUT", "LOW_SIMILARITY")) {
                when(service.compare(7, 10, 1, 2)).thenReturn(new Response(10, null, null, outcome,
                        List.of(), List.of(), null, List.of(), List.of()));
                mvc.perform(get(PATH).param("baseVersionId", "1")).andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.data.status").value(outcome));
            }
            when(service.compare(7, 10, 1, 2)).thenThrow(new DiffException(429, "DIFF_CAPACITY"));
            mvc.perform(get(PATH).param("baseVersionId", "1")).andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.data").isEmpty()).andExpect(jsonPath("$.reasonCode").value("DIFF_CAPACITY"));
            doThrow(new IllegalStateException("private-body")).when(service).compare(7, 10, 1, 2);
            mvc.perform(get(PATH).param("baseVersionId", "1")).andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.reasonCode").value("UNAVAILABLE"));
        }
    }
}
