package com.zhishiku.kb.app.reporting.service;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.reporting.mapper.OperationsReportMapper;
import com.zhishiku.kb.app.reporting.model.OperationsReport;
import com.zhishiku.kb.app.reporting.model.OperationsReportDefinition;
import com.zhishiku.kb.app.reporting.model.OperationsReport.Comments;
import com.zhishiku.kb.app.reporting.model.OperationsReport.Coverage;
import com.zhishiku.kb.app.reporting.model.OperationsReport.Day;
import com.zhishiku.kb.app.reporting.model.OperationsReport.Inventory;
import com.zhishiku.kb.app.reporting.model.OperationsReport.Review;
import com.zhishiku.kb.app.reporting.model.OperationsReport.ReviewWindow;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class OperationsReportService {
    public static final List<String> FORMATS = OperationsReportDefinition.FORMATS;
    public static final List<String> PARSING_STATES = OperationsReportDefinition.PARSING_STATES;
    private static final List<String> COUNTS = List.of("approvedCount", "rejectedCount", "durationSampleCount",
            "durationUnknownCount", "decisionElapsedMsTotal", "rootCommentCount", "replyCount");
    private final OperationsReportMapper mapper;
    private final AdminQueryAccess access;

    public OperationsReportService(OperationsReportMapper mapper, AdminQueryAccess access) {
        this.mapper = mapper;
        this.access = access;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 6)
    public OperationsReport read(Map<String, List<String>> input) {
        try {
            access.require(Permission.OPERATIONS_REPORTS_READ);
            Instant asOf = mapper.asOf();
            if (asOf == null) {
                throw unavailable();
            }
            var window = OperationsWindow.parse(input, asOf);
            var starts = coverageStarts(mapper.coverage(), asOf);
            Coverage reviewCoverage = coverage(starts.get("REVIEW"), window.from(), window.to());
            Coverage commentCoverage = coverage(starts.get("COMMENT"), window.from(), window.to());
            Inventory inventory = inventory(mapper.documents(), mapper.versions());
            String pending = count(mapper.pendingReviews().get("pendingReviewCount")).toString();
            var rows = mapper.events(window.from(), window.to(), effective(reviewCoverage), effective(commentCoverage));
            Map<LocalDate, Map<String, BigInteger>> byDay = new HashMap<>();
            for (var row : rows) {
                LocalDate day = LocalDate.parse(row.get("day").toString());
                Instant start = day.atStartOfDay().toInstant(ZoneOffset.UTC);
                if (!start.isBefore(window.to()) || !start.plusSeconds(86400).isAfter(window.from())
                        || byDay.containsKey(day)) {
                    throw unavailable();
                }
                var values = new LinkedHashMap<String, BigInteger>();
                for (String key : COUNTS) {
                    values.put(key, count(row.get(key)));
                }
                if (!values.get("approvedCount").add(values.get("rejectedCount")).equals(
                        values.get("durationSampleCount").add(values.get("durationUnknownCount")))) {
                    throw unavailable();
                }
                byDay.put(day, values);
            }
            var totals = zero();
            List<Day> days = new ArrayList<>();
            Instant cursor = window.from();
            while (cursor.isBefore(window.to())) {
                LocalDate day = cursor.atOffset(ZoneOffset.UTC).toLocalDate();
                Instant midnight = day.atStartOfDay().toInstant(ZoneOffset.UTC);
                Instant end = midnight.plusSeconds(86400).isBefore(window.to()) ? midnight.plusSeconds(86400) : window.to();
                var values = byDay.getOrDefault(day, zero());
                Coverage rc = coverage(starts.get("REVIEW"), cursor, end);
                Coverage cc = coverage(starts.get("COMMENT"), cursor, end);
                for (String key : COUNTS) {
                    boolean observed = key.equals("rootCommentCount") || key.equals("replyCount")
                            ? effective(cc) != null : effective(rc) != null;
                    if (!observed && values.get(key).signum() != 0) {
                        throw unavailable();
                    }
                    totals.put(key, totals.get(key).add(values.get(key)));
                }
                days.add(new Day(day.toString(), cursor.toString(), end.toString(),
                        !cursor.equals(midnight) || !end.equals(midnight.plusSeconds(86400)), review(values, rc), comments(values, cc)));
                cursor = end;
            }
            var r = review(totals, reviewCoverage);
            return new OperationsReport("OPERATIONS_V1", asOf.toString(), window.from().toString(), window.to().toString(),
                    "UTC", inventory, new Review(pending, r.approvedCount(), r.rejectedCount(), r.durationSampleCount(),
                    r.durationUnknownCount(), r.decisionElapsedMsTotal(), r.meanDecisionMs(), r.coverage()),
                    comments(totals, commentCoverage), List.copyOf(days));
        } catch (QueryException | ApiException expected) {
            throw expected;
        } catch (RuntimeException ignored) {
            throw unavailable();
        }
    }

    private static Inventory inventory(Map<String, Object> documents, List<Map<String, Object>> versions) {
        BigInteger total = count(documents.get("documentCount")), archived = count(documents.get("archivedDocumentCount"));
        if (archived.compareTo(total) > 0) {
            throw unavailable();
        }
        var formats = buckets(FORMATS);
        var states = buckets(PARSING_STATES);
        BigInteger versionCount = BigInteger.ZERO;
        for (var row : versions) {
            String format = row.get("format").toString(), state = row.get("parsingState").toString();
            if (!formats.containsKey(format) || !states.containsKey(state)) {
                throw unavailable();
            }
            BigInteger value = count(row.get("versionCount"));
            formats.put(format, formats.get(format).add(value));
            states.put(state, states.get(state).add(value));
            versionCount = versionCount.add(value);
        }
        return new Inventory(total.toString(), archived.toString(), versionCount.toString(), strings(formats), strings(states));
    }

    private static Map<String, Instant> coverageStarts(List<Map<String, Object>> rows, Instant asOf) {
        var result = new HashMap<String, Instant>();
        for (var row : rows) {
            String domain = row.get("domain").toString();
            Instant start = instant(row.get("startedAt"));
            if (!Set.of("REVIEW", "COMMENT").contains(domain) || !BigInteger.ONE.equals(count(row.get("protocolVersion")))
                    || start.isAfter(asOf) || result.putIfAbsent(domain, start) != null) {
                throw unavailable();
            }
            if (row.get("firstOccurredAt") != null && instant(row.get("firstOccurredAt")).isBefore(start)) {
                throw unavailable();
            }
        }
        return result;
    }

    private static Coverage coverage(Instant start, Instant from, Instant to) {
        if (start == null || !start.isBefore(to)) {
            return new Coverage("NOT_STARTED", start == null ? null : start.toString(), null, null);
        }
        Instant effective = start.isAfter(from) ? start : from;
        return new Coverage(start.isAfter(from) ? "PARTIAL" : "COMPLETE", start.toString(), effective.toString(), to.toString());
    }

    private static Instant effective(Coverage value) {
        return value.effectiveFrom() == null ? null : Instant.parse(value.effectiveFrom());
    }

    private static ReviewWindow review(Map<String, BigInteger> values, Coverage coverage) {
        boolean known = effective(coverage) != null;
        BigInteger samples = values.get("durationSampleCount"), elapsed = values.get("decisionElapsedMsTotal");
        if (samples.signum() == 0 && elapsed.signum() != 0) {
            throw unavailable();
        }
        String mean = !known || samples.signum() == 0 ? null
                : new BigDecimal(elapsed).divide(new BigDecimal(samples), 2, RoundingMode.HALF_UP).toPlainString();
        return new ReviewWindow(value(values, "approvedCount", known), value(values, "rejectedCount", known),
                value(values, "durationSampleCount", known), value(values, "durationUnknownCount", known),
                value(values, "decisionElapsedMsTotal", known), mean, coverage);
    }

    private static Comments comments(Map<String, BigInteger> values, Coverage coverage) {
        boolean known = effective(coverage) != null;
        return new Comments(value(values, "rootCommentCount", known), value(values, "replyCount", known), coverage);
    }

    private static String value(Map<String, BigInteger> values, String key, boolean known) {
        return known ? values.get(key).toString() : null;
    }

    private static Map<String, BigInteger> zero() {
        return buckets(COUNTS);
    }

    private static Map<String, BigInteger> buckets(List<String> keys) {
        var result = new LinkedHashMap<String, BigInteger>();
        for (String key : keys) {
            result.put(key, BigInteger.ZERO);
        }
        return result;
    }

    private static Map<String, String> strings(Map<String, BigInteger> input) {
        var result = new LinkedHashMap<String, String>();
        input.forEach((key, value) -> result.put(key, value.toString()));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static BigInteger count(Object value) {
        BigInteger result = new BigDecimal(value.toString()).toBigIntegerExact();
        if (result.signum() < 0) {
            throw unavailable();
        }
        return result;
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        throw unavailable();
    }

    private static QueryException unavailable() {
        return new QueryException(503, "运营报表暂不可用");
    }
}
