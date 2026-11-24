package com.zhishiku.kb.app.search.term;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.audit.model.AuditQueryViews.Change;
import com.zhishiku.kb.app.audit.service.AuditSummaryProjector;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.service.AuditScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TermAuditProjectionTest {
    private final AuditSummaryProjector projector = new AuditSummaryProjector();

    @Test
    void termProjectionKeepsExactVersionsAndDisabledHistoryButDropsAllInjectedText() throws Exception {
        String before = "{\"termId\":1,\"enabled\":true,\"version\":9223372036854775806,\"dictionaryRevision\":9223372036854775806}";
        String after = "{\"termId\":1,\"enabled\":false,\"version\":9223372036854775807,\"dictionaryRevision\":9223372036854775807,"
                + "\"changedFields\":[\"canonicalName\",\"description\",\"aliases\",\"enabled\"],\"canonicalName\":\"private-name\","
                + "\"description\":\"private-description\",\"aliases\":[\"private-alias\"],\"query\":\"private-query\","
                + "\"selectionContext\":\"private-context\",\"inputDigest\":\"private-digest\",\"receipt\":{\"text\":\"private-receipt\"}}";
        var detail = projector.detail(row(AuditScope.SEARCH_TERM_UPDATE, before, after));
        assertThat(detail.summaryAvailable()).isTrue();
        assertThat(detail.changes()).contains(new Change("version", Long.MAX_VALUE - 1, Long.MAX_VALUE),
                new Change("dictionaryRevision", Long.MAX_VALUE - 1, Long.MAX_VALUE), new Change("enabled", true, false));
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(detail))
                .doesNotContain("private-", "inputDigest", "selectionContext", "receipt", "beforeState", "afterState");
    }

    @Test
    void correctionProjectionKeepsBothStableTargetsAndAppliesEntitySpecificFieldWhitelist() {
        String before = "{\"correctionId\":1,\"targetTermId\":5,\"enabled\":true,\"version\":3,\"dictionaryRevision\":10}";
        String after = "{\"correctionId\":1,\"targetTermId\":9223372036854775807,\"enabled\":true,\"version\":4,"
                + "\"dictionaryRevision\":11,\"changedFields\":[\"wrongExpression\",\"targetTermId\"],\"wrongExpression\":\"private-expression\"}";
        var detail = projector.detail(row(AuditScope.SEARCH_CORRECTION_UPDATE, before, after));
        assertThat(detail.summaryAvailable()).isTrue();
        assertThat(detail.changes()).contains(new Change("targetTermId", 5L, Long.MAX_VALUE));
        assertThat(projector.detail(row(AuditScope.SEARCH_CORRECTION_UPDATE, before,
                after.replace("[\"wrongExpression\",\"targetTermId\"]", "[\"canonicalName\"]"))).summaryAvailable()).isFalse();
    }

    @Test
    void missingBrokenFieldsDuplicatesAndInconsistentFactsKeepMetadataButMakeSummaryUnavailable() {
        String before = "{\"termId\":1,\"enabled\":true,\"version\":3,\"dictionaryRevision\":10}";
        String valid = "{\"termId\":1,\"enabled\":false,\"version\":4,\"dictionaryRevision\":11,\"changedFields\":[\"enabled\"]}";
        for (String after : List.of("{}", valid.replace("\"version\":4", "\"version\":\"4\""),
                valid.replace("\"version\":4", "\"version\":5"), valid.replace("\"termId\":1", "\"termId\":2"),
                valid.replace("\"dictionaryRevision\":11", "\"dictionaryRevision\":10"),
                valid.replace("[\"enabled\"]", "[]"), valid.replace("[\"enabled\"]", "[\"enabled\",\"enabled\"]"),
                valid.replace("[\"enabled\"]", "[\"private-query\"]"), valid.replace("\"enabled\":false", "\"enabled\":null"))) {
            var detail = projector.detail(row(AuditScope.SEARCH_TERM_UPDATE, before, after));
            assertThat(detail.summaryAvailable()).isFalse();
            assertThat(detail.changes()).isEmpty();
            assertThat(detail.metadata().operationScope()).isEqualTo("SEARCH_TERM_UPDATE");
            assertThat(detail.metadata().targetId()).isEqualTo(1);
        }
    }

    private static IdentityAuditRecord row(AuditScope scope, String before, String after) {
        return new IdentityAuditRecord(1L, UUID.randomUUID(), scope.name(), "request", 7L, scope.targetType(), 1L,
                "update", "SUCCESS", before, after, null, Instant.EPOCH);
    }
}
