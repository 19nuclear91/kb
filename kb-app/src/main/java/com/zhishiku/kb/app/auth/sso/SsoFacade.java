package com.zhishiku.kb.app.auth.sso;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.LoginResult;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.common.security.AuthHeaders;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 平台授权与本平台会话、事务、审计之间的编排边界。
 */
@Service
public class SsoFacade {
    private final SsoVerification verification = new SsoVerification();
    private final Map<String, ExternalLoginProvider> providers;
    private final SsoFlows flows;
    private final SsoSession session;
    private final SsoLocalProof proof;
    private final SsoBindings bindings;
    private final IdentityDepartmentMapper mapper;
    private final IdentityService identities;
    private final SsoAudit audit;

    public SsoFacade(List<ExternalLoginProvider> providers, SsoFlows flows, SsoSession session,
                     SsoLocalProof proof, SsoBindings bindings, IdentityDepartmentMapper mapper,
                     IdentityService identities, SsoAudit audit) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(ExternalLoginProvider::platform,
                Function.identity()));
        this.flows = flows;
        this.session = session;
        this.proof = proof;
        this.bindings = bindings;
        this.mapper = mapper;
        this.identities = identities;
        this.audit = audit;
    }

    public SsoFlows.Started start(String id, String browser, String password, String token, boolean bind) {
        Long owner = null;
        try {
            String credential = null;
            if (bind) {
                owner = session.require(token);
                credential = proof.verify(owner, password);
            }
            return flows.start(id, bind ? "BIND" : "LOGIN", browser, owner,
                    bind ? SsoCrypto.digest(token) : null, credential);
        } catch (RuntimeException rejected) {
            SsoException failure = rejected instanceof SsoException known ? known : SsoException.storage();
            if (bind && SsoSettings.PLATFORMS.contains(id))
                audit.terminal(UUID.randomUUID().toString(), "BIND", id, owner, false, failure.code());
            throw failure;
        }
    }

    public String authorization(SsoFlows.Started started) {
        return providers.get(started.flow().getConnectionId()).authorizationUrl(started.state(),
                started.flow().getVerifier());
    }

    public SsoFlows.ResultSecret callback(String id, String browser, Map<String, String> parameters) {
        SsoFlow f = flows.claim(id, browser, parameters.get("state"));
        VerifiedExternalIdentity identity = null;
        SsoException failure = null;
        try {
            identity = verification.verify(providers.get(id), parameters, f.getVerifier());
        } catch (SsoException safe) {
            failure = safe;
        } catch (RuntimeException unavailable) {
            failure = SsoException.unavailable();
        }
        return flows.ready(f, identity, failure);
    }

    public SsoFlow current(String browser) {
        return flows.current(browser);
    }

    public Object exchange(SsoFlow expected, String browser, String secret, String token) {
        if (!SsoCrypto.proof(browser) || !expected.getBrowserDigest().equals(SsoCrypto.digest(browser)))
            throw SsoException.invalid();
        boolean bind = expected.getIntent().equals("BIND");
        if (bind) session.original(expected, token);
        SsoFlow f = flows.consume(expected, secret);
        Long user = bind ? f.getOwnerUserId() : null;
        String issued = null;
        try {
            if (f.getErrorCode() != null) throw new SsoException(f.getErrorStatus(), f.getErrorCode());
            if (f.getIdentity() == null || !f.getIdentity().provider().equals(f.getIdentityScope()))
                throw SsoException.invalid();
            if (bind) {
                session.original(f, token);
                bindings.bind(f);
                return new SsoViews.Bound(f.getConnectionId(), f.getConnectionId(), true);
            }
            var row = mapper.findExternalIdentity(f.getIdentity().provider(), f.getIdentity().subject());
            if (row == null || !Boolean.TRUE.equals(row.getEnabled()))
                throw new SsoException(409, "SSO_BINDING_REQUIRED");
            user = row.getUserId();
            proof.current(user, true);
            boolean mustChange = Boolean.TRUE.equals(mapper.findMustChangePasswordById(user));
            var summary = identities.get(user);
            issued = session.issue(user);
            audit.terminal(f.getFlowId(), "LOGIN", f.getConnectionId(), user, true, "OK");
            return LoginResult.builder().tokenName(AuthHeaders.SA_TOKEN).tokenValue(issued).loginId(user)
                    .mustChangePassword(mustChange).user(summary).build();
        } catch (RuntimeException failed) {
            if (issued != null) {
                try {
                    session.revoke(issued);
                } catch (RuntimeException unavailable) { /* 自然过期约束未交付会话。 */ }
            }
            SsoException safe = failed instanceof SsoException known ? known : SsoException.storage();
            try {
                audit.terminal(f.getFlowId(), f.getIntent(), f.getConnectionId(), user, false, safe.code());
            } catch (RuntimeException unavailable) {
                throw SsoException.storage();
            }
            throw safe;
        }
    }

    public List<SsoViews.BindingStatus> statuses(String token) {
        return bindings.status(session.require(token));
    }
}
