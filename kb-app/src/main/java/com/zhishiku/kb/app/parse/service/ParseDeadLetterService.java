package com.zhishiku.kb.app.parse.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.parse.mapper.ParseDeadLetterMapper;
import com.zhishiku.kb.app.parse.model.ParseDeadLetterPage;
import com.zhishiku.kb.app.parse.model.ParseDeadLetterView;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 管理员死信查询与重放；响应始终只使用脱敏死信投影。
 */
@Service
public class ParseDeadLetterService {

    private final ParseDeadLetterMapper mapper;
    private final IdentityAuditService audits;
    private final EmbeddingControlService embeddingControl;
    private final ObjectMapper json;

    public ParseDeadLetterService(ParseDeadLetterMapper mapper, IdentityAuditService audits,
                                  EmbeddingControlService embeddingControl) {
        this(mapper, audits, embeddingControl, new ObjectMapper());
    }

    @Autowired
    public ParseDeadLetterService(ParseDeadLetterMapper mapper, IdentityAuditService audits,
                                  EmbeddingControlService embeddingControl, ObjectMapper json) {
        this.mapper = mapper;
        this.audits = audits;
        this.embeddingControl = embeddingControl;
        this.json = json;
    }

    public ParseDeadLetterPage list(int page, int size, String status, String failureClass,
                                    OffsetDateTime from, OffsetDateTime to,
                                    long actorUserId, String requestId) {
        validatePage(page, size);
        String normalizedRequest = required(requestId, "X-Request-Id", 256);
        status = filter(status, Set.of("OPEN", "REPLAYED", "RESOLVED"));
        failureClass = filter(failureClass, Set.of("PERMANENT", "RETRYABLE", "UNKNOWN"));
        if (from != null && to != null && !from.isBefore(to)) {
            throw ApiException.badRequest("时间范围无效");
        }
        long total = mapper.count(status, failureClass, from, to);
        ParseDeadLetterPage result = new ParseDeadLetterPage(page, size, total,
                total == 0 ? List.of() : mapper.list(status, failureClass, from, to,
                        (long) (page - 1) * size, size));
        audits.success(AuditScope.PARSE_DEAD_LETTER_QUERY, normalizedRequest, actorUserId,
                null, "LIST", "{}",
                write(Map.of("status", status == null ? "" : status,
                        "failureClass", failureClass == null ? "" : failureClass)));
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public ParseDeadLetterView replay(long actorUserId, long deadLetterId,
                                      String requestId, String reason) {
        if (actorUserId <= 0 || deadLetterId <= 0) throw ApiException.badRequest("目标或操作者无效");
        String normalizedRequest = required(requestId, "X-Request-Id", 256);
        String normalizedReason = required(reason, "reason", 2000);
        try {
            mapper.limitLockWait();
            mapper.lockReplayRequest("parse-deadletter-replay:" + normalizedRequest);
        } catch (CannotAcquireLockException | QueryTimeoutException unavailable) {
            throw ApiException.unavailable("操作暂不可确认，请按原请求核对");
        }
        var binding = mapper.findReplayBinding(normalizedRequest);
        if (binding != null) {
            if (!Objects.equals(binding.getDeadLetterId(), deadLetterId)
                    || !Objects.equals(binding.getActorUserId(), actorUserId)
                    || !Objects.equals(binding.getReason(), normalizedReason)) {
                throw ApiException.conflict("请求标识已绑定其他操作");
            }
            return receipt(normalizedRequest, deadLetterId);
        }
        embeddingControl.lockShared();
        int changed = mapper.replay(deadLetterId, normalizedRequest, actorUserId, normalizedReason);
        if (changed != 1) {
            throw ApiException.notFound("死信不存在、已处置或重放请求无效");
        }
        ParseDeadLetterView result = receipt(normalizedRequest, deadLetterId);
        audits.success(AuditScope.PARSE_DEAD_LETTER_REPLAY, normalizedRequest, actorUserId,
                result.getId(), "REPLAY", "{\"status\":\"OPEN\"}",
                write(Map.of("status", "REPLAYED", "reason", normalizedReason)));
        return result;
    }

    private static void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw ApiException.badRequest("分页参数无效");
        }
    }

    private ParseDeadLetterView receipt(String requestId, long target) {
        var result = mapper.findByReplayRequest(requestId);
        if (result == null || !Objects.equals(result.getId(), target) || !"REPLAYED".equals(result.getStatus())) {
            throw new IllegalStateException("重放回执不可确认");
        }
        return result;
    }

    private static String required(String value, String name, int max) {
        if (value == null) throw ApiException.badRequest("缺少 " + name);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.indexOf('\0') >= 0
                || normalized.codePointCount(0, normalized.length()) > max) {
            throw ApiException.badRequest(name + " 长度或内容无效");
        }
        return normalized;
    }

    private static String filter(String value, Set<String> allowed) {
        if (value == null || value.isEmpty()) return null;
        if (!allowed.contains(value)) throw ApiException.badRequest("筛选条件无效");
        return value;
    }

    private String write(Map<String, String> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("审计摘要序列化失败");
        }
    }
}
