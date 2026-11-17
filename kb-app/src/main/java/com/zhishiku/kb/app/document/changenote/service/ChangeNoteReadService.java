package com.zhishiku.kb.app.document.changenote.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.changenote.mapper.ChangeNoteMapper;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteInput;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteViews.Detail;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteViews.History;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.zhishiku.kb.app.document.changenote.service.ChangeNoteException.Reason.NOT_FOUND;
import static com.zhishiku.kb.app.document.changenote.service.ChangeNoteException.Reason.UNAVAILABLE;

@Service
public class ChangeNoteReadService {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final ChangeNoteMapper mapper;
    private final ChangeNoteAccess access;

    public ChangeNoteReadService(ChangeNoteMapper mapper, ChangeNoteAccess access) {
        this.mapper = mapper;
        this.access = access;
    }

    @Transactional(readOnly = true, timeout = 3)
    public Detail get(long actor, long document, long version) {
        try {
            var handle = access.current(actor);
            access.version(handle, document, version);
            var target = mapper.target(document, version);
            var row = mapper.get(document, version);
            if (target == null || row == null) {
                throw new ChangeNoteException(NOT_FOUND);
            }
            var base = target.visibleToReaders() && row.hasNote()
                    ? new ChangeNoteMapper.Base(row.baseVersionId(), row.baseVersionNo()) : mapper.base(document, version);
            boolean frozen = !target.visibleToReaders() && target.reviewInProgress();
            boolean editable = !frozen && !target.spaceArchived() && !"archived".equals(target.documentStatus())
                    && access.has(handle, document, PermissionService.PERM_EDIT);
            return new Detail(document, version, row.hasNote(), row.text(), row.baseVersionId(), row.baseVersionNo(),
                    row.corrected(), row.createdAfterVisible(), row.noteVersion(), base == null ? null : base.versionId(),
                    base == null ? null : base.versionNo(), editable, frozen, target.visibleToReaders());
        } catch (ChangeNoteException domain) {
            throw domain;
        } catch (Exception failure) {
            throw new ChangeNoteException(UNAVAILABLE);
        }
    }

    @Transactional(readOnly = true, timeout = 3)
    public History history(long actor, long document, long version, int limit, String token) {
        if (limit < 1 || limit > 100) {
            throw ChangeNoteInput.invalid();
        }
        String context = ChangeNoteCommands.digest("change-note-history:" + actor + ":" + document + ":" + version);
        Cursor cursor = decode(token, context);
        try {
            access.version(access.current(actor), document, version);
            var rows = mapper.history(document, version, cursor == null ? null : cursor.time(),
                    cursor == null ? null : cursor.id(), limit + 1);
            if (rows == null || rows.size() > limit + 1) {
                throw new ChangeNoteException(UNAVAILABLE);
            }
            var page = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
            String next = null;
            if (rows.size() > limit) {
                var last = page.getLast();
                next = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(
                        Map.of("context", context, "id", last.id(), "time", last.changedAt().toString())));
            }
            return new History(page, next);
        } catch (ChangeNoteException domain) {
            throw domain;
        } catch (Exception failure) {
            throw new ChangeNoteException(UNAVAILABLE);
        }
    }

    private record Cursor(long id, OffsetDateTime time) {
    }

    private static Cursor decode(String token, String context) {
        if (token == null) {
            return null;
        }
        try {
            if (!token.matches("[A-Za-z0-9_-]{1,4096}")) {
                throw ChangeNoteInput.invalid();
            }
            try (var parser = JSON.createParser(Base64.getUrlDecoder().decode(token))) {
                JsonNode node = JSON.readTree(parser);
                if (node == null || !node.isObject() || node.size() != 3 || parser.nextToken() != null
                        || !node.path("context").isTextual() || !context.equals(node.get("context").textValue())
                        || !node.path("id").isIntegralNumber() || !node.get("id").canConvertToLong()
                        || node.get("id").longValue() <= 0 || !node.path("time").isTextual()) {
                    throw ChangeNoteInput.invalid();
                }
                return new Cursor(node.get("id").longValue(), OffsetDateTime.parse(node.get("time").textValue()));
            }
        } catch (Exception invalid) {
            throw ChangeNoteInput.invalid();
        }
    }
}
