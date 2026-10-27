package com.zhishiku.kb.app.embedding.service;

import com.zhishiku.kb.app.embedding.mapper.EmbeddingProfileMapper;
import com.zhishiku.kb.app.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.app.embedding.model.EmbeddingCommand;
import com.zhishiku.kb.app.embedding.model.EmbeddingReceipt;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingCommandService {
    private final EmbeddingCommandTransaction transaction;
    private final EmbeddingProfileMapper profiles;
    private final EmbeddingStateMapper state;
    private final EmbeddingReadinessService readiness;

    public EmbeddingCommandService(EmbeddingCommandTransaction transaction, EmbeddingProfileMapper profiles,
                                   EmbeddingStateMapper state, EmbeddingReadinessService readiness) {
        this.transaction = transaction;
        this.profiles = profiles;
        this.state = state;
        this.readiness = readiness;
    }

    public EmbeddingReceipt execute(long actor, EmbeddingCommand command) {
        if (actor <= 0 || command == null) throw ApiException.badRequest("invalid embedding command");
        var replay = transaction.replay(actor, command);
        if (replay != null) return replay;
        String profileId = command.profileId();
        if ("RESUME".equals(command.domain())) {
            var run = state.run(command.runId());
            if (run == null) throw ApiException.notFound("embedding run not found");
            profileId = run.targetProfileId();
        }
        var profile = profiles.find(profileId);
        if (profile == null) throw ApiException.badRequest("unknown embedding profile");
        if (!readiness.ready(profile)) throw ApiException.unavailable("embedding target not ready");
        return transaction.execute(actor, command, profile);
    }
}
