package com.zhishiku.kb.app.document.preview;

import com.zhishiku.kb.app.document.model.DocumentFile;
import com.zhishiku.kb.common.office.OfficeCrypto;
import com.zhishiku.kb.common.office.OfficeProtocol;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

@Service
public class OfficePreviewSessions {
    private final OfficePreviewSettings settings;
    private final OfficePreviewStore store;
    private final OfficePreviewAccess access;
    private final Clock clock;

    @Autowired
    public OfficePreviewSessions(OfficePreviewSettings settings, OfficePreviewStore store, OfficePreviewAccess access) {
        this(settings, store, access, Clock.systemUTC());
    }

    public OfficePreviewSessions(OfficePreviewSettings settings, OfficePreviewStore store,
                                 OfficePreviewAccess access, Clock clock) {
        this.settings = settings;
        this.store = store;
        this.access = access;
        this.clock = clock;
    }

    public record Launch(int protocolVersion, String sessionId, String launchAction, String launchToken,
                         long expiresAt, long launchExpiresAt, int heartbeatSeconds) {
        @Override
        public String toString() {
            return "OfficePreviewLaunch[redacted]";
        }
    }

    public record Lease(long expiresAt, long absoluteExpiresAt) {
    }

    /** 仅用于内部调用，禁止将 session 或 cookie 直接返回普通业务端点。 */
    public record Activated(String cookie, long expiresAt) {
        @Override
        public String toString() {
            return "OfficePreviewActivation[redacted]";
        }
    }

    public record Authorized(OfficePreviewSession session, DocumentFile file) {
        @Override
        public String toString() {
            return "OfficePreviewAuthorization[redacted]";
        }
    }

    public Launch create(String token, long documentId, long versionId) {
        if (documentId <= 0 || versionId <= 0) {
            throw new OfficePreviewFailure(400);
        }
        var granted = access.require(token, null, documentId, versionId);
        fileAllowed(granted.file());
        enabled();
        String id = UUID.randomUUID().toString().replace("-", "");
        String proof = OfficeCrypto.proof();
        long now = clock.millis();
        long absolute = now + settings.getAbsoluteSeconds() * 1000L;
        long pending = Math.min(absolute, now + settings.getLaunchSeconds() * 1000L);
        String key = "kb-" + OfficeCrypto.digest(settings.getDeploymentId() + ":" + documentId + ":" + versionId + ":" + id);
        var session = new OfficePreviewSession(id, granted.userId(), token, documentId, versionId, key,
                settings.getDeploymentId(), "PENDING", OfficeCrypto.digest(proof), "", now, absolute, pending);
        store.create(session, now, settings.getMaxPerUser(), settings.getMaxTotal());
        return new Launch(OfficeProtocol.PROTOCOL_VERSION, id, settings.getPreviewOrigin() + "/s/" + id + "/bootstrap",
                proof, absolute, pending, settings.getHeartbeatSeconds());
    }

    public Activated activate(String id, String proof, String origin) {
        enabled();
        if (!settings.getApplicationOrigin().equals(origin) || !validProof(proof)) {
            throw new OfficePreviewFailure(404);
        }
        OfficePreviewSession pending = find(id);
        if (!"PENDING".equals(pending.state()) || !OfficeCrypto.equal(pending.launchDigest(), OfficeCrypto.digest(proof))) {
            throw new OfficePreviewFailure(404);
        }
        current(pending);
        String cookie = OfficeCrypto.proof();
        long now = clock.millis();
        OfficePreviewSession active = store.activate(pending, OfficeCrypto.digest(proof), OfficeCrypto.digest(cookie),
                now, now + settings.getLeaseSeconds() * 1000L);
        return new Activated(cookie, active.absoluteExpiresAt());
    }

    public Lease heartbeat(String id, String originalToken) {
        enabled();
        OfficePreviewSession active = find(id);
        if (!"ACTIVE".equals(active.state()) || !OfficeCrypto.equal(active.originalToken(), originalToken)) {
            throw new OfficePreviewFailure(404);
        }
        current(active);
        long now = clock.millis();
        var renewed = store.heartbeat(active, now, now + settings.getLeaseSeconds() * 1000L);
        return new Lease(renewed.expiresAt(), renewed.absoluteExpiresAt());
    }

    public Authorized authorize(String id, String cookie) {
        enabled();
        OfficePreviewSession session = find(id);
        if (!"ACTIVE".equals(session.state()) || !validProof(cookie)
                || !OfficeCrypto.equal(session.cookieDigest(), OfficeCrypto.digest(cookie))) {
            throw new OfficePreviewFailure(404);
        }
        return current(session);
    }

    /** 仅在调用方已核验文档服务 outbox 签名后调用。 */
    public Authorized source(String id) {
        enabled();
        OfficePreviewSession session = find(id);
        if (!"ACTIVE".equals(session.state())) {
            throw new OfficePreviewFailure(404);
        }
        return current(session);
    }

    public void close(String id, String originalToken) {
        try {
            OfficePreviewSession session = find(id);
            if (OfficeCrypto.equal(session.originalToken(), originalToken)) {
                store.close(session);
            }
        } catch (OfficePreviewFailure missing) {
            if (missing.status() != 404) {
                throw missing;
            }
        }
    }

    private OfficePreviewSession find(String id) {
        OfficePreviewSession session = store.find(id, clock.millis());
        if (!settings.getDeploymentId().equals(session.deploymentId())) {
            throw new OfficePreviewFailure(404);
        }
        return session;
    }

    private Authorized current(OfficePreviewSession session) {
        try {
            var granted = access.require(session.originalToken(), session.userId(), session.documentId(), session.versionId());
            fileAllowed(granted.file());
            return new Authorized(session, granted.file());
        } catch (OfficePreviewFailure denied) {
            // 授权故障也终止旧会话；关闭失败时仍拒绝本次读取，后续请求继续重新授权。
            try {
                store.close(session);
            } catch (RuntimeException ignored) {
                // 会话仍有有限租约，不将清理失败解释为允许读取。
            }
            throw denied;
        }
    }

    private void fileAllowed(DocumentFile file) {
        if (!OfficeProtocol.FORMATS.contains(OfficeProtocol.extension(file.getFileName()))) {
            throw new OfficePreviewFailure(415);
        }
        if (file.getFileSize() == null || file.getFileSize() <= 0 || file.getFileSize() > settings.getMaxFileBytes()) {
            throw new OfficePreviewFailure(503);
        }
    }

    private void enabled() {
        if (!settings.isEnabled()) {
            throw new OfficePreviewFailure(503);
        }
    }

    private static boolean validProof(String proof) {
        return proof != null && proof.matches("[A-Za-z0-9_-]{43}");
    }
}
