package com.zhishiku.kb.app.knowledgegap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapCategory;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapSource;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAccess;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapCommandService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapCreateService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapEventService;
import com.zhishiku.kb.app.knowledgegap.web.KnowledgeGapController;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 离线仓储替身检查调用次序和创建/重放；不宣称证明真实数据库锁或回滚。 */
class KnowledgeGapCreateTest {
    private final KnowledgeGapMapper mapper = mock(KnowledgeGapMapper.class);
    private final KnowledgeGapAccess access = mock(KnowledgeGapAccess.class);
    private final KnowledgeGapCommandService commands = new KnowledgeGapCommandService(mapper, access);
    private final KnowledgeGapCreateService service = new KnowledgeGapCreateService(access, mapper, commands,
            new KnowledgeGapEventService(mapper));
    private final KnowledgeGapController controller = new KnowledgeGapController(access, service);
    private final AtomicInteger open = new AtomicInteger();
    private final Map<String, KnowledgeGapRows.Command> bindings = new HashMap<>();
    private final Map<Long, KnowledgeGapRows.Gap> gaps = new HashMap<>();

    @BeforeEach
    void setUp() {
        var principals = mock(PermissionMapper.class);
        var roles = mock(RolePermissionMapper.class);
        when(principals.findClearanceLevel(7)).thenReturn((short) 1);
        when(roles.findUserPermissions(7)).thenReturn(List.of());
        var actor = new PermissionService(principals, null, roles).principalOf(7);
        when(access.current()).thenReturn(actor);
        when(mapper.claim(any())).thenAnswer(call -> {
            KnowledgeGapRows.Binding binding = call.getArgument(0);
            var stored = new KnowledgeGapRows.Command(binding.requestId(), binding.actorUserId(), binding.operation(),
                    binding.gapId(), binding.expectedVersion(), binding.inputDigest(), null, null, null, null, null);
            return bindings.putIfAbsent(binding.requestId(), stored) == null ? 1 : 0;
        });
        when(mapper.command(anyString())).thenAnswer(call -> bindings.get(call.getArgument(0)));
        when(mapper.complete(anyString(), any())).thenAnswer(call -> {
            String request = call.getArgument(0);
            KnowledgeGapRows.Receipt receipt = call.getArgument(1);
            var stored = bindings.get(request);
            bindings.put(request, new KnowledgeGapRows.Command(stored.requestId(), stored.actorUserId(), stored.operation(),
                    stored.gapId(), stored.expectedVersion(), stored.inputDigest(), receipt.gapId(), receipt.resultVersion(),
                    receipt.state(), receipt.eventId(), receipt.solutionId()));
            return 1;
        });
        when(mapper.lockActorGuard(7)).thenReturn(7L);
        when(mapper.countOpen(7)).thenAnswer(call -> (long) open.get());
        when(mapper.create(eq(7L), any(), eq("GAP_SHARE_V1"), any())).thenAnswer(call -> {
            KnowledgeGapInputs.Creation input = call.getArgument(1);
            UUID event = call.getArgument(3);
            long id = gaps.size() + 11L;
            var gap = new KnowledgeGapRows.Gap(id, 7, input.category(), input.source(), input.description(),
                    "GAP_SHARE_V1", KnowledgeGapState.SUBMITTED, 0, null, 0, null, event, Instant.EPOCH, Instant.EPOCH);
            gaps.put(id, gap);
            open.incrementAndGet();
            return gap;
        });
        when(mapper.insertEvent(any())).thenReturn(1);
        when(access.requireVisible(eq(actor), anyLong())).thenAnswer(call -> gaps.get(call.getArgument(1)));
    }

    @Test
    void firstCreateReturns201AndMatchingReplayReturns200WithoutNewQuota() throws Exception {
        var first = controller.create(request("req-1", validBody()));
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(first.getBody().data().state()).isEqualTo(KnowledgeGapState.SUBMITTED);
        assertThat(first.getBody().data().resultVersion()).isZero();
        var replay = controller.create(request("req-1", validBody()));
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody().data().gapId()).isEqualTo(first.getBody().data().gapId());
        assertThat(replay.getBody().data().eventId()).isEqualTo(first.getBody().data().eventId());
        assertThat(replay.getBody().data().replayed()).isTrue();
        assertThat(replay.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(mapper, times(1)).countOpen(7);
        verify(mapper, times(1)).create(eq(7L), any(), eq("GAP_SHARE_V1"), any());
        verify(mapper, times(1)).insertEvent(any());
        assertThat(bindings.get("req-1").gapId()).isNull();
    }

