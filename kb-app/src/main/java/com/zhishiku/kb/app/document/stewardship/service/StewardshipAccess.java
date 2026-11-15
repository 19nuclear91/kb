package com.zhishiku.kb.app.document.stewardship.service;

import com.zhishiku.kb.app.document.applicability.service.ValidityAccess;
import com.zhishiku.kb.app.document.applicability.service.ValidityException;
import com.zhishiku.kb.app.document.stewardship.mapper.StewardshipMapper;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipDocument;
import com.zhishiku.kb.app.space.service.SpaceQueryService;
import org.springframework.stereotype.Service;

import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.DOCUMENT_ARCHIVED;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.NOT_FOUND;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.SPACE_ARCHIVED;

@Service
public class StewardshipAccess {
    private final StewardshipMapper mapper;
    private final ValidityAccess access;
    private final SpaceQueryService spaces;

    public StewardshipAccess(StewardshipMapper mapper, ValidityAccess access, SpaceQueryService spaces) {
        this.mapper = mapper;
        this.access = access;
        this.spaces = spaces;
    }

    public StewardshipDocument manager(long actor, long document, boolean write) {
        var handle = access.current(actor);
        var target = mapper.document(document);
        if (target == null) {
            throw new ValidityException(NOT_FOUND);
        }
        var space = spaces.findManaged(handle, target.spaceId());
        if (space == null) {
            throw new ValidityException(NOT_FOUND);
        }
        boolean archived = Boolean.TRUE.equals(space.getArchived());
        if (write && archived) {
            throw new ValidityException(SPACE_ARCHIVED);
        }
        if (write && "archived".equals(target.status())) {
            throw new ValidityException(DOCUMENT_ARCHIVED);
        }
        return new StewardshipDocument(target.documentId(), target.spaceId(), target.status(), archived);
    }

    public void space(long actor, long space) {
        if (spaces.findManaged(access.current(actor), space) == null) {
            throw new ValidityException(NOT_FOUND);
        }
    }
}
