package com.zhishiku.kb.app.document.applicability.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.document.applicability.mapper.ValidityCommandMapper;
import com.zhishiku.kb.app.document.applicability.model.ValidityCommandRow;
import com.zhishiku.kb.app.document.applicability.support.ValidityInput;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;

import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.REQUEST_MISMATCH;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.UNAVAILABLE;

@Service
public class ValidityCommands {
    private final ValidityCommandMapper mapper;
    private final ObjectMapper json;

    public ValidityCommands(ValidityCommandMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json.copy().findAndRegisterModules().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public record Binding(AuditScope scope, String requestId, long actor, long target, long document,
                          long expected, String digest) {
    }

    public Binding bind(AuditScope scope, String requestId, long actor, long target, long document, long expected, Object input) {
        if (!Set.of(AuditScope.DOCUMENT_APPLICABILITY_UPDATE, AuditScope.DOCUMENT_STEWARDSHIP_UPDATE,
                AuditScope.DOCUMENT_STEWARDSHIP_CONFIRM).contains(scope) || actor <= 0 || target <= 0 || document <= 0 || expected < 0) {
            throw ValidityInput.invalid();
        }
        String request = ValidityInput.requestId(requestId);
        return new Binding(scope, request, actor, target, document, expected,
                digest(write(Arrays.asList(1, scope.name(), actor, target, document, expected, input))));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ValidityCommandRow lock(Binding binding) {
        mapper.lockRequest("document-validity:" + binding.scope().name() + ":" + binding.requestId());
        var row = mapper.get(binding.scope().name(), binding.requestId());
        if (row != null && (row.digestVersion() != 1 || row.actorUserId() != binding.actor() || row.targetId() != binding.target()
                || row.documentId() != binding.document() || row.expectedVersion() != binding.expected() || !row.inputDigest().equals(binding.digest()))) {
            throw new ValidityException(REQUEST_MISMATCH);
        }
        return row;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(Binding binding, Object receipt) {
        if (mapper.insert(new ValidityCommandRow(binding.scope().name(), binding.requestId(), binding.actor(), binding.target(),
                binding.document(), binding.expected(), 1, binding.digest(), write(receipt))) != 1) {
            throw new ValidityException(UNAVAILABLE);
        }
    }

    public String write(Object input) {
        try {
            return json.writeValueAsString(input);
        } catch (Exception ignored) {
            throw new ValidityException(UNAVAILABLE);
        }
    }

    public <T> T receipt(ValidityCommandRow row, Class<T> type) {
        try {
            return json.readValue(row.receipt(), type);
        } catch (Exception ignored) {
            throw new ValidityException(UNAVAILABLE);
        }
    }

    public static String digest(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 不可用");
        }
    }
}
