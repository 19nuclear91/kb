package com.zhishiku.kb.app.comment;

import cn.dev33.satoken.exception.SaTokenException;
import com.zhishiku.kb.app.comment.model.CommentViews;
import com.zhishiku.kb.app.comment.service.DocumentCommentQueryService;
import com.zhishiku.kb.app.comment.service.DocumentCommentService;
import com.zhishiku.kb.app.comment.web.DocumentCommentAdvice;
import com.zhishiku.kb.app.comment.web.DocumentCommentController;
import com.zhishiku.kb.app.config.DocumentCommentResponseFilter;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 评论 HTTP 契约：严格输入、固定错误码与 no-store；服务全部 Mock，不启动应用。 */
class DocumentCommentControllerTest {
    private static final String BASE = "/api/documents/9007199254740993/versions/9007199254740995/comments";
    private static final UUID ROOT = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID REPLY = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Instant AT = Instant.parse("2026-10-26T00:00:00Z");
    private final DocumentCommentQueryService queries = mock(DocumentCommentQueryService.class);
    private final DocumentCommentService commands = mock(DocumentCommentService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DocumentCommentController(queries, commands))
            .setControllerAdvice(new DocumentCommentAdvice()).addFilters(new DocumentCommentResponseFilter()).build();

    @Test
    void createReturnsCreatedReplayReturnsOkAndPassesExactIdsAndHeaderRequestId() throws Exception {
        when(commands.create(anyLong(), anyLong(), any(), anyString(), anyString()))
                .thenReturn(new CommentViews.Receipt(ROOT, null, 9007199254740993L, 9007199254740995L, AT, false, false),
                        new CommentViews.Receipt(ROOT, null, 9007199254740993L, 9007199254740995L, AT, false, true));
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "请求-1")
                        .content("{\"body\":\"  第一行\\n第二行 \"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.commentId").value(ROOT.toString()))
                .andExpect(jsonPath("$.data.replayed").value(false))
                .andExpect(jsonPath("$.data.body").doesNotExist());
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "请求-1")
                        .content("{\"body\":\"  第一行\\n第二行 \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true));
        verify(commands, times(2)).create(9007199254740993L, 9007199254740995L, null,
                "  第一行\n第二行 ", "请求-1");
        reset(commands);
        when(commands.create(anyLong(), anyLong(), any(), anyString(), anyString()))
                .thenReturn(new CommentViews.Receipt(REPLY, ROOT, 1, 2, AT, false, false));
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "r")
                        .content("{\"parentId\":\"" + ROOT + "\",\"body\":\"<img src=x>\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.parentId").value(ROOT.toString()));
        verify(commands).create(9007199254740993L, 9007199254740995L, ROOT, "<img src=x>", "r");
    }

    @Test
    void strictInputRejectsUnknownDuplicateIdentityTimeAndStateOverridesBeforeTheService() throws Exception {
        for (String body : List.of("{}", "[]", "{", "", "{\"body\":1}", "{\"body\":\"x\",\"body\":\"y\"}",
                "{\"body\":\"x\",\"authorUserId\":7}", "{\"body\":\"x\",\"createdAt\":\"2026-01-01T00:00:00Z\"}",
                "{\"body\":\"x\",\"deleted\":true}", "{\"body\":\"x\",\"commentId\":\"" + ROOT + "\"}",
                "{\"body\":\"x\",\"documentId\":1}", "{\"body\":\"x\",\"parentId\":\"bad\"}",
                "{\"body\":\"x\",\"parentId\":null}")) {
            mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "r").content(body))
                    .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.data.errorCode").value("INVALID_REQUEST"));
        }
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "a", "b")
                .content("{\"body\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "a".repeat(129))
                .content("{\"body\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(BASE).param("actor", "7").contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "r")
                .content("{\"body\":\"x\"}")).andExpect(status().isBadRequest());
        for (String path : List.of("/api/documents/0/versions/1/comments", "/api/documents/1/versions/x/comments",
                "/api/documents/01/versions/1/comments", "/api/documents/99999999999999999999/versions/1/comments")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "r")
                    .content("{\"body\":\"x\"}")).andExpect(status().isBadRequest());
            mvc.perform(get(path)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(BASE + "/1-2-3-4-5/replies")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/" + ROOT).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(delete(BASE + "/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(delete(BASE + "/" + ROOT).param("authorUserId", "7")).andExpect(status().isBadRequest());
        mvc.perform(delete(BASE + "/" + ROOT).contentType(MediaType.APPLICATION_JSON).content("{\"deleted\":true}"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post(BASE).contentType(MediaType.TEXT_PLAIN).header("X-Request-Id", "r").content("x"))
                .andExpect(status().is4xxClientError()).andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(commands, queries);
    }

    @Test
    void readsPassOnlyBoundedParametersAndSerializeMinimalItems() throws Exception {
        var item = new CommentViews.Item(ROOT, null, 9007199254740993L, 9007199254740995L, AT, false,
                new CommentViews.Author(7, "成员"), "<b>粗体</b>", true, true);
        when(queries.roots(anyLong(), anyLong(), any())).thenReturn(new CommentViews.Page(List.of(item), true, "next",
                false));
        mvc.perform(get(BASE).param("limit", "1").param("cursor", "c"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items[0].isOwn").value(true))
                .andExpect(jsonPath("$.data.items[0].own").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].body").value("<b>粗体</b>"))
                .andExpect(jsonPath("$.data.items[0].author.label").value("成员"))
                .andExpect(jsonPath("$.data.total").doesNotExist())
                .andExpect(jsonPath("$.data.nextCursor").value("next"))
                .andExpect(jsonPath("$.data.canCreate").value(false));
        verify(queries).roots(9007199254740993L, 9007199254740995L, Map.of("limit", List.of("1"), "cursor", List.of("c")));
        when(queries.replies(anyLong(), anyLong(), any(), any())).thenReturn(new CommentViews.Replies(List.of(), false,
                null, true));
        mvc.perform(get(BASE + "/" + ROOT + "/replies")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.hasMore").value(false));
        verify(queries).replies(eq(9007199254740993L), eq(9007199254740995L), eq(ROOT), any());
        when(queries.detail(anyLong(), anyLong(), any(), any())).thenReturn(new CommentViews.Detail(
                new CommentViews.Item(REPLY, ROOT, 1, 2, AT, true, null, null, false, false), false));
        mvc.perform(get(BASE + "/" + REPLY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.deleted").value(true))
                .andExpect(jsonPath("$.data.item.body").value(nullValue()))
                .andExpect(jsonPath("$.data.item.author").value(nullValue()))
                .andExpect(jsonPath("$.data.canReply").value(false));
        mvc.perform(delete(BASE + "/" + REPLY)).andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(commands).delete(9007199254740993L, 9007199254740995L, REPLY);
    }

    @Test
    void failuresMapToFixedCodesWithoutResourceDetailsAndAreNeverCached() throws Exception {
        Map<RuntimeException, String> failures = new LinkedHashMap<>();
        failures.put(new SaTokenException("private token"), "UNAUTHENTICATED");
        failures.put(IdentityException.unauthorized("private session"), "UNAUTHENTICATED");
        failures.put(IdentityException.passwordChangeRequired("private"), "FORBIDDEN");
        failures.put(QueryException.forbidden(), "FORBIDDEN");
        failures.put(ApiException.forbidden("private 当前不可发表评论"), "FORBIDDEN");
        failures.put(ApiException.notFound("private 文档 V1 标题"), "NOT_FOUND");
        failures.put(QueryException.invalid(), "INVALID_REQUEST");
        failures.put(ApiException.conflict("private STATE_CONFLICT"), "STATE_CONFLICT");
        failures.put(ApiException.unavailable("private"), "SERVICE_UNAVAILABLE");
        failures.put(new IllegalStateException("private SQL 正文"), "SERVICE_UNAVAILABLE");
        Map<String, Integer> statuses = Map.of("UNAUTHENTICATED", 401, "FORBIDDEN", 403, "NOT_FOUND", 404,
                "INVALID_REQUEST", 400, "STATE_CONFLICT", 409, "SERVICE_UNAVAILABLE", 503);
        for (var entry : failures.entrySet()) {
            reset(queries, commands);
            when(queries.roots(anyLong(), anyLong(), any())).thenThrow(entry.getKey());
            when(commands.create(anyLong(), anyLong(), isNull(), anyString(), anyString())).thenThrow(entry.getKey());
            doThrow(entry.getKey()).when(commands).delete(anyLong(), anyLong(), any());
            List<RequestBuilder> requests = List.of(get(BASE),
                    post(BASE).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", "r").content("{\"body\":\"x\"}"),
                    delete(BASE + "/" + ROOT));
            for (var request : requests) {
                var result = mvc.perform(request).andExpect(status().is(statuses.get(entry.getValue())))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.code").value(500))
                        .andExpect(jsonPath("$.message").value("评论请求未完成"))
                        .andExpect(jsonPath("$.data.errorCode").value(entry.getValue())).andReturn();
                assertThat(result.getResponse().getContentAsString()).doesNotContain("private");
            }
        }
    }
}
