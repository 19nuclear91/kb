package com.zhishiku.kb.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.ConversationSessionService;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.auth.service.InternalAuthenticationService;
import com.zhishiku.kb.app.auth.web.InternalAuthenticationController;
import com.zhishiku.kb.app.conversation.service.ConversationProperties;
import com.zhishiku.kb.app.conversation.service.ConversationService;
import com.zhishiku.kb.app.conversation.service.InternalConversationService;
import com.zhishiku.kb.app.conversation.service.InternalRequestAuthenticator;
import com.zhishiku.kb.app.conversation.service.TurnExecutionService;
import com.zhishiku.kb.app.conversation.web.InternalConversationController;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.conversation.RequestSignature;
import com.zhishiku.kb.common.security.AccountRevokedException;
import com.zhishiku.kb.common.security.AuthenticationSignature;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalIdentityFailureAdviceTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void onlyAuthenticatedUserDenialHasMarkerAndServiceOrDependencyFailuresDoNot() throws Exception {
        var sessions = mock(EpochSessionService.class);
        var service = new InternalAuthenticationService(SECRET, (caller, nonce, ttl) -> true, sessions, Clock.systemUTC());
        var mvc = MockMvcBuilders.standaloneSetup(new InternalAuthenticationController(service))
                .setControllerAdvice(new GlobalExceptionHandler(), new InternalIdentityFailureAdvice()).build();
        for (int code : new int[]{401, 403}) {
            doThrow(code == 401 ? IdentityException.unauthorized("private identity")
                    : IdentityException.disabled("private identity")).when(sessions).check("token");
            var signed = AuthenticationSignature.sign(SECRET, "kb-ai", "token", Clock.systemUTC());
            mvc.perform(post(AuthenticationSignature.PATH).header("satoken", "token")
                            .header(AuthenticationSignature.CALLER, signed.caller())
                            .header(AuthenticationSignature.TIMESTAMP, signed.timestamp())
                            .header(AuthenticationSignature.NONCE, signed.nonce())
                            .header(AuthenticationSignature.SIGNATURE, signed.signature()))
                    .andExpect(status().is(code)).andExpect(header().string(AccountRevokedException.HEADER, AccountRevokedException.CODE))
                    .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().string(""));
        }
        doThrow(ApiException.unavailable("依赖不可用")).when(sessions).check("token");
        var signed = AuthenticationSignature.sign(SECRET, "kb-ai", "token", Clock.systemUTC());
        mvc.perform(post(AuthenticationSignature.PATH).header("satoken", "token")
                        .header(AuthenticationSignature.CALLER, signed.caller())
                        .header(AuthenticationSignature.TIMESTAMP, signed.timestamp())
                        .header(AuthenticationSignature.NONCE, signed.nonce())
                        .header(AuthenticationSignature.SIGNATURE, signed.signature()))
                .andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist(AccountRevokedException.HEADER));

        var untouched = mock(EpochSessionService.class);
        var rejectedMvc = MockMvcBuilders.standaloneSetup(new InternalAuthenticationController(
                        new InternalAuthenticationService(SECRET, (caller, nonce, ttl) -> true, untouched, Clock.systemUTC())))
                .setControllerAdvice(new InternalIdentityFailureAdvice(), new GlobalExceptionHandler()).build();
        rejectedMvc.perform(post(AuthenticationSignature.PATH).header("satoken", "replacement")
                        .header(AccountRevokedException.HEADER, AccountRevokedException.CODE)
                        .header(AuthenticationSignature.CALLER, signed.caller())
                        .header(AuthenticationSignature.TIMESTAMP, signed.timestamp())
                        .header(AuthenticationSignature.NONCE, signed.nonce())
                        .header(AuthenticationSignature.SIGNATURE, signed.signature()))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist(AccountRevokedException.HEADER));
        verifyNoInteractions(untouched);
    }

    @Test
    void executionRevocationMarkerFollowsServiceAuthenticationAndCannotBeSuppliedByCaller() throws Exception {
        var properties = new ConversationProperties();
        properties.setSecret(SECRET);
        var execution = mock(TurnExecutionService.class);
        doThrow(new AccountRevokedException(401)).when(execution).dispatch(any());
        var service = new InternalConversationService(new InternalRequestAuthenticator(properties, (a, b, c) -> true, Clock.systemUTC()),
                mock(ConversationSessionService.class), mock(ConversationService.class), execution, new ObjectMapper());
        var mvc = MockMvcBuilders.standaloneSetup(new InternalConversationController(service))
                .setControllerAdvice(new GlobalExceptionHandler(), new InternalIdentityFailureAdvice()).build();
        String path = "/internal/conversations/dispatch";
        byte[] body = "{\"execution\":{\"conversationId\":9,\"turnId\":\"00000000-0000-0000-0000-000000000001\",\"ownerUserId\":1,\"executionEpoch\":0,\"authEpoch\":1,\"ticket\":\"ticket\"}}".getBytes(StandardCharsets.UTF_8);
        var signed = RequestSignature.sign(SECRET, "kb-ai", "POST", path, body, null, Clock.systemUTC());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Service-Caller", signed.caller()).header("X-Service-Timestamp", signed.timestamp())
                        .header("X-Service-Nonce", signed.nonce()).header("X-Service-Signature", signed.signature()))
                .andExpect(status().isUnauthorized()).andExpect(header().string(AccountRevokedException.HEADER, AccountRevokedException.CODE))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().string(""));
        verify(execution).dispatch(any());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(AccountRevokedException.HEADER, AccountRevokedException.CODE)
                        .header("X-Service-Caller", signed.caller()).header("X-Service-Timestamp", signed.timestamp())
                        .header("X-Service-Nonce", signed.nonce()).header("X-Service-Signature", "invalid"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist(AccountRevokedException.HEADER));
        verify(execution).dispatch(any());
    }
}
