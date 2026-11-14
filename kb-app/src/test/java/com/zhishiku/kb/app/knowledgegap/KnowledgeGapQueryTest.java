package com.zhishiku.kb.app.knowledgegap;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.zhishiku.kb.app.auth.service.IdentityQueryService;
import com.zhishiku.kb.app.auth.service.KnowledgeGapAssigneeService;
import com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapViews;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapQueryService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapReferenceService;
import com.zhishiku.kb.app.knowledgegap.web.KnowledgeGapQueryController;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.app.support.MapperRowStub;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.LinkedMultiValueMap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 读取服务/控制器与 MyBatis 结构的离线验证；不启动 Spring 应用、数据库或身份目录。 */
class KnowledgeGapQueryTest {
    private final KnowledgeGapMemoryFixture fixture = new KnowledgeGapMemoryFixture();
    private final IdentityQueryService identities = mock(IdentityQueryService.class);
    private final KnowledgeGapAssigneeService assignees = mock(KnowledgeGapAssigneeService.class);
    private final KnowledgeGapQueryService service = new KnowledgeGapQueryService(fixture.access, fixture.mapper, identities,
            assignees, new KnowledgeGapReferenceService(fixture.access, fixture.documents));
    private final KnowledgeGapQueryController controller = new KnowledgeGapQueryController(service);

    @BeforeEach
    void setUp() {
        when(identities.labels(anyCollection())).thenAnswer(call -> {
            Collection<Long> ids = call.getArgument(0);
            var result = new HashMap<Long, String>();
            for (long id : ids) {
                result.put(id, "显示标签" + id);
            }
            return result;
        });
        when(assignees.eligible(anyLong())).thenReturn(true);
    }

    @Test
    void mineListUsesServerFilteredDescendingPositionAndHasAccurateLastPage() {
        fixture.actorId = 10;
        when(fixture.mapper.list(10, "mine", null, null, 2)).thenReturn(List.of(gap(13), gap(12)));
        var first = service.list(Map.of("limit", List.of("1")));
        assertThat(first.items()).extracting("gapId").containsExactly(13L);
        assertThat(first.hasMore()).isTrue();
        when(fixture.mapper.list(10, "mine", null, 13L, 2)).thenReturn(List.of(gap(11)));
        var last = service.list(Map.of("limit", List.of("1"), "cursor", List.of(first.nextCursor())));
        assertThat(last.items()).extracting("gapId").containsExactly(11L);
        assertThat(last.hasMore()).isFalse();
        assertThat(last.nextCursor()).isNull();
        assertThat(last.items().getFirst().reporter().userId()).isEqualTo(10);
        verifyNoInteractions(fixture.documents);
    }

    @Test
    void assignedAndQueueRequireIndependentPermissionBeforeReadingList() {
        fixture.actorId = 10;
        assertStatus(403, () -> service.list(Map.of("view", List.of("assigned"))));
        assertStatus(403, () -> service.queue(Map.of()));
        verify(fixture.mapper, never()).list(anyLong(), anyString(), any(), any(), anyInt());
        fixture.actorId = 8;
        when(fixture.mapper.list(8, "assigned", null, null, 21)).thenReturn(List.of(fixture.gap));
        assertThat(service.list(Map.of("view", List.of("assigned"))).items()).hasSize(1);
        assertStatus(403, () -> service.queue(Map.of()));
    }

    @Test
    void queueShowsOnlyControlledReassignmentFlagWithoutChangingFacts() {
        fixture.actorId = 7;
        var before = fixture.gap;
        when(fixture.mapper.list(7, "queue", null, null, 21)).thenReturn(List.of(before));
        when(assignees.eligible(8)).thenReturn(false);
        var item = service.queue(Map.of()).items().getFirst();
        assertThat(item.needsReassignment()).isTrue();
        assertThat(item.state()).isEqualTo(KnowledgeGapState.IN_PROGRESS);
        assertThat(fixture.gap).isEqualTo(before);
        assertThat(JsonMapper.builder().findAndAddModules().build().valueToTree(item).toString())
                .doesNotContain("enabled", "department", "role", "password", "clearance", "description");
    }

    @Test
    void crossActorViewAndFilterCursorsNeverReachListRepository() {
        fixture.actorId = 7;
        for (var filters : List.of(QueryParameters.filters("actor", 10, "view", "queue"),
                QueryParameters.filters("actor", 7, "view", "mine"),
                QueryParameters.filters("actor", 7, "view", "queue", "status", "CLOSED"))) {
            String cursor = QueryCursor.encode("knowledge-gap-list", filters, 20);
            assertThatThrownBy(() -> service.queue(Map.of("cursor", List.of(cursor)))).isInstanceOf(QueryException.class);
        }
        verify(fixture.mapper, never()).list(anyLong(), anyString(), any(), any(), anyInt());
    }

