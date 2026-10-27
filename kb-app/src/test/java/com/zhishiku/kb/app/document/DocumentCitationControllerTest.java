package com.zhishiku.kb.app.document;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.config.PasswordChangeRequiredInterceptor;
import com.zhishiku.kb.app.document.model.DocumentCitationView;
import com.zhishiku.kb.app.document.model.DocumentCitationView.Status;
import com.zhishiku.kb.app.document.service.DocumentCitationService;
import com.zhishiku.kb.app.document.service.DocumentManagementService;
import com.zhishiku.kb.app.document.service.DocumentUploadService;
import com.zhishiku.kb.app.document.web.DocumentController;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentityException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 分别直接调用控制器、校验器和门禁，不组合请求链路或启动 HTTP 服务。
 */
class DocumentCitationControllerTest {
    private final DocumentCitationService service = mock(DocumentCitationService.class);
    private final DocumentController controller = new DocumentController(mock(DocumentUploadService.class),
            mock(DocumentManagementService.class), service);
    private final GlobalExceptionHandler errors = new GlobalExceptionHandler();

    @ParameterizedTest
    @EnumSource(Status.class)
    void bothAuthorizedResultsHaveTheExistingEnvelopeAndNoStore(Status status) {
        var chunk = status == Status.AVAILABLE ? new DocumentCitationView.Chunk(0, "正文", null,
                null, null, null, null, null, null, null) : null;
        var view = new DocumentCitationView(status, 10L, "说明", 20L, 1, 30L, chunk,
                "/api/documents/10/versions/20/preview", null);
        when(service.find(7L, 10L, 20L, 30L)).thenReturn(view);
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            var response = controller.citation(10L, 20L, 30L);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(response.getBody()).isEqualTo(R.ok(view));
            JsonNode json = new ObjectMapper().valueToTree(response.getBody());
            assertThat(json.path("code").asInt()).isEqualTo(200);
            assertThat(json.path("data").path("status").asText()).isEqualTo(status.name());
            assertThat(json.path("data").path("chunk").isNull()).isEqualTo(status == Status.CHUNK_UNAVAILABLE);
            assertThat(json.toString()).doesNotContain("objectKey", "satoken", "https://");
        }
        verify(service).find(7L, 10L, 20L, 30L);
    }

    @Test
    void routeAndPositivePathValidationRemainExplicit() throws Exception {
        Method method = citationMethod();
        assertThat(DocumentController.class.getAnnotation(RequestMapping.class).value()).containsExactly("/api" +
                "/documents");
        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/{documentId}/versions/{versionId}/citations/{chunkId}");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator().forExecutables();
            assertThat(validator.validateParameters(controller, method, new Object[]{10L, 20L, 30L})).isEmpty();
            for (int index = 0; index < 3; index++) {
                Object[] values = {10L, 20L, 30L};
                values[index] = 0L;
                var violations = validator.validateParameters(controller, method, values);
                assertThat(violations).hasSize(1);
                assertFailure(errors.handleInvalidRequest(new ConstraintViolationException(violations)), 400);
            }
        }
        for (String value : Arrays.asList("not-an-id", "9223372036854775808")) {
            var mismatch = new MethodArgumentTypeMismatchException(value, long.class, "versionId",
                    new MethodParameter(method, 1), new NumberFormatException(value));
            assertFailure(errors.handleInvalidRequest(mismatch), 400);
        }
        verifyNoInteractions(service);
    }

    @Test
    void inaccessibleResourceUses404WithoutCitationData() {
        ApiException denied = ApiException.notFound("文档或版本不存在，或当前用户无权访问");
        when(service.find(7L, 10L, 20L, 30L)).thenThrow(denied);
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            assertThatThrownBy(() -> controller.citation(10L, 20L, 30L)).isSameAs(denied);
        }
        assertFailure(errors.handleApi(denied), 404);
    }

    @Test
    void missingLoginMapsTo401BeforeCallingTheService() {
        var denied = new NotLoginException(NotLoginException.NOT_TOKEN_MESSAGE, NotLoginException.NOT_TOKEN, null);
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsLong).thenThrow(denied);
            assertThatThrownBy(() -> controller.citation(10L, 20L, 30L)).isSameAs(denied);
        }
        assertFailure(errors.handleAuth(denied), 401);
        verifyNoInteractions(service);
    }

    @Test
    void citationPathDoesNotBypassThePasswordChangeGate() throws Exception {
        var departments = mock(IdentityDepartmentMapper.class);
        var gate = new PasswordChangeRequiredInterceptor(departments);
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/documents/10/versions/20/citations/30");
        when(departments.findMustChangePasswordById(7L)).thenReturn(true, false);
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            assertThatThrownBy(() -> gate.preHandle(request, response, new Object()))
                    .isInstanceOfSatisfying(IdentityException.class,
                            error -> assertFailure(errors.handleIdentity(error), 403));
            assertThat(gate.preHandle(request, response, new Object())).isTrue();
        }
        verify(departments, times(2)).findMustChangePasswordById(7L);
        verifyNoInteractions(service);
    }

    private static Method citationMethod() throws NoSuchMethodException {
        return DocumentController.class.getMethod("citation", long.class, long.class, long.class);
    }

    private static void assertFailure(ResponseEntity<R<Void>> response, int status) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(R.FAILURE);
        assertThat(response.getBody().data()).isNull();
    }
}
