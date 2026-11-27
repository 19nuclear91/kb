package com.zhishiku.kb.app.controller.subscription;

import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.config.EpochAuthenticationInterceptor;
import com.zhishiku.kb.app.config.KnowledgeSubscriptionResponseFilter;
import com.zhishiku.kb.app.domain.vo.subscription.SubscriptionViews;
import com.zhishiku.kb.app.service.subscription.KnowledgeUpdateReadService;
import com.zhishiku.kb.app.service.subscription.SubscriptionFailure;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeUpdateControllerTest {
    private final KnowledgeUpdateReadService reads = mock(KnowledgeUpdateReadService.class);
    private final EpochSessionService.Session actor = new EpochSessionService.Session(7, 1, null, false, Instant.EPOCH);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new KnowledgeUpdateController(reads))
            .setControllerAdvice(new SubscriptionAdvice()).addFilter(new KnowledgeSubscriptionResponseFilter()).build();

    @Test
    void rejectsBadWeeksDuplicateOrUnknownQueryAndInvalidIdentifiersBeforeReads() throws Exception {
        for (String week : List.of("2026-11-24", "2026-02-30", "2026-1-5")) {
            mvc.perform(get("/api/knowledge-updates").param("weekStart", week).requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor))
                    .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(get("/api/knowledge-updates").param("limit", "1", "2").requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/knowledge-digests").param("userId", "8").requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/knowledge-updates/1-1-1-1-1").requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor)).andExpect(status().isBadRequest());
        verifyNoInteractions(reads);
    }

    @Test
    void wrapsExactWeekPageAndHidesMissingSourcesWithoutSwallowingDependencyFailures() throws Exception {
        when(reads.updates(7, LocalDate.of(2026, 11, 23), 20, null)).thenReturn(new SubscriptionViews.Page<>(List.of(), false, null, Instant.EPOCH));
        mvc.perform(get("/api/knowledge-updates").param("weekStart", "2026-11-23").requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray()).andExpect(header().string("Cache-Control", "no-store"));
        UUID id = UUID.randomUUID();
        when(reads.detail(7, id)).thenThrow(SubscriptionFailure.hidden());
        mvc.perform(get("/api/knowledge-updates/" + id).requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor))
                .andExpect(status().isNotFound());
        when(reads.digests(7, 20, null)).thenThrow(new IllegalStateException("private dependency"));
        mvc.perform(get("/api/knowledge-digests").requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("知识订阅暂不可用"));
        verify(reads).detail(7, id);
    }
}
