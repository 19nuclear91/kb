package com.zhishiku.kb.app.search;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.search.model.SearchFilters;
import com.zhishiku.kb.app.search.service.SearchService;
import com.zhishiku.kb.app.search.web.SearchController;
import com.zhishiku.kb.app.search.web.SearchRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class SearchFilterInputTest {
    @Test
    void decodesExactIdsAndTimeOffsetsIntoImmutableFilters() throws Exception {
        var request = new ObjectMapper().readValue("""
                {"query":"中文", "spaceId":9223372036854775807,"categoryId":8,
                 "tagIds":[9223372036854775807,2],"fileType":"PDF",
                 "updatedFrom":"2026-10-01T08:00:00+08:00","updatedTo":"2026-11-01T00:00:00Z"}
                """, SearchRequest.class);
        assertThat(request.filters().spaceId()).isEqualTo(Long.MAX_VALUE);
        assertThat(request.filters().tagIds()).containsExactly(2L, Long.MAX_VALUE);
        assertThat(request.filters().fileType()).isEqualTo(SearchFilters.FileType.PDF);
        assertThat(request.filters().updatedFrom().getOffset().getTotalSeconds()).isEqualTo(28800);
    }

    @Test
    void invalidOrAmbiguousFieldsNeverReachSearch() throws Exception {
        var service = mock(SearchService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SearchController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String field : List.of("\"spaceId\":\"1\"", "\"spaceId\":1.5", "\"spaceId\":9223372036854775808",
                "\"categoryId\":0", "\"categoryId\":1,\"uncategorized\":true", "\"uncategorized\":null",
                "\"tagIds\":null", "\"tagIds\":[1,1]", "\"tagIds\":[\"1\"]", "\"tagIds\":[1.0]",
                "\"tagIds\":[null]", "\"fileType\":\"UNPUBLISHED\"", "\"fileType\":null",
                "\"updatedFrom\":null", "\"updatedFrom\":\"2026-10-01T00:00:00\"",
                "\"updatedFrom\":\"2026-11-01T00:00:00Z\",\"updatedTo\":\"2026-10-01T00:00:00Z\"",
                "\"unknown\":1", "\"userId\":1", "\"spaceId\":1,\"spaceId\":2",
                "\"candidates\":1.0", "\"topK\":\"2\"")) {
            mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"query\":\"q\"," + field + "}"))
                    .andExpect(status().isBadRequest());
        }
        for (String body : List.of("{\"query\":\"q\"} {}", "{\"query\":\"q\",\"query\":\"other\"}", "[]", "{}")) {
            mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void absentAndExplicitlyNullCountsKeepOriginalDefaults() throws Exception {
        var service = mock(SearchService.class);
        when(service.searchResponse(7, "q", null, 50, 50)).thenReturn("{\"data\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var mvc = MockMvcBuilders.standaloneSetup(new SearchController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON).content("""
                    {"query":"q","candidates":null,"topK":null,"spaceId":null,
                     "categoryId":null,"uncategorized":false,"tagIds":[]}
                    """)).andExpect(status().isOk()).andExpect(header().string("X-Search-Filter-Version", "1"))
                    .andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.data").isEmpty());
            verify(service).searchResponse(7, "q", null, 50, 50);
        }
    }
}
