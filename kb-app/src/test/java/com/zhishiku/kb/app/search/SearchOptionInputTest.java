package com.zhishiku.kb.app.search;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.model.SearchOptions;
import com.zhishiku.kb.app.search.service.SearchOptionService;
import com.zhishiku.kb.app.search.web.SearchOptionController;
import com.zhishiku.kb.app.search.web.SearchOptionInput;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SearchOptionInputTest {
    @Test
    void endpointDefaultsAndExactIdsAreParsedWithoutListening() throws Exception {
        var service = mock(SearchOptionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SearchOptionController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            mvc.perform(post("/api/search/categories").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"spaceId\":9223372036854775807,\"parentId\":null}")).andExpect(status().isOk());
            verify(service).categories(7, new SearchOptions.CategoryQuery(Long.MAX_VALUE, null, 20, null));
            mvc.perform(post("/api/search/tags").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nameQuery\":\"  标签  \"}")).andExpect(status().isOk());
            verify(service).tags(7, new SearchOptions.TagQuery("标签", 20, null));
        }
    }

    @Test
    void rejectsMalformedConditionsDuplicatesAndQueryParameters() throws Exception {
        var service = mock(SearchOptionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SearchOptionController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String body : List.of("{}", "{\"spaceId\":\"1\"}", "{\"spaceId\":1.5}",
                "{\"spaceId\":1,\"spaceId\":2}", "{\"spaceId\":1} {}", "{\"spaceId\":1,\"limit\":null}",
                "{\"spaceId\":1,\"parentId\":0}", "{\"spaceId\":1,\"cursor\":null}", "{\"spaceId\":1,\"limit\":101}")) {
            mvc.perform(post("/api/search/categories").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        for (String body : List.of("{\"spaceId\":1}", "{\"query\":\"q\"}", "{\"nameQuery\":null}",
                "{\"nameQuery\":\"\\ud800\"}", "{\"nameQuery\":\"\\u0000\"}", "{\"limit\":\"1\"}", "{\"limit\":0}",
                "{\"cursor\":\"bad!\"}", "{\"nameQuery\":\"" + "a".repeat(65) + "\"}")) {
            mvc.perform(post("/api/search/tags").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/search/tags?nameQuery=private").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void actualBodyIsBoundedAndUnicodeNameUsesCodepoints() {
        var input = new ByteArrayInputStream(new byte[SearchOptionInput.MAX_BYTES + 100]);
        assertThatThrownBy(() -> SearchOptionInput.body(input)).isInstanceOfSatisfying(SearchFailure.class,
                error -> assertThat(error.status()).isEqualTo(413));
        assertThat(input.available()).isEqualTo(99);
        assertThat(new SearchOptions.TagQuery("😀".repeat(64), 100, null).nameQuery()).isEqualTo("😀".repeat(64));
        assertThat(new SearchOptions.TagQuery("\u00a0 \u00a0", 20, null).nameQuery()).isNull();
    }
}
