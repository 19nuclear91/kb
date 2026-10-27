package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.AuditQueryMapper;
import com.zhishiku.kb.app.auth.model.AuditQueryFilter;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 全局审计的只读来源；首期仅覆盖 sys_identity_audit。
 */
@Service
@Transactional(readOnly = true, timeout = 5)
public class AuditQuerySource {
    private final AuditQueryMapper mapper;

    public AuditQuerySource(AuditQueryMapper mapper) {
        this.mapper = mapper;
    }

    public List<IdentityAuditRecord> list(AuditQueryFilter filter) {
        return mapper.list(filter);
    }

    public IdentityAuditRecord detail(UUID eventId) {
        return mapper.detail(eventId);
    }
}
