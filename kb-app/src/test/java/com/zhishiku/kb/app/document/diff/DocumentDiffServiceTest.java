package com.zhishiku.kb.app.document.diff;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.diff.mapper.DocumentDiffMapper;
import com.zhishiku.kb.app.document.diff.model.DiffViews.Response;
import com.zhishiku.kb.app.document.diff.model.SnapshotHeader;
import com.zhishiku.kb.app.document.diff.service.DiffAdmission;
import com.zhishiku.kb.app.document.diff.service.DiffDeadline;
import com.zhishiku.kb.app.document.diff.service.DiffException;
import com.zhishiku.kb.app.document.diff.service.DiffExecutor;
import com.zhishiku.kb.app.document.diff.service.DocumentDiffService;
import com.zhishiku.kb.app.document.service.DocumentVersionAccessService;
import com.zhishiku.kb.common.conversation.SourceKey;
import com.zhishiku.kb.common.review.VersionAccessPurpose;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentDiffServiceTest {
    private final DocumentVersionAccessService access = mock(DocumentVersionAccessService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final DocumentDiffMapper mapper = mock(DocumentDiffMapper.class);
    private final DiffExecutor executor = mock(DiffExecutor.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final DiffAdmission admission = new DiffAdmission(BlockDiffTest.properties());
    private final DocumentDiffService service = new DocumentDiffService(access, permissions, mapper,
            BlockDiffTest.properties(), admission, executor, transactions);
    private final Set<SourceKey> keys = Set.of(new SourceKey(10, 1), new SourceKey(10, 2));

    private void visible() {
        when(access.visible(any(), eq(keys), eq(VersionAccessPurpose.INTERACTIVE))).thenReturn(keys);
        when(transactions.getTransaction(any())).thenAnswer(call -> {
            TransactionDefinition definition = call.getArgument(0);
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            return new SimpleTransactionStatus();
        });
    }

    private static SnapshotHeader header(long id, boolean available, long count) {
        return new SnapshotHeader(id, (int) id, "parser", "1", false, 1, count, available);
    }

    @Test
    void rejectsUnreadableCandidateForeignBaseAndRevokedAccessBeforeReading() {
        for (Set<SourceKey> allowed : List.of(Set.of(new SourceKey(10, 1)), Set.of(new SourceKey(10, 2)), Set.<SourceKey>of())) {
            when(access.visible(any(), eq(keys), eq(VersionAccessPurpose.INTERACTIVE))).thenReturn(allowed);
            assertFailure(404);
        }
        verifyNoInteractions(mapper, executor, transactions);
    }

    @Test
    void missingSnapshotHasPriorityOverOversizeWithoutReadingBlocks() {
        visible();
        // 表头 available 是 SQL 对执行周期和 indexed 的合取结果。
        for (long missingId : List.of(1L, 2L)) {
            when(mapper.headers(10, 1, 2)).thenReturn(List.of(header(1, missingId != 1, 600000),
                    header(2, missingId != 2, 600000)));
            Response result = service.compare(7, 10, 1, 2);
            assertThat(result.status()).isEqualTo("SNAPSHOT_MISSING");
            assertThat(result.missingSides()).containsExactly(missingId == 1 ? "BASE" : "TARGET");
            assertThat(result.operations()).isEmpty();
            assertThat(result.stats()).isNull();
        }
        when(mapper.headers(10, 1, 2)).thenReturn(List.of(header(1, true, 600000), header(2, true, 2)));
        assertThat(service.compare(7, 10, 1, 2).status()).isEqualTo("TOO_LARGE");
        verify(mapper, never()).blocks(anyLong());
        verifyNoInteractions(executor);
        assertThat(admission.inflight()).isZero();
    }

    @Test
    void timeoutReturnsNoPartialResultsAndQualityWarningsArePreserved() {
        visible();
        when(mapper.headers(10, 1, 2)).thenReturn(List.of(header(1, true, 2),
                new SnapshotHeader(2, 2, "other", "2", true, 1, 2, true)));
        when(mapper.blocks(anyLong())).thenReturn(List.of(BlockDiffTest.block(0, "正文")));
        when(executor.execute(any(), any())).thenAnswer(call -> {
            DiffAdmission.Ticket ticket = call.getArgument(0);
            ticket.close();
            throw new DiffDeadline.Expired();
        });
        var result = service.compare(7, 10, 1, 2);
        assertThat(result.status()).isEqualTo("TIMEOUT");
        assertThat(result.operations()).isEmpty();
        assertThat(result.valueChanges()).isEmpty();
        assertThat(result.warnings()).extracting(warning -> warning.code()).containsExactly("PARSER_DIFFERS", "TRUNCATED");
        assertThat(result.warnings().get(1).side()).isEqualTo("TARGET");
    }

    @Test
    void successfulAndUnrelatedComputationsHaveCorrectStatus() {
        visible();
        when(mapper.headers(10, 1, 2)).thenReturn(List.of(header(1, true, 2), header(2, true, 2)));
        when(mapper.blocks(anyLong())).thenReturn(List.of(BlockDiffTest.block(0, "正文")));
        when(executor.execute(any(), any())).thenAnswer(call -> {
            DiffAdmission.Ticket ticket = call.getArgument(0);
            java.util.function.Function<DiffDeadline, Response> compute = call.getArgument(1);
            try (ticket) {
                return compute.apply(BlockDiffTest.deadline());
            }
        });
        assertThat(service.compare(7, 10, 1, 2).status()).isEqualTo("AVAILABLE");
        when(mapper.blocks(2)).thenReturn(List.of(BlockDiffTest.block(0, "修订")));
        var low = service.compare(7, 10, 1, 2);
        assertThat(low.status()).isEqualTo("LOW_SIMILARITY");
        assertThat(low.operations()).isEmpty();
    }

    @Test
    void dependencyFailuresAre503WithoutContentInLogsAndReleaseReadTicket() {
        visible();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);
        try {
            when(mapper.headers(10, 1, 2)).thenThrow(new IllegalStateException("private-body 8,000 元"));
            assertFailure(503);
            assertThat(admission.inflight()).isZero();
            doThrow(new IllegalStateException("private-fragment")).when(access)
                    .visible(any(), eq(keys), eq(VersionAccessPurpose.INTERACTIVE));
            assertFailure(503);
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain("private-body", "private-fragment", "8,000"));
        } finally {
            root.detachAppender(appender);
        }
    }

    private void assertFailure(int status) {
        assertThatThrownBy(() -> service.compare(7, 10, 1, 2)).isInstanceOfSatisfying(DiffException.class,
                failure -> assertThat(failure.status()).isEqualTo(status));
    }
}
