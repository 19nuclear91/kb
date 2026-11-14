package com.zhishiku.kb.app.knowledgegap;

import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.PublishedDocumentMapper;
import com.zhishiku.kb.app.document.model.PublishedDocumentReference;
import com.zhishiku.kb.app.document.service.PublishedDocumentQueryService;
import com.zhishiku.kb.app.support.MapperRowStub;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.review.PublicationEvidence;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 文档读取行为和真实 MyBatis 配置的离线验证，不执行 SQL 或打开文件。 */
class KnowledgeGapDocumentAccessTest {
    private final PermissionMapper scope = mock(PermissionMapper.class);
    private final PermissionService permissions = new PermissionService(scope, null, mock(RolePermissionMapper.class));
    private final PublishedDocumentMapper mapper = mock(PublishedDocumentMapper.class);
    private final PublishedDocumentQueryService service = new PublishedDocumentQueryService(mapper, permissions);
    private PermissionService.PrincipalHandle actor;

    @BeforeEach
    void setUp() {
        when(scope.findClearanceLevel(7)).thenReturn((short) 1);
        when(scope.findVisibleSpaceIds(anyList(), any())).thenReturn(List.of(2L));
        actor = permissions.principalOf(7);
    }

    @Test
    void indexOrLegacyVisibilityCannotReplacePublicationEvidence() {
        for (PublicationEvidence evidence : PublicationEvidence.values()) {
            when(mapper.find(anyLong(), anyLong(), anyList(), anyList(), anyShort()))
                    .thenReturn(new PublishedDocumentReference(11, 21, "资料", evidence));
            assertThat(service.reference(actor, 11, 21).isPresent()).isEqualTo(evidence != PublicationEvidence.NONE);
        }
    }

    @Test
    void fixedHistoricalVersionDoesNotSwitchToCurrentAndRevocationHasNoSnapshotFallback() {
        when(mapper.find(11, 21, List.of(2L), List.of("1:7"), (short) 1))
                .thenReturn(new PublishedDocumentReference(11, 21, "历史固定资料", PublicationEvidence.PUBLISHED));
        assertThat(service.reference(actor, 11, 21).orElseThrow().versionId()).isEqualTo(21);
        when(mapper.find(11, 21, List.of(2L), List.of("1:7"), (short) 1)).thenReturn(null);
        assertThat(service.reference(actor, 11, 21)).isEmpty();
    }

    @Test
    void unpublishedOrInconsistentCandidatePageFailsInsteadOfPostPageFiltering() {
        when(mapper.candidates(any(), any(), anyInt(), anyList(), anyList(), anyShort())).thenReturn(List.of(
                new PublishedDocumentReference(11, 21, "资料", PublicationEvidence.NONE)));
        assertThatThrownBy(() -> service.candidates(actor, null, null, 21))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.httpStatus()).isEqualTo(503));
    }

    @Test
    void dependencyFailureReturnsSafeErrorWithoutTitleSqlOrRawException() {
        when(mapper.find(anyLong(), anyLong(), anyList(), anyList(), anyShort()))
                .thenThrow(new DataAccessResourceFailureException("secret title and sql"));
        assertThatThrownBy(() -> service.reference(actor, 11, 21))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.httpStatus()).isEqualTo(503))
                .hasMessage("暂时无法确认发布资料访问权限");
    }

    @Test
    void mapperReusesLiveAclClearanceAndPublishedPredicatesBeforePaging() throws Exception {
        var configuration = MapperRowStub.configuration("/mapper/DocumentMapper.xml");
        try (var input = getClass().getResourceAsStream("/mapper/PublishedDocumentMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "/mapper/PublishedDocumentMapper.xml", configuration.getSqlFragments()).parse();
        }
        String namespace = PublishedDocumentMapper.class.getName() + ".";
        var params = new LinkedHashMap<String, Object>();
        params.put("documentId", 11L);
        params.put("versionId", 21L);
        params.put("spaceIds", List.of(2L));
        params.put("principalKeys", List.of("1:7"));
        params.put("clearance", (short) 1);
        params.put("like", "%资料%");
        params.put("afterId", 5L);
        params.put("fetch", 21);
        String find = configuration.getMappedStatement(namespace + "find").getBoundSql(params).getSql()
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        assertThat(find).contains("v.doc_id = d.id", "d.id = ? and v.id = ?", "d.status not in ('deleted', 'archived')",
                "d.security_level <= ?", "s.archived = false", "va.permission = 'view'",
                "v.publication_evidence in ('published', 'observed_current')");
        assertThat(find).doesNotContain("d.current_version_id");
        String candidates = configuration.getMappedStatement(namespace + "candidates").getBoundSql(params).getSql()
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        assertThat(candidates).contains("v.id = d.current_version_id", "order by d.id asc limit ?");
        assertThat(candidates.indexOf("v.publication_evidence in")).isLessThan(candidates.indexOf("order by"));
        var result = MapperRowStub.readOne(configuration, namespace + "find", params,
                Map.of("document_id", 9007199254740993L, "version_id", 9007199254740994L,
                        "title", "固定标题", "publication_evidence", "OBSERVED_CURRENT"), PublishedDocumentReference.class);
        assertThat(result.documentId()).isEqualTo(9007199254740993L);
        assertThat(result.versionId()).isEqualTo(9007199254740994L);
        assertThat(result.published()).isTrue();
    }
}
