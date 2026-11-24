package com.zhishiku.kb.app.search.term;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 仅追加固定身份、状态、版本和 changedFields，绝不保存词条正文。 */
@Component
public class TermAudit {
    private final IdentityAuditService audit;
    private final ObjectMapper json;

    public TermAudit(IdentityAuditService audit, ObjectMapper json) {
        this.audit = audit;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void term(TermCommands.Binding binding, TermRows.Term before, TermRows.Term after,
                     long previousRevision, long revision, List<String> changedFields) {
        requireFields(changedFields, Set.of("canonicalName", "description", "aliases", "enabled"));
        AuditScope scope = before == null ? AuditScope.SEARCH_TERM_CREATE : AuditScope.SEARCH_TERM_UPDATE;
        audit.appendSuccess(scope, binding.requestId(), binding.actorId(), after.id(), before == null ? "create" : "update",
                write(before == null ? Map.of() : termState(before, previousRevision, List.of())),
                write(termState(after, revision, changedFields)));
    }

    private static Map<String, Object> termState(TermRows.Term term, long revision, List<String> changedFields) {
        var state = new LinkedHashMap<String, Object>();
        state.put("termId", term.id());
        state.put("enabled", term.enabled());
        state.put("version", term.version());
        state.put("dictionaryRevision", revision);
        state.put("changedFields", List.copyOf(changedFields));
        return state;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void correction(TermCommands.Binding binding, TermRows.Correction before, TermRows.Correction after,
                           long previousRevision, long revision, List<String> changedFields) {
        requireFields(changedFields, Set.of("wrongExpression", "targetTermId", "enabled"));
        AuditScope scope = before == null ? AuditScope.SEARCH_CORRECTION_CREATE : AuditScope.SEARCH_CORRECTION_UPDATE;
        audit.appendSuccess(scope, binding.requestId(), binding.actorId(), after.id(), before == null ? "create" : "update",
                write(before == null ? Map.of() : correctionState(before, previousRevision, List.of())),
                write(correctionState(after, revision, changedFields)));
    }

    private static Map<String, Object> correctionState(TermRows.Correction correction, long revision, List<String> changedFields) {
        var state = new LinkedHashMap<String, Object>();
        state.put("correctionId", correction.id());
        state.put("targetTermId", correction.targetTermId());
        state.put("enabled", correction.enabled());
        state.put("version", correction.version());
        state.put("dictionaryRevision", revision);
        state.put("changedFields", List.copyOf(changedFields));
        return state;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ignored) {
            throw new TermException(TermException.Reason.UNAVAILABLE);
        }
    }

    private static void requireFields(List<String> fields, Set<String> allowed) {
        if (fields == null || fields.isEmpty() || fields.size() > allowed.size()
                || fields.stream().distinct().count() != fields.size() || fields.stream().anyMatch(field -> field == null || !allowed.contains(field))) {
            throw new TermException(TermException.Reason.UNAVAILABLE);
        }
    }
}
