package com.zhishiku.kb.app.knowledgegap;

import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.KnowledgeGapAssigneeService;
import com.zhishiku.kb.app.auth.service.KnowledgeGapParticipantAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.model.PublishedDocumentReference;
import com.zhishiku.kb.app.document.service.PublishedDocumentQueryService;
import com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapCategory;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapSource;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapWorkflow;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAccess;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAssignmentService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapCommandService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapEventService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapSolutionQueryService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapSolutionService;
import com.zhishiku.kb.app.knowledgegap.web.KnowledgeGapSolutionController;
import com.zhishiku.kb.common.review.PublicationEvidence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 离线可变仓储；只模拟方法结果和版本竞争，不证明真实数据库事务语义。 */
final class KnowledgeGapMemoryFixture {
    final KnowledgeGapMapper mapper = mock(KnowledgeGapMapper.class);
    final AdminQueryAccess identities = mock(AdminQueryAccess.class);
    final KnowledgeGapAccess access = new KnowledgeGapAccess(identities, mapper);
    final PublishedDocumentQueryService documents = mock(PublishedDocumentQueryService.class);
    final KnowledgeGapParticipantAccess participants = mock(KnowledgeGapParticipantAccess.class);
    final KnowledgeGapCommandService commands = new KnowledgeGapCommandService(mapper, access);
    final KnowledgeGapEventService facts = new KnowledgeGapEventService(mapper);
    final KnowledgeGapSolutionService solutions = new KnowledgeGapSolutionService(access, mapper, commands, facts, documents, participants);
    final KnowledgeGapSolutionQueryService queries = new KnowledgeGapSolutionQueryService(access, documents);
    final KnowledgeGapSolutionController controller = new KnowledgeGapSolutionController(access, solutions, queries);
    final KnowledgeGapAssignmentService assignments = new KnowledgeGapAssignmentService(access, mapper, commands,
            mock(KnowledgeGapAssigneeService.class), facts);
    final Map<String, KnowledgeGapRows.Command> bindings = new HashMap<>();
    final Map<UUID, KnowledgeGapRows.Entry> entries = new HashMap<>();
    final List<KnowledgeGapRows.Event> events = new ArrayList<>();
    final Map<Long, Boolean> readable = new HashMap<>(Map.of(8L, true, 10L, true));
    KnowledgeGapRows.Gap gap = new KnowledgeGapRows.Gap(11, 10, KnowledgeGapCategory.OTHER, KnowledgeGapSource.MANUAL,
            "共享问题", "GAP_SHARE_V1", KnowledgeGapState.IN_PROGRESS, 1, 8L, 1, null, null, Instant.EPOCH, Instant.EPOCH);
    long actorId = 8;

    KnowledgeGapMemoryFixture() {
        when(identities.current()).thenAnswer(call -> principal(actorId));
        when(mapper.findVisible(eq(11L), anyLong())).thenAnswer(call -> gap);
        when(mapper.lock(eq(11L), anyLong())).thenAnswer(call -> gap);
        when(mapper.claim(any())).thenAnswer(call -> {
            KnowledgeGapRows.Binding binding = call.getArgument(0);
            var row = new KnowledgeGapRows.Command(binding.requestId(), binding.actorUserId(), binding.operation(), binding.gapId(),
                    binding.expectedVersion(), binding.inputDigest(), null, null, null, null, null);
            return bindings.putIfAbsent(binding.requestId(), row) == null ? 1 : 0;
        });
        when(mapper.command(anyString())).thenAnswer(call -> bindings.get(call.getArgument(0)));
        when(mapper.complete(anyString(), any())).thenAnswer(call -> {
            String request = call.getArgument(0);
            KnowledgeGapRows.Receipt receipt = call.getArgument(1);
            var row = bindings.get(request);
            bindings.put(request, new KnowledgeGapRows.Command(row.requestId(), row.actorUserId(), row.operation(), row.gapId(),
                    row.expectedVersion(), row.inputDigest(), receipt.gapId(), receipt.resultVersion(), receipt.state(),
                    receipt.eventId(), receipt.solutionId()));
            return 1;
        });
        when(mapper.advance(eq(11L), anyLong(), anyLong(), any())).thenAnswer(call -> {
            long expected = call.getArgument(2);
            KnowledgeGapWorkflow next = call.getArgument(3);
            if (gap.version() != expected) {
                return 0;
            }
            gap = new KnowledgeGapRows.Gap(gap.id(), gap.reporterUserId(), gap.category(), gap.source(), gap.description(),
                    gap.consentVersion(), next.state(), next.version(), next.assigneeUserId(), next.assignmentEpoch(),
                    next.pendingSolutionId(), next.queueEventId(), gap.createdAt(), Instant.EPOCH);
            return 1;
        });
        when(mapper.insertEntry(any())).thenAnswer(call -> {
            KnowledgeGapRows.Entry entry = call.getArgument(0);
            entries.put(entry.entryId(), entry);
            return 1;
        });
        when(mapper.entry(eq(11L), any(), anyLong())).thenAnswer(call -> entries.get(call.getArgument(1)));
        when(mapper.insertEvent(any())).thenAnswer(call -> {
            events.add(call.getArgument(0));
            return 1;
        });
        when(participants.active(10)).thenAnswer(call -> principal(10));
        when(documents.reference(any(), anyLong(), anyLong())).thenAnswer(call -> {
            PermissionService.PrincipalHandle principal = call.getArgument(0);
            long document = call.getArgument(1);
            long version = call.getArgument(2);
            return readable.getOrDefault(principal.userId(), false)
                    ? Optional.of(new PublishedDocumentReference(document, version, "授权标题", PublicationEvidence.PUBLISHED))
                    : Optional.empty();
        });
    }

    private static PermissionService.PrincipalHandle principal(long userId) {
        var principals = mock(PermissionMapper.class);
        var roles = mock(RolePermissionMapper.class);
        when(principals.findClearanceLevel(userId)).thenReturn((short) 1);
        when(roles.findUserPermissions(userId)).thenReturn(userId == 7
                ? List.of("knowledge:gap:read", "knowledge:gap:assign", "knowledge:gap:close")
                : userId == 8 || userId == 9 ? List.of("knowledge:gap:handle") : List.of());
        return new PermissionService(principals, null, roles).principalOf(userId);
    }
}
