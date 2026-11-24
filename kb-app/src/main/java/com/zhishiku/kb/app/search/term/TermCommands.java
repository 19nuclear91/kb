package com.zhishiku.kb.app.search.term;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.search.term.mapper.TermMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

import static com.zhishiku.kb.app.search.term.TermException.Reason.REQUEST_MISMATCH;
import static com.zhishiku.kb.app.search.term.TermException.Reason.UNAVAILABLE;

/** 词典域独立幂等账本；调用方必须在锁和匹配重放前重新核对当前写资格。 */
@Service
public class TermCommands {
    private final TermMapper mapper;
    private final ObjectMapper json;

    public TermCommands(TermMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json.copy();
    }

    /**
     * 完整规范化意图的用途隔离绑定。
     * @param operation 精确操作
     * @param requestId 词典域请求标识
     * @param actorId 本次有效操作者
     * @param targetKind 目标种类
     * @param targetId 原意图目标，创建为空
     * @param expectedVersion 原意图前置版本，创建为空
     * @param digest 完整意图 SHA-256 摘要
     */
    public record Binding(TermRows.Operation operation, String requestId, long actorId, TermRows.EntityKind targetKind,
                          Long targetId, Long expectedVersion, String digest) {
        @Override
        public String toString() {
            return "TermCommandBinding[redacted]";
        }
    }

    public Binding bind(TermRows.Operation operation, String requestId, long actorId, Long targetId,
                        Long expectedVersion, Object input) {
        if (operation == null || actorId <= 0 || (operation.create() ? targetId != null || expectedVersion != null
                : targetId == null || targetId <= 0 || expectedVersion == null || expectedVersion < 0)) {
            throw TermInput.invalid();
        }
        Object normalized = normalized(operation, input, expectedVersion);
        String request = TermInput.requestId(requestId);
        return new Binding(operation, request, actorId, operation.kind(), targetId, expectedVersion,
                digest(write(Arrays.asList("search-terminology-command", 1, operation.name(), actorId,
                        operation.kind().name(), targetId, expectedVersion, normalized))));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public TermRows.Command lock(Binding binding) {
        mapper.lockRequest("search-terminology:" + binding.requestId());
        TermRows.Command row = mapper.command(binding.requestId());
        if (row != null && (!complete(row) || !Objects.equals(row.requestId(), binding.requestId())
                || row.operation() != binding.operation() || row.actorUserId() != binding.actorId()
                || row.targetKind() != binding.targetKind() || !Objects.equals(row.targetId(), binding.targetId())
                || !Objects.equals(row.expectedVersion(), binding.expectedVersion()) || !Objects.equals(row.inputDigest(), binding.digest()))) {
            throw new TermException(REQUEST_MISMATCH);
        }
        return row;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(Binding binding, TermReceipt receipt) {
        TermRows.Command row = new TermRows.Command(binding.requestId(), binding.operation(), binding.actorId(),
                binding.targetKind(), binding.targetId(), binding.expectedVersion(), 1, binding.digest(), write(receipt), null);
        validateReceipt(row, receipt);
        if (mapper.insertCommand(row) != 1) {
            throw new TermException(UNAVAILABLE);
        }
    }

    public TermReceipt receipt(TermRows.Command row) {
        if (!complete(row)) {
            throw new TermException(REQUEST_MISMATCH);
        }
        try {
            if (row.receipt() == null || row.receipt().getBytes(StandardCharsets.UTF_8).length > 4096) {
                throw new TermException(UNAVAILABLE);
            }
            var body = TermInput.body(new ByteArrayInputStream(row.receipt().getBytes(StandardCharsets.UTF_8)));
            Set<String> fields = Set.of("kind", "id", "version", "dictionaryRevision", "replayed");
            TermInput.fields(body, fields, fields);
            var receipt = new TermReceipt(TermRows.EntityKind.valueOf(TermInput.rawText(body.get("kind"))),
                    decimal(TermInput.rawText(body.get("id")), true), decimal(TermInput.rawText(body.get("version")), false),
                    decimal(TermInput.rawText(body.get("dictionaryRevision")), true), TermInput.bool(body.get("replayed")));
            validateReceipt(row, receipt);
            return receipt;
        } catch (IllegalArgumentException | TermException damaged) {
            throw new TermException(UNAVAILABLE);
        }
    }

    private static boolean complete(TermRows.Command row) {
        if (row == null || row.requestId() == null || row.requestId().isEmpty() || row.operation() == null
                || row.actorUserId() <= 0 || row.targetKind() != row.operation().kind()
                || row.digestVersion() != 1 || row.inputDigest() == null || !row.inputDigest().matches("[0-9a-f]{64}")) {
            return false;
        }
        return row.operation().create() ? row.targetId() == null && row.expectedVersion() == null
                : row.targetId() != null && row.targetId() > 0 && row.expectedVersion() != null && row.expectedVersion() >= 0;
    }

    private static Object normalized(TermRows.Operation operation, Object input, Long expectedVersion) {
        Object normalized;
        Long actualVersion;
        switch (operation) {
            case SEARCH_TERM_CREATE, SEARCH_TERM_UPDATE -> {
                if (!(input instanceof TermInputs.TermWrite term)) {
                    throw TermInput.invalid();
                }
                var value = TermInput.normalized(term, operation.create());
                normalized = value;
                actualVersion = value.expectedVersion();
            }
            case SEARCH_CORRECTION_CREATE, SEARCH_CORRECTION_UPDATE -> {
                if (!(input instanceof TermInputs.CorrectionWrite correction)) {
                    throw TermInput.invalid();
                }
                var value = TermInput.normalized(correction, operation.create());
                normalized = value;
                actualVersion = value.expectedVersion();
            }
            case SEARCH_TERM_ENABLED, SEARCH_CORRECTION_ENABLED -> {
                if (!(input instanceof TermInputs.EnabledWrite enabled) || enabled.expectedVersion() < 0) {
                    throw TermInput.invalid();
                }
                normalized = enabled;
                actualVersion = enabled.expectedVersion();
            }
            default -> throw TermInput.invalid();
        }
        if (!Objects.equals(expectedVersion, actualVersion)) {
            throw TermInput.invalid();
        }
        return normalized;
    }

    private static void validateReceipt(TermRows.Command row, TermReceipt receipt) {
        if (receipt == null || receipt.kind() != row.targetKind() || receipt.id() <= 0 || receipt.version() < 0
                || receipt.dictionaryRevision() <= 0 || receipt.replayed()
                || (row.operation().create() ? receipt.version() != 0 : !Objects.equals(row.targetId(), receipt.id())
                || row.expectedVersion() == null || row.expectedVersion() == Long.MAX_VALUE
                || receipt.version() != row.expectedVersion() + 1)) {
            throw new TermException(UNAVAILABLE);
        }
    }

    private static long decimal(String value, boolean positive) {
        if (!value.matches(positive ? "[1-9][0-9]{0,18}" : "0|[1-9][0-9]{0,18}")) {
            throw new TermException(UNAVAILABLE);
        }
        return Long.parseLong(value);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ignored) {
            throw new TermException(UNAVAILABLE);
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用");
        }
    }
}
