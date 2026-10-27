package com.zhishiku.kb.app.notification;

import com.zhishiku.kb.app.config.UuidTypeHandler;
import com.zhishiku.kb.app.notification.mapper.NotificationDerivationMapper;
import com.zhishiku.kb.app.notification.mapper.UserNotificationMapper;
import com.zhishiku.kb.app.notification.model.ClaimedEvent;
import com.zhishiku.kb.app.notification.model.NotificationBacklog;
import com.zhishiku.kb.app.notification.model.NotificationRow;
import com.zhishiku.kb.app.notification.model.NotificationViews;
import com.zhishiku.kb.app.support.MapperRowStub;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.builder.xml.XMLMapperEntityResolver;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 MyBatis 映射与替代 JDBC 结果集；不连接数据库，不断言 SQL 文本。 */
class NotificationMappingTest {
    private static final String USER = UserNotificationMapper.class.getName();
    private static final String DERIVATION = NotificationDerivationMapper.class.getName();
    private static final String PENDING_REVIEWER = "com.zhishiku.kb.app.document.mapper.DocumentReviewEligibilityMapper.pendingReviewer";
    private static Configuration configuration;

    @BeforeAll
    static void load() throws Exception {
        configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
        for (String resource : List.of("/mapper/DocumentMapper.xml", "/mapper/DocumentReviewEligibilityMapper.xml",
                "/mapper/DocumentCommentMapper.xml", "/mapper/UserNotificationMapper.xml",
                "/mapper/NotificationDerivationMapper.xml")) {
            parse(configuration, resource);
        }
    }