    @Test
    void quotaUsesCommandThenActorLockAndRejectsAtOneHundred() {
        open.set(99);
        service.create(input(), "quota-1");
        assertThat(open.get()).isEqualTo(100);
        assertStatus(409, () -> service.create(input(), "quota-2"));
        var order = inOrder(mapper);
        order.verify(mapper).configureLockTimeout();
        order.verify(mapper).claim(any());
        order.verify(mapper).ensureActorGuard(7);
        order.verify(mapper).lockActorGuard(7);
        order.verify(mapper).countOpen(7);
        verify(mapper, times(1)).create(eq(7L), any(), eq("GAP_SHARE_V1"), any());
    }

    @Test
    void changingPayloadDoesNotReuseSuccessfulRequestId() {
        service.create(input(), "same-request");
        var changed = new KnowledgeGapInputs.Creation(KnowledgeGapCategory.OTHER, "修改后的描述", KnowledgeGapSource.MANUAL, true);
        assertStatus(409, () -> service.create(changed, "same-request"));
        verify(mapper, times(1)).countOpen(7);
    }

    @Test
    void creationRequiresConsentAndRejectsPrivateOrForgedPayloadFields() {
        for (String body : List.of(validBody().replace("true", "false"),
                validBody().replace("\"source\"", "\"conversationId\""),
                validBody().replace("}", ",\"answer\":\"模型回答\"}"),
                validBody().replace("}", ",\"reporterUserId\":8}"))) {
            assertThatThrownBy(() -> controller.create(request("invalid", body))).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void sourceIsOnlyInputMetadataAndEventReceiptHaveNoBody() throws Exception {
        for (KnowledgeGapSource source : KnowledgeGapSource.values()) {
            var input = new KnowledgeGapInputs.Creation(KnowledgeGapCategory.MISSING_KNOWLEDGE, "用户主动报告", source, true);
            var receipt = service.create(input, "source-" + source);
            assertThat(gaps.get(receipt.gapId()).source()).isEqualTo(source);
            assertThat(receipt.state()).isEqualTo(KnowledgeGapState.SUBMITTED);
        }
        var captured = ArgumentCaptor.forClass(KnowledgeGapRows.Event.class);
        verify(mapper, times(3)).insertEvent(captured.capture());
        for (var event : captured.getAllValues()) {
            assertThat(event.kind()).isEqualTo("GAP_CREATED");
            assertThat(event.entryId()).isNull();
            assertThat(event.solutionId()).isNull();
        }
        var json = new ObjectMapper().valueToTree(bindings.get("source-MANUAL"));
        assertThat(json.toString()).doesNotContain("用户主动报告");
    }

    @Test
    void storageFailureIsSafeAndStopsReceiptCompletion() {
        when(mapper.insertEvent(any())).thenThrow(new DataAccessResourceFailureException("secret description and sql"));
        assertThatThrownBy(() -> service.create(input(), "failure"))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.httpStatus()).isEqualTo(503))
                .hasMessage("知识问题单服务暂不可用");
        verify(mapper, never()).complete(anyString(), any());
    }

    private static KnowledgeGapInputs.Creation input() {
        return new KnowledgeGapInputs.Creation(KnowledgeGapCategory.OTHER, "分享的问题", KnowledgeGapSource.MANUAL, true);
    }

    private static String validBody() {
        return "{\"category\":\"OTHER\",\"description\":\"分享的问题\",\"source\":\"MANUAL\",\"shareAcknowledged\":true}";
    }

    private static MockHttpServletRequest request(String requestId, String body) {
        var request = new MockHttpServletRequest("POST", "/api/knowledge-gaps");
        request.setContentType("application/json");
        request.addHeader("X-Request-Id", requestId);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static void assertStatus(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(status));
    }
}
