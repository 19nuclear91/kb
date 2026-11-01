package com.zhishiku.kb.app.document.metadata;

import com.zhishiku.kb.app.document.metadata.model.MetadataViews;
import com.zhishiku.kb.app.document.metadata.service.CategoryService;
import com.zhishiku.kb.app.document.metadata.service.CategoryWriteService;
import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.app.document.metadata.web.CategoryController;
import com.zhishiku.kb.app.document.metadata.web.MetadataAdvice;
import com.zhishiku.kb.app.document.metadata.web.MetadataResponseFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CategoryControllerTest {
    @Test
    void rejectsBadQueriesAndKeepsFailureSeparateFromEmptyCandidates() throws Exception {
        var reads = mock(CategoryService.class);
        var writes = mock(CategoryWriteService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CategoryController(reads, writes))
                .setControllerAdvice(new MetadataAdvice()).addFilters(new MetadataResponseFilter()).build();
        when(reads.managed(eq(2L), any())).thenReturn(new MetadataViews.CategoryPage(List.of(), false, null, 0));
        mvc.perform(get("/api/admin/spaces/2/categories")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(get("/api/admin/spaces/2/categories?limit=1&limit=2")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/documents/query/categories?q=private").contentType("application/json").content("{\"spaceId\":2}"))
                .andExpect(status().isBadRequest());
        when(reads.editable(eq(9L), any())).thenThrow(new MetadataException(MetadataException.Reason.FORBIDDEN));
        mvc.perform(get("/api/documents/9/metadata-options/categories")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reasonCode").value("FORBIDDEN"));
        when(reads.managed(eq(2L), any())).thenThrow(new IllegalStateException("private database error"));
        mvc.perform(get("/api/admin/spaces/2/categories")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("服务暂不可用，请稍后核对"));
    }
}
