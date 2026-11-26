package com.zhishiku.kb.app.controller.answercard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.config.AnswerCardResponseFilter;
import com.zhishiku.kb.app.config.AnswerCardSearchProperties;
import com.zhishiku.kb.app.config.EpochAuthenticationInterceptor;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.domain.vo.answercard.AnswerCardReceipt;
import com.zhishiku.kb.app.domain.vo.answercard.AnswerCardViews;
import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import com.zhishiku.kb.app.service.answercard.AnswerCardReadService;
import com.zhishiku.kb.app.service.answercard.AnswerCardWriteService;
import com.zhishiku.kb.app.service.impl.answercard.AnswerCardSearchExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 只使用MockMvc内存派发，不启动业务服务、不监听端口。 */
class AnswerCardControllerTest {
    private final AnswerCardReadService reads = mock(AnswerCardReadService.class);
    private final AnswerCardWriteService writes = mock(AnswerCardWriteService.class);
    private final AnswerCardSearchExecutor executor = new AnswerCardSearchExecutor(new AnswerCardSearchProperties(2, 1, 5000, 262144), new ObjectMapper().findAndRegisterModules());
    private MockMvc mvc;
    private final EpochSessionService.Session actor = new EpochSessionService.Session(9007199254740993L, 2, null, false, Instant.now());
    private static final String CREATE = """
            {"spaceId":"9","content":{"question":"问题","aliases":[],"answer":"","applicabilityText":"",
            "dates":{"effectiveFrom":null,"untilKind":"UNDECLARED","effectiveUntil":null},"sources":[]}}
            """;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new AnswerCardController(reads, writes, executor))
                .setControllerAdvice(new AnswerCardAdvice(), new GlobalExceptionHandler())
                .addFilters(new AnswerCardResponseFilter()).build();
    }

    @AfterEach
    void close() {
        executor.close();
    }

    @Test
    void allPathsRequireVerifiedSessionAndPasswordGateBeforeBusinessReads() throws Exception {
        mvc.perform(get("/api/answer-cards")).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        var restricted = new EpochSessionService.Session(7, 2, null, true, Instant.now());
        mvc.perform(get("/api/answer-cards").requestAttr(EpochAuthenticationInterceptor.VERIFIED, restricted))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("X-Answer-Card-Version"));
        verifyNoInteractions(reads, writes);
    }

    @Test
    void createAndCommandQueryKeepStringIdentityAndOnlyReturnBoundReceipt() throws Exception {
        var receipt = new AnswerCardReceipt("9007199254740994", "0", "11", "12", null, null, null, "DRAFT",
                OffsetDateTime.parse("2026-11-26T10:00:00+08:00"), false);
        when(writes.execute(eq(actor.userId()), eq("create-1"), any())).thenReturn(receipt);
        mvc.perform(post("/api/answer-cards").requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor)
                        .contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "create-1").content(CREATE))
                .andExpect(status().isOk()).andExpect(header().string("X-Answer-Card-Version", "1"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.cardId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.question").doesNotExist()).andExpect(jsonPath("$.data.sources").doesNotExist());
        String query = "{\"operation\":\"ANSWER_CARD_CREATE\",\"requestId\":\"create-1\",\"cardId\":null,\"reviewId\":null,\"payload\":" + CREATE + "}";
        when(writes.query(eq(actor.userId()), eq("create-1"), any())).thenReturn(receipt.replay());
        mvc.perform(post("/api/answer-cards/commands/query").requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor)
                        .contentType(MediaType.APPLICATION_JSON).content(query))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMMITTED"))
                .andExpect(jsonPath("$.data.receipt.replayed").value(true));
    }

    @Test
    void badInputLimitsAndDependenciesRemainSafeAndPrivate() throws Exception {
        mvc.perform(post("/api/answer-cards").requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor)
                        .contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "x").content(CREATE.replace("\"spaceId\":\"9\"", "\"spaceId\":9")))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("X-Answer-Card-Version"));
        mvc.perform(post("/api/answer-cards/search").contentType(MediaType.APPLICATION_JSON).content("x".repeat(16385)))
                .andExpect(status().isPayloadTooLarge()).andExpect(header().string("Cache-Control", "no-store"));
        verify(writes, never()).execute(anyLong(), anyString(), any());
        when(reads.browse(anyLong(), any())).thenThrow(new IllegalStateException("private query and source"));
        mvc.perform(get("/api/answer-cards").requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("标准答案功能暂不可用"));
    }

    @Test
    void successfulEmptySearchHasOwnProtocolWhileUnsupportedFiltersNeverReachSearch() throws Exception {
        when(reads.search(eq(actor.userId()), eq("问题"), eq(null), eq(10))).thenReturn(new AnswerCardViews.Search(List.of(),
                LocalDate.of(2026, 11, 26), OffsetDateTime.parse("2026-11-26T10:00:00+08:00"), "问题", null));
        mvc.perform(post("/api/answer-cards/search").requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"问题\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Answer-Card-Version", "1")).andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(post("/api/answer-cards/search").requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"问题\"}"))
                .andExpect(jsonPath("$.data.asOfDate").value("2026-11-26"))
                .andExpect(jsonPath("$.data.evaluatedAt").isString());
        mvc.perform(post("/api/answer-cards/search").requestAttr(EpochAuthenticationInterceptor.VERIFIED, actor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"private\",\"fileType\":\"pdf\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("标准答案请求无效"));
        verify(reads, never()).search(eq(actor.userId()), eq("private"), any(), any(int.class));
    }
}
