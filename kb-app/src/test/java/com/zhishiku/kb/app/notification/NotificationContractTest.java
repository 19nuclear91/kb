package com.zhishiku.kb.app.notification;

import com.zhishiku.kb.app.config.NotificationResponseFilter;
import com.zhishiku.kb.app.notification.model.NotificationType;
import com.zhishiku.kb.app.notification.web.NotificationAdvice;
import com.zhishiku.kb.app.notification.web.NotificationInput;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.NotificationContract;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 新旧能力头、类型集合及游标合同的离线验证，不连接服务或真实浏览器。 */
class NotificationContractTest {
    private final GapInboxHarness harness = new GapInboxHarness();

    @Test
    void defaultContractKeepsLegacyTypesCountsAndOriginalCursorDigest() {
        harness.rows.addAll(List.of(GapInboxHarness.comment(100), GapInboxHarness.solution(60, true), GapInboxHarness.comment(50)));
        var page = harness.service.list(Map.of("limit", List.of("1")));
        assertThat(page.items().getFirst().type()).isEqualTo(NotificationType.COMMENT_REPLIED);
        assertThat(harness.service.unreadCount(Map.of()).count()).isEqualTo("2");
        assertThat(harness.listTypes.getFirst()).containsExactly("REVIEW_SUBMITTED", "REVIEW_APPROVED", "REVIEW_REJECTED", "COMMENT_REPLIED");
        var cursor = QueryCursor.decode(page.nextCursor(), "user-notifications");
        cursor.verify("user-notifications", QueryParameters.filters("userId", 10));
        String old = QueryCursor.encode("user-notifications", QueryParameters.filters("userId", 10), 100);
        assertThat(harness.service.list(Map.of("cursor", List.of(old))).items()).hasSize(1);
        assertThat(harness.listTypes.getFirst()).isEqualTo(harness.countTypes.getFirst());
    }

    @Test
    void declaredListAndCountBothReturnSameMixedContractAndExactMinimalTarget() throws Exception {
        harness.rows.addAll(List.of(GapInboxHarness.solution(100, true), GapInboxHarness.comment(50)));
        var mvc = MockMvcBuilders.standaloneSetup(harness.controller).setControllerAdvice(new NotificationAdvice())
                .addFilters(new NotificationResponseFilter()).build();
        mvc.perform(get("/api/notifications").header(NotificationContract.HEADER, "KNOWLEDGE_GAPS_V1"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items[0].type").value("GAP_SOLUTION_SUBMITTED"))
                .andExpect(jsonPath("$.data.items[0].target.kind").value("KNOWLEDGE_GAP"))
                .andExpect(jsonPath("$.data.items[0].target.gapId").value(11))
                .andExpect(jsonPath("$.data.items[0].target.documentId").doesNotExist());
        mvc.perform(get("/api/notifications/unread-count").header(NotificationContract.HEADER, "KNOWLEDGE_GAPS_V1"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.count").value("2"));
        assertThat(harness.listTypes.getFirst()).isEqualTo(harness.countTypes.getFirst()).hasSize(12);
    }

    @Test
    void crossContractAndCrossUserCursorsFailWithoutQueryingOrExpandingAccess() {
        String old = QueryCursor.encode("user-notifications", QueryParameters.filters("userId", 10), 100);
        String current = QueryCursor.encode("user-notifications", QueryParameters.filters("userId", 10, "contract", "KNOWLEDGE_GAPS_V1"), 100);
        assertThatThrownBy(() -> harness.service.list(Map.of("cursor", List.of(old)), NotificationContract.KNOWLEDGE_GAPS_V1))
                .isInstanceOf(QueryException.class);
        assertThatThrownBy(() -> harness.service.list(Map.of("cursor", List.of(current)))).isInstanceOf(QueryException.class);
        harness.userId = 8;
        assertThatThrownBy(() -> harness.service.list(Map.of("cursor", List.of(current)), NotificationContract.KNOWLEDGE_GAPS_V1))
                .isInstanceOf(QueryException.class);
        assertThat(harness.listTypes).isEmpty();
    }

    @Test
    void unknownRepeatedCombinedOrWhitespaceHeadersAre400ForBothReads() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(harness.controller).setControllerAdvice(new NotificationAdvice())
                .addFilters(new NotificationResponseFilter()).build();
        for (String value : List.of("UNKNOWN", "", "KNOWLEDGE_GAPS_V1 ", "KNOWLEDGE_GAPS_V1,KNOWLEDGE_GAPS_V1")) {
            for (String path : List.of("/api/notifications", "/api/notifications/unread-count")) {
                mvc.perform(get(path).header(NotificationContract.HEADER, value)).andExpect(status().isBadRequest())
                        .andExpect(header().string("Cache-Control", "no-store"));
            }
        }
        mvc.perform(get("/api/notifications").header(NotificationContract.HEADER, "KNOWLEDGE_GAPS_V1", "KNOWLEDGE_GAPS_V1"))
                .andExpect(status().isBadRequest());
        assertThat(harness.listTypes).isEmpty();
        assertThat(harness.countTypes).isEmpty();
        assertThat(NotificationInput.contract(new MockHttpServletRequest())).isEqualTo(NotificationContract.LEGACY);
    }

    @Test
    void declaredReadFailureNeverRetriesAsLegacyOrReturnsFakeZero() {
        doThrow(new IllegalStateException("secret source failure")).when(harness.mapper)
                .list(anyLong(), anyList(), anyList(), anyList(), anyShort(), any(), anyInt());
        doThrow(new IllegalStateException("secret source failure")).when(harness.mapper)
                .countUnread(anyLong(), anyList(), anyList(), anyList(), anyShort());
        assertStatus503(() -> harness.service.list(Map.of(), NotificationContract.KNOWLEDGE_GAPS_V1));
        assertStatus503(() -> harness.service.unreadCount(Map.of(), NotificationContract.KNOWLEDGE_GAPS_V1));
    }

    @Test
    void capabilityDoesNotRevealOtherUsersOrCurrentlyUnauthorizedGap() {
        harness.rows.addAll(List.of(GapInboxHarness.solution(100, false), GapInboxHarness.comment(50)));
        assertThat(harness.service.list(Map.of(), NotificationContract.KNOWLEDGE_GAPS_V1).items()).hasSize(1);
        harness.userId = 8;
        assertThat(harness.service.list(Map.of(), NotificationContract.KNOWLEDGE_GAPS_V1).items()).isEmpty();
        assertThat(harness.service.unreadCount(Map.of(), NotificationContract.KNOWLEDGE_GAPS_V1).count()).isEqualTo("0");
    }

    private static void assertStatus503(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(503)).hasMessage("通知服务暂不可用");
    }
}
