package com.zhishiku.kb.app.document.metadata.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.document.metadata.mapper.MetadataMapper;
import com.zhishiku.kb.app.document.metadata.model.MetadataCommandRow;
import com.zhishiku.kb.app.document.metadata.web.MetadataInput;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.REQUEST_MISMATCH;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.UNAVAILABLE;

@Service
public class MetadataCommands {
    private static final Set<AuditScope> SCOPES = Set.of(AuditScope.DOCUMENT_METADATA_UPDATE,
            AuditScope.DOCUMENT_CATEGORY_CREATE, AuditScope.DOCUMENT_CATEGORY_UPDATE, AuditScope.DOCUMENT_CATEGORY_DELETE,
            AuditScope.DOCUMENT_TAG_CREATE, AuditScope.DOCUMENT_TAG_UPDATE, AuditScope.DOCUMENT_RELOCATE);
    private final MetadataMapper mapper;
    private final ObjectMapper json;

    public MetadataCommands(MetadataMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public record Binding(AuditScope scope, String requestId, long actorId, Long targetId,
                          Long spaceId, Long documentId, Long expectedVersion, String digest) {
    }

    public Binding bind(AuditScope scope, String requestId, long actor, Long target, Long space, Long document,
                        Long expectedVersion, Object normalizedInput) {
        if (!SCOPES.contains(scope) || actor <= 0) {
            throw new IllegalArgumentException("命令操作域或主体无效");
        }
        String request = MetadataInput.requestId(requestId);
        String digest = digest(write(Arrays.asList(1, scope.name(), actor, target, space, document,
                expectedVersion, normalizedInput)));
        return new Binding(scope, request, actor, target, space, document, expectedVersion, digest);
    }

    /** 调用方在返回旧回执前仍须按锁后的实际目标重新鉴权。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public MetadataCommandRow lock(Binding binding) {
        mapper.lockRequest("document-metadata:" + binding.scope().name() + ":" + binding.requestId());
        MetadataCommandRow existing = mapper.command(binding.scope().name(), binding.requestId());
        if (existing != null && (existing.digestVersion() != 1 || existing.actorUserId() != binding.actorId()
                || !Objects.equals(existing.targetId(), binding.targetId())
                || !Objects.equals(existing.spaceId(), binding.spaceId())
                || !Objects.equals(existing.documentId(), binding.documentId())
                || !Objects.equals(existing.expectedVersion(), binding.expectedVersion())
                || !Objects.equals(existing.inputDigest(), binding.digest()))) {
            throw new MetadataException(REQUEST_MISMATCH);
        }
        return existing;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(Binding binding, Object receipt) {
        var row = new MetadataCommandRow(binding.scope().name(), binding.requestId(), binding.actorId(),
                binding.targetId(), binding.spaceId(), binding.documentId(), binding.expectedVersion(), 1,
                binding.digest(), write(receipt), null);
        if (mapper.insertCommand(row) != 1) {
            throw new MetadataException(UNAVAILABLE);
        }
    }

    public <T> T receipt(MetadataCommandRow row, Class<T> type) {
        try {
            return json.readValue(row.receipt(), type);
        } catch (JsonProcessingException ignored) {
            throw new MetadataException(UNAVAILABLE);
        }
    }

    public String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ignored) {
            throw new MetadataException(UNAVAILABLE);
        }
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用");
        }
    }
}
