package com.zhishiku.kb.app.notification;

import com.zhishiku.kb.app.config.UuidTypeHandler;
import com.zhishiku.kb.app.notification.mapper.UserNotificationMapper;
import com.zhishiku.kb.app.notification.model.GapNotificationFact;
import com.zhishiku.kb.app.notification.model.NotificationRow;
import com.zhishiku.kb.app.notification.model.NotificationTarget;
import com.zhishiku.kb.app.notification.model.NotificationType;
import com.zhishiku.kb.app.support.MapperRowStub;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.NotificationContract;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.doThrow;

/** 混合集合与真实 MyBatis 结构/映射的离线验证，不执行数据库查询。 */
class GapNotificationVisibilityTest {
    private final GapInboxHarness harness = new GapInboxHarness();

    @Test
    void sparseMixedPageAndUnreadUseTheSameCurrentlyVisibleUnion() {
        harness.rows.addAll(List.of(GapInboxHarness.solution(100, false), GapInboxHarness.comment(97),
                GapInboxHarness.solution(60, true), GapInboxHarness.comment(12)));
        var page = harness.service.list(Map.of("limit", List.of("2")), NotificationContract.KNOWLEDGE_GAPS_V1);
        assertThat(page.items()).extracting("type").containsExactly(NotificationType.COMMENT_REPLIED, NotificationType.GAP_SOLUTION_SUBMITTED);
        assertThat(page.hasMore()).isTrue();
        assertThat(harness.service.unreadCount(Map.of(), NotificationContract.KNOWLEDGE_GAPS_V1).count()).isEqualTo("3");
        var last = harness.service.list(Map.of("limit", List.of("2"), "cursor", List.of(page.nextCursor())), NotificationContract.KNOWLEDGE_GAPS_V1);
        assertThat(last.items()).hasSize(1);
        assertThat(last.hasMore()).isFalse();
        assertThat(last.items().getFirst().target().versionId()).isEqualTo(21);
        assertThat(harness.listTypes.getFirst()).isEqualTo(harness.countTypes.getFirst());
    }

    @Test
    void noDocumentViewDoesNotHideCurrentlyAuthorizedGapNotification() {
        harness.documentReadable = false;
        harness.rows.addAll(List.of(GapInboxHarness.comment(97), GapInboxHarness.solution(60, true)));
        var page = harness.service.list(Map.of(), NotificationContract.KNOWLEDGE_GAPS_V1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().target()).isEqualTo(NotificationTarget.knowledgeGap(11));
        assertThat(harness.service.unreadCount(Map.of(), NotificationContract.KNOWLEDGE_GAPS_V1).count()).isEqualTo("1");
    }

    @Test
    void oldSolutionAndLostParticipationAreHiddenFromListAndCountTogether() {
        var row = GapInboxHarness.solution(60, true);
        harness.rows.add(row);
        assertThat(harness.service.unreadCount(Map.of(), NotificationContract.KNOWLEDGE_GAPS_V1).count()).isEqualTo("1");
        var fact = row.gap();
        var changed = new GapNotificationFact(fact.gapId(), fact.eventId(), fact.eventKind(), fact.state(), 4L,
                fact.reporterUserId(), fact.assigneeUserId(), 1L, 1L, null, UUID.randomUUID(), fact.eventSolutionId(), 2L, true, false);
        harness.rows.set(0, GapInboxHarness.withGap(row, changed));
        assertThat(harness.service.list(Map.of(), NotificationContract.KNOWLEDGE_GAPS_V1).items()).isEmpty();
        assertThat(harness.service.unreadCount(Map.of(), NotificationContract.KNOWLEDGE_GAPS_V1).count()).isEqualTo("0");
        harness.rows.set(0, GapInboxHarness.solution(60, false));
        assertThat(harness.service.list(Map.of(), NotificationContract.KNOWLEDGE_GAPS_V1).items()).isEmpty();
    }

    @Test
    void noteDoesNotInvalidateAssignmentButEpochAndOwnerChangeDo() {
        harness.userId = 8;
        UUID id = UUID.randomUUID();
        var fact = new GapNotificationFact(11L, id, "GAP_ASSIGNED", "IN_PROGRESS", 5L, 10L, 8L, 1L, 1L,
                null, null, null, 1L, true, true);
        var row = NotificationRow.knowledgeGap(60, UUID.randomUUID(), 8, "GAP_ASSIGNED", Instant.EPOCH, null, fact);
        harness.rows.add(row);
        assertThat(harness.service.list(Map.of(), NotificationContract.KNOWLEDGE_GAPS_V1).items()).hasSize(1);
        var next = new GapNotificationFact(11L, id, "GAP_ASSIGNED", "IN_PROGRESS", 6L, 10L, 9L, 2L, 1L,
                null, null, null, 1L, false, false);
        harness.rows.set(0, GapInboxHarness.withGap(row, next));
        assertThat(harness.service.unreadCount(Map.of(), NotificationContract.KNOWLEDGE_GAPS_V1).count()).isEqualTo("0");
    }

