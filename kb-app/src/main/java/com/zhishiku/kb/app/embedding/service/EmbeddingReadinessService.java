package com.zhishiku.kb.app.embedding.service;

import com.zhishiku.kb.app.embedding.mapper.EmbeddingReadinessMapper;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class EmbeddingReadinessService {
    private final EmbeddingReadinessMapper mapper;
    private final Clock clock;

    @Autowired
    public EmbeddingReadinessService(EmbeddingReadinessMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    public EmbeddingReadinessService(EmbeddingReadinessMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public boolean ready(EmbeddingProfile profile) {
        var rows = mapper.findFresh(profile.profileId());
        var now = clock.instant();
        return rows.stream().anyMatch(row -> row.usable(profile, EmbeddingRole.QUERY, now))
                && rows.stream().anyMatch(row -> row.usable(profile, EmbeddingRole.DOCUMENT, now));
    }
}
