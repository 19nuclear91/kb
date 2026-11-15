package com.zhishiku.kb.app.document.stewardship;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.applicability.service.BusinessDate;
import com.zhishiku.kb.app.document.applicability.service.ValidityAccess;
import com.zhishiku.kb.app.document.applicability.service.ValidityException;
import com.zhishiku.kb.app.document.applicability.support.ValidityInput;
import com.zhishiku.kb.app.document.stewardship.mapper.StewardshipMapper;
import com.zhishiku.kb.app.document.stewardship.model.StewardCandidate;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipDocument;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipInputs;
import com.zhishiku.kb.app.document.stewardship.service.StewardshipAccess;
import com.zhishiku.kb.app.document.stewardship.service.StewardshipCandidateService;
import com.zhishiku.kb.app.document.stewardship.support.StewardshipInput;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import com.zhishiku.kb.app.space.service.SpaceQueryService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StewardshipCandidateTest {
    @Test
    void managerWithoutIdentityDirectoryPermissionCanReadMinimalEligibleCandidates() {
        var mapper = mock(StewardshipMapper.class);
        var validity = mock(ValidityAccess.class);
        var spaces = mock(SpaceQueryService.class);
        var handle = mock(PermissionService.PrincipalHandle.class);
        var date = mock(BusinessDate.class);
        when(handle.functionalPermissions()).thenReturn(Set.of());
        when(validity.current(7)).thenReturn(handle);
        when(mapper.document(10)).thenReturn(new StewardshipDocument(10, 2, "active", false));
        var space = new SpaceRecord();
        space.setArchived(false);
        when(spaces.findManaged(handle, 2)).thenReturn(space);
        when(date.today()).thenReturn(LocalDate.of(2026, 11, 15));
        // SQL 边界替身返回资格过滤后的投影；真实 view/edit/密级谓词另由 MappingTest 核对。
        when(mapper.candidates(10, "%%", null, 21)).thenReturn(List.of(new StewardCandidate(8, "合格责任人")));
        var service = new StewardshipCandidateService(mapper, new StewardshipAccess(mapper, validity, spaces), date);
        assertThat(service.query(7, 10, new StewardshipInputs.Candidates("", 20, null)).items())
                .containsExactly(new StewardCandidate(8, "合格责任人"));
    }

    @Test
    void cursorsCannotCrossActorDocumentQueryOrPageSize() {
        var mapper = mock(StewardshipMapper.class);
        var date = mock(BusinessDate.class);
        var service = new StewardshipCandidateService(mapper, mock(StewardshipAccess.class), date);
        when(mapper.candidates(10, "%甲%", null, 2)).thenReturn(List.of(new StewardCandidate(8, "甲一"), new StewardCandidate(9, "甲二")));
        when(date.today()).thenReturn(LocalDate.of(2026, 11, 15));
        var page = service.query(7, 10, new StewardshipInputs.Candidates("甲", 1, null));
        for (StewardshipInputs.Candidates input : List.of(new StewardshipInputs.Candidates("乙", 1, page.nextCursor()),
                new StewardshipInputs.Candidates("甲", 2, page.nextCursor()))) {
            assertThatThrownBy(() -> service.query(7, 10, input)).isInstanceOf(ValidityException.class);
        }
        assertThatThrownBy(() -> service.query(7, 11, new StewardshipInputs.Candidates("甲", 1, page.nextCursor()))).isInstanceOf(ValidityException.class);
        assertThatThrownBy(() -> service.query(8, 10, new StewardshipInputs.Candidates("甲", 1, page.nextCursor()))).isInstanceOf(ValidityException.class);
    }

    @Test
    void nameQueryCountsCodePointsAndRejectsUnknownOrDuplicateFields() {
        assertThat(parse("{\"nameQuery\":\" " + "😀".repeat(64) + " \"}").nameQuery()).isEqualTo("😀".repeat(64));
        for (String raw : List.of("{\"nameQuery\":\"" + "😀".repeat(65) + "\"}", "{\"nameQuery\":null}",
                "{\"nameQuery\":\"a\",\"nameQuery\":\"b\"}", "{\"unknown\":1}")) {
            assertThatThrownBy(() -> parse(raw)).isInstanceOf(ValidityException.class);
        }
    }

    @Test
    void candidateQueryRequiresEditInAdditionToViewAndEnoughClearance() throws Exception {
        var configuration = StewardshipMappingTest.configuration();
        var sql = configuration.getMappedStatement(StewardshipMapper.class.getName() + ".candidates")
                .getBoundSql(Map.of("documentId", 10, "namePattern", "%%", "limit", 21)).getSql();
        assertThat(sql).contains("u.clearance_level >= d.security_level", "a.permission = 'view'", "a.permission = 'edit'");
        assertThat(sql).doesNotContain("identity:user:read");
    }

    private static StewardshipInputs.Candidates parse(String raw) {
        return StewardshipInput.candidates(ValidityInput.body(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8))));
    }
}
