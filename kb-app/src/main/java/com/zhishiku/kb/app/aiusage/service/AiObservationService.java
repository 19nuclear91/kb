package com.zhishiku.kb.app.aiusage.service;

import com.zhishiku.kb.app.aiusage.mapper.AiUsageMapper;
import com.zhishiku.kb.app.aiusage.model.AiCallRow;
import com.zhishiku.kb.app.auth.service.AuthService;
import com.zhishiku.kb.app.auth.service.ConversationSessionService;
import com.zhishiku.kb.common.aiusage.AiObservation;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 首次登记复核当前主体；固定收尾只访问已登记的原绑定。
 */
@Service
public class AiObservationService {
    private final AiUsageWriter writer;
    private final AiUsageMapper mapper;
    private final ConversationSessionService sessions;
    private final AuthService auth;

    public AiObservationService(AiUsageWriter writer, AiUsageMapper mapper, ConversationSessionService sessions, AuthService auth) {
        this.writer = writer; this.mapper = mapper; this.sessions = sessions; this.auth = auth;
    }

    public AiCallRow register(AiObservation.Registration registration, String token) {
        if (!"SINGLE".equals(registration.mode())) throw AiUsageWriter.invalid();
        long user = sessions.user(token);
        var principal = auth.quotaPrincipal(user);
        return writer.register(registration, user, principal.departmentId());
    }

    public AiCallRow events(AiObservation.Event event) {
        var target = registered(event.callId());
        if (!"SINGLE".equals(target.getMode()) && !"QUOTA_REJECTED".equals(event.kind())) throw AiUsageWriter.conflict();
        return writer.accept(event);
    }

    public void renew(UUID id, long epoch) { single(id); writer.renew(id, epoch); }
    public AiCallRow status(UUID id, long epoch) {
        var row = single(id);
        if (row.getEpoch() != epoch) throw AiUsageWriter.conflict();
        return row;
    }

    private AiCallRow single(UUID id) {
        var row = registered(id);
        if (!"SINGLE".equals(row.getMode())) throw AiUsageWriter.conflict();
        return row;
    }
    private AiCallRow registered(UUID id) {
        var row = mapper.call(id.toString());
        if (row == null) throw ApiException.notFound("AI 调用未登记或已清理");
        return row;
    }
}
