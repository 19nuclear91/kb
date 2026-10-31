package com.zhishiku.kb.app.search;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.search.config.SearchLimitsProperties;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.model.SearchInput;
import com.zhishiku.kb.app.search.service.SearchService;
import com.zhishiku.kb.app.search.web.SearchController;
import com.zhishiku.kb.app.search.web.SearchInputAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SearchInputTest {
    @Test
    void supplementaryCharactersUseCodepointsAndAmountsRemainCompatible() throws Exception {
        var service = mock(SearchService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SearchController(service))
                .setControllerAdvice(new GlobalExceptionHandler(), new SearchInputAdvice(SearchLimitsProperties.defaults()))
                .build();
        String query = "😀".repeat(8000);
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsBytes(Map.of("query", query, "candidates", 20, "topK", 50))))
                    .andExpect(status().isOk());
            verify(service).searchResponse(7, query, null, 20, 50);
        }
    }

    @Test
    void invalidQueriesNeverReachTheService() throws Exception {
        var service = mock(SearchService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SearchController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String query : List.of("😀".repeat(8001), "x\u0000y", " ")) {
            mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(Map.of("query", query))))
                    .andExpect(status().isBadRequest());
        }
        // 孤立代理项以 JSON 转义传输，避免测试请求的 UTF-8 编码先把它替换成问号。
        for (String json : List.of("{\"query\":\"\\ud800\"}", "{\"query\":\"\\udc00\"}")) {
            mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void actualBytesAreBoundedWithoutLengthHeaderOrWithMisleadingHeader() {
        var limits = new SearchLimitsProperties(8000, 16, 100, 100, 100, 100);
        var advice = new SearchInputAdvice(limits);
        for (long declared : new long[]{-1, 1}) {
            var bytes = new ByteArrayInputStream(new byte[100]);
            var input = new MockHttpInputMessage(bytes);
            if (declared >= 0) {
                input.getHeaders().setContentLength(declared);
            }
            assertThatThrownBy(() -> advice.beforeBodyRead(input, null, null, null))
                    .isInstanceOfSatisfying(SearchFailure.class, e -> assertThat(e.status()).isEqualTo(413));
            assertThat(bytes.available()).isEqualTo(83);
        }
    }

    @Test
    void oversizedBodyIs413BeforeJsonAndNoSearchCall() throws Exception {
        var service = mock(SearchService.class);
        var limits = new SearchLimitsProperties(8000, 32, 100, 100, 100, 100);
        var mvc = MockMvcBuilders.standaloneSetup(new SearchController(service, limits))
                .setControllerAdvice(new GlobalExceptionHandler(), new SearchInputAdvice(limits)).build();
        var response = mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + "x".repeat(50) + "\"}"))
                .andExpect(status().isPayloadTooLarge()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("x".repeat(50));
        verifyNoInteractions(service);
    }

    @Test
    void internalInputValidationAlsoRejectsInvalidCounts() {
        assertThatThrownBy(() -> SearchInput.validate("q", 201, 1, 8000)).isInstanceOf(SearchFailure.class);
        assertThatThrownBy(() -> SearchInput.validate("q", 1, 0, 8000)).isInstanceOf(SearchFailure.class);
    }
}