    @Test
    void invalidFiltersAreNotEmptyOrUnrestrictedQueries() {
        fixture.actorId = 7;
        for (Map<String, List<String>> parameters : List.of(Map.of("view", List.of("queue")),
                Map.of("status", List.of("OPEN")), Map.of("limit", List.of("101")), Map.of("limit", List.of("1.5")),
                Map.of("status", List.of("CLOSED", "RESOLVED")), Map.of("description", List.of("秘密")))) {
            assertThatThrownBy(() -> service.list(parameters)).isInstanceOf(QueryException.class);
        }
        assertThatThrownBy(() -> service.queue(Map.of("view", List.of("mine")))).isInstanceOf(QueryException.class);
        verify(fixture.mapper, never()).list(anyLong(), anyString(), any(), any(), anyInt());
    }

    @Test
    void emptyPageSkipsLabelsButRepositoryAndQualificationFailuresAre503() {
        fixture.actorId = 7;
        when(fixture.mapper.list(7, "queue", null, null, 21)).thenReturn(List.of());
        assertThat(controller.queue(new LinkedMultiValueMap<>()).data().items()).isEmpty();
        verifyNoInteractions(identities, assignees);
        doReturn(null).when(fixture.mapper).list(7, "queue", null, null, 21);
        assertStatus(503, () -> service.queue(Map.of()));
        doReturn(List.of(fixture.gap)).when(fixture.mapper).list(7, "queue", null, null, 21);
        doThrow(new IllegalStateException("secret lifecycle reason")).when(assignees).eligible(8);
        assertThatThrownBy(() -> service.queue(Map.of())).hasMessage("知识问题单服务暂不可用");
    }

    @Test
    void detailIsAuthorizedBeforeLabelsAndDoesNotEmbedHistory() {
        fixture.actorId = 10;
        var detail = controller.detail("11", new LinkedMultiValueMap<>()).data();
        assertThat(detail.description()).isEqualTo("共享问题");
        assertThat(detail.capabilities().note()).isTrue();
        assertThat(detail.capabilities().submitSolution()).isFalse();
        assertThat(detail.currentSolution()).isNull();
        var ordered = inOrder(fixture.mapper, identities);
        ordered.verify(fixture.mapper).findVisible(11, 10);
        ordered.verify(identities).labels(anyCollection());
        verify(fixture.mapper, never()).history(anyLong(), anyLong(), any(), anyInt());
        fixture.actorId = 9;
        assertStatus(404, () -> service.detail("11", Map.of()));
    }

    @Test
    void historyIncludesCreationAtZeroAndUsesPositiveBoundCursorWithoutHiddenTotals() {
        fixture.actorId = 10;
        var created = event(0, "GAP_CREATED", 10, null, null);
        var assigned = event(1, "GAP_ASSIGNED", 7, 8L, null);
        var entry = new KnowledgeGapRows.Entry(UUID.randomUUID(), 11, 2, "NOTE", 10, "补充说明", null, null, Instant.EPOCH);
        var note = event(2, "NOTE_ADDED", 10, 8L, entry.entryId());
        fixture.entries.put(entry.entryId(), entry);
        when(fixture.mapper.history(11, 10, null, 2)).thenReturn(List.of(created, assigned));
        var first = service.history("11", Map.of("limit", List.of("1")));
        assertThat(first.items().getFirst().resultVersion()).isZero();
        assertThat(first.hasMore()).isTrue();
        when(fixture.mapper.history(11, 10, 0L, 2)).thenReturn(List.of(note));
        var last = service.history("11", Map.of("limit", List.of("1"), "cursor", List.of(first.nextCursor())));
        assertThat(last.items().getFirst().entry().text()).isEqualTo("补充说明");
        assertThat(last.hasMore()).isFalse();
        assertThat(last.nextCursor()).isNull();
        verifyNoInteractions(fixture.documents);
    }

    @Test
    void historyAndCurrentSolutionBothUseIndependentReferenceProjection() {
        fixture.actorId = 7;
        UUID id = UUID.randomUUID();
        var entry = new KnowledgeGapRows.Entry(id, 11, 2, "SOLUTION", 8, "人工分享方案", 21L, 31L, Instant.EPOCH);
        fixture.entries.put(id, entry);
        fixture.gap = new KnowledgeGapRows.Gap(11, 10, fixture.gap.category(), fixture.gap.source(), fixture.gap.description(),
                fixture.gap.consentVersion(), KnowledgeGapState.AWAITING_CONFIRMATION, 2, 8L, 1, id, null, Instant.EPOCH, Instant.EPOCH);
        when(fixture.mapper.history(11, 7, null, 21)).thenReturn(List.of(event(2, "GAP_SOLUTION_SUBMITTED", 8, 8L, id)));
        var detail = service.detail("11", Map.of());
        var history = service.history("11", Map.of());
        assertThat(detail.currentSolution().referenceState()).isEqualTo(KnowledgeGapViews.ReferenceState.UNAVAILABLE);
        assertThat(history.items().getFirst().entry().referenceState()).isEqualTo(KnowledgeGapViews.ReferenceState.UNAVAILABLE);
        assertThat(JsonMapper.builder().findAndAddModules().build().valueToTree(detail).toString()).doesNotContain("documentId", "versionId", "授权标题");
        doThrow(ApiException.unavailable("permission failure")).when(fixture.documents).reference(any(), anyLong(), anyLong());
        assertStatus(503, () -> service.history("11", Map.of()));
    }

