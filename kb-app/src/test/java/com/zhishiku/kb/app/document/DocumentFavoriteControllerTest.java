package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.config.DocumentFavoriteResponseFilter;
import com.zhishiku.kb.app.document.model.DocumentFavoriteViews;
import com.zhishiku.kb.app.document.service.DocumentFavoriteService;
import com.zhishiku.kb.app.document.web.DocumentFavoriteAdvice;
import com.zhishiku.kb.app.document.web.DocumentFavoriteController;
import com.zhishiku.kb.app.document.web.DocumentFavoriteInput;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.security.IdentityException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.filter.FormContentFilter;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentFavoriteControllerTest {
    private final DocumentFavoriteService service = mock(DocumentFavoriteService.class);
    private final DocumentFavoriteController controller = new DocumentFavoriteController(service);

    @Test
    void exactIntegerBodyAndExplicitOperationsUseR() throws Exception {
        when(service.put("11")).thenReturn(new DocumentFavoriteViews.State(11, true));
        when(service.delete("11")).thenReturn(new DocumentFavoriteViews.State(11, false));
        assertThat(controller.put("11", request("")).data().favorited()).isTrue();
        assertThat(controller.delete("11", request("")).data().favorited()).isFalse();
        when(service.states(List.of(Long.MAX_VALUE))).thenReturn(new DocumentFavoriteViews.States(List.of()));
        assertThat(controller.states(request("{\"documentIds\":[9223372036854775807]}")).code()).isEqualTo(200);
        verify(service).states(List.of(Long.MAX_VALUE));
        var params = new LinkedMultiValueMap<String, String>();
        params.add("limit", "20");
        when(service.list(params)).thenReturn(new DocumentFavoriteViews.Page(List.of(), false, null));
        assertThat(controller.list(params).data().items()).isEmpty();
    }

    @Test
    void strictParserRejectsCoercionUnknownAndDuplicateKeysAndTrailingRoots() {
        for (String body : List.of("", "null", "[]", "{}", "{\"userId\":7}",
                "{\"documentIds\":[]}", "{\"documentIds\":[1,1]}", "{\"documentIds\":[0]}",
                "{\"documentIds\":[\"1\"]}", "{\"documentIds\":[1.0]}", "{\"documentIds\":[true]}",
                "{\"documentIds\":[null]}", "{\"documentIds\":[9223372036854775808]}",
                "{\"documentIds\":[1],\"userId\":8}", "{\"documentIds\":[1],\"documentIds\":[2]}",
                "{\"documentIds\":[1]} {}", "{\"documentIds\":[1", "{\"documentIds\":[-1]}")) {
            assertThatThrownBy(() -> parse(body)).as(body).isInstanceOf(QueryException.class);
        }
        String ids = IntStream.rangeClosed(1, 101).mapToObj(Integer::toString).collect(Collectors.joining(","));
        assertThatThrownBy(() -> parse("{\"documentIds\":[" + ids + "]}")).isInstanceOf(QueryException.class);
        assertThat(parse("{\"documentIds\":[9007199254740993]}").documentIds()).containsExactly(9007199254740993L);
    }

    @Test
    void commandsRejectBodiesAndAllQueryParameters() {
        assertThatThrownBy(() -> controller.put("11", request("{}"))).isInstanceOf(QueryException.class);
        assertThatThrownBy(() -> controller.delete("11", request(" "))).isInstanceOf(QueryException.class);
        var request = request("{\"documentIds\":[1]}");
        request.setQueryString("userId=8");
        assertThatThrownBy(() -> controller.states(request)).isInstanceOf(QueryException.class);
        verifyNoInteractions(service);
    }

    @Test
    void commandsRejectConsumedFormBodiesWithKnownOrUnknownLength() {
        for (String method : List.of("PUT", "DELETE")) {
            for (boolean unknownLength : List.of(false, true)) {
                for (String body : List.of("&&", "&".repeat(8192))) {
                    assertThatThrownBy(() -> filteredCommand(method, body, unknownLength))
                            .as("%s unknownLength=%s bytes=%s", method, unknownLength, body.length())
                            .isInstanceOfSatisfying(QueryException.class,
                                    error -> assertThat(error.status()).isEqualTo(400));
                }
            }
        }
        verifyNoInteractions(service);
    }

    @Test
    void emptyFormCommandsRemainValidWithKnownOrUnknownLength() throws Exception {
        when(service.put("11")).thenReturn(new DocumentFavoriteViews.State(11, true));
        when(service.delete("11")).thenReturn(new DocumentFavoriteViews.State(11, false));
        for (String method : List.of("PUT", "DELETE")) {
            for (boolean unknownLength : List.of(false, true)) {
                filteredCommand(method, "", unknownLength);
            }
        }
        verify(service, times(2)).put("11");
        verify(service, times(2)).delete("11");
    }

    @Test
    void controllerDoesNotReplaceIdentityErrorsWithSuccess() {
        when(service.put("11")).thenThrow(IdentityException.unauthorized("private"));
        when(service.delete("11")).thenThrow(IdentityException.passwordChangeRequired("private"));
        var advice = new DocumentFavoriteAdvice();
        try {
            controller.put("11", request(""));
            throw new AssertionError("必须拒绝未登录请求");
        } catch (Exception error) {
            assertThat(advice.failure(error).getStatusCode().value()).isEqualTo(401);
        }
        try {
            controller.delete("11", request(""));
            throw new AssertionError("必须拒绝强制改密请求");
        } catch (Exception error) {
            assertThat(advice.failure(error).getStatusCode().value()).isEqualTo(403);
        }
    }

    private DocumentFavoriteInput.StatusRequest parse(String body) {
        return DocumentFavoriteInput.parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private void filteredCommand(String method, String body, boolean unknownLength) throws Exception {
        var request = new MockHttpServletRequest(method, "/api/documents/11/favorite");
        request.setContentType("application/x-www-form-urlencoded");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        if (unknownLength) request.addHeader("Transfer-Encoding", "chunked");
        HttpServletRequest input = unknownLength ? new HttpServletRequestWrapper(request) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        } : request;
        var response = new MockHttpServletResponse();
        new DocumentFavoriteResponseFilter().doFilter(input, response, (beforeForm, res) -> {
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            new FormContentFilter().doFilter(beforeForm, res, (beforeController, ignored) -> {
                var result = "PUT".equals(method)
                        ? controller.put("11", (HttpServletRequest) beforeController)
                        : controller.delete("11", (HttpServletRequest) beforeController);
                assertThat(result.code()).isEqualTo(200);
                assertThat(result.data()).isEqualTo(new DocumentFavoriteViews.State(11, "PUT".equals(method)));
            });
        });
    }

    private MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest();
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
