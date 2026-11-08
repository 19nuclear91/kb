package com.zhishiku.kb.app.storage;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zhishiku.kb.app.storage.mapper.StorageReconciliationMapper;
import com.zhishiku.kb.app.storage.service.StorageReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 对账轮次的护栏与失败语义，全部用 mock 驱动，不连接数据库与对象桶。
 *
 * <p>要钉住的核心是“删除只有在多重护栏全部通过后才发生”：未获单飞锁、列举失败、
 * 比对或复核查询失败、存活引用为空、删除开关关闭时，删除数必须为零。另一类是
 * 计数与失败语义的区分：孤儿计数不受候选上限截断；单个删除失败继续本轮，而
 * 复核查询失败停止后续删除（前者是对象存储问题，后者是数据库问题）。
 *
 * <p>mock 通过只证明编排逻辑；真实桶上的列举与删除效果不在本测试的证明范围内。
 */
class StorageReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final int GRACE_SECONDS = 3600;
    /**
     * 早于宽限期下限，可进入删除候选。
     */
    private static final Instant EXPIRED_AT = NOW.minusSeconds(GRACE_SECONDS + 60);
    private static final String PREFIX = "documents/";
    private static final String LOCK_SQL = "SELECT pg_try_advisory_lock(741306012)";
    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(741306012)";

    private ObjectStorage storage;
    private StorageReconciliationMapper mapper;
    private DataSource dataSource;
    private Connection connection;
    private ResultSet lockResult;
    private PreparedStatement unlockStatement;

    @BeforeEach
    void setUp() throws Exception {
        storage = mock(ObjectStorage.class);
        mapper = mock(StorageReconciliationMapper.class);
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        PreparedStatement lockStatement = mock(PreparedStatement.class);
        lockResult = mock(ResultSet.class);
        unlockStatement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(LOCK_SQL)).thenReturn(lockStatement);
        when(lockStatement.executeQuery()).thenReturn(lockResult);
        when(lockResult.next()).thenReturn(true);
        when(lockResult.getBoolean(1)).thenReturn(true);
        when(connection.prepareStatement(UNLOCK_SQL)).thenReturn(unlockStatement);
        when(mapper.hasLiveReference()).thenReturn(true);
    }

    @Test
    @DisplayName("未获单飞锁：跳过本轮，不列举、不解锁")
    void lockNotAcquiredSkipsCycleWithoutListingOrUnlock() throws Exception {
        when(lockResult.getBoolean(1)).thenReturn(false);

        StorageReconciliationReport report = service(properties(false, 500)).runCycle();

        assertThat(report).isEqualTo(StorageReconciliationReport.empty());
        verify(storage, never()).list(anyString(), any(), anyInt());
        verifyNoInteractions(mapper);
        verify(connection, never()).prepareStatement(UNLOCK_SQL);
    }

    @Test
    @DisplayName("分页列举：游标推进、每页一次 IN 比对、finally 解锁")
    void listsPagesWithCursorAndComparesEachPageInOneQuery() throws Exception {
        when(storage.list(PREFIX, null, 1000))
                .thenReturn(page(List.of(expired("documents/a.txt")), "cursor-1"));
        when(storage.list(PREFIX, "cursor-1", 1000))
                .thenReturn(page(List.of(expired("documents/b.txt")), null));
        when(mapper.listLiveObjectKeys(List.of("documents/a.txt")))
                .thenReturn(List.of("documents/a.txt"));
        when(mapper.listLiveObjectKeys(List.of("documents/b.txt"))).thenReturn(List.of());

        StorageReconciliationReport report = service(properties(false, 500)).runCycle();

        assertThat(report).isEqualTo(new StorageReconciliationReport(2, 1, 0, 0, 0, 0));
        verify(connection).prepareStatement(UNLOCK_SQL);
        verify(unlockStatement).executeQuery();
    }

    @Test
    @DisplayName("前缀防御：前缀外键跳过，不进比对、不计孤儿")
    void outOfPrefixKeysAreSkippedBeforeComparison() {
        when(storage.list(PREFIX, null, 1000)).thenReturn(page(List.of(
                expired("documents/a.txt"), expired("other/b.txt")), null));
        when(mapper.listLiveObjectKeys(List.of("documents/a.txt")))
                .thenReturn(List.of("documents/a.txt"));

        StorageReconciliationReport report = service(properties(false, 500)).runCycle();

        assertThat(report).isEqualTo(new StorageReconciliationReport(2, 0, 0, 0, 0, 0));
        verify(mapper, never()).listLiveObjectKeys(List.of("other/b.txt"));
    }

    @Test
    @DisplayName("宽限期：仅过期孤儿进入删除候选，缺失 LastModified 不删")
    void onlyExpiredOrphansBecomeDeletionCandidates() {
        when(storage.list(PREFIX, null, 1000)).thenReturn(page(List.of(
                expired("documents/old.txt"),
                fresh("documents/fresh.txt"),
                new StoredObject("documents/unknown.txt", null)), null));
        when(mapper.listLiveObjectKeys(List.of("documents/old.txt", "documents/fresh.txt",
                "documents/unknown.txt"))).thenReturn(List.of());
        when(mapper.listLiveObjectKeys(List.of("documents/old.txt"))).thenReturn(List.of());

        StorageReconciliationReport report = service(properties(true, 500)).runCycle();

        assertThat(report).isEqualTo(new StorageReconciliationReport(3, 1, 1, 0, 0, 0));
        verify(storage).delete("documents/old.txt");
        verify(storage, never()).delete("documents/fresh.txt");
        verify(storage, never()).delete("documents/unknown.txt");
    }

    @Test
    @DisplayName("上限：孤儿计数不随候选上限截断，删除尝试截断在上限")
    void orphanCountsAreIndependentOfCandidateCap() {
        List<String> keys = List.of("documents/1.txt", "documents/2.txt", "documents/3.txt",
                "documents/4.txt", "documents/5.txt");
        when(storage.list(PREFIX, null, 1000))
                .thenReturn(page(keys.stream().map(StorageReconciliationServiceTest::expired).toList(), null));
        when(mapper.listLiveObjectKeys(keys)).thenReturn(List.of());
        for (String key : keys) {
            when(mapper.listLiveObjectKeys(List.of(key))).thenReturn(List.of());
        }

        StorageReconciliationReport report = service(properties(true, 2)).runCycle();

        assertThat(report).isEqualTo(new StorageReconciliationReport(5, 5, 2, 0, 0, 0));
        verify(storage).delete("documents/1.txt");
        verify(storage).delete("documents/2.txt");
        verify(storage, never()).delete("documents/3.txt");
        verify(storage, never()).delete("documents/4.txt");
        verify(storage, never()).delete("documents/5.txt");
    }

    @Test
    void exactGraceBoundaryAndUnknownModificationTimeAreNotReportedAsOrphans() {
        when(storage.list(PREFIX, null, 1000)).thenReturn(page(List.of(
                new StoredObject("documents/boundary", NOW.minusSeconds(GRACE_SECONDS)),
                new StoredObject("documents/unknown", null), fresh("documents/fresh")), null));
        when(mapper.listLiveObjectKeys(any())).thenReturn(List.of());
        assertThat(service(properties(false, 500)).runCycle()).isEqualTo(new StorageReconciliationReport(3, 0, 0, 0, 0, 0));
        verify(storage, never()).delete(anyString());
    }

    @Test
    @DisplayName("删除前复核：复核发现存活引用的键跳过删除")
    void recheckSkipsKeysThatBecameLive() {
        when(storage.list(PREFIX, null, 1000)).thenReturn(page(List.of(
                expired("documents/a.txt"), expired("documents/b.txt")), null));
        when(mapper.listLiveObjectKeys(List.of("documents/a.txt", "documents/b.txt")))
                .thenReturn(List.of());
        when(mapper.listLiveObjectKeys(List.of("documents/a.txt")))
                .thenReturn(List.of("documents/a.txt"));
        when(mapper.listLiveObjectKeys(List.of("documents/b.txt"))).thenReturn(List.of());

        StorageReconciliationReport report = service(properties(true, 500)).runCycle();

        assertThat(report).isEqualTo(new StorageReconciliationReport(2, 2, 1, 0, 0, 0));
        verify(storage, never()).delete("documents/a.txt");
        verify(storage).delete("documents/b.txt");
    }

    @Test
    @DisplayName("单个删除失败：计数并继续本轮其余删除")
    void deleteFailureIsCountedAndDoesNotStopOtherDeletions() {
        when(storage.list(PREFIX, null, 1000)).thenReturn(page(List.of(
                expired("documents/a.txt"), expired("documents/b.txt"), expired("documents/c.txt")), null));
        when(mapper.listLiveObjectKeys(List.of("documents/a.txt", "documents/b.txt", "documents/c.txt")))
                .thenReturn(List.of());
        when(mapper.listLiveObjectKeys(List.of("documents/a.txt"))).thenReturn(List.of());
        when(mapper.listLiveObjectKeys(List.of("documents/b.txt"))).thenReturn(List.of());
        when(mapper.listLiveObjectKeys(List.of("documents/c.txt"))).thenReturn(List.of());
        doThrow(new IllegalStateException("s3 down")).when(storage).delete("documents/a.txt");

        StorageReconciliationReport report = service(properties(true, 500)).runCycle();

        assertThat(report).isEqualTo(new StorageReconciliationReport(3, 3, 2, 1, 0, 0));
        verify(storage).delete("documents/a.txt");
        verify(storage).delete("documents/b.txt");
        verify(storage).delete("documents/c.txt");
    }

    @Test
    @DisplayName("列举失败：中止本轮且零删除，锁仍释放")
    void listingFailureAbortsCycleWithoutDeleting() throws Exception {
        when(storage.list(PREFIX, null, 1000))
                .thenReturn(page(List.of(expired("documents/a.txt")), "cursor-1"));
        when(mapper.listLiveObjectKeys(List.of("documents/a.txt"))).thenReturn(List.of());
        when(storage.list(PREFIX, "cursor-1", 1000)).thenThrow(new IllegalStateException("s3 down"));

        StorageReconciliationReport report = service(properties(true, 500)).runCycle();

        assertThat(report).isEqualTo(new StorageReconciliationReport(1, 1, 0, 0, 0, 0));
        verify(storage, never()).delete(anyString());
        verify(connection).prepareStatement(UNLOCK_SQL);
    }

    @Test
    @DisplayName("比对查询失败：中止本轮且零删除")
    void databaseFailureDuringComparisonAbortsCycleWithoutDeleting() {
        when(storage.list(PREFIX, null, 1000))
                .thenReturn(page(List.of(expired("documents/a.txt")), null));
        when(mapper.listLiveObjectKeys(List.of("documents/a.txt")))
                .thenThrow(new IllegalStateException("db down"));

        StorageReconciliationReport report = service(properties(true, 500)).runCycle();

        assertThat(report).isEqualTo(new StorageReconciliationReport(1, 0, 0, 0, 0, 0));
        verify(storage, never()).delete(anyString());
    }

    @Test
    @DisplayName("复核查询失败：停止后续删除，已删不回滚")
    void recheckFailureStopsFurtherDeletions() {
        when(storage.list(PREFIX, null, 1000)).thenReturn(page(List.of(
                expired("documents/a.txt"), expired("documents/b.txt")), null));
        when(mapper.listLiveObjectKeys(List.of("documents/a.txt", "documents/b.txt")))
                .thenReturn(List.of());
        when(mapper.listLiveObjectKeys(List.of("documents/a.txt"))).thenReturn(List.of());
        when(mapper.listLiveObjectKeys(List.of("documents/b.txt")))
                .thenThrow(new IllegalStateException("db down"));

        StorageReconciliationReport report = service(properties(true, 500)).runCycle();

        assertThat(report).isEqualTo(new StorageReconciliationReport(2, 2, 1, 0, 0, 0));
        verify(storage).delete("documents/a.txt");
        verify(storage, never()).delete("documents/b.txt");
    }

    @Test
    @DisplayName("存活引用为空：高优先级告警并整体拒绝删除")
    void emptyLiveReferenceRefusesAllDeletionsWithAlarm() {
        when(mapper.hasLiveReference()).thenReturn(false);
        when(storage.list(PREFIX, null, 1000))
                .thenReturn(page(List.of(expired("documents/a.txt")), null));
        when(mapper.listLiveObjectKeys(List.of("documents/a.txt"))).thenReturn(List.of());

        ListAppender<ILoggingEvent> logs = attachLogAppender();
        StorageReconciliationReport report;
        try {
            report = service(properties(true, 500)).runCycle();
        } finally {
            detachLogAppender(logs);
        }

        assertThat(report).isEqualTo(new StorageReconciliationReport(1, 1, 0, 0, 0, 0));
        verify(storage, never()).delete(anyString());
        assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("疑似连接到错误数据库"));
    }

    @Test
    @DisplayName("报告模式：识别孤儿但零删除、零复核")
    void reportModeCountsOrphansWithoutDeleting() {
        when(storage.list(PREFIX, null, 1000)).thenReturn(page(List.of(
                expired("documents/a.txt"), expired("documents/b.txt")), null));
        when(mapper.listLiveObjectKeys(List.of("documents/a.txt", "documents/b.txt")))
                .thenReturn(List.of());

        StorageReconciliationReport report = service(properties(false, 500)).runCycle();

        assertThat(report).isEqualTo(new StorageReconciliationReport(2, 2, 0, 0, 0, 0));
        verify(storage, never()).delete(anyString());
        verify(mapper, never()).listLiveObjectKeys(List.of("documents/a.txt"));
        verify(mapper, never()).listLiveObjectKeys(List.of("documents/b.txt"));
    }

    @Test
    @DisplayName("存活引用探测失败：跳过本轮，不列举")
    void liveReferenceProbeFailureSkipsCycle() throws Exception {
        when(mapper.hasLiveReference()).thenThrow(new IllegalStateException("db down"));

        StorageReconciliationReport report = service(properties(true, 500)).runCycle();

        assertThat(report).isEqualTo(StorageReconciliationReport.empty());
        verify(storage, never()).list(anyString(), any(), anyInt());
        verify(connection).prepareStatement(UNLOCK_SQL);
    }

    @Test
    @DisplayName("缺失检测：NotFound 计缺失、其他异常计检查失败，分别计数")
    void missingAndFailedChecksAreCountedSeparately() {
        when(storage.list(PREFIX, null, 1000)).thenReturn(page(List.of(), null));
        when(mapper.sampleLiveObjectKeys(100)).thenReturn(List.of(
                "documents/live-a.txt", "documents/live-b.txt", "documents/live-c.txt"));
        when(storage.objectExists("documents/live-a.txt")).thenReturn(true);
        when(storage.objectExists("documents/live-b.txt")).thenReturn(false);
        when(storage.objectExists("documents/live-c.txt"))
                .thenThrow(new IllegalStateException("head failed"));

        ListAppender<ILoggingEvent> logs = attachLogAppender();
        StorageReconciliationReport report;
        try {
            report = service(properties(false, 500)).runCycle();
        } finally {
            detachLogAppender(logs);
        }

        assertThat(report).isEqualTo(new StorageReconciliationReport(0, 0, 0, 0, 1, 1));
        assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("documents/live-b.txt"));
        assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("documents/live-c.txt"));
    }

    @Test
    @DisplayName("缺失检测：开关关闭或上限为零时不抽样")
    void missingCheckIsSkippedWhenDisabledOrCappedAtZero() {
        when(storage.list(PREFIX, null, 1000)).thenReturn(page(List.of(), null));
        List<StorageReconciliationProperties> variants = List.of(
                new StorageReconciliationProperties(0, 0, GRACE_SECONDS, null, false, 500, false, 100),
                new StorageReconciliationProperties(0, 0, GRACE_SECONDS, null, false, 500, true, 0));
        for (StorageReconciliationProperties properties : variants) {
            assertThat(service(properties).runCycle()).isEqualTo(StorageReconciliationReport.empty());
        }

        verify(mapper, never()).sampleLiveObjectKeys(anyInt());
        verify(storage, never()).objectExists(anyString());
    }

    @Test
    @DisplayName("缺失检测：抽样查询失败只跳过检测，本轮其余计数保留")
    void missingSampleQueryFailureSkipsDetectionOnly() {
        when(storage.list(PREFIX, null, 1000))
                .thenReturn(page(List.of(expired("documents/a.txt")), null));
        when(mapper.listLiveObjectKeys(List.of("documents/a.txt"))).thenReturn(List.of());
        when(mapper.sampleLiveObjectKeys(100)).thenThrow(new IllegalStateException("db down"));

        StorageReconciliationReport report = service(properties(false, 500)).runCycle();

        assertThat(report).isEqualTo(new StorageReconciliationReport(1, 1, 0, 0, 0, 0));
    }

    @Test
    @DisplayName("轮次汇总：一条 INFO 记录六个计数器")
    void cycleSummaryLogsAllSixCounters() {
        when(storage.list(PREFIX, null, 1000)).thenReturn(page(List.of(
                expired("documents/a1.txt"), expired("documents/a2.txt"),
                expired("documents/b.txt")), null));
        when(mapper.listLiveObjectKeys(List.of("documents/a1.txt", "documents/a2.txt", "documents/b.txt")))
                .thenReturn(List.of("documents/b.txt"));
        when(mapper.listLiveObjectKeys(List.of("documents/a1.txt"))).thenReturn(List.of());
        when(mapper.listLiveObjectKeys(List.of("documents/a2.txt"))).thenReturn(List.of());
        doThrow(new IllegalStateException("s3 down")).when(storage).delete("documents/a1.txt");
        when(mapper.sampleLiveObjectKeys(100)).thenReturn(List.of("documents/x.txt", "documents/y.txt"));
        when(storage.objectExists("documents/x.txt")).thenReturn(false);
        when(storage.objectExists("documents/y.txt")).thenThrow(new IllegalStateException("head failed"));

        ListAppender<ILoggingEvent> logs = attachLogAppender();
        StorageReconciliationReport report;
        try {
            report = service(properties(true, 500)).runCycle();
        } finally {
            detachLogAppender(logs);
        }

        assertThat(report).isEqualTo(new StorageReconciliationReport(3, 2, 1, 1, 1, 1));
        assertThat(logs.list).anyMatch(event -> event.getLevel() == Level.INFO
                && event.getFormattedMessage().equals(
                "对账轮次完成: 扫描=3 孤儿=2 删除=1 删除失败=1 缺失=1 检查失败=1"));
    }

    @Test
    @DisplayName("孤儿键打印：每轮至多 100 条，日志只含键与汇总")
    void orphanKeysArePrintedAtMostOneHundredPerCycle() {
        List<String> keys = IntStream.rangeClosed(1, 150)
                .mapToObj(index -> "documents/" + index + ".txt").toList();
        when(storage.list(PREFIX, null, 1000))
                .thenReturn(page(keys.stream().map(StorageReconciliationServiceTest::expired).toList(), null));
        when(mapper.listLiveObjectKeys(keys)).thenReturn(List.of());

        ListAppender<ILoggingEvent> logs = attachLogAppender();
        StorageReconciliationReport report;
        try {
            report = service(properties(false, 500)).runCycle();
        } finally {
            detachLogAppender(logs);
        }

        assertThat(report).isEqualTo(new StorageReconciliationReport(150, 150, 0, 0, 0, 0));
        String summary = "对账轮次完成: 扫描=150 孤儿=150 删除=0 删除失败=0 缺失=0 检查失败=0";
        assertThat(logs.list).hasSize(101);
        assertThat(logs.list).filteredOn(event -> event.getFormattedMessage()
                        .matches("孤儿对象: key=documents/\\d+\\.txt"))
                .hasSize(100);
        assertThat(logs.list).anyMatch(event -> event.getFormattedMessage().contains("documents/100.txt"));
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("documents/101.txt"));
        assertThat(logs.list).allSatisfy(event -> {
            String message = event.getFormattedMessage();
            assertThat(message.matches("孤儿对象: key=documents/\\d+\\.txt") || message.equals(summary))
                    .as("日志只允许包含孤儿键行或汇总行: %s", message)
                    .isTrue();
        });
    }

    private StorageReconciliationService service(StorageReconciliationProperties properties) {
        return new StorageReconciliationService(storage, mapper, dataSource, properties, CLOCK);
    }

    @Test
    void expiredActiveImportAndArchivedVersionBothRemainProtected() {
        var keys = List.of("documents/imports/expired-active", "documents/archived-version");
        when(storage.list(PREFIX, null, 1000))
                .thenReturn(page(keys.stream().map(StorageReconciliationServiceTest::expired).toList(), null));
        when(mapper.listLiveObjectKeys(keys)).thenReturn(keys);
        assertThat(service(properties(true, 500)).runCycle()).isEqualTo(new StorageReconciliationReport(2, 0, 0, 0, 0, 0));
        verify(storage, never()).delete(anyString());
    }

    @Test
    void activeImportBeforePutIsExcludedFromMissingSample() {
        when(mapper.hasLiveReference()).thenReturn(true);
        when(storage.list(PREFIX, null, 1000)).thenReturn(page(List.of(), null));
        when(mapper.sampleLiveObjectKeys(100)).thenReturn(List.of("documents/existing-version"));
        when(storage.objectExists("documents/existing-version")).thenReturn(true);
        assertThat(service(properties(true, 500)).runCycle()).isEqualTo(StorageReconciliationReport.empty());
        verify(storage).objectExists("documents/existing-version");
        verify(storage, never()).objectExists("documents/imports/not-put-yet");
    }

    @Test
    void acceptedHistoryDoesNotProtectDeletedVersionOrGetDeletedByCleaner() {
        String key = "documents/imports/accepted";
        AtomicBoolean documentDeleted = new AtomicBoolean();
        when(storage.list(PREFIX, null, 1000)).thenReturn(page(List.of(expired(key)), null));
        when(mapper.listLiveObjectKeys(List.of(key))).thenAnswer(call -> documentDeleted.get() ? List.of() : List.of(key));
        when(mapper.sampleLiveObjectKeys(100)).thenAnswer(call -> documentDeleted.get() ? List.of() : List.of(key));
        when(storage.objectExists(key)).thenReturn(true);
        assertThat(service(properties(true, 500)).runCycle().deleted()).isZero();
        documentDeleted.set(true);
        assertThat(service(properties(true, 500)).runCycle().deleted()).isEqualTo(1);
        verify(storage).delete(key);
    }

    private static StorageReconciliationProperties properties(boolean deleteEnabled, int maxDeletes) {
        return new StorageReconciliationProperties(0, 0, GRACE_SECONDS, null, deleteEnabled, maxDeletes,
                null, null);
    }

    private static StoredObject expired(String key) {
        return new StoredObject(key, EXPIRED_AT);
    }

    private static StoredObject fresh(String key) {
        return new StoredObject(key, NOW.minusSeconds(60));
    }

    private static ListObjectsPage page(List<StoredObject> objects, String nextCursor) {
        return new ListObjectsPage(objects, nextCursor);
    }

    private static ListAppender<ILoggingEvent> attachLogAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger().addAppender(appender);
        return appender;
    }

    private static void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        serviceLogger().detachAppender(appender);
    }

    private static Logger serviceLogger() {
        return (Logger) LoggerFactory.getLogger(StorageReconciliationService.class);
    }
}
