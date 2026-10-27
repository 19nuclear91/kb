package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingConnection;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.LocalProfileEmbeddingClient;
import com.zhishiku.kb.common.embedding.ProfileEmbeddingClient;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.parse.mapper.ParseTaskMapper;
import com.zhishiku.kb.worker.parse.model.ParseTask;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ParseTaskClaimServiceTest {
    private final EmbeddingStateMapper state = mock(EmbeddingStateMapper.class);
    private final ParseTaskMapper tasks = mock(ParseTaskMapper.class);
    private final EmbeddingClientRegistry clients = mock(EmbeddingClientRegistry.class);
    private final DocumentParser parser = mock(DocumentParser.class);
    private final ParseTaskClaimService service = new ParseTaskClaimService(state, clients, tasks, parser);

    @BeforeEach
    void parserIsConfigured() {
        when(parser.isAvailable()).thenReturn(true);
    }

    @Test
    void unavailableParserLeavesQueueAndAttemptsUntouched() {
        when(parser.isAvailable()).thenReturn(false);
        assertThat(service.claim("owner", 60, 1)).isEmpty();
        assertThat(service.claim("owner", 60, 1)).isEmpty();
        verifyNoInteractions(state, clients, tasks);
    }

    @Test
    void maintenanceNeverClaimsOrChangesAttemptAndVersionState() {
        for (String mode : List.of("UNINITIALIZED", "QUIESCING", "REBUILDING", "VERIFYING", "BLOCKED")) {
            boolean initial = "UNINITIALIZED".equals(mode);
            when(state.lockShared()).thenReturn(new EmbeddingControl(mode, null, null,
                    initial ? null : "target", initial ? null : "fingerprint", 3,
                    initial ? null : UUID.randomUUID()));
            assertThat(service.claim("owner", 60, 1)).isEmpty();
        }
        verifyNoInteractions(tasks, clients);
    }

    @Test
    void activeWithoutCompatibleDocumentClientDoesNotClaim() {
        when(state.lockShared()).thenReturn(active());
        assertThat(service.claim("owner", 60, 1)).isEmpty();
        verify(clients).find("p", "fp");
        verifyNoInteractions(tasks);
    }

    @Test
    void locksControlBeforeClaimAndSavesIdentityWithoutModelCall() {
        when(state.lockShared()).thenReturn(active());
        var client = mock(ProfileEmbeddingClient.class);
        when(client.role()).thenReturn(EmbeddingRole.DOCUMENT);
        when(clients.find("p", "fp")).thenReturn(Optional.of(client));
        var task = new ParseTask(1L, 2L, 2, 3, OffsetDateTime.now().plusMinutes(1), "owner",
                null, "RETRYABLE", "TIMEOUT", "history", null, 0L,
                "key", "a.txt", 1L, "text/plain", "p", "fp", 4L, 9L);
        when(tasks.claim("owner", 60, 1, "p", 4L)).thenReturn(List.of(task));
        assertThat(service.claim("owner", 60, 1)).containsExactly(task);
        var order = inOrder(state, clients, tasks);
        order.verify(state).lockShared();
        order.verify(clients).find("p", "fp");
        order.verify(tasks).claim("owner", 60, 1, "p", 4L);
        order.verify(tasks).updateVersionIndexStatus(2L, "parsing", null);
        verify(client, never()).embed(anyList());
        assertThat(task.hasEmbeddingIdentity()).isTrue();
    }

    @Test
    void missingOrFailedControlReadFailsClosed() {
        assertThatThrownBy(() -> service.claim("owner", 60, 1)).isInstanceOf(IllegalStateException.class);
        when(state.lockShared()).thenThrow(new IllegalStateException("storage unavailable"));
        assertThatThrownBy(() -> service.claim("owner", 60, 1)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(tasks, clients);
    }

    @Test
    void claimSqlKeepsIndependentMonotonicTokenAndTransactionBoundary() throws Exception {
        var method = ParseTaskMapper.class.getMethod("claim", String.class, int.class, int.class,
                String.class, long.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value());
        assertThat(sql).contains("claim_token = t.claim_token + 1", "embedding_profile_id = #{profileId}",
                "embedding_epoch = #{epoch}", "FOR UPDATE SKIP LOCKED", "c.attempts < c.max_attempts",
                "c.embedding_epoch AS embedding_epoch", "c.claim_token AS claim_token",
                "p.fingerprint AS embedding_fingerprint").doesNotContain("claim_token = t.attempts");
        assertThat(ParseTaskClaimService.class.getMethod("claim", String.class, int.class, int.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(String.join(" ", EmbeddingStateMapper.class.getMethod("lockShared")
                .getAnnotation(Select.class).value())).contains("FOR SHARE OF c");
        assertThat(ParseTaskRunner.class.getMethod("poll").getAnnotation(Transactional.class)).isNull();
    }

    private EmbeddingControl active() {
        return new EmbeddingControl("ACTIVE", "p", "fp", null, null, 4L, null);
    }

    @Test
    void profileSwitchStopsLaggingRegistryAndNewRegistryClaimsWithNewIdentity() {
        var old = new EmbeddingProfile("old", "local", "kb/local", "local-hash-v1", "1", 1024,
                "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
        var next = new EmbeddingProfile("next", "local", "kb/local", "local-hash-v1", "2", 1024,
                "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
        var oldDefinition = new EmbeddingProfileDefinition(old, new EmbeddingConnection(null, null, 30, 16));
        var nextDefinition = new EmbeddingProfileDefinition(next, new EmbeddingConnection(null, null, 30, 16));
        var lagging = new EmbeddingClientRegistry(List.of(oldDefinition), EmbeddingRole.DOCUMENT,
                d -> new LocalProfileEmbeddingClient(d, EmbeddingRole.DOCUMENT));
        var upgraded = new EmbeddingClientRegistry(List.of(oldDefinition, nextDefinition), EmbeddingRole.DOCUMENT,
                d -> new LocalProfileEmbeddingClient(d, EmbeddingRole.DOCUMENT));
        when(state.lockShared()).thenReturn(new EmbeddingControl("ACTIVE", "next", next.fingerprint(), null, null, 10
                , null));
        assertThat(new ParseTaskClaimService(state, lagging, tasks, parser).claim("owner", 60, 1)).isEmpty();
        verifyNoInteractions(tasks);
        new ParseTaskClaimService(state, upgraded, tasks, parser).claim("owner", 60, 1);
        verify(tasks).claim("owner", 60, 1, "next", 10);
    }
}