    @Test
    void listRowMapsExactIdsNullableFactsAndUuidTargets() throws Exception {
        UUID notification = UUID.randomUUID(), review = UUID.randomUUID();
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 9007199254740993L);
        row.put("notification_id", notification);
        row.put("recipient_user_id", 7L);
        row.put("type", "REVIEW_SUBMITTED");
        row.put("created_at", Instant.EPOCH);
        row.put("read_at", null);
        row.put("document_id", 9007199254740995L);
        row.put("version_id", 9007199254740997L);
        row.put("review_id", review);
        row.put("round_no", 2);
        row.put("live", true);
        row.put("cleared", true);
        row.put("viewable", false);
        row.put("actionable", true);
        row.put("round_state", "IN_REVIEW");
        row.put("round_submitted_by", 8L);
        row.put("round_in_space", true);
        row.put("uploader_user_id", null);
        row.put("publication_policy", "REVIEW_REQUIRED");
        row.put("publication_evidence", "NONE");
        row.put("comment_id", null);
        row.put("discussable", false);
        row.put("reply_readable", false);
        var mapped = MapperRowStub.readOne(configuration, USER + ".list", listParameters(List.of("1:7")), row,
                NotificationRow.class);
        assertThat(mapped.id()).isEqualTo(9007199254740993L);
        assertThat(mapped.notificationId()).isEqualTo(notification);
        assertThat(mapped.documentId()).isEqualTo(9007199254740995L);
        assertThat(mapped.versionId()).isEqualTo(9007199254740997L);
        assertThat(mapped.reviewId()).isEqualTo(review);
        assertThat(mapped.readAt()).isNull();
        assertThat(mapped.uploaderUserId()).isNull();
        assertThat(mapped.viewable()).isFalse();
        assertThat(mapped.actionable()).isTrue();
        assertThat(mapped.roundSubmittedBy()).isEqualTo(8L);
    }

    @Test
    void derivationAndReceiptProjectionsMapEveryField() throws Exception {
        UUID event = UUID.randomUUID();
        var claimed = MapperRowStub.readOne(configuration, DERIVATION + ".claim",
                Map.of("owner", "app-1", "limit", 50, "leaseSeconds", 60),
                new LinkedHashMap<>(Map.of("event_id", event, "kind", "COMMENT_REPLIED", "claim_token", 9007199254740993L,
                        "recipient_cursor", 100L, "attempts", 3, "takeover", true)), ClaimedEvent.class);
        assertThat(claimed).isEqualTo(new ClaimedEvent(event, "COMMENT_REPLIED", 9007199254740993L, 100, 3, true));
        var receipt = MapperRowStub.readOne(configuration, USER + ".markRead",
                Map.of("userId", 7L, "notificationId", event),
                new LinkedHashMap<>(Map.of("notification_id", event, "read_at", Instant.EPOCH)), NotificationViews.ReadReceipt.class);
        assertThat(receipt).isEqualTo(new NotificationViews.ReadReceipt(event, Instant.EPOCH));
        var backlog = MapperRowStub.readOne(configuration, DERIVATION + ".backlog", Map.of(),
                new LinkedHashMap<>(Map.of("pending", 3L, "waiting_source", 2L, "oldest_age_seconds", 120L)),
                NotificationBacklog.class);
        assertThat(backlog).isEqualTo(new NotificationBacklog(3, 2, 120));
    }

    @Test
    void listAndCountBindTheSameOwnerScopeAndVisibilityParameters() {
        var list = names(USER + ".list", listParameters(List.of("1:7", "2:3")));
        var count = names(USER + ".countUnread", countParameters(List.of("1:7", "2:3")));
        assertThat(list).contains("userId", "clearance", "limit", "beforeId", "aclPermission", "principalKeys", "spaceIds", "types");
        var shared = new TreeSet<>(list);
        shared.removeAll(Set.of("limit", "beforeId"));
        assertThat(count).isEqualTo(shared);
        var bound = configuration.getMappedStatement(USER + ".countUnread").getBoundSql(countParameters(List.of("1:7")));
        assertThat(bound.getAdditionalParameter("aclPermission")).isEqualTo("view");
        assertThat(values(USER + ".countUnread", countParameters(List.of("1:7", "2:3")), "principalKeys"))
                .contains("1:7", "2:3");
    }

    @Test
    void pendingReminderAndReviewWorkbenchIncludeTheSameReviewerFragment() throws Exception {
        // 用带唯一参数的探针替换共享片段：两个语句都绑定该参数，即证明引用的是同一片段，不比较 SQL 文本。
        var probed = new Configuration();
        probed.setMapUnderscoreToCamelCase(true);
        probed.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
        parse(probed, "/mapper/DocumentReviewEligibilityMapper.xml");
        var probe = new XPathParser("<sql id=\"pendingReviewer\">#{pendingReviewerProbe} = 1</sql>").evalNode("/sql");
        assertThat(probed.getSqlFragments().replace(PENDING_REVIEWER, probe)).as(PENDING_REVIEWER).isNotNull();
        for (String resource : List.of("/mapper/DocumentMapper.xml", "/mapper/DocumentReviewQueryMapper.xml",
                "/mapper/DocumentCommentMapper.xml", "/mapper/UserNotificationMapper.xml")) {
            parse(probed, resource);
        }
        var workbench = probed.getMappedStatement("com.zhishiku.kb.app.document.mapper.DocumentReviewQueryMapper.pending")
                .getBoundSql(new HashMap<>(Map.of("actor", 7L, "limit", 21)));
        var reminder = probed.getMappedStatement(USER + ".countUnread").getBoundSql(countParameters(List.of("1:7")));
        for (var bound : List.of(workbench, reminder)) {
            assertThat(bound.getParameterMappings()).extracting(ParameterMapping::getProperty).contains("pendingReviewerProbe");
        }
    }

    @Test
    void commentReplyRowMapsExternalCommentIdAndIndependentDiscussionFacts() throws Exception {
        UUID notification = UUID.randomUUID(), comment = UUID.randomUUID();
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 5L);
        row.put("notification_id", notification);
        row.put("recipient_user_id", 7L);
        row.put("type", "COMMENT_REPLIED");
        row.put("created_at", Instant.EPOCH);
        row.put("read_at", null);
        row.put("document_id", 10L);
        row.put("version_id", 21L);
        row.put("review_id", null);
        row.put("round_no", null);
        row.put("live", true);
        row.put("cleared", true);
        row.put("viewable", true);
        row.put("actionable", false);
        row.put("round_state", null);
        row.put("round_submitted_by", null);
        row.put("round_in_space", false);
        row.put("uploader_user_id", 9L);
        row.put("publication_policy", "AUTO");
        row.put("publication_evidence", "PUBLISHED");
        row.put("comment_id", comment);
        row.put("discussable", true);
        row.put("reply_readable", false);
        var mapped = MapperRowStub.readOne(configuration, USER + ".list", listParameters(List.of("1:7")), row,
                NotificationRow.class);
        assertThat(mapped.commentId()).isEqualTo(comment);
        assertThat(mapped.reviewId()).isNull();
        assertThat(mapped.roundNo()).isNull();
        assertThat(mapped.discussable()).isTrue();
        assertThat(mapped.replyReadable()).isFalse();
    }

    @Test
    void replyReminderIncludesTheCommentModuleFragmentAndTheSharedEvidenceRule() throws Exception {
        // 以带唯一参数的探针替换共享片段：列表与计数都绑定该参数，即证明引用同一片段，不比较 SQL 文本。
        var probed = new Configuration();
        probed.setMapUnderscoreToCamelCase(true);
        probed.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
        for (String resource : List.of("/mapper/DocumentMapper.xml", "/mapper/DocumentReviewEligibilityMapper.xml",
                "/mapper/DocumentCommentMapper.xml")) {
            parse(probed, resource);
        }
        String reply = "com.zhishiku.kb.app.comment.mapper.DocumentCommentMapper.liveReply";
        String evidence = "com.zhishiku.kb.app.document.mapper.DocumentMapper.commentTargetEvidence";
        assertThat(probed.getSqlFragments().replace(reply,
                new XPathParser("<sql id=\"liveReply\">#{liveReplyProbe} = 1</sql>").evalNode("/sql"))).isNotNull();
        assertThat(probed.getSqlFragments().replace(evidence,
                new XPathParser("<sql id=\"commentTargetEvidence\">#{evidenceProbe} = 1</sql>").evalNode("/sql"))).isNotNull();
        parse(probed, "/mapper/UserNotificationMapper.xml");
        for (String id : List.of(USER + ".list", USER + ".countUnread")) {
            var parameters = id.endsWith("list") ? listParameters(List.of("1:7")) : countParameters(List.of("1:7"));
            assertThat(probed.getMappedStatement(id).getBoundSql(parameters).getParameterMappings())
                    .extracting(ParameterMapping::getProperty).contains("liveReplyProbe", "evidenceProbe");
        }
    }

    @Test
    void notificationSqlIncludesOnlyTheRuleFragmentsSourceModulesPublish() throws Exception {
        // 通知只以 include 复用源模块公开的授权片段；引用其他片段等同于直接读取源模块的表。
        Set<String> allowed = Set.of("facts", "visible",
                "com.zhishiku.kb.app.document.mapper.DocumentMapper.documentAccessPredicate",
                "com.zhishiku.kb.app.document.mapper.DocumentMapper.versionInteractivePredicate",
                "com.zhishiku.kb.app.document.mapper.DocumentMapper.currentViewGrant",
                "com.zhishiku.kb.app.document.mapper.DocumentMapper.commentTargetEvidence",
                PENDING_REVIEWER,
                "com.zhishiku.kb.app.comment.mapper.DocumentCommentMapper.liveReply");
        var all = new TreeSet<String>();
        for (String resource : List.of("/mapper/UserNotificationMapper.xml", "/mapper/NotificationDerivationMapper.xml")) {
            try (var input = NotificationMappingTest.class.getResourceAsStream(resource)) {
                assertThat(input).as(resource).isNotNull();
                var parser = new XPathParser(input, true, null, new XMLMapperEntityResolver());
                var referenced = parser.evalNodes("//include").stream().map(node -> node.getStringAttribute("refid"))
                        .collect(Collectors.toCollection(TreeSet::new));
                assertThat(allowed).as(resource).containsAll(referenced);
                all.addAll(referenced);
            }
        }
        // 确认确实解析到了 include，避免空集合使白名单断言恒真。
        assertThat(all).contains(PENDING_REVIEWER, "com.zhishiku.kb.app.comment.mapper.DocumentCommentMapper.liveReply");
    }

    @Test
    void emptyScopeOrTypesStillBuildAFilteredStatementWithoutPrincipalOrTypeValues() {
        var parameters = countParameters(List.of());
        parameters.put("spaceIds", List.of());
        parameters.put("types", List.of());
        var bound = names(USER + ".countUnread", parameters);
        assertThat(bound).contains("userId", "clearance").doesNotContain("principalKeys", "spaceIds", "types");
    }

    @Test
    void deliverBindsOnlyTheRequestedBatchAndClaimUsesDatabaseLeaseSeconds() {
        UUID event = UUID.randomUUID();
        var empty = names(DERIVATION + ".deliver", new HashMap<>(Map.of("eventId", event, "type", "REVIEW_SUBMITTED",
                "recipients", List.of())));
        assertThat(empty).containsExactlyInAnyOrder("eventId", "type");
        var batch = configuration.getMappedStatement(DERIVATION + ".deliver").getBoundSql(new HashMap<>(Map.of(
                "eventId", event, "type", "REVIEW_SUBMITTED", "recipients", List.of(3L, 5L))));
        assertThat(batch.getParameterMappings()).hasSize(4);
        assertThat(names(DERIVATION + ".claim", Map.of("owner", "app-1", "limit", 50, "leaseSeconds", 60)))
                .containsExactlyInAnyOrder("owner", "leaseSeconds", "limit");
        assertThat(names(DERIVATION + ".advance", Map.of("eventId", event, "owner", "o", "token", 1L, "cursor", 2L,
                "leaseSeconds", 60))).containsExactlyInAnyOrder("eventId", "owner", "token", "cursor", "leaseSeconds");
    }

    @Test
    void readProjectionsNeverMapTitleBodyReasonOrStorageColumns() {
        Set<String> forbidden = Set.of("title", "file_name", "reason", "storage_key", "content_hash", "body", "token_value",
                "label", "display_name", "author_user_id");
        for (String id : List.of(USER + ".list", USER + ".markRead", DERIVATION + ".claim", DERIVATION + ".backlog")) {
            for (var map : configuration.getMappedStatement(id).getResultMaps()) {
                assertThat(map.getMappedColumns().stream().map(String::toLowerCase)).as(id).noneMatch(forbidden::contains);
            }
        }
    }

    private static void parse(Configuration target, String resource) throws Exception {
        try (var input = NotificationMappingTest.class.getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            new XMLMapperBuilder(input, target, resource, target.getSqlFragments()).parse();
        }
    }

    private static Map<String, Object> listParameters(List<String> keys) {
        var parameters = countParameters(keys);
        parameters.put("beforeId", 99L);
        parameters.put("limit", 21);
        return parameters;
    }

    private static Map<String, Object> countParameters(List<String> keys) {
        var parameters = new HashMap<String, Object>();
        parameters.put("userId", 7L);
        parameters.put("types", List.of("REVIEW_SUBMITTED", "REVIEW_APPROVED", "REVIEW_REJECTED"));
        parameters.put("spaceIds", List.of(3L));
        parameters.put("principalKeys", keys);
        parameters.put("clearance", (short) 2);
        return parameters;
    }

    private static Set<String> names(String id, Map<String, Object> parameters) {
        return configuration.getMappedStatement(id).getBoundSql(parameters).getParameterMappings().stream()
                .map(ParameterMapping::getProperty).map(NotificationMappingTest::base)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** 某集合经 foreach 实际绑定的值。 */
    private static List<Object> values(String id, Map<String, Object> parameters, String collection) {
        var bound = configuration.getMappedStatement(id).getBoundSql(parameters);
        return bound.getParameterMappings().stream().map(ParameterMapping::getProperty)
                .filter(name -> name.startsWith("__frch_") && base(name).equals(collection))
                .filter(bound::hasAdditionalParameter).map(bound::getAdditionalParameter).toList();
    }

    /** 将 foreach 生成的参数名还原为集合名，供列表与计数比较。 */
    private static String base(String property) {
        if (!property.startsWith("__frch_")) {
            return property;
        }
        String item = property.substring("__frch_".length()).replaceAll("_\\d+$", "");
        return switch (item) {
            case "k", "principal" -> "principalKeys";
            case "space" -> "spaceIds";
            case "t" -> "types";
            case "id" -> "recipients";
            default -> item;
        };
    }
}
