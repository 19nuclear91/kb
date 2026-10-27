package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.config.UuidTypeHandler;
import com.zhishiku.kb.app.document.mapper.DocumentCommentTargetMapper;
import com.zhishiku.kb.app.document.model.DocumentCommentTarget;
import com.zhishiku.kb.app.document.service.DocumentCommentTargetService;
import com.zhishiku.kb.app.support.BoundValues;
import com.zhishiku.kb.app.support.MapperRowStub;
import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.review.PublicationProtocol;
import com.zhishiku.kb.common.security.PermissionScope;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 评论目标资格矩阵：模型判定、按权限位分开解析的服务绑定及真实 MyBatis 映射；不连接数据库。 */
class DocumentCommentTargetTest {
    private static final String NAMESPACE = DocumentCommentTargetMapper.class.getName();

    static DocumentCommentTarget target(boolean live, boolean cleared, boolean view, boolean comment,
                                        PublicationProtocol protocol, PublicationEvidence evidence) {
        return new DocumentCommentTarget(10, 20, live, cleared, view, comment, PublicationPolicy.AUTO, protocol,
                protocol == PublicationProtocol.VERSIONED_REVIEW ? 9L : null, evidence,
                evidence == PublicationEvidence.PUBLISHED ? Instant.EPOCH : null);
    }

    @Test
    void readRequiresLiveClearedViewAndPublicationEvidenceAndWriteAlsoRequiresComment() {
        var versions = List.of(Map.entry(PublicationProtocol.VERSIONED_REVIEW, PublicationEvidence.PUBLISHED),
                Map.entry(PublicationProtocol.LEGACY_COMPAT, PublicationEvidence.PUBLISHED),
                Map.entry(PublicationProtocol.LEGACY_COMPAT, PublicationEvidence.OBSERVED_CURRENT),
                Map.entry(PublicationProtocol.LEGACY_COMPAT, PublicationEvidence.NONE),
                Map.entry(PublicationProtocol.VERSIONED_REVIEW, PublicationEvidence.NONE));
        for (var version : versions) {
            for (int mask = 0; mask < 16; mask++) {
                boolean live = (mask & 1) != 0, cleared = (mask & 2) != 0, view = (mask & 4) != 0, comment = (mask & 8) != 0;
                var target = target(live, cleared, view, comment, version.getKey(), version.getValue());
                boolean published = version.getValue() != PublicationEvidence.NONE;
                assertThat(target.readable()).as("%s %s mask=%d", version.getKey(), version.getValue(), mask)
                        .isEqualTo(live && cleared && view && published);
                assertThat(target.commentable()).as("%s %s mask=%d", version.getKey(), version.getValue(), mask)
                        .isEqualTo(live && cleared && view && comment && published);
            }
        }
    }

    @Test
    void legacyCompatAloneCandidatesAndCommentWithoutViewAreNotCommentTargets() {
        // 旧兼容版本只有 indexed 与兼容访问标记：交互可见，但不能开放评论。
        assertThat(target(true, true, true, true, PublicationProtocol.LEGACY_COMPAT, PublicationEvidence.NONE).readable()).isFalse();
        // 待审候选：上传者、送审人或审核池身份不参与 COMMENT_TARGET。
        assertThat(target(true, true, true, true, PublicationProtocol.VERSIONED_REVIEW, PublicationEvidence.NONE).commentable()).isFalse();
        // comment 不暗含 view，view 也不替代 comment。
        var commentOnly = target(true, true, false, true, PublicationProtocol.VERSIONED_REVIEW, PublicationEvidence.PUBLISHED);
        assertThat(commentOnly.readable()).isFalse();
        assertThat(commentOnly.commentable()).isFalse();
        var viewOnly = target(true, true, true, false, PublicationProtocol.VERSIONED_REVIEW, PublicationEvidence.PUBLISHED);
        assertThat(viewOnly.readable()).isTrue();
        assertThat(viewOnly.commentable()).isFalse();
        // 升密、归档或删除后旧评论关系不保留访问。
        for (var gated : List.of(target(true, false, true, true, PublicationProtocol.LEGACY_COMPAT, PublicationEvidence.OBSERVED_CURRENT),
                target(false, true, true, true, PublicationProtocol.LEGACY_COMPAT, PublicationEvidence.OBSERVED_CURRENT))) {
            assertThat(gated.readable()).isFalse();
            assertThat(gated.commentable()).isFalse();
        }
    }

