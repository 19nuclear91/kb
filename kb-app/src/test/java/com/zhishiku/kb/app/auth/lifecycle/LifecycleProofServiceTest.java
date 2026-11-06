package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.mapper.LifecycleMapper;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LifecycleProofServiceTest {
    @Test
    void shortProofRejectsDifferentActorTargetOperationBindingAndExpiry() {
        var mapper = mock(LifecycleMapper.class);
        var stored = new AtomicReference<LifecycleProof>();
        when(mapper.insertProof(any())).thenAnswer(call -> {
            stored.set(call.getArgument(0));
            return 1;
        });
        when(mapper.lockProof(anyString())).thenAnswer(call -> stored.get());
        var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var service = new LifecycleProofService(mapper, clock);
        var binding = Map.of("version", 1, "source", "dingtalk");
        var issued = service.issue(7, 7, "EMERGENCY", binding, new EmergencyManagementService.Evidence(true));
        assertThat(issued.expiresAt()).isEqualTo(clock.instant().plusSeconds(300));
        assertThatThrownBy(() -> service.consume(issued.proof(), 8, 7, "EMERGENCY", binding, Map.class)).isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> service.consume(issued.proof(), 7, 8, "EMERGENCY", binding, Map.class)).isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> service.consume(issued.proof(), 7, 7, "RESTORE", binding, Map.class)).isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> service.consume(issued.proof(), 7, 7, "EMERGENCY", Map.of("version", 2), Map.class)).isInstanceOf(IdentityException.class);
        var expired = new LifecycleProofService(mapper, Clock.offset(clock, Duration.ofSeconds(300)));
        assertThatThrownBy(() -> expired.consume(issued.proof(), 7, 7, "EMERGENCY", binding, Map.class)).isInstanceOf(IdentityException.class);
        verify(mapper, never()).consumeProof(anyString(), any());
    }
}
