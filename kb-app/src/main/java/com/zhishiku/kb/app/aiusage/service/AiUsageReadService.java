package com.zhishiku.kb.app.aiusage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.aiusage.AiObservationProperties;
import com.zhishiku.kb.app.aiusage.mapper.AiUsageMapper;
import com.zhishiku.kb.app.aiusage.model.AiEventRow;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.aiusage.AiObservation;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 查询只读取安全投影；聚合按调用，事件明细按阶段，不相互推算数量。
 */
@Service
public class AiUsageReadService {
    private static final Set<String> NUMBERS = Set.of("userId", "departmentId", "promptTokens", "completionTokens", "reservedTokens", "plannedChargedTokens", "settledChargedTokens", "elapsedMs");
    private static final Set<String> FIELDS = Set.of("callId", "turnId", "userId", "departmentId", "mode", "startedAt", "generationStatus", "resultCode", "usageStatus", "promptTokens", "completionTokens", "reservedTokens", "plannedChargedTokens", "settledChargedTokens", "settlementPolicy", "settlementStatus", "finalizedAt", "settledAt", "elapsedMs");
    private static final Set<String> METRICS = Set.of("callCount", "modelDispatchCount", "knownCount", "unknownCount", "notInvokedCount", "promptTokens", "completionTokens",
            "plannedChargedTokens", "settledChargedTokens", "runningCount", "completedCount", "failedCount", "cancelledCount", "interruptedCount", "rejectedCount",
            "reservedCount", "pendingCount", "reviewCount", "notRequiredCount", "settledCount");
    private final AiUsageMapper mapper;
    private final AdminQueryAccess access;
    private final AiObservationProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public AiUsageReadService(AiUsageMapper mapper, AdminQueryAccess access, AiObservationProperties properties, ObjectMapper json) {
        this(mapper, access, properties, json, Clock.systemUTC());
    }

    public AiUsageReadService(AiUsageMapper mapper, AdminQueryAccess access, AiObservationProperties properties, ObjectMapper json, Clock clock) {
        this.mapper = mapper; this.access = access; this.properties = properties; this.json = json; this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 6)
    public Map<String, Object> usage(Map<String, List<String>> input, boolean grouped) {
        access.require(Permission.AI_USAGE_READ);
        var query = new AiUsageQuery(grouped ? "groups" : "usage", input, clock.instant());
        var result = metadata(query);
        if (!grouped) result.put("metrics", numbers(mapper.aggregate(query.sql)));
        else {
            var rows = mapper.groups(query.sql);
            boolean more = rows.size() > query.limit;
            var visible = rows.subList(0, Math.min(query.limit, rows.size()));
            List<Map<String, Object>> items = new ArrayList<>();
            for (var row : visible) {
                var item = numbers(row);
                String key = row.get("key").toString();
                item.put("key", "day".equals(query.groupBy) ? key : new BigInteger(key).toString());
                items.add(item);
            }
            result.put("groupBy", query.groupBy); result.put("items", items); result.put("hasMore", more);
            result.put("nextCursor", more && !visible.isEmpty() ? query.cursor(0, null, visible.get(visible.size() - 1).get("key").toString()) : null);
        }
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 6)
    public Map<String, Object> audits(Map<String, List<String>> input) {
        access.require(Permission.AI_AUDIT_READ);
        var query = new AiUsageQuery("audits", input, clock.instant());
        if (!query.continuing()) query.upper = mapper.upperEventId();
        query.sql.put("upper", query.upper);
        var result = metadata(query);
        var rows = mapper.events(query.sql);
        boolean more = rows.size() > query.limit;
        var visible = rows.subList(0, Math.min(query.limit, rows.size()));
        result.put("items", visible.stream().map(this::project).toList());
        result.put("hasMore", more);
        var last = visible.isEmpty() ? null : visible.get(visible.size() - 1);
        result.put("nextCursor", more && last != null ? query.cursor(last.getId(), last.getOccurredAt(), null) : null);
        return result;
    }

    @Transactional(readOnly = true, timeout = 6)
    public Map<String, Object> detail(String eventId) {
        access.require(Permission.AI_AUDIT_READ);
        var row = mapper.event(AiUsageQuery.uuid(eventId));
        if (row == null) throw QueryException.missing();
        return project(row);
    }

