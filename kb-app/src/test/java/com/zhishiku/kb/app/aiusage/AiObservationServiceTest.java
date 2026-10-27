package com.zhishiku.kb.app.aiusage;

import com.zhishiku.kb.app.aiusage.mapper.AiUsageMapper;
import com.zhishiku.kb.app.aiusage.model.AiCallRow;
import com.zhishiku.kb.app.aiusage.service.AiObservationService;
import com.zhishiku.kb.app.aiusage.service.AiUsageWriter;
import com.zhishiku.kb.app.auth.service.AuthService;
import com.zhishiku.kb.app.auth.service.ConversationSessionService;
import com.zhishiku.kb.common.aiusage.AiObservation;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.QuotaPrincipal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiObservationServiceTest {
    private final AiUsageWriter writer = mock(AiUsageWriter.class);
    private final AiUsageMapper mapper = mock(AiUsageMapper.class);
    private final ConversationSessionService sessions = mock(ConversationSessionService.class);
    private final AuthService auth = mock(AuthService.class);
    private final AiObservationService service = new AiObservationService(writer, mapper, sessions, auth);
    private final UUID id = UUID.randomUUID();
    @Test
    void firstRegistrationRechecksSubjectAndUsesOnlyTrustedDepartment() {
        var registration = new AiObservation.Registration(1, id, Instant.EPOCH, "SINGLE", null);
        when(sessions.user("token")).thenReturn(5L); when(auth.quotaPrincipal(5)).thenReturn(new QuotaPrincipal(5, 6));
        service.register(registration, "token"); verify(writer).register(registration, 5, 6);
        when(sessions.user("token")).thenThrow(ApiException.forbidden("停用"));
        assertThatThrownBy(() -> service.register(registration, "token")).isInstanceOf(ApiException.class);
        verify(writer, times(1)).register(any(), anyLong(), anyLong());
    }
    @Test
    void fixedFinalizationNeedsExistingBindingButDoesNotRecheckExpiredSession() {
        var event = new AiObservation.Event(1, id, AiObservation.eventId(id, "REVIEW"), 1, "REVIEW", Instant.EPOCH,
                null, null, null, null, null, null, null, null, null);
        var row = new AiCallRow(); row.setMode("SINGLE"); when(mapper.call(id.toString())).thenReturn(row);
        service.events(event); verify(writer).accept(event); verifyNoInteractions(auth, sessions);
        when(mapper.call(id.toString())).thenReturn(null);
        assertThatThrownBy(() -> service.events(event)).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.httpStatus()).isEqualTo(404));
        verify(writer, times(1)).accept(any());
    }
}