    @Test
    void historyRejectsCrossGapCursorAndNeverReadsAfterLostParticipation() {
        String cursor = QueryCursor.encode("knowledge-gap-history", QueryParameters.filters("actor", 8, "gapId", 12), 1);
        assertThatThrownBy(() -> service.history("11", Map.of("cursor", List.of(cursor)))).isInstanceOf(QueryException.class);
        fixture.actorId = 9;
        assertStatus(404, () -> service.history("11", Map.of()));
        verify(fixture.mapper, never()).history(anyLong(), anyLong(), any(), anyInt());
        verifyNoInteractions(identities);
    }

    @Test
    void maximumPageBatchesOnlyVisibleItemsWithinTwoHundredLabelIds() {
        fixture.actorId = 7;
        var rows = new ArrayList<KnowledgeGapRows.Gap>();
        for (int index = 0; index < 101; index++) {
            var gap = gap(5000 - index);
            rows.add(new KnowledgeGapRows.Gap(gap.id(), 1000 + index, gap.category(), gap.source(), gap.description(), gap.consentVersion(),
                    gap.state(), gap.version(), 2000L + index, gap.assignmentEpoch(), null, null, gap.createdAt(), gap.updatedAt()));
        }
        when(fixture.mapper.list(7, "queue", null, null, 101)).thenReturn(rows);
        var page = service.queue(Map.of("limit", List.of("100")));
        assertThat(page.items()).hasSize(100);
        assertThat(page.hasMore()).isTrue();
        var ids = ArgumentCaptor.forClass(Collection.class);
        verify(identities).labels(ids.capture());
        assertThat(ids.getValue()).hasSize(200);
    }

    @Test
    void actualSqlFiltersCurrentScopeBeforeFixedOrderAndLimit() throws Exception {
        var configuration = MapperRowStub.configuration("/mapper/KnowledgeGapMapper.xml");
        for (String view : List.of("mine", "assigned", "queue")) {
            var parameters = new HashMap<String, Object>();
            parameters.put("actor", 7L);
            parameters.put("view", view);
            parameters.put("status", KnowledgeGapState.CLOSED);
            parameters.put("beforeId", 20L);
            parameters.put("limit", 21);
            String sql = configuration.getMappedStatement(KnowledgeGapMapper.class.getName() + ".list")
                    .getBoundSql(parameters).getSql().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            assertThat(sql).contains("ga_u.enabled", "not ga_u.must_change_password", "g.state = ?", "g.id < ?", "order by g.id desc limit ?");
            assertThat(sql.indexOf("where")).isLessThan(sql.indexOf("order by"));
        }
    }

    @Test
    void emptyHistoryIsDistinctFromFailureAndUnorderedRowsFailClosed() {
        when(fixture.mapper.history(11, 8, null, 21)).thenReturn(List.of());
        var empty = service.history("11", Map.of());
        assertThat(empty.items()).isEmpty();
        assertThat(empty.hasMore()).isFalse();
        doReturn(null).when(fixture.mapper).history(11, 8, null, 21);
        assertStatus(503, () -> service.history("11", Map.of()));
        doReturn(List.of(event(1, "GAP_ASSIGNED", 7, 8L, null), event(0, "GAP_CREATED", 10, null, null)))
                .when(fixture.mapper).history(11, 8, null, 21);
        assertStatus(503, () -> service.history("11", Map.of()));
        verifyNoInteractions(identities);
    }

    private KnowledgeGapRows.Gap gap(long id) {
        return new KnowledgeGapRows.Gap(id, 10, fixture.gap.category(), fixture.gap.source(), fixture.gap.description(),
                fixture.gap.consentVersion(), KnowledgeGapState.IN_PROGRESS, 1, 8L, 1, null, null, Instant.EPOCH, Instant.EPOCH);
    }

    private static KnowledgeGapRows.Event event(long version, String kind, long actor, Long assignee, UUID entryId) {
        return new KnowledgeGapRows.Event(UUID.randomUUID(), 11, kind, version, actor, assignee,
                assignee == null ? 0 : 1, entryId, kind.equals("GAP_SOLUTION_SUBMITTED") ? entryId : null, Instant.EPOCH);
    }

    private static void assertStatus(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(status));
    }
}
