package com.zhishiku.kb.app.document.metadata;

import com.zhishiku.kb.app.document.metadata.model.MetadataInputs;
import com.zhishiku.kb.app.document.metadata.model.MetadataViews;
import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.app.document.metadata.service.TagService;
import com.zhishiku.kb.app.document.metadata.web.MetadataAdvice;
import com.zhishiku.kb.app.document.metadata.web.MetadataResponseFilter;
import com.zhishiku.kb.app.document.metadata.web.TagController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TagControllerTest {
    @Test
    void managedReadAndWriteHaveIndependentFailuresAndQueriesNormalize() throws Exception {
        var service = mock(TagService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new TagController(service))
                .setControllerAdvice(new MetadataAdvice()).addFilters(new MetadataResponseFilter()).build();
        when(service.managed(any())).thenReturn(new MetadataViews.Page<>(List.of(), false, null));
        mvc.perform(post("/api/admin/document-tags/query").contentType("application/json").content("{\"nameQuery\":\"  主题  \"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(service).managed(new MetadataInputs.TagQuery("主题", null, 20, null));
        when(service.write(eq(null), any(), eq("create"))).thenThrow(new MetadataException(MetadataException.Reason.FORBIDDEN));
        mvc.perform(post("/api/admin/document-tags").header("X-Request-Id", "create").contentType("application/json")
                .content("{\"name\":\"主题\"}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.reasonCode").value("FORBIDDEN"));
        mvc.perform(post("/api/documents/9/metadata-options/tags").contentType("application/json")
                .content("{\"enabled\":false}")).andExpect(status().isBadRequest());
    }
}
