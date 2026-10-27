package com.zhishiku.kb.app.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.dev33.satoken.exception.SaTokenException;
import com.zhishiku.kb.app.config.NotificationResponseFilter;
import com.zhishiku.kb.app.notification.model.NotificationViews;
import com.zhishiku.kb.app.notification.service.UserNotificationService;
import com.zhishiku.kb.app.notification.web.NotificationAdvice;
import com.zhishiku.kb.app.notification.web.NotificationController;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.filter.FormContentFilter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 控制器、严格输入、固定错误投影与 no-store；服务为 Mock，不启动 Spring 或 HTTP。 */
class NotificationControllerTest {
    private static final UUID ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private final UserNotificationService service = mock(UserNotificationService.class);
    private final NotificationController controller = new NotificationController(service);

    @Test
    void endpointsReturnRWrappedMinimalResults() throws Exception {
        var params = new LinkedMultiValueMap<String, String>();
        params.add("limit", "20");
        when(service.list(params)).thenReturn(new NotificationViews.Page(List.of(), false, null, Instant.EPOCH));
        when(service.unreadCount(new LinkedMultiValueMap<>())).thenReturn(new NotificationViews.UnreadCount("0", Instant.EPOCH));
        when(service.markRead(ID)).thenReturn(new NotificationViews.ReadReceipt(ID, Instant.EPOCH));
        assertThat(controller.list(params, request("GET", "")).code()).isEqualTo(200);
        assertThat(controller.unreadCount(new LinkedMultiValueMap<>(), request("GET", "")).data().count()).isEqualTo("0");
        assertThat(controller.read(ID.toString(), request("PUT", "")).data()).isEqualTo(new NotificationViews.ReadReceipt(ID, Instant.EPOCH));
    }

    @Test
    void forgedRecipientTitleBodyOrExternalTargetsAreRejectedBeforeTheService() {
        for (String body : List.of("{\"userId\":8}", "{\"title\":\"x\"}", "{\"target\":\"https://evil.test\"}", " ", "&&")) {
            assertThatThrownBy(() -> controller.read(ID.toString(), request("PUT", body))).as(body).isInstanceOf(QueryException.class);
            assertThatThrownBy(() -> controller.list(new LinkedMultiValueMap<>(), request("GET", body))).isInstanceOf(QueryException.class);
        }
        var query = request("PUT", "");
        query.setQueryString("userId=8");
        query.addParameter("userId", "8");
        assertThatThrownBy(() -> controller.read(ID.toString(), query)).isInstanceOf(QueryException.class);
        for (String id : List.of("1", "not-a-uuid", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeeeX", "../read", "")) {
            assertThatThrownBy(() -> controller.read(id, request("PUT", ""))).as(id).isInstanceOf(QueryException.class);
        }
        verifyNoInteractions(service);
    }

    @Test
    void consumedFormBodiesAreStillDetectedThroughTheResponseFilter() throws Exception {
        for (String body : List.of("&&", "userId=8&title=x")) {
            var request = new MockHttpServletRequest("PUT", "/api/notifications/" + ID + "/read");
            request.setContentType("application/x-www-form-urlencoded");
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
            var response = new MockHttpServletResponse();
            assertThatThrownBy(() -> new NotificationResponseFilter().doFilter(request, response, (beforeForm, res) ->
                    new FormContentFilter().doFilter(beforeForm, res, (beforeController, ignored) ->
                            controller.read(ID.toString(), (HttpServletRequest) beforeController))))
                    .isInstanceOf(QueryException.class);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
        verifyNoInteractions(service);
    }

    @Test
    void responseFilterMarksSuccessAndEarlyRejectionAsNoStoreOnlyForNotificationPaths() throws Exception {
        var filter = new NotificationResponseFilter();
        for (String path : List.of("/api/notifications", "/api/notifications/unread-count",
                "/api/notifications/" + ID + "/read", "/api/notifications/bad/read")) {
            var response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", path), response, (req, res) -> response.setStatus(401));
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
        var other = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/notificationsx"), other, (req, res) -> { });
        assertThat(other.getHeader("Cache-Control")).isNull();
    }

    @Test
    void adviceProjectsFixedStatusesWithoutLeakingOrLogging() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        root.addAppender(captured);
        try {
            var advice = new NotificationAdvice();
            var errors = List.<Exception>of(QueryException.invalid(), new SaTokenException("secret token"),
                    IdentityException.unauthorized("secret session"), IdentityException.disabled("secret name"),
                    IdentityException.passwordChangeRequired("secret"), ApiException.notFound("secret title"),
                    ApiException.unavailable("secret sql"), ApiException.conflict("secret"),
                    new IllegalStateException("secret body", new RuntimeException("secret cause")));
            var statuses = List.of(400, 401, 401, 403, 403, 404, 503, 503, 503);
            for (int i = 0; i < errors.size(); i++) {
                var result = advice.failure(errors.get(i));
                assertThat(result.getStatusCode().value()).as(errors.get(i).getClass().getSimpleName()).isEqualTo(statuses.get(i));
                assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
                assertThat(result.getBody().message()).doesNotContain("secret");
                assertThat(result.getBody().data()).isNull();
            }
            assertThat(captured.list).isEmpty();
        } finally {
            root.detachAppender(captured);
            captured.stop();
        }
    }

    private static MockHttpServletRequest request(String method, String body) {
        var request = new MockHttpServletRequest(method, "/api/notifications");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
