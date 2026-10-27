package com.zhishiku.kb.app.reporting;

import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.collaboration.mapper.CollaborationEventMapper;
import com.zhishiku.kb.app.collaboration.service.CollaborationEventService;
import com.zhishiku.kb.app.reporting.mapper.OperationsReportMapper;
import com.zhishiku.kb.app.reporting.model.OperationsReport;
import com.zhishiku.kb.app.reporting.model.OperationsReportDefinition;
import com.zhishiku.kb.app.reporting.service.OperationsReportService;
import com.zhishiku.kb.common.review.CollaborationEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 原始内存表经生产口径求值后进入真实读取服务；不预填期望聚合，不连接数据库。 */
final class OperationsFactsFixture {
    static final Instant FROM = Instant.parse("2026-10-26T00:00:00Z");
    static final Instant TO = Instant.parse("2026-10-27T00:00:00Z");
    final Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
    final OperationsReportMapper mapper = mock(OperationsReportMapper.class);
    final OperationsReportService reports = new OperationsReportService(mapper, mock(AdminQueryAccess.class));
    final CollaborationEventMapper factMapper = mock(CollaborationEventMapper.class);
    final CollaborationEventService producer = new CollaborationEventService(factMapper);
    final Map<String, Map<String, Object>> facts = new LinkedHashMap<>();

    OperationsFactsFixture() {
        for (String table : List.of("kb_space", "kb_document", "kb_document_version", "kb_document_review",
                "kb_collaboration_event", "kb_chunk", "kb_document_comment", "kb_user_notification")) {
            tables.put(table, new ArrayList<>());
        }
        when(mapper.asOf()).thenReturn(TO.plusSeconds(43200));
        when(mapper.coverage()).thenReturn(List.of(coverage("REVIEW", FROM.minusSeconds(864000)),
                coverage("COMMENT", FROM.minusSeconds(864000))));
        when(mapper.documents()).thenAnswer(call -> OperationsReportDefinition.DOCUMENTS.evaluate(tables, Map.of()).getFirst());
        when(mapper.versions()).thenAnswer(call -> OperationsReportDefinition.VERSIONS.evaluate(tables, Map.of()));
        when(mapper.pendingReviews()).thenAnswer(call -> OperationsReportDefinition.PENDING_REVIEWS.evaluate(tables, Map.of()).getFirst());
        when(mapper.events(any(), any(), any(), any())).thenAnswer(call -> {
            var parameters = row("from", call.getArgument(0), "to", call.getArgument(1),
                    "reviewFrom", call.getArgument(2), "commentFrom", call.getArgument(3));
            return OperationsReportDefinition.EVENTS.evaluate(tables, parameters);
        });
        when(factMapper.append(any())).thenAnswer(call -> {
            CollaborationEvent event = call.getArgument(0);
            return append(event.businessKey(), row("kind", event.kind().name(), "occurred_at", event.occurredAt(),
                    "submitted_at", event.submittedAt(), "decided_at", event.decidedAt(), "doc_id", event.documentId(),
                    "version_id", event.versionId(), "review_id", event.reviewId(), "round_no", event.roundNo(),
                    "comment_id", event.commentId()));
        });
        when(factMapper.recipient(any(), anyLong())).thenReturn(1);
    }

    OperationsReport read() {
        return reports.read(Map.of("from", List.of(FROM.toString()), "to", List.of(TO.toString())));
    }

    Map<String, Object> add(String table, Object... pairs) {
        var record = row(pairs);
        tables.get(table).add(record);
        return record;
    }

    int append(String businessKey, Map<String, Object> event) {
        if (facts.putIfAbsent(businessKey, event) != null) {
            return 0;
        }
        tables.get("kb_collaboration_event").add(event);
        return 1;
    }

    static Map<String, Object> coverage(String domain, Instant start) {
        return row("domain", domain, "protocolVersion", 1, "startedAt", start);
    }

    static Map<String, Object> row(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }
}
