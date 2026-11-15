package com.zhishiku.kb.app.document.stewardship;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.applicability.model.UntilKind;
import com.zhishiku.kb.app.document.applicability.service.BusinessDate;
import com.zhishiku.kb.app.document.applicability.service.ValidityAccess;
import com.zhishiku.kb.app.document.applicability.service.ValidityException;
import com.zhishiku.kb.app.document.stewardship.mapper.StewardshipMapper;
import com.zhishiku.kb.app.document.stewardship.model.StewardCandidate;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipDocument;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipListRow;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipRow;
import com.zhishiku.kb.app.document.stewardship.service.StewardshipAccess;
import com.zhishiku.kb.app.document.stewardship.service.StewardshipQueryService;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StewardshipQueryTest {
    private final StewardshipMapper mapper = mock(StewardshipMapper.class);
    private final StewardshipAccess management = mock(StewardshipAccess.class);
    private final ValidityAccess access = mock(ValidityAccess.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final BusinessDate date = mock(BusinessDate.class);
    private final PermissionService.PrincipalHandle handle = mock(PermissionService.PrincipalHandle.class);
    private final LocalDate today = LocalDate.of(2026, 11, 15);
    private final StewardshipQueryService service = new StewardshipQueryService(mapper, management, access, permissions, date);

    @BeforeEach
    void setup() {
        when(access.current(7)).thenReturn(handle);
        when(permissions.resolve(handle, "view")).thenReturn(PermissionScope.of(7, (short) 2, List.of(2L), Set.of("1:7")));
        when(date.today()).thenReturn(today);
        when(management.manager(7, 10, false)).thenReturn(new StewardshipDocument(10, 2, "active", false));
    }

    @Test
    void unassignedWinsOverOverdueAndNeedsReassignNeverContainsReason() throws Exception {
        var empty = service.get(7, 10);
        assertThat(empty.state().name()).isEqualTo("UNASSIGNED");
        assertThat(empty.cycleMonths()).isNull();
        when(mapper.get(10)).thenReturn(new StewardshipRow(10, 8L, 3, today.minusMonths(4), null, null, today.minusMonths(1), 1, null));
        when(mapper.responsible(10)).thenReturn(new StewardCandidate(8, "原责任人"));
        when(mapper.eligible(10, 8)).thenReturn(false);
        var ineligible = service.get(7, 10);
        assertThat(ineligible.state().name()).isEqualTo("NEEDS_REASSIGN");
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(ineligible)).doesNotContain("reason", "disabled", "clearance", "department");
        when(mapper.get(10)).thenReturn(new StewardshipRow(10, null, 3, today.minusMonths(4), null, null, today.minusMonths(1), 2, null));
        var cancelled = service.get(7, 10);
        assertThat(cancelled.state().name()).isEqualTo("UNASSIGNED");
        assertThat(cancelled.dueDate()).isEqualTo(today.minusMonths(1));
    }

    @Test
    void applicabilityOnlyAttentionAndNoCurrentVersionAreDistinctAndReassignmentChangesNextPage() {
        var expiring = row(10, today.plusMonths(6), 20L, today.plusDays(20));
        when(mapper.mine(7, "attention", today, List.of(2L), List.of("1:7"), (short) 2, null, null, 21)).thenReturn(List.of(expiring));
        var page = service.mine(7, "attention", 20, null);
        assertThat(page.items().getFirst().state().name()).isEqualTo("OK");
        assertThat(page.items().getFirst().attentionReasons()).containsExactly("APPLICABILITY_EXPIRING_SOON");
        when(mapper.mine(7, "attention", today, List.of(2L), List.of("1:7"), (short) 2, null, null, 21))
                .thenReturn(List.of(row(11, today.plusDays(5), null, null)), List.of());
        assertThat(service.mine(7, "attention", 20, null).items().getFirst().currentVersion()).isNull();
        assertThat(service.mine(7, "attention", 20, null).items()).isEmpty();
    }

    @Test
    void cursorsBindUserAndViewAndManagementMustBeRecheckedOnEverySpacePage() {
        when(mapper.mine(7, "all", today, List.of(2L), List.of("1:7"), (short) 2, null, null, 2))
                .thenReturn(List.of(row(10, today.plusDays(5), null, null), row(11, today.plusDays(6), null, null)));
        String token = service.mine(7, "all", 1, null).nextCursor();
        assertThatThrownBy(() -> service.mine(8, "all", 1, token)).isInstanceOf(ValidityException.class);
        assertThatThrownBy(() -> service.mine(7, "attention", 1, token)).isInstanceOf(ValidityException.class);
        var unassigned = new StewardshipListRow(10, null, null, null, null, null, null, 0, false, null, null, UntilKind.UNDECLARED, null, false);
        when(mapper.space(2, "UNASSIGNED", today, null, null, 2)).thenReturn(List.of(unassigned, unassigned));
        String spaceToken = service.space(7, 2, "UNASSIGNED", 1, null).nextCursor();
        doThrow(new ValidityException(ValidityException.Reason.NOT_FOUND)).when(management).space(7, 2);
        assertThatThrownBy(() -> service.space(7, 2, "UNASSIGNED", 1, spaceToken)).isInstanceOfSatisfying(ValidityException.class,
                error -> assertThat(error.reason().status()).isEqualTo(404));
    }

    @Test
    void archivedManagementReadRemainsAvailableButMineSqlExcludesArchiveAndFailuresAreNotEmpty() throws Exception {
        when(management.manager(7, 10, false)).thenReturn(new StewardshipDocument(10, 2, "active", true));
        assertThat(service.get(7, 10).state().name()).isEqualTo("UNASSIGNED");
        var config = StewardshipMappingTest.configuration();
        String sql = config.getMappedStatement(StewardshipMapper.class.getName() + ".mine").getBoundSql(java.util.Map.of(
                "actor", 7, "view", "attention", "today", today, "spaceIds", List.of(2), "principalKeys", List.of("1:7"), "clearance", 2, "limit", 21)).getSql();
        assertThat(sql).contains("d.status NOT IN ('deleted', 'archived')", "s.archived = false");
        when(mapper.mine(7, "attention", today, List.of(2L), List.of("1:7"), (short) 2, null, null, 21)).thenReturn(null);
        when(mapper.space(2, "UNASSIGNED", today, null, null, 21)).thenReturn(null);
        assertThatThrownBy(() -> service.mine(7, "attention", 20, null)).isInstanceOfSatisfying(ValidityException.class,
                error -> assertThat(error.reason().status()).isEqualTo(503));
        assertThatThrownBy(() -> service.count(7)).isInstanceOf(ValidityException.class);
        assertThatThrownBy(() -> service.space(7, 2, "UNASSIGNED", 20, null)).isInstanceOf(ValidityException.class);
        when(mapper.count(7, "attention", today, List.of(2L), List.of("1:7"), (short) 2)).thenReturn("9007199254740993");
        assertThat(service.count(7).count()).isEqualTo("9007199254740993");
    }

    private static StewardshipListRow row(long id, LocalDate due, Long version, LocalDate until) {
        return new StewardshipListRow(id, "已授权标题", 7L, "本人", 6, null, due, 1, true, version, null,
                until == null ? UntilKind.UNDECLARED : UntilKind.DATE, until, false);
    }
}