    @Test
    void contradictoryPublicationFactsAreAFailureNotADenial() {
        var invalid = new DocumentCommentTarget(10, 20, true, true, true, true, PublicationPolicy.AUTO,
                PublicationProtocol.VERSIONED_REVIEW, 9L, PublicationEvidence.OBSERVED_CURRENT, null);
        assertThatThrownBy(invalid::readable).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void viewAndCommentScopesAreResolvedSeparatelyAndDisabledPrincipalsSeeNothing() {
        var mapper = mock(DocumentCommentTargetMapper.class);
        var permissions = mock(PermissionService.class);
        var handle = mock(PermissionService.PrincipalHandle.class);
        var service = new DocumentCommentTargetService(mapper, permissions);
        when(permissions.resolve(handle, "view")).thenReturn(PermissionScope.of(7, (short) 2, List.of(3L), List.of("1:7", "2:4", "3:1")));
        when(permissions.resolve(handle, "comment")).thenReturn(PermissionScope.of(7, (short) 2, List.of(5L), List.of("1:7", "2:4", "3:1")));
        var row = target(true, true, true, true, PublicationProtocol.VERSIONED_REVIEW, PublicationEvidence.PUBLISHED);
        when(mapper.facts(10, 20, List.of(3L), List.of(5L), List.of("1:7", "2:4", "3:1"), (short) 2)).thenReturn(row);
        when(mapper.lockFacts(10, 20, List.of(3L), List.of(5L), List.of("1:7", "2:4", "3:1"), (short) 2)).thenReturn(row);
        assertThat(service.inspect(handle, 10, 20)).isEqualTo(row);
        assertThat(service.lock(handle, 10, 20)).isEqualTo(row);
        verify(mapper, never()).facts(anyLong(), anyLong(), eq(List.of(5L)), eq(List.of(3L)), anyList(), anyShort());
        // 停用主体的解析为 denyAll：两个范围都为空，读取只能得到不可见事实。
        when(permissions.resolve(handle, "view")).thenReturn(PermissionScope.denyAll(7));
        when(permissions.resolve(handle, "comment")).thenReturn(PermissionScope.denyAll(7));
        service.inspect(handle, 10, 20);
        verify(mapper).facts(10, 20, List.of(), List.of(), List.of(), (short) 0);
        // 映射返回其他目标时不能当作本目标授权。
        when(mapper.facts(eq(10L), eq(21L), any(), any(), any(), anyShort())).thenReturn(row);
        assertThatThrownBy(() -> service.inspect(handle, 10, 21)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lockingRequiresTheCallersWriteTransaction() throws Exception {
        var lock = DocumentCommentTargetService.class.getMethod("lock", PermissionService.PrincipalHandle.class, long.class, long.class);
        assertThat(lock.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void mappingBindsSeparateViewAndCommentSpacesWithSharedPrincipalKeysAndMapsEnums() throws Exception {
        Configuration configuration = MapperRowStub.configuration("/mapper/DocumentCommentTargetMapper.xml");
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
        Map<String, Object> parameters = parameters(List.of(3L), List.of(5L, 6L), List.of("1:7", "3:1"));
        for (String id : List.of(NAMESPACE + ".facts", NAMESPACE + ".lockFacts")) {
            var values = BoundValues.values(configuration, id, parameters);
            assertThat(values).containsExactly((short) 2, 3L, "1:7", "3:1", 5L, 6L, "1:7", "3:1", 20L, 10L);
        }
        // 空范围构造过滤为 FALSE 的语句，而不是省略过滤。
        var empty = BoundValues.values(configuration, NAMESPACE + ".facts", parameters(List.of(), List.of(), List.of()));
        assertThat(empty).containsExactly((short) 0, 20L, 10L);
        var row = new LinkedHashMap<String, Object>();
        row.put("document_id", 9007199254740993L);
        row.put("version_id", 9007199254740995L);
        row.put("live", true);
        row.put("cleared", true);
        row.put("view_granted", true);
        row.put("comment_granted", false);
        row.put("publication_policy", "REVIEW_REQUIRED");
        row.put("publication_protocol", "LEGACY_COMPAT");
        row.put("uploader_user_id", null);
        row.put("publication_evidence", "OBSERVED_CURRENT");
        row.put("published_at", null);
        var mapped = MapperRowStub.readOne(configuration, NAMESPACE + ".facts", parameters, row, DocumentCommentTarget.class);
        assertThat(mapped.documentId()).isEqualTo(9007199254740993L);
        assertThat(mapped.versionId()).isEqualTo(9007199254740995L);
        assertThat(mapped.policy()).isEqualTo(PublicationPolicy.REVIEW_REQUIRED);
        assertThat(mapped.evidence()).isEqualTo(PublicationEvidence.OBSERVED_CURRENT);
        assertThat(mapped.readable()).isTrue();
        assertThat(mapped.commentable()).isFalse();
        for (var map : configuration.getMappedStatement(NAMESPACE + ".facts").getResultMaps()) {
            assertThat(map.getMappedColumns().stream().map(String::toLowerCase))
                    .noneMatch(column -> List.of("title", "file_name", "storage_key", "body").contains(column));
        }
    }

    @Test
    void removingTheDocumentCommentExceptionKeepsTheIndependentSpaceGrant() throws Exception {
        Configuration configuration = MapperRowStub.configuration("/mapper/DocumentCommentTargetMapper.xml");
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
        // 例外已删除：没有匹配的例外主体键，但空间 comment 仍在范围内，空间分支照常绑定。
        var spaceOnly = parameters(List.of(3L), List.of(3L), List.of());
        spaceOnly.put("clearance", (short) 2);
        assertThat(BoundValues.values(configuration, NAMESPACE + ".facts", spaceOnly))
                .containsExactly((short) 2, 3L, 3L, 20L, 10L);
        // 反向：空间未授予时只剩例外分支；两者是并集，删除其一不会变成 deny。
        assertThat(BoundValues.values(configuration, NAMESPACE + ".facts", parameters(List.of(3L), List.of(), List.of("1:7"))))
                .containsExactly((short) 2, 3L, "1:7", "1:7", 20L, 10L);
        var viaSpace = target(true, true, true, true, PublicationProtocol.VERSIONED_REVIEW, PublicationEvidence.PUBLISHED);
        assertThat(viaSpace.commentable()).isTrue();
        // 服务层每次重新解析 comment 范围，不缓存例外删除前的结果。
        var mapper = mock(DocumentCommentTargetMapper.class);
        var permissions = mock(PermissionService.class);
        var handle = mock(PermissionService.PrincipalHandle.class);
        when(permissions.resolve(handle, "view")).thenReturn(PermissionScope.of(7, (short) 2, List.of(3L), List.of("1:7")));
        when(permissions.resolve(handle, "comment")).thenReturn(PermissionScope.of(7, (short) 2, List.of(3L), List.of("1:7")));
        when(mapper.facts(10, 20, List.of(3L), List.of(3L), List.of("1:7"), (short) 2)).thenReturn(viaSpace);
        assertThat(new DocumentCommentTargetService(mapper, permissions).inspect(handle, 10, 20).commentable()).isTrue();
        verify(permissions).resolve(handle, "comment");
    }

    private static Map<String, Object> parameters(List<Long> view, List<Long> comment, List<String> keys) {
        var parameters = new HashMap<String, Object>();
        parameters.put("documentId", 10L);
        parameters.put("versionId", 20L);
        parameters.put("viewSpaceIds", view);
        parameters.put("commentSpaceIds", comment);
        parameters.put("principalKeys", keys);
        parameters.put("clearance", keys.isEmpty() ? (short) 0 : (short) 2);
        return parameters;
    }
}
