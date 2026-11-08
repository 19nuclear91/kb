package com.zhishiku.kb.app.document.imports;

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
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MockMvc multipart 消息与服务替身，不建立 HTTP 监听。 */
class ImportFileControllerTest {
    private final ImportUploadService uploads = mock(ImportUploadService.class);
    private final ImportAccess access = mock(ImportAccess.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var properties = new ImportProperties("100", "1024", "8", "2", "120", "60", "30000", "100");
        mvc = MockMvcBuilders.standaloneSetup(new ImportController(properties,
                        new StorageProperties(null, null, null, null, null, true, 100), access,
                        mock(ImportCreateService.class), mock(ImportReadService.class), uploads))
                .setControllerAdvice(new ImportExceptionHandler()).addFilters(new ImportHeadersFilter()).build();
    }

    @Test
    void originalOrMatchingReplayReturnsOnlyFixedReceipt() throws Exception {
        when(uploads.upload(eq("token"), eq(9L), eq(2L), eq("request"), eq(Long.MAX_VALUE), any()))
                .thenReturn(new Receipt(Long.MAX_VALUE, 20, 30));
        mvc.perform(request().param("expectedRevision", Long.toString(Long.MAX_VALUE)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.documentId").value(Long.MAX_VALUE))
                .andExpect(jsonPath("$.data.parseTaskId").value(30));
        verify(uploads).upload(eq("token"), eq(9L), eq(2L), eq("request"), eq(Long.MAX_VALUE), any());
    }

    @Test
    void rejectsMissingRepeatedUnknownAndMalformedParts() throws Exception {
        mvc.perform(request()).andExpect(status().isBadRequest());
        mvc.perform(request().param("expectedRevision", "0", "1")).andExpect(status().isBadRequest());
        mvc.perform(request().param("expectedRevision", "0").param("title", "changed")).andExpect(status().isBadRequest());
        var duplicate = request().file(new MockMultipartFile("file", "b.txt", "text/plain", new byte[]{2}));
        mvc.perform(duplicate.param("expectedRevision", "0")).andExpect(status().isBadRequest());
        mvc.perform(request().header("X-Request-Id", "another").param("expectedRevision", "0"))
                .andExpect(status().isBadRequest());
        for (String revision : List.of("-1", "1.0", "9223372036854775808", "", "00")) {
            mvc.perform(request().param("expectedRevision", revision)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(uploads);
    }

    @Test
    void conflictOwnershipContentAndUnknownErrorsKeepRealStatuses() throws Exception {
        for (ImportException.Reason reason : List.of(ImportException.Reason.CONFLICT, ImportException.Reason.NOT_FOUND,
                ImportException.Reason.FORBIDDEN, ImportException.Reason.PAYLOAD_TOO_LARGE,
                ImportException.Reason.INVALID_FILE, ImportException.Reason.UNAVAILABLE)) {
            doThrow(new ImportException(reason)).when(uploads).upload(anyString(), anyLong(), anyLong(), anyString(), anyLong(), any());
            mvc.perform(request().param("expectedRevision", "0")).andExpect(status().is(reason.status()))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    @Test
    void invalidIdentityCannotInvokeUploadService() throws Exception {
        when(access.current("token")).thenThrow(IdentityException.unauthorized("session revoked"));
        mvc.perform(request().param("expectedRevision", "0")).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(uploads);
    }

    private MockMultipartHttpServletRequestBuilder request() {
        var request = multipart("/api/document-imports/9/items/2/file")
                .file(new MockMultipartFile("file", "a.txt", "text/plain", new byte[]{1}));
        request.header("satoken", "token").header("X-Request-Id", "request");
        return request;
    }
}
