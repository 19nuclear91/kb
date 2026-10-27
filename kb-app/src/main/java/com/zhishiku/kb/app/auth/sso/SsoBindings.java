package com.zhishiku.kb.app.auth.sso;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.SsoBindingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 本人绑定的短 SQL 事务，不在持锁期间调用供应商。
 */
@Service
public class SsoBindings {
    private final IdentityDepartmentMapper mapper;
    private final SsoBindingMapper writes;
    private final SsoLocalProof proof;
    private final SsoSettings settings;
    private final SsoAudit audit;

    public SsoBindings(IdentityDepartmentMapper mapper, SsoBindingMapper writes, SsoLocalProof proof,
                       SsoSettings settings, SsoAudit audit) {
        this.mapper = mapper;
        this.writes = writes;
        this.proof = proof;
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void bind(SsoFlow f) {
        long owner = f.getOwnerUserId();
        var c = settings.connection(f.getConnectionId());
        SsoScope.matches(f.getConnectionId(), c, f.getIdentity());
        if (!f.getFingerprint().equals(SsoScope.fingerprint(f.getConnectionId(), settings)))
            throw new SsoException(400, "CONFIG_CHANGED");
        if (mapper.findIdentityForUpdate(owner) == null) throw new SsoException(403, "USER_DISABLED");
        proof.unchanged(owner, f.getCredentialFingerprint());
        var identity = f.getIdentity();
        int inserted = writes.insertIfAbsent(owner, identity.provider(), identity.subject());
        // ON CONFLICT DO NOTHING 后的独立语句在 READ COMMITTED 下读取赢家，不捕获 aborted 事务继续查询。
        var row = mapper.findExternalIdentity(identity.provider(), identity.subject());
        if (row == null || !Long.valueOf(owner).equals(row.getUserId()) || !Boolean.TRUE.equals(row.getEnabled()))
            throw new SsoException(409, "BINDING_CONFLICT");
        audit.terminal(f.getFlowId(), "BIND", f.getConnectionId(), owner, true, inserted == 1 ? "OK" : "ALREADY_BOUND");
    }

    public List<SsoViews.BindingStatus> status(long user) {
        proof.current(user, false);
        return SsoSettings.PLATFORMS.stream().sorted().map(id -> {
            var c = settings.properties().getConnections().get(id);
            boolean configured = c != null && !c.getClientId().isBlank() && !c.getTargetEnterpriseId().isBlank();
            boolean bound = configured && mapper.listExternalIdentities(user, SsoScope.provider(id, c)).stream()
                    .anyMatch(row -> Boolean.TRUE.equals(row.getEnabled()));
            return new SsoViews.BindingStatus(id, id, c != null && c.isEnabled(), bound);
        }).toList();
    }
}
