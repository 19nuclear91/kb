package com.zhishiku.kb.app.controller.subscription;

import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.config.EpochAuthenticationInterceptor;
import com.zhishiku.kb.app.domain.vo.subscription.SubscriptionViews;
import com.zhishiku.kb.app.service.subscription.SubscriptionFailure;
import com.zhishiku.kb.app.service.subscription.SubscriptionReadService;
import com.zhishiku.kb.app.service.subscription.SubscriptionWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MockMvc仅内存派发，不启动应用和HTTP服务器。 */
class SubscriptionControllerTest {
    private final SubscriptionReadService reads = mock(SubscriptionReadService.class);
    private final SubscriptionWriteService writes = mock(SubscriptionWriteService.class);
    private final EpochSessionService.Session actor = new EpochSessionService.Session(1, 1, null, false, Instant.now());
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new SubscriptionController(reads, writes))
                .setControllerAdvice(new SubscriptionAdvice()).build();
    }

    @Test
    void identityAndPasswordGateRunBeforeReads() throws Exception {
        mvc.perform(get("/api/knowledge-subscriptions")).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));
        var restricted = new EpochSessionService.Session(1, 1, null, true, Instant.now());
        mvc.perform(get("/api/knowledge-subscriptions").requestAttr(EpochAuthenticationInterceptor.VERIFIED, restricted))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reads, writes);
    }

    @Test
    void unknownDuplicateAndOversizedInputsNeverReachWrites() throws Exception {
        for (String body : new String[]{"{\"kind\":\"TAG\",\"targetId\":\"1\",\"expectedVersion\":\"0\",\"userId\":2}",
                "{\"kind\":\"TAG\",\"kind\":\"SPACE\",\"targetId\":\"1\",\"expectedVersion\":\"0\"}",
                "{\"kind\":\"TAG\",\"targetId\":1,\"expectedVersion\":\"0\"}"}) {
            mvc.perform(post("/api/knowledge-subscriptions").requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor)
                    .contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "x").content(body))
                    .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(post("/api/knowledge-subscriptions").requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor)
                .contentType(MediaType.APPLICATION_JSON).content("x".repeat(16385))).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(writes);
    }

    @Test
    void versionConflictIsPrivateAndNeverClaimedAsSuccess() throws Exception {
        when(writes.subscribe(eq(1L), anyString(), any(), anyLong(), anyLong())).thenThrow(SubscriptionFailure.conflict("VERSION_CONFLICT"));
        mvc.perform(post("/api/knowledge-subscriptions").requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor)
                .contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "original")
                .content("{\"kind\":\"DOCUMENT\",\"targetId\":\"9007199254740993\",\"expectedVersion\":\"0\"}"))
                .andExpect(status().isConflict()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Kb-Subscription-Error", "VERSION_CONFLICT"));
    }

    @Test
    void errorsRemainFixedAndReceiptLookupDoesNotGuessSuccess() throws Exception {
        when(reads.list(eq(1L), any(), any(), anyInt(), any())).thenThrow(SubscriptionFailure.hidden());
        mvc.perform(get("/api/knowledge-subscriptions").requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor))
                .andExpect(status().isNotFound());
        when(reads.list(eq(1L), any(), any(), anyInt(), any())).thenThrow(new IllegalStateException("private source"));
        mvc.perform(get("/api/knowledge-subscriptions").requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("知识订阅暂不可用"));
        when(reads.receipt(1, "lost")).thenReturn(new SubscriptionViews.ReceiptLookup("lost", "UNKNOWN", null));
        mvc.perform(post("/api/knowledge-subscriptions/receipts/query").requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor)
                .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"lost\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("UNKNOWN"));
    }
}
