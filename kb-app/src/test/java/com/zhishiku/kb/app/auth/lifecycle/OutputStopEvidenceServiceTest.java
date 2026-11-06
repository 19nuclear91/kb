package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.mapper.OutputStopEvidenceMapper;
import com.zhishiku.kb.app.auth.web.OutputStopEvidenceController;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.AuthenticationSignature;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutputStopEvidenceServiceTest {
    private final String secret = "test-auth-secret-32-bytes-minimum";
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-06T00:00:10Z"), ZoneOffset.UTC);
    private final OutputStopEvidenceMapper mapper = mock(OutputStopEvidenceMapper.class);
    private final LifecycleObservations observations = mock(LifecycleObservations.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final HashSet<String> nonces = new HashSet<>();
    private final OutputStopEvidenceService service = new OutputStopEvidenceService(mapper, observations, manager, secret,
            (caller, nonce, ttl) -> nonces.add(caller + nonce), clock);
    private final byte[] body = ("{\"executionId\":\"12345678-1234-1234-1234-123456789012\",\"userId\":7,\"authEpoch\":3,"
            + "\"checkedAt\":\"2026-11-06T00:00:00Z\",\"afterCheckMinNanos\":4000000000,\"afterCheckMaxNanos\":5000000000,"
            + "\"reason\":\"ACCOUNT_REVOKED\"}").getBytes(StandardCharsets.UTF_8);

    @Test
    void signedStopIsNoStoreIdempotentAndChangedBindingIsRejected() {
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        var stored = new HashMap<UUID, String>();
        when(mapper.stopped(any(), anyString())).thenAnswer(call -> {
            var report = call.getArgument(0, com.zhishiku.kb.common.security.OutputStopReport.class);
            return stored.putIfAbsent(report.executionId(), call.getArgument(1)) == null ? 1 : 0;
        });
        when(mapper.binding(any())).thenAnswer(call -> stored.get(call.getArgument(0)));
        var controller = new OutputStopEvidenceController(service);
        var response = controller.stopped(request(body));
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        controller.stopped(request(body));
        assertThat(stored).hasSize(1);
        byte[] changed = new String(body, StandardCharsets.UTF_8).replace("4000000000", "3000000000").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> controller.stopped(request(changed))).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(409));
    }

    @Test
    void authenticationAndBoundsPrecedeAnyEvidenceAccess() {
        var headers = AuthenticationSignature.signStop(secret, body, clock);
        assertThatThrownBy(() -> service.accept("POST", AuthenticationSignature.PATH, body, headers)).isInstanceOf(ApiException.class);
        var invalid = new MockHttpServletRequest("POST", AuthenticationSignature.STOP_PATH);
        invalid.setContent(new byte[AuthenticationSignature.STOP_MAX_BYTES + 1]);
        assertThatThrownBy(() -> new OutputStopEvidenceController(service).stopped(invalid)).isInstanceOf(ApiException.class);
        byte[] extra = new String(body, StandardCharsets.UTF_8).replace("\"userId\":7", "\"userId\":7,\"payload\":\"private\"").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new OutputStopEvidenceController(service).stopped(request(extra))).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(400));
        verifyNoInteractions(mapper);
    }

    @Test
    void postCommitObservationFailureDoesNotRejectAlreadyCommittedReceptionOrFillMissingTime() {
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        doThrow(new IllegalStateException("private-db-detail")).when(mapper).receptionCommitted(11);
        service.committed(11, true);
        verify(observations).dependencyFailed(LifecycleObservations.Dependency.EVIDENCE);
        service.committed(12, false);
        verify(mapper).barriersCommitted(12);
    }

    private MockHttpServletRequest request(byte[] payload) {
        var request = new MockHttpServletRequest("POST", AuthenticationSignature.STOP_PATH);
        request.setContent(payload);
        var signed = AuthenticationSignature.signStop(secret, payload, clock);
        request.addHeader(AuthenticationSignature.CALLER, signed.caller());
        request.addHeader(AuthenticationSignature.TIMESTAMP, signed.timestamp());
        request.addHeader(AuthenticationSignature.NONCE, signed.nonce());
        request.addHeader(AuthenticationSignature.SIGNATURE, signed.signature());
        return request;
    }
}