    @Test
    void dependencyFailureIsNotAFalseZeroOrEmptyMixedPage() {
        doThrow(new IllegalStateException("secret sql")).when(harness.mapper)
                .list(anyLong(), anyList(), anyList(), anyList(), anyShort(), any(), anyInt());
        doThrow(new IllegalStateException("secret acl")).when(harness.mapper)
                .countUnread(anyLong(), anyList(), anyList(), anyList(), anyShort());
        for (Runnable action : List.<Runnable>of(() -> harness.service.list(Map.of(), NotificationContract.KNOWLEDGE_GAPS_V1),
                () -> harness.service.unreadCount(Map.of(), NotificationContract.KNOWLEDGE_GAPS_V1))) {
            assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.httpStatus()).isEqualTo(503))
                    .hasMessage("通知服务暂不可用");
        }
    }

    @Test
    void listAndCountIncludeTheExactSameGapAccessAndWorkRulesBeforePagination() throws Exception {
        var configuration = GapInboxHarness.configuration();
        String prefix = "com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper.";
        // 已构建语句使用展开后的原片段；另建配置时替换后再解析通知 Mapper。
        var probed = MapperRowStub.configuration("/mapper/DocumentMapper.xml");
        probed.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
        for (String resource : List.of("/mapper/DocumentReviewEligibilityMapper.xml", "/mapper/DocumentCommentMapper.xml", "/mapper/KnowledgeGapMapper.xml")) {
            try (var input = getClass().getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, probed, resource, probed.getSqlFragments()).parse();
            }
        }
        probed.getSqlFragments().replace(prefix + "visible", new XPathParser("<sql id='visible'>#{gapAccessProbe} = 1</sql>").evalNode("/sql"));
        probed.getSqlFragments().replace(prefix + "notificationWorkCurrent", new XPathParser("<sql id='notificationWorkCurrent'>#{gapWorkProbe} = 1</sql>").evalNode("/sql"));
        try (var input = getClass().getResourceAsStream("/mapper/UserNotificationMapper.xml")) {
            new XMLMapperBuilder(input, probed, "/mapper/UserNotificationMapper.xml", probed.getSqlFragments()).parse();
        }
        for (String method : List.of("list", "countUnread")) {
            var bound = probed.getMappedStatement(UserNotificationMapper.class.getName() + "." + method).getBoundSql(parameters());
            assertThat(bound.getParameterMappings()).extracting("property").contains("gapAccessProbe", "gapWorkProbe");
            String sql = configuration.getMappedStatement(UserNotificationMapper.class.getName() + "." + method).getBoundSql(parameters())
                    .getSql().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            assertThat(sql).contains("union all", "f.gap_visible and f.gap_work_current", "g.assignment_epoch = e.assignment_epoch");
            assertThat(sql).doesNotContain("kb_knowledge_gap_entry", "body", "description", "title");
            if (method.equals("list")) {
                assertThat(sql.indexOf("union all")).isLessThan(sql.indexOf("order by f.id desc limit"));
            }
        }
    }

    @Test
    void actualNestedMappingKeepsGapIdentityExactAndDocumentIdentityNull() throws Exception {
        var configuration = GapInboxHarness.configuration();
        UUID eventId = UUID.randomUUID();
        UUID solution = UUID.randomUUID();
        var values = new LinkedHashMap<String, Object>();
        values.put("id", 9007199254740993L);
        values.put("notification_id", UUID.randomUUID());
        values.put("recipient_user_id", 10L);
        values.put("type", "GAP_SOLUTION_SUBMITTED");
        values.put("created_at", Instant.EPOCH);
        values.put("read_at", null);
        values.put("document_id", null);
        values.put("version_id", null);
        for (String field : List.of("review_id", "round_no", "round_state", "round_submitted_by", "uploader_user_id",
                "publication_policy", "publication_evidence", "comment_id")) {
            values.put(field, null);
        }
        for (String field : List.of("live", "cleared", "viewable", "actionable", "round_in_space", "discussable", "reply_readable")) {
            values.put(field, false);
        }
        values.put("gap_id", 9007199254740995L);
        values.put("gap_event_id", eventId);
        values.put("gap_event_kind", "GAP_SOLUTION_SUBMITTED");
        values.put("gap_state", "AWAITING_CONFIRMATION");
        values.put("gap_version", 2L);
        values.put("gap_reporter_user_id", 10L);
        values.put("gap_assignee_user_id", 8L);
        values.put("gap_assignment_epoch", 1L);
        values.put("gap_event_assignment_epoch", 1L);
        values.put("gap_queue_event_id", null);
        values.put("gap_pending_solution_id", solution);
        values.put("gap_event_solution_id", solution);
        values.put("gap_event_result_version", 2L);
        values.put("gap_visible", true);
        values.put("gap_work_current", true);
        var row = MapperRowStub.readOne(configuration, UserNotificationMapper.class.getName() + ".list", parameters(), values, NotificationRow.class);
        assertThat(row.documentId()).isNull();
        assertThat(row.versionId()).isNull();
        assertThat(row.reviewId()).isNull();
        assertThat(row.commentId()).isNull();
        assertThat(row.publicationEvidence()).isNull();
        assertThat(row.gap().gapId()).isEqualTo(9007199254740995L);
        assertThat(row.gap().currentWork(10)).isTrue();
    }

    private static Map<String, Object> parameters() {
        var parameters = new HashMap<String, Object>();
        parameters.put("userId", 10L);
        parameters.put("types", List.of("COMMENT_REPLIED", "GAP_SOLUTION_SUBMITTED"));
        parameters.put("spaceIds", List.of(3L));
        parameters.put("principalKeys", List.of("1:10"));
        parameters.put("clearance", (short) 2);
        parameters.put("limit", 3);
        parameters.put("beforeId", null);
        return parameters;
    }
}
