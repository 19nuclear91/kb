package com.zhishiku.kb.app.knowledgegap;

import com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.support.MapperRowStub;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** 源事件固定意图的离线业务/事务/SQL 检查，不代表通知已派生或送达。 */
class KnowledgeGapEventTest {
    @ParameterizedTest(name = "{0} 固定收件人")
    @MethodSource("recipientRules")
    void eightKindsFixRecipientsAndExcludeActor(String kind, long actor, Long assignee, List<Long> expected) {
        var fixture = new KnowledgeGapTransactionFixture();
        var pool = new ArrayList<>(List.of(7L, 7L, 8L, 10L));
        receptionPool(fixture, pool);
        var event = event(kind, actor, assignee);
        transact(fixture, () -> fixture.events.record(event, 10));
        assertThat(fixture.memory.events).containsExactly(event);
        assertThat(fixture.recipients.get(event.eventId())).containsExactlyElementsOf(expected);
        assertThat(fixture.recipients.get(event.eventId())).doesNotContain(actor);
        assertThat(fixture.transactions.commits).isEqualTo(1);
    }

    @Test
    void laterPermissionDoesNotBackfillOldIntentAndOverlappingRolesAreDeduplicated() {
        var fixture = new KnowledgeGapTransactionFixture();
        var pool = new ArrayList<>(List.of(7L, 7L, 10L));
        receptionPool(fixture, pool);
        var created = event("GAP_CREATED", 10, null);
        transact(fixture, () -> fixture.events.record(created, 10));
        assertThat(fixture.recipients.get(created.eventId())).containsExactly(7L);
        pool.add(9L);
        assertThat(fixture.recipients.get(created.eventId())).containsExactly(7L);
        var returned = event("GAP_RETURNED", 8, 8L);
        transact(fixture, () -> fixture.events.record(returned, 10));
        assertThat(fixture.recipients.get(returned.eventId())).containsExactly(7L, 10L, 9L);
    }

    @Test
    void closedWithOverlappingParticipantsHasOneRecipientAndSelfOnlyIsEmpty() {
        var fixture = new KnowledgeGapTransactionFixture();
        var overlap = event("GAP_CLOSED", 7, 10L);
        transact(fixture, () -> fixture.events.record(overlap, 10));
        assertThat(fixture.recipients.get(overlap.eventId())).containsExactly(10L);
        var self = event("GAP_ASSIGNED", 8, 8L);
        fixture.calls.clear();
        transact(fixture, () -> fixture.events.record(self, 10));
        assertThat(fixture.recipients).doesNotContainKey(self.eventId());
        assertThat(fixture.calls).doesNotContain("insertRecipients", "insertReceptionRecipients");
    }

    @Test
    void sharedNoteRetainsFactButNeverCreatesNotificationIntent() {
        var fixture = new KnowledgeGapTransactionFixture();
        var receipt = fixture.assignments.note(11, new KnowledgeGapInputs.Message(1, "分享的说明正文"), "note");
        assertThat(fixture.memory.events).hasSize(1);
        assertThat(fixture.memory.events.getFirst().kind()).isEqualTo("NOTE_ADDED");
        assertThat(fixture.memory.entries.values()).anyMatch(entry -> entry.body().equals("分享的说明正文"));
        assertThat(fixture.recipients).isEmpty();
        assertThat(fixture.calls).doesNotContain("insertRecipients", "insertReceptionRecipients");
        assertThat(receipt.toString()).doesNotContain("分享的说明正文");
    }

    @Test
    void eventOrIntentFailureRollsBackStateTextAndCommandWithoutClaimingDelivery() {
        for (String stage : List.of("insertEvent", "insertRecipients")) {
            var fixture = new KnowledgeGapTransactionFixture();
            var before = fixture.snapshot();
            fixture.failureAt = stage;
            assertThatThrownBy(() -> fixture.solutions.submit(11, new KnowledgeGapInputs.Solution(1, "方案正文", null, null), "submit"))
                    .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.httpStatus()).isEqualTo(503));
            assertThat(fixture.snapshot()).isEqualTo(before);
            assertThat(fixture.transactions.rollbacks).isEqualTo(1);
        }
    }

    @Test
    void actualSqlStoresOnlyIdentityAndSelectsEligibleReadPoolWithoutJavaEnumeration() throws Exception {
        var configuration = MapperRowStub.configuration("/mapper/KnowledgeGapMapper.xml");
        String namespace = KnowledgeGapMapper.class.getName() + ".";
        var event = event("GAP_CREATED", 10, null);
        var binding = configuration.getMappedStatement(namespace + "insertEvent").getBoundSql(Map.of("event", event));
        assertThat(binding.getParameterMappings()).extracting("property").doesNotContain("body", "text", "description");
        String pool = configuration.getMappedStatement(namespace + "insertReceptionRecipients").getBoundSql(Map.of("eventId", event.eventId()))
                .getSql().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        assertThat(pool).contains("u.enabled and d.enabled and not u.must_change_password", "u.id <> e.actor_user_id",
                "rp.permission = 'knowledge:gap:read'", "exists (select 1", "on conflict (event_id, user_id) do nothing");
        assertThat(pool).doesNotContain("body", "description", "label", "role_admin");
        String fixed = configuration.getMappedStatement(namespace + "insertRecipients")
                .getBoundSql(Map.of("eventId", event.eventId(), "userIds", List.of(8L, 10L)))
                .getSql().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        assertThat(fixed).contains("u.id <> e.actor_user_id", "u.id in", "on conflict (event_id, user_id) do nothing");
    }

    private static void receptionPool(KnowledgeGapTransactionFixture fixture, List<Long> pool) {
        doAnswer(call -> {
            UUID id = call.getArgument(0);
            var event = fixture.memory.events.stream().filter(row -> row.eventId().equals(id)).findFirst().orElseThrow();
            var users = new LinkedHashSet<>(pool);
            users.remove(event.actorUserId());
            fixture.recipients.put(id, List.copyOf(users));
            return users.size();
        }).when(fixture.memory.mapper).insertReceptionRecipients(any());
    }

    private static void transact(KnowledgeGapTransactionFixture fixture, Runnable action) {
        var template = new TransactionTemplate(fixture.transactions);
        template.setTimeout(5);
        template.executeWithoutResult(status -> action.run());
    }

    private static KnowledgeGapRows.Event event(String kind, long actor, Long assignee) {
        return new KnowledgeGapRows.Event(UUID.randomUUID(), 11, kind, 2, actor, assignee, assignee == null ? 0 : 1,
                null, null, null);
    }

    private static Stream<Arguments> recipientRules() {
        return Stream.of(Arguments.of("GAP_CREATED", 10L, null, List.of(7L, 8L)),
                Arguments.of("GAP_RETURNED", 8L, 8L, List.of(7L, 10L)),
                Arguments.of("GAP_ASSIGNED", 7L, 8L, List.of(8L)),
                Arguments.of("GAP_SOLUTION_SUBMITTED", 8L, 8L, List.of(10L)),
                Arguments.of("GAP_SOLUTION_REJECTED", 10L, 8L, List.of(8L)),
                Arguments.of("GAP_RESOLVED", 10L, 8L, List.of(8L)),
                Arguments.of("GAP_WITHDRAWN", 10L, 8L, List.of(8L)),
                Arguments.of("GAP_CLOSED", 7L, 8L, List.of(10L, 8L)));
    }
}
