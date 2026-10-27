package com.zhishiku.kb.app.conversation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.service.ConversationIdentityGate;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationIdentityAndPrivacyTest {
    @Test
    void enabledAndMustChangeFactsAreReadForEachNewGateCall() {
        var permissions = mock(PermissionService.class);
        var identities = mock(IdentityDepartmentMapper.class);
        var handle = mock(PermissionService.PrincipalHandle.class);
        when(permissions.principalOf(1)).thenReturn(handle);
        var gate = new ConversationIdentityGate(permissions, identities);
        when(identities.findMustChangePasswordById(1)).thenReturn(false, true);
        assertThat(gate.require(1)).isSameAs(handle);
        assertThatThrownBy(() -> gate.require(1)).isInstanceOf(IdentityException.class);
        doThrow(IdentityException.disabled("disabled")).when(handle).requireEnabled();
        assertThatThrownBy(() -> gate.require(1)).isInstanceOf(IdentityException.class);
    }

    @Test
    void internalAndPublicExceptionsNeverLogPrivateInputsOrCauses() {
        var logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        Level before = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            for (String path : new String[]{"/api/conversations/9/turns", "/internal/conversations/append"}) {
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(
                        "POST", path)));
                var result = new GlobalExceptionHandler().handleOther(new IllegalStateException("private-question" +
                        "-ticket-token"));
                assertThat(result.getBody().message()).doesNotContain("private");
            }
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("private-question-ticket-token");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            RequestContextHolder.resetRequestAttributes();
            logger.detachAppender(appender);
            logger.setLevel(before);
        }
    }
}

