package com.zhishiku.kb.app.document.metadata.service;

import com.zhishiku.kb.app.document.metadata.mapper.MetadataMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RelocationContextService {
    private final MetadataMapper mapper;
    private final MetadataAccess access;

    public RelocationContextService(MetadataMapper mapper, MetadataAccess access) {
        this.mapper = mapper;
        this.access = access;
    }

    public record Context(long documentId, long fromSpaceId, long metadataVersion) {
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public Context get(long id) {
        access.current();
        var row = mapper.document(id);
        if (row == null || "deleted".equals(row.getStatus())) {
            throw new MetadataException(MetadataException.Reason.NOT_FOUND);
        }
        access.manager(row.getSpaceId());
        return new Context(id, row.getSpaceId(), row.getMetadataVersion());
    }
}
