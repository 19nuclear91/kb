package com.zhishiku.kb.app.search.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingReadiness;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class EmbeddingInstanceReadiness {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingInstanceReadiness.class);

    /**
     * 当前应用实例的嵌入就绪快照，不包含连接凭据。
     *
     * @param instanceId  当前应用实例标识
     * @param profileId   不可变嵌入配置标识
     * @param fingerprint 嵌入配置的语义指纹
     * @param epoch       嵌入控制代次，用于隔离旧任务与旧查询
     * @param ready       当前配置是否已通过就绪检查
     * @param reason      操作或状态原因
     */
    @Schema(description = "当前应用实例的嵌入就绪快照，不包含连接凭据。")
    public record Snapshot(
            @Schema(description = "当前应用实例标识") UUID instanceId,
            @Schema(description = "不可变嵌入配置标识") String profileId,
            @Schema(description = "嵌入配置的语义指纹") String fingerprint,
            @Schema(description = "嵌入控制代次，用于隔离旧任务与旧查询") Long epoch,
            @Schema(description = "当前配置是否已通过就绪检查") boolean ready,
            @Schema(description = "操作或状态原因") String reason) {
    }

    private final UUID instanceId = UUID.randomUUID();
    private final EmbeddingClientRegistry clients;
    private final Clock clock;
    private final Map<String, EmbeddingReadiness> probes = new HashMap<>();
    private EmbeddingControl control;
    private boolean controlAvailable;
    private String previousReason;

    @Autowired
    public EmbeddingInstanceReadiness(EmbeddingClientRegistry clients) {
        this(clients, Clock.systemUTC());
    }

    public EmbeddingInstanceReadiness(EmbeddingClientRegistry clients, Clock clock) {
        this.clients = clients;
        this.clock = clock;
    }

    public UUID instanceId() {
        return instanceId;
    }

    public synchronized void record(EmbeddingReadiness row) {
        if (!instanceId.equals(row.instanceId()) || row.role() != EmbeddingRole.QUERY) {
            throw new IllegalArgumentException("foreign embedding readiness record");
        }
        probes.put(row.profileId(), row);
    }

    public synchronized Snapshot observe(EmbeddingControl current) {
        if (current == null) throw new IllegalArgumentException("embedding control required");
        // 慢查询不能把较新代次的不就绪信号覆盖回旧 profile 的成功信号。
        if (control == null || current.epoch() >= control.epoch()) {
            control = current;
            controlAvailable = true;
        }
        return snapshot();
    }

    public synchronized void controlUnavailable() {
        controlAvailable = false;
        snapshot();
    }

    public synchronized Snapshot snapshot() {
        String reason;
        if (!controlAvailable) reason = "CONTROL_UNAVAILABLE";
        else if (!control.active()) reason = "EMBEDDING_MAINTENANCE";
        else if (clients.find(control.activeProfileId(), control.activeFingerprint())
                .filter(client -> client.role() == EmbeddingRole.QUERY).isEmpty()) reason = "MISSING_PROFILE";
        else {
            var row = probes.get(control.activeProfileId());
            reason = row != null && row.usable(control.activeProfileId(), control.activeFingerprint(),
                    EmbeddingRole.QUERY, clock.instant()) ? "READY" : "PROBE_UNAVAILABLE";
        }
        if (!reason.equals(previousReason)) {
            log.info("embedding instance readiness reason={}", reason);
            previousReason = reason;
        }
        return new Snapshot(instanceId, control == null ? null : control.activeProfileId(),
                control == null ? null : control.activeFingerprint(), control == null ? null : control.epoch(),
                "READY".equals(reason), reason);
    }
}
