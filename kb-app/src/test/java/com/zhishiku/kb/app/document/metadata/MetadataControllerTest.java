package com.zhishiku.kb.app.document.metadata;

import com.zhishiku.kb.app.document.metadata.service.MetadataReadService;
import com.zhishiku.kb.app.document.metadata.service.MetadataWriteService;
import com.zhishiku.kb.app.document.metadata.web.MetadataAdvice;
import com.zhishiku.kb.app.document.metadata.web.MetadataController;
import com.zhishiku.kb.app.document.metadata.web.MetadataResponseFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MetadataControllerTest {
    @Test
    void fullReplacementDistinguishesMissingNullAndOversizedBodies() throws Exception {
        var writes = mock(MetadataWriteService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MetadataController(mock(MetadataReadService.class), writes))
                .setControllerAdvice(new MetadataAdvice()).addFilters(new MetadataResponseFilter()).build();
        String body = "{\"title\":\"标题\",\"summary\":null,\"categoryId\":null,\"tagIds\":[],\"expectedMetadataVersion\":0}";
        mvc.perform(put("/api/documents/9/metadata").header("X-Request-Id", "id").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(writes).update(eq(9L), any(), eq("id"));
        mvc.perform(put("/api/documents/9/metadata").header("X-Request-Id", "id").content(body.replace("\"categoryId\":null,", "")))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/documents/9/metadata").header("X-Request-Id", "id").content(new byte[65537]))
                .andExpect(status().isPayloadTooLarge());
        when(writes.update(eq(9L), any(), eq("id"))).thenThrow(new IllegalStateException("private"));
        mvc.perform(put("/api/documents/9/metadata").header("X-Request-Id", "id").content(body)).andExpect(status().isServiceUnavailable());
    }
}
