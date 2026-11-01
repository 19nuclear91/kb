package com.zhishiku.kb.app.document.metadata;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.document.metadata.service.MetadataQueryService;
import com.zhishiku.kb.app.document.metadata.web.MetadataAdvice;
import com.zhishiku.kb.app.document.metadata.web.MetadataQueryController;
import com.zhishiku.kb.app.document.metadata.web.MetadataResponseFilter;
import com.zhishiku.kb.app.document.model.DocumentPage;
import com.zhishiku.kb.app.document.service.DocumentCitationService;
import com.zhishiku.kb.app.document.service.DocumentManagementService;
import com.zhishiku.kb.app.document.service.DocumentUploadService;
import com.zhishiku.kb.app.document.web.DocumentController;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MetadataQueryControllerTest {
    @Test
    void loginInterceptorRejectionRemainsUnauthorizedBeforeControllerExecution() throws Exception {
        var service = mock(MetadataQueryService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MetadataQueryController(service))
                .setControllerAdvice(new MetadataAdvice(), new GlobalExceptionHandler())
                .addInterceptors(new SaInterceptor(handler -> StpUtil.checkLogin()))
                .addFilters(new MetadataResponseFilter()).build();
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::checkLogin).thenThrow(new SaTokenException("private-session-token"));
            mvc.perform(post("/api/documents/query").content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.data").value(nullValue()))
                    .andExpect(jsonPath("$.reasonCode").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").value("当前无操作资格"));
            verifyNoInteractions(service);
        }
    }

    @Test
    void oldGetAndNewPostCoexistAndFailuresRemainPrivate() throws Exception {
        var service = mock(MetadataQueryService.class);
        var old = mock(DocumentManagementService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MetadataQueryController(service),
                new DocumentController(mock(DocumentUploadService.class), old, mock(DocumentCitationService.class)))
                .setControllerAdvice(new MetadataAdvice()).addFilters(new MetadataResponseFilter()).build();
        when(service.query(any())).thenReturn(new MetadataQueryService.Page(1, 20, 0, List.of()));
        mvc.perform(post("/api/documents/query").content("{}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0)).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/documents/query?titleQuery=private").content("{}")).andExpect(status().isBadRequest());
        try (var session = mockStatic(StpUtil.class)) {
            session.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            when(old.list(7, null, 1, 20)).thenReturn(new DocumentPage(1, 20, 0, List.of()));
            mvc.perform(get("/api/documents")).andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1));
        }
        when(service.query(any())).thenThrow(new QueryTimeoutException("private"));
        mvc.perform(post("/api/documents/query").content("{}")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reasonCode").value("UNAVAILABLE"));
        doThrow(IdentityException.unauthorized("private")).when(service).query(any());
        mvc.perform(post("/api/documents/query").content("{}")).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));
        doThrow(IdentityException.forbidden("private")).when(service).query(any());
        mvc.perform(post("/api/documents/query").content("{}")).andExpect(status().isForbidden());
    }
}
