package com.zhishiku.kb.app.document.preview;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.document.model.DocumentFile;
import com.zhishiku.kb.app.document.service.DocumentManagementService;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PreviewInfoTest {
    @Test
    void controllerPreservesExactIdentityAndNoStoreWithoutStartingServer() throws Exception {
        var service = mock(PreviewInfoService.class);
        when(service.info(7, 8, 9007199254740993L)).thenReturn(new PreviewInfo(1, "8", "9007199254740993",
                "OFFICE", null, "docx", null));
        var mvc = MockMvcBuilders.standaloneSetup(new PreviewInfoController(service))
                .setControllerAdvice(new OfficePreviewAdvice()).build();
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            mvc.perform(get("/api/documents/8/versions/9007199254740993/preview-info"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.data.versionId").value("9007199254740993"))
                    .andExpect(jsonPath("$.data.mode").value("OFFICE"));
        }
    }

    @Test
    void descriptorsFollowAuthorizationFormatAndFeatureState() {
        var documents = mock(DocumentManagementService.class);
        var settings = new OfficePreviewSettings();
        var service = new PreviewInfoService(documents, settings);
        long version = 9007199254740993L;
        for (String type : new String[]{"docx", "xlsx", "pptx", "pdf", "txt", "md", "doc", "xls", "ppt"}) {
            var file = new DocumentFile(8L, version, 2, "private-key", "file." + type, 12L, "application/octet-stream");
            when(documents.file(7, 8, version, "view")).thenReturn(file);
            settings.setEnabled(false);
            PreviewInfo info = service.info(7, 8, version);
            assertThat(info.versionId()).isEqualTo("9007199254740993");
            if (type.endsWith("x")) {
                assertThat(info.mode()).isEqualTo("UNAVAILABLE");
                settings.setEnabled(true);
                assertThat(service.info(7, 8, version).mode()).isEqualTo("OFFICE");
            } else if (type.equals("pdf") || type.equals("txt") || type.equals("md")) {
                assertThat(info.mode()).isEqualTo("DIRECT");
                assertThat(info.previewPath()).endsWith("/versions/9007199254740993/preview");
            } else {
                assertThat(info.mode()).isEqualTo("UNSUPPORTED");
                assertThat(info.previewPath()).isNull();
            }
        }
    }

    @Test
    void unavailableFeatureDoesNotRevealHiddenFiles() {
        var documents = mock(DocumentManagementService.class);
        var service = new PreviewInfoService(documents, new OfficePreviewSettings());
        when(documents.file(7, 8, 9L, "view")).thenThrow(ApiException.notFound("private filename"));
        assertThatThrownBy(() -> service.info(7, 8, 9)).isInstanceOfSatisfying(OfficePreviewFailure.class,
                failure -> assertThat(failure.status()).isEqualTo(404)).hasMessageNotContaining("private");
        verify(documents).file(7, 8, 9L, "view");
        doThrow(new IllegalStateException("database credentials")).when(documents).file(7, 8, 9L, "view");
        assertThatThrownBy(() -> service.info(7, 8, 9)).isInstanceOfSatisfying(OfficePreviewFailure.class,
                failure -> assertThat(failure.status()).isEqualTo(503)).hasMessageNotContaining("credentials");
    }

    @Test
    void disabledSettingsNeedNoSupplierAndEnabledSettingsFailClosed() {
        var settings = new OfficePreviewSettings();
        settings.validate();
        settings.setEnabled(true);
        assertThatThrownBy(settings::validate).isInstanceOf(IllegalStateException.class)
                .hasMessage("Office 预览启用配置无效");
        var response = new OfficePreviewAdvice().failure(new OfficePreviewFailure(404));
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }
}
