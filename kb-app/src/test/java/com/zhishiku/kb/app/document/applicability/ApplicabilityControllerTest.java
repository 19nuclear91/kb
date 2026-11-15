package com.zhishiku.kb.app.document.applicability;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.document.applicability.service.ApplicabilityReadService;
import com.zhishiku.kb.app.document.applicability.service.ApplicabilityWriteService;
import com.zhishiku.kb.app.document.applicability.service.ValidityException;
import com.zhishiku.kb.app.document.applicability.web.ApplicabilityController;
import com.zhishiku.kb.app.document.applicability.web.ValidityAdvice;
import com.zhishiku.kb.app.document.applicability.web.ValidityResponseFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
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

class ApplicabilityControllerTest {
    private static final String PATH = "/api/documents/10/versions/20/applicability";
    private static final String INPUT = "{\"effectiveFrom\":null,\"untilKind\":\"DATE\",\"effectiveUntil\":\"2026-12-31\",\"reason\":\"private-reason\",\"expectedApplicabilityVersion\":0}";

    @Test
    void allDomainErrorsHaveSafeReasonCodesAndNoStoreWithoutReasonEcho() throws Exception {
        var writes = mock(ApplicabilityWriteService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ApplicabilityController(mock(ApplicabilityReadService.class), writes))
                .setControllerAdvice(new ValidityAdvice()).addFilters(new ValidityResponseFilter()).build();
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            for (ValidityException.Reason reason : ValidityException.Reason.values()) {
                doThrow(new ValidityException(reason)).when(writes).update(eq(7L), eq(10L), eq(20L), any(), eq("request"));
                var result = mvc.perform(put(PATH).header("X-Request-Id", "request").content(INPUT))
                        .andExpect(status().is(reason.status())).andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.reasonCode").value(reason.name())).andReturn();
                assertThat(result.getResponse().getContentAsString()).doesNotContain("private-reason");
            }
            doThrow(new IllegalStateException("private-database-details")).when(writes).update(eq(7L), eq(10L), eq(20L), any(), eq("request"));
            mvc.perform(put(PATH).header("X-Request-Id", "request").content(INPUT)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.reasonCode").value("UNAVAILABLE"));
        }
    }

    @Test
    void literalBatchPathAndStrictParametersDoNotPassThroughDocumentIdParsing() throws Exception {
        var reads = mock(ApplicabilityReadService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ApplicabilityController(reads, mock(ApplicabilityWriteService.class)))
                .setControllerAdvice(new ValidityAdvice()).addFilters(new ValidityResponseFilter()).build();
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            mvc.perform(post("/api/documents/applicability/query").content("{\"items\":[{\"documentId\":10,\"versionId\":20}]}"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
            verify(reads).batch(eq(7L), any());
            mvc.perform(get(PATH).param("unknown", "1")).andExpect(status().isBadRequest());
            mvc.perform(get(PATH + "/history").param("limit", "1", "2")).andExpect(status().isBadRequest());
            mvc.perform(put(PATH).content(INPUT)).andExpect(status().isBadRequest());
            mvc.perform(put(PATH).header("X-Request-Id", "request").content(new byte[16385])).andExpect(status().isPayloadTooLarge());
            mvc.perform(put(PATH).header("X-Request-Id", "request").queryParam("unknown", "1").content(INPUT)).andExpect(status().isBadRequest());
        }
    }
}