    private Map<String, Object> metadata(AiUsageQuery query) {
        var result = new LinkedHashMap<String, Object>();
        result.put("from", query.from.toString()); result.put("to", query.to.toString());
        result.put("asOf", clock.instant().toString()); result.put("collectionEnabled", properties.isEnabled());
        result.put("collectionStatus", properties.isEnabled() ? "ENABLED" : "DISABLED");
        result.put("retentionDays", Integer.toString(properties.getRetentionDays())); result.put("dayTimeZone", "UTC");
        var periods = mapper.periods(query.from, query.to, 101);
        Instant first = mapper.firstCoverage();
        result.put("coverageStartedAt", first == null ? null : first.toString());
        List<Map<String, Object>> intervals = new ArrayList<>();
        Instant covered = query.from;
        boolean truncated = false;
        for (var period : periods) {
            Instant start = instant(period.get("openedAt")), end = period.get("closedAt") == null
                    ? (query.to.isBefore(clock.instant()) ? query.to : clock.instant()) : instant(period.get("closedAt"));
            if (!end.isAfter(query.from) || !start.isBefore(query.to)) continue;
            if (intervals.size() == 100) { truncated = true; break; }
            if (!start.isAfter(covered) && end.isAfter(covered)) covered = end;
            intervals.add(Map.of("from", start.toString(), "to", end.toString()));
        }
        result.put("coverageIntervals", intervals); result.put("coverageIntervalsTruncated", truncated);
        Instant cleared = mapper.clearedThrough();
        result.put("historyMayBeIncomplete", truncated || covered.isBefore(query.to)
                || (cleared != null && !query.from.isAfter(cleared))
                || query.from.isBefore(clock.instant().minusSeconds(properties.getRetentionDays() * 86400L)));
        return result;
    }

    private Map<String, Object> numbers(Map<String, Object> raw) {
        var result = new LinkedHashMap<String, Object>();
        result.put("promptTokens", null);
        result.put("completionTokens", null);
        raw.forEach((key, value) -> {
            if (!METRICS.contains(key)) return;
            result.put(key, value == null ? null : new BigDecimal(value.toString()).toBigIntegerExact().toString());
        });
        return result;
    }

    private Map<String, Object> project(AiEventRow row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", Long.toString(row.getId())); result.put("eventId", row.getEventId());
        result.put("callId", row.getCallId()); result.put("eventType", row.getKind());
        result.put("occurredAt", row.getOccurredAt().toString()); result.put("recordedAt", row.getRecordedAt().toString());
        var snapshot = new LinkedHashMap<String, Object>();
        boolean valid = true;
        try {
            JsonNode data = json.readTree(row.getPayload());
            if (!data.isObject()) throw new IllegalArgumentException();
            for (String field : FIELDS) {
                JsonNode value = data.get(field);
                if (value == null || value.isNull()) { snapshot.put(field, null); continue; }
                if (!value.isTextual() || value.textValue().length() > 128) throw new IllegalArgumentException();
                String text = value.textValue();
                if (NUMBERS.contains(field) && !text.matches("[0-9]{1,128}")) throw new IllegalArgumentException();
                if (Set.of("startedAt", "finalizedAt", "settledAt").contains(field)) Instant.parse(text);
                if (Set.of("callId", "turnId").contains(field)) AiUsageQuery.uuid(text);
                if ("mode".equals(field) && !AiObservation.MODES.contains(text)) throw new IllegalArgumentException();
                if ("generationStatus".equals(field) && !AiObservation.GENERATIONS.contains(text)) throw new IllegalArgumentException();
                if ("usageStatus".equals(field) && !AiObservation.USAGES.contains(text)) throw new IllegalArgumentException();
                if ("settlementStatus".equals(field) && !AiObservation.SETTLEMENTS.contains(text)) throw new IllegalArgumentException();
                if ("resultCode".equals(field) && !AiObservation.RESULTS.contains(text)) throw new IllegalArgumentException();
                if ("settlementPolicy".equals(field) && !Set.of("SINGLE_EXISTING_V1", "MULTI_EXISTING_V1").contains(text)) throw new IllegalArgumentException();
                snapshot.put(field, text);
            }
            if (!row.getCallId().equals(snapshot.get("callId")) || snapshot.get("userId") == null
                    || snapshot.get("departmentId") == null || snapshot.get("mode") == null) throw new IllegalArgumentException();
        } catch (Exception ignored) { snapshot.clear(); valid = false; }
        result.put("snapshotAvailable", valid); result.put("snapshot", snapshot);
        return result;
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof Timestamp stamp) return stamp.toInstant();
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        throw ApiException.unavailable("AI 覆盖元数据不可用");
    }
}
