package com.zhishiku.kb.app.document.stewardship;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.document.applicability.service.ValidityException;
import com.zhishiku.kb.app.document.applicability.web.ValidityAdvice;
import com.zhishiku.kb.app.document.applicability.web.ValidityResponseFilter;
import com.zhishiku.kb.app.document.stewardship.service.StewardshipCandidateService;
import com.zhishiku.kb.app.document.stewardship.service.StewardshipConfirmService;
import com.zhishiku.kb.app.document.stewardship.service.StewardshipQueryService;
import com.zhishiku.kb.app.document.stewardship.service.StewardshipWriteService;
import com.zhishiku.kb.app.document.stewardship.web.StewardshipCandidateController;
import com.zhishiku.kb.app.document.stewardship.web.StewardshipController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StewardshipControllerTest {
    @Test
    void invalidCyclesAndUnknownDuplicateOrOversizedInputsAreRejected() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new StewardshipController(mock(StewardshipQueryService.class),
                mock(StewardshipWriteService.class), mock(StewardshipConfirmService.class)))
                .setControllerAdvice(new ValidityAdvice()).addFilters(new ValidityResponseFilter()).build();
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            for (int cycle : java.util.List.of(1, 9)) {
                mvc.perform(put("/api/admin/documents/10/stewardship").header("X-Request-Id", "request")
                        .content("{\"responsibleUserId\":8,\"cycleMonths\":" + cycle + ",\"expectedStewardshipVersion\":0}"))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reasonCode").value("INVALID_INPUT"));
            }
            mvc.perform(get("/api/documents/stewardship").param("view", "all", "attention")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/documents/stewardship/count").param("unknown", "1")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/admin/spaces/2/stewardship")).andExpect(status().isBadRequest());
            mvc.perform(post("/api/documents/10/stewardship/confirm").header("X-Request-Id", "request")
                    .content("{\"expectedStewardshipVersion\":1,\"expectedStewardshipVersion\":1}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(put("/api/admin/documents/10/stewardship").header("X-Request-Id", "request").content(new byte[16385]))
                    .andExpect(status().isPayloadTooLarge()).andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    @Test
    void literalPersonalListCountAndCandidatePathsAreRoutedAndNoStoreIsApplied() throws Exception {
        var reads = mock(StewardshipQueryService.class);
        var candidates = mock(StewardshipCandidateService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new StewardshipController(reads, mock(StewardshipWriteService.class),
                mock(StewardshipConfirmService.class)), new StewardshipCandidateController(candidates))
                .setControllerAdvice(new ValidityAdvice()).addFilters(new ValidityResponseFilter()).build();
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            mvc.perform(get("/api/documents/stewardship")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
            verify(reads).mine(7, "attention", 20, null);
            mvc.perform(get("/api/documents/stewardship/count")).andExpect(status().isOk());
            verify(reads).count(7);
            mvc.perform(post("/api/admin/documents/10/stewardship/candidates").content("{}"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
            verify(candidates).query(eq(7L), eq(10L), any());
            doThrow(new ValidityException(ValidityException.Reason.STEWARD_INELIGIBLE)).when(candidates).query(eq(7L), eq(10L), any());
            mvc.perform(post("/api/admin/documents/10/stewardship/candidates").content("{\"nameQuery\":\"private-name\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.reasonCode").value("STEWARD_INELIGIBLE"));
        }
    }
}
