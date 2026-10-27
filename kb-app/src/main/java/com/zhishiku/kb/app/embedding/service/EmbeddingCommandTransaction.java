package com.zhishiku.kb.app.embedding.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.embedding.mapper.EmbeddingCommandMapper;
import com.zhishiku.kb.app.embedding.mapper.EmbeddingProfileMapper;
import com.zhishiku.kb.app.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.app.embedding.model.EmbeddingCommand;
import com.zhishiku.kb.app.embedding.model.EmbeddingReceipt;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
public class EmbeddingCommandTransaction {
    private final EmbeddingStateMapper state;
    private final EmbeddingProfileMapper profiles;
    private final EmbeddingCommandMapper commands;
    private final EmbeddingReadinessService readiness;
    private final ObjectMapper json;

    public EmbeddingCommandTransaction(EmbeddingStateMapper state, EmbeddingProfileMapper profiles,
                                       EmbeddingCommandMapper commands, EmbeddingReadinessService readiness,
                                       ObjectMapper json) {
        this.state = state;
        this.profiles = profiles;
        this.commands = commands;
        this.readiness = readiness;
        this.json = json;
    }

    public EmbeddingReceipt replay(long actor, EmbeddingCommand command) {
        var existing = commands.operation(command.domain(), command.requestId());
        if (existing == null) return null;
        if (existing.actorUserId() != actor || !command.digest(actor).equals(existing.inputDigest())) {
            throw ApiException.conflict("embedding request identifier already bound");
        }
        try {
            return json.readValue(existing.receipt(), EmbeddingReceipt.class).replay();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid stored embedding receipt");
        }
    }

    @Transactional(timeout = 10)
    public EmbeddingReceipt execute(long actor, EmbeddingCommand command, EmbeddingProfile preparedProfile) {
        var control = state.lockExclusive();
        if (control == null) throw new IllegalStateException("embedding control unavailable");
        // 控制独占锁同时串行化请求标识；必须先判重放，不能先因旧版本拒绝。
        var replay = replay(actor, command);
        if (replay != null) return replay;
        UUID runId;
        String next;
        long version;
        if ("START".equals(command.domain())) {
            if ((!control.active() && !"UNINITIALIZED".equals(control.state()))
                    || control.epoch() != command.expectedVersion() || control.runId() != null) throw conflict();
            if (!command.profileId().equals(preparedProfile.profileId())) throw conflict();
            runId = UUID.randomUUID();
            next = "QUIESCING";
            version = 0;
        } else {
            runId = command.runId();
            var run = state.run(runId);
            if (run == null) throw ApiException.notFound("embedding run not found");
            if (!"BLOCKED".equals(control.state()) || !runId.equals(control.runId())
                    || !"BLOCKED".equals(run.state()) || run.version() != command.expectedVersion()
                    || !run.targetProfileId().equals(preparedProfile.profileId())
                    || !run.targetProfileId().equals(control.targetProfileId())) throw conflict();
            next = run.resumePhase();
            if (next == null || !Set.of("QUIESCING", "REBUILDING", "VERIFYING").contains(next)) throw conflict();
            version = Math.addExact(run.version(), 1);
        }
        var currentProfile = profiles.find(preparedProfile.profileId());
        if (!preparedProfile.compatibleWith(currentProfile)) throw conflict();
        if (!readiness.ready(currentProfile)) throw ApiException.unavailable("embedding target not ready");
        long epoch = Math.addExact(control.epoch(), 1);
        if ("START".equals(command.domain())) {
            requireOne(commands.createRun(runId, currentProfile.profileId(), actor));
            requireOne(commands.start(runId, currentProfile.profileId(), control.epoch()));
        } else {
            requireOne(commands.resumeRun(runId, command.expectedVersion()));
            commands.resumeItems(runId);
            requireOne(commands.resumeControl(runId, control.epoch(), next));
        }
        var receipt = new EmbeddingReceipt(UUID.randomUUID(), runId, currentProfile.profileId(), next, version, epoch
                , false);
        String stored;
        try {
            stored = json.writeValueAsString(receipt);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("embedding receipt serialization failed");
        }
        // 操作记录本身为追加审计；异常必须传播到事务边界，不吞掉失败伪造成功回执。
        requireOne(commands.record(receipt.operationId(), command.domain(), command.requestId(), actor,
                command.digest(actor), runId, command.reason(), stored, control.state(), next, epoch));
        return receipt;
    }

    private void requireOne(int count) {
        if (count != 1) throw conflict();
    }

    private ApiException conflict() {
        return ApiException.conflict("embedding state or version conflict");
    }
}
