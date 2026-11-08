package com.zhishiku.kb.app.document.imports;

import com.zhishiku.kb.app.document.imports.model.ImportModels.ItemState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.ItemView;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Receipt;
import com.zhishiku.kb.app.document.imports.service.ImportAccess;
import com.zhishiku.kb.app.document.imports.service.ImportCreateService;
import com.zhishiku.kb.app.document.imports.service.ImportException;
import com.zhishiku.kb.app.document.imports.service.ImportReadService;
import com.zhishiku.kb.app.document.imports.service.ImportUploadService;
import com.zhishiku.kb.app.document.imports.web.ImportController;
import com.zhishiku.kb.app.document.imports.web.ImportExceptionHandler;
import com.zhishiku.kb.app.document.imports.web.ImportHeadersFilter;
import com.zhishiku.kb.app.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** standalone MockMvc 不启动容器或监听端口。 */
class ImportControllerTest {
    private final ImportReadService reads = mock(ImportReadService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var properties = new ImportProperties("100", "1073741824", "8", "2", "120", "60", "30000", "100");
        var storage = new StorageProperties(null, null, null, null, null, true, 1024);
        mvc = MockMvcBuilders.standaloneSetup(new ImportController(properties, storage, mock(ImportAccess.class),
                        mock(ImportCreateService.class), reads, mock(ImportUploadService.class)))
                .setControllerAdvice(new ImportExceptionHandler()).addFilters(new ImportHeadersFilter()).build();
    }

    @Test
    void returnsMinimalReceiptWithNoStoreAndExactIds() throws Exception {
        when(reads.item("token", 9, Long.MAX_VALUE)).thenReturn(new ItemView(Long.MAX_VALUE, 9, 1, 2,
                ItemState.ACCEPTED, null, false, null, new Receipt(4, 5, 6)));
        mvc.perform(get("/api/document-imports/9/items/9223372036854775807").header("satoken", "token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.itemId").value(Long.MAX_VALUE))
                .andExpect(jsonPath("$.data.file").doesNotExist()).andExpect(jsonPath("$.data.canUpload").value(false))
                .andExpect(jsonPath("$.data.receipt.versionId").value(5));
    }

    @Test
    void notFoundAndDependencyFailuresDoNotLeakOrBecomeEmpty() throws Exception {
        when(reads.detail("token", 9)).thenThrow(new ImportException(ImportException.Reason.NOT_FOUND));
        mvc.perform(get("/api/document-imports/9").header("satoken", "token"))
                .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"));
        when(reads.detail("token", 10)).thenThrow(new IllegalStateException("secret-file-and-key"));
        var result = mvc.perform(get("/api/document-imports/10").header("satoken", "token"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-file-and-key");
    }

    @Test
    void rejectsUnknownRepeatedAndInvalidQueryInputs() throws Exception {
        mvc.perform(get("/api/document-imports").param("ownerId", "9")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/document-imports").param("limit", "1", "2")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/document-imports").param("limit", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/document-imports/9223372036854775808")).andExpect(status().isBadRequest());
    }
}
