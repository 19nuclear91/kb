package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.document.service.ReviewCancellationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.document.metadata.mapper.CategoryMapper;
import com.zhishiku.kb.app.document.metadata.mapper.MetadataMapper;
import com.zhishiku.kb.app.document.metadata.model.MetadataCommandRow;
import com.zhishiku.kb.app.document.metadata.model.CategoryRow;
import com.zhishiku.kb.app.document.metadata.service.MetadataCommands;
import com.zhishiku.kb.app.document.metadata.service.MetadataAccess;
import com.zhishiku.kb.app.document.model.DocumentEntity;
import com.zhishiku.kb.app.auth.service.PermissionService;
import java.util.concurrent.atomic.AtomicReference;
import com.zhishiku.kb.app.space.mapper.DocumentAclMapper;
import com.zhishiku.kb.app.space.mapper.DocumentRelocationMapper;
import com.zhishiku.kb.app.space.mapper.SpaceMapper;
import com.zhishiku.kb.app.space.model.DocumentAclEntry;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import com.zhishiku.kb.app.space.model.SpaceViewPrincipal;
import com.zhishiku.kb.common.security.DocumentRelocationResult;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PrincipalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.clearInvocations;

/**
 * 文档搬迁的行为契约。
 *
 * <p>覆盖的是"看起来成功、权限已经漏了"那一类：少判一侧 manage、按声明值而不是实际
 * 归属鉴权、重放时再清一次例外、只改权威归属不同步分块副本。
 *
 * <p>三条需求在本变更里<b>只被实现、不被验证</b>（见 design 的 Risks 一节）：
 * 「搬迁必须让检索与读取立即按新空间过滤」「搬迁与并发解析发布不得留下陈旧空间
 * 归属」以及例外清理的端到端可见效果，都需要 PG + MinIO + Worker 同时在位。这里只能
 * 守住"调用了同步 SQL""取锁发生在写入之前"这类结构性事实，守不住"原空间的人真的
 * 搜不到了"。
 */
class DocumentRelocationServiceTest {

    private static final long DOC = 10L;
    private static final long SOURCE = 1L;
    private static final long TARGET = 2L;
    /**
     * 文档的实际归属被别人改成了这个空间，用来构造"声明与实际不符"。
     */
    private static final long ELSEWHERE = 3L;
    private static final long ADMIN = 7L;
    private static final long OUTSIDE_DEPT = 5L;

    private DocumentRelocationMapper mapper;
    private DocumentAclMapper acls;
    private SpaceMapper spaces;
    private SpaceAuthorizer authorizer;
    private IdentityAuditService audit;
    private DocumentRelocationService service;
    private ReviewCancellationService cancellation;
    private MetadataMapper metadata;
    private CategoryMapper categories;
    private DocumentEntity document;
    private final AtomicReference<MetadataCommandRow> receipt = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        mapper = mock(DocumentRelocationMapper.class);
        acls = mock(DocumentAclMapper.class);
        spaces = mock(SpaceMapper.class);
        authorizer = mock(SpaceAuthorizer.class);
        audit = mock(IdentityAuditService.class);
        cancellation = mock(ReviewCancellationService.class);
        metadata = mock(MetadataMapper.class);
        categories = mock(CategoryMapper.class);
        var access = mock(MetadataAccess.class);
        var actor = mock(PermissionService.PrincipalHandle.class);
        when(access.current()).thenReturn(actor);
        when(actor.userId()).thenReturn(ADMIN);
        document = DocumentEntity.builder().id(DOC).spaceId(SOURCE).metadataVersion(0L).build();
        when(metadata.document(DOC)).thenAnswer(call -> document);
        when(categories.shareTree(anyLong())).thenReturn(0L);
        when(metadata.command("DOCUMENT_RELOCATE", "req-1")).thenAnswer(call -> receipt.get());
        when(metadata.insertCommand(any())).thenAnswer(call -> {
            receipt.set(call.getArgument(0));
            return 1;
        });
        service = new DocumentRelocationService(mapper, acls, spaces, authorizer, audit, cancellation,
                categories, metadata, new MetadataCommands(metadata, new ObjectMapper().findAndRegisterModules()), access);

        when(mapper.findOwningSpaceIdForUpdate(DOC)).thenReturn(SOURCE);
        when(mapper.updateDocumentPlacement(DOC, TARGET, null, 0)).thenReturn(1);
        when(authorizer.requireSpaceManager(anyLong())).thenReturn(ADMIN);
        when(spaces.findRawById(TARGET)).thenReturn(space(TARGET, false));
    }

    /**
     * 默认的"一切就绪"调用：声明归属与实际一致，目标空间存在且未归档。
     */
    private DocumentRelocationResult relocate() {
        return service.relocate(DOC, SOURCE, TARGET, null, null, "req-1", null, 0);
    }

    @Test
    void metadataVersionAndTargetCategoryAreCheckedBeforeAnyMutation() {
        document.setMetadataVersion(1L);
        assertThatThrownBy(this::relocate).isInstanceOfSatisfying(IdentityException.class,
                e -> assertThat(e.errorCode()).isEqualTo(IdentityErrorCode.INVALID_VERSION));
        document.setMetadataVersion(0L);
        assertThatThrownBy(() -> service.relocate(DOC, SOURCE, TARGET, null, null, "category", 8L, 0))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.errorCode()).isEqualTo(IdentityErrorCode.INVALID_PARENT));
        verify(mapper, never()).clearAcl(DOC);
    }

    @Test
    void placementUpdatesCategoryAndVersionPreservingGlobalTagsAndLockOrder() {
        when(metadata.category(TARGET, 8)).thenReturn(new CategoryRow(8, TARGET, null, "目录", "目录", 0, null, null));
        when(mapper.updateDocumentPlacement(DOC, TARGET, 8L, 0)).thenReturn(1);
        var result = service.relocate(DOC, SOURCE, TARGET, null, null, "category", 8L, 0);
        assertThat(result.targetCategoryId()).isEqualTo(8);
        assertThat(result.metadataVersion()).isEqualTo(1);
        verify(metadata, never()).clearTags(anyLong());
        var order = inOrder(categories, mapper);
        order.verify(categories).shareTree(SOURCE);
        order.verify(categories).shareTree(TARGET);
        order.verify(mapper).findOwningSpaceIdForUpdate(DOC);
    }

    @Test
    void legacyAuditIdentityCannotBeReusedUnderNewProtocol() {
        when(audit.existing(AuditScope.DOCUMENT_RELOCATE, "req-1")).thenReturn(new IdentityAuditRecord());
        assertThatThrownBy(this::relocate).isInstanceOfSatisfying(IdentityException.class,
                e -> assertThat(e.httpStatus()).isEqualTo(409));
        verify(mapper, never()).clearAcl(DOC);
    }

    @Test
    void strictAuditFailurePropagatesWithoutAcknowledgement() {
        doThrow(new IllegalStateException("审计不可用")).when(audit).appendSuccess(any(), any(), anyLong(), anyLong(), any(), any(), any());
        assertThatThrownBy(this::relocate).isInstanceOf(IllegalStateException.class);
    }

    private static SpaceRecord space(long id, boolean archived) {
        SpaceRecord record = new SpaceRecord();
        record.setId(id);
        record.setArchived(archived);
        record.setVersion(0L);
        return record;
    }

    private void verifyNothingWasRelocated() {
        verify(mapper, never()).updateDocumentPlacement(anyLong(), anyLong(), any(), anyLong());
        verify(mapper, never()).syncChunkSpace(anyLong(), anyLong());
        verify(mapper, never()).clearAcl(anyLong());
        verify(audit, never()).success(any(), anyString(), any(), any(), anyString(), anyString(), anyString());
    }

    private static void assertConflict(Throwable ex, IdentityConflictReason reason) {
        IdentityException failure = (IdentityException) ex;
        assertThat(failure.httpStatus()).isEqualTo(409);
        assertThat(failure.errorCode()).isEqualTo(IdentityErrorCode.CONFLICT);
        if (reason == null) {
            assertThat(failure.reason()).isEmpty();
        } else {
            assertThat(failure.reason()).contains(reason);
        }
    }

    @Test
    void cancelsReviewsUnderOriginalDocumentLockBeforeClearingAllExceptions() {
        relocate();
        var order = inOrder(mapper, cancellation);
        order.verify(mapper).findOwningSpaceIdForUpdate(DOC);
        order.verify(cancellation).cancelDocument(DOC, ADMIN);
        order.verify(mapper).clearAcl(DOC);
        order.verify(mapper).updateDocumentPlacement(DOC, TARGET, null, 0);
        order.verify(mapper).syncChunkSpace(DOC, TARGET);
    }

    @Test
    void cancellationFactFailureCannotAcknowledgeRelocationOrWriteSuccessAudit() {
        doThrow(new IllegalStateException("fact unavailable")).when(cancellation).cancelDocument(DOC, ADMIN);
        assertThatThrownBy(this::relocate).isInstanceOf(IllegalStateException.class);
        verifyNothingWasRelocated();
    }

    @Test
    @DisplayName("更新归属失败保持通用冲突，不继续同步或审计")
    void concurrentDocumentChangeRemainsGenericConflict() {
        when(mapper.updateDocumentPlacement(DOC, TARGET, null, 0)).thenReturn(0);
        assertThatThrownBy(this::relocate)
                .isInstanceOf(IdentityException.class)
                .satisfies(ex -> assertConflict(ex, null));
        verify(mapper, never()).syncChunkSpace(anyLong(), anyLong());
        verify(audit, never()).success(any(), anyString(), any(), any(), anyString(), anyString(), anyString());
    }

    // ================= 2.1 两侧管理资格 =================

    @Nested
    @DisplayName("文档搬迁必须同时具备源空间与目标空间的管理资格")
    class ManageOnBothSides {

        @Test
        @DisplayName("两侧均具备管理资格：归属变更为目标空间，回执返回变更后的归属")
        void bothSidesManageRelocates() {
            DocumentRelocationResult result = relocate();

            assertThat(result.fromSpaceId()).isEqualTo(SOURCE);
            assertThat(result.toSpaceId()).isEqualTo(TARGET);
            assertThat(result.replayed()).isFalse();
            verify(mapper).updateDocumentPlacement(DOC, TARGET, null, 0);
            verify(authorizer).requireSpaceManager(SOURCE);
            verify(authorizer).requireSpaceManager(TARGET);
        }

        @Test
        @DisplayName("只具备源空间管理资格：禁止，归属不变")
        void sourceOnlyIsForbidden() {
            when(authorizer.requireSpaceManager(TARGET))
                    .thenThrow(IdentityException.forbidden("无权管理该空间"));

            assertThatThrownBy(DocumentRelocationServiceTest.this::relocate)
                    .isInstanceOf(IdentityException.class)
                    .hasMessageContaining("目标空间");
            verifyNothingWasRelocated();
        }

        @Test
        @DisplayName("只具备目标空间管理资格：禁止，归属不变，且不再去判目标空间")
        void targetOnlyIsForbidden() {
            when(authorizer.requireSpaceManager(SOURCE))
                    .thenThrow(IdentityException.forbidden("无权管理该空间"));

            assertThatThrownBy(DocumentRelocationServiceTest.this::relocate)
                    .isInstanceOf(IdentityException.class)
                    .hasMessageContaining("源空间");
            verify(authorizer, never()).requireSpaceManager(TARGET);
            verifyNothingWasRelocated();
        }

        @Test
        @DisplayName("只有 edit 资格：禁止，并说明搬迁需要 manage 管理资格")
        void editOnlyIsToldManageIsRequired() {
            when(authorizer.requireSpaceManager(anyLong()))
                    .thenThrow(IdentityException.forbidden("无权管理该空间"));

            assertThatThrownBy(DocumentRelocationServiceTest.this::relocate)
                    .isInstanceOf(IdentityException.class)
                    .hasMessageContaining("manage")
                    .hasMessageContaining("管理资格")
                    .hasMessageContaining("edit");
            verifyNothingWasRelocated();
        }

        /**
         * 这是 {@code fromSpaceId} 方案存在的全部理由：鉴权必须打在实际归属上。
         *
         * <p>若服务端改用声明值鉴权，一个带着过期归属的请求会去校验调用方恰好管得动
         * 的那个空间，于是执行一次他从未打算做的搬迁，并清掉一批他从未看见的例外。
         */
        @Test
        @DisplayName("manage 判定针对实际归属，不针对请求声明的空间")
        void managerCheckTargetsActualOwnerNotDeclaredOne() {
            long declaredButWrong = 99L;

            assertThatThrownBy(() -> service.relocate(DOC, declaredButWrong, TARGET, null, null, "req-1", null, 0))
                    .isInstanceOf(IdentityException.class);

            verify(authorizer).requireSpaceManager(SOURCE);
            verify(authorizer, never()).requireSpaceManager(declaredButWrong);
            verifyNothingWasRelocated();
        }
    }

    // ================= 2.2 幂等与并发冲突 =================

    @Nested
    @DisplayName("搬迁必须幂等并检测并发冲突")
    class IdempotencyAndConflicts {

        /**
         * 首次搬迁已把文档搬到目标空间，重投时服务端读到的实际归属就是目标空间。
         */
        private void simulateAlreadyRelocated() {
            when(mapper.listAclEntries(DOC)).thenReturn(List.of(new DocumentAclEntry("3:5", "view")));
            when(mapper.clearAcl(DOC)).thenReturn(1);
            when(mapper.listViewPrincipals(TARGET)).thenReturn(List.of(new SpaceViewPrincipal((short) 1, 5L)));
            when(acls.findUserClearance(5)).thenReturn((short) 1);
            when(acls.countDocumentAboveClearance(DOC, (short) 1)).thenReturn(1L);
            relocate();
            clearInvocations(mapper, audit, authorizer);
            when(mapper.findOwningSpaceIdForUpdate(DOC)).thenReturn(TARGET);
            document.setSpaceId(TARGET);
            document.setMetadataVersion(1L);
        }

        @Test
        @DisplayName("同一请求标识重复提交：返回首次搬迁的结果，不再改变归属")
        void repeatedRequestReturnsTheFirstResult() {
            simulateAlreadyRelocated();

            DocumentRelocationResult result = relocate();

            assertThat(result.replayed()).isTrue();
            assertThat(result.fromSpaceId()).isEqualTo(SOURCE);
            assertThat(result.toSpaceId()).isEqualTo(TARGET);
            assertThat(result.clearedExceptionCount()).isEqualTo(1);
            assertThat(result.clearedExceptions()).containsExactly("3:5:view");
            assertThat(result.clearanceBlockedPrincipalCount()).isEqualTo(1);
            verify(mapper, never()).updateDocumentPlacement(anyLong(), anyLong(), any(), anyLong());
        }

        /**
         * 重放最危险的那一面：它绝不能再清一次例外。
         *
         * <p>首次搬迁清空例外之后，目标空间的管理员本就该重新授出需要的那几条。
         * 若一次超时重投又执行一遍清空，清掉的正是这些刚授出的授权——而调用方看到的
         * 是一次"成功的重试"。
         */
        @Test
        @DisplayName("重放不再清除例外，搬迁后由新空间管理员授出的例外得以保留")
        void replayDoesNotClearExceptionsGrantedAfterTheRelocation() {
            simulateAlreadyRelocated();

            DocumentRelocationResult result = relocate();

            assertThat(result.replayed()).isTrue();
            verify(mapper, never()).clearAcl(anyLong());
            verify(mapper, never()).listAclEntries(anyLong());
        }

        @Test
        @DisplayName("缺少请求标识：请求错误，一次库都不碰")
        void missingRequestIdIsBadRequest() {
            assertThatThrownBy(() -> service.relocate(DOC, SOURCE, TARGET, null, null, "  ", null, 0))
                    .isInstanceOf(IdentityException.class)
                    .hasMessageContaining("requestId");

            verifyNoInteractions(mapper);
            verifyNoInteractions(authorizer);
        }

        @Test
        @DisplayName("声明的当前所属空间与实际不符：冲突，归属与例外均不变")
        void declaredSourceMismatchIsConflict() {
            when(mapper.findOwningSpaceIdForUpdate(DOC)).thenReturn(ELSEWHERE);

            assertThatThrownBy(DocumentRelocationServiceTest.this::relocate)
                    .isInstanceOf(IdentityException.class)
                    .hasMessageContaining("不在声明的源空间")
                    .satisfies(ex -> assertConflict(ex, IdentityConflictReason.SOURCE_MISMATCH));
            verifyNothingWasRelocated();
        }

        @Test
        @DisplayName("并发搬往不同空间：后一次返回冲突，而不是静默覆盖")
        void concurrentRelocationToAnotherSpaceLosesWithConflict() {
            // 行锁让两次搬迁只能一前一后；后到的那次读到的已是别人写下的归属
            when(mapper.findOwningSpaceIdForUpdate(DOC)).thenReturn(ELSEWHERE);

            assertThatThrownBy(DocumentRelocationServiceTest.this::relocate)
                    .isInstanceOf(IdentityException.class)
                    .hasMessageContaining("可能已被他人搬走");
            verifyNothingWasRelocated();
        }
    }

    // ================= 2.3 目标空间校验 =================

    @Nested
    @DisplayName("目标空间必须是一个可用且不同于当前归属的空间")
    class TargetSpaceValidity {

        @Test
        @DisplayName("目标空间不存在或已删除：未找到，归属不变")
        void missingTargetSpaceIsNotFound() {
            when(spaces.findRawById(TARGET)).thenReturn(null);

            assertThatThrownBy(DocumentRelocationServiceTest.this::relocate)
                    .isInstanceOf(IdentityException.class)
                    .hasMessageContaining("目标空间不存在");
            verifyNothingWasRelocated();
        }

        @Test
        @DisplayName("目标空间已归档：拒绝并说明不能迁入，归属不变")
        void archivedTargetSpaceIsRejected() {
            when(spaces.findRawById(TARGET)).thenReturn(space(TARGET, true));

            assertThatThrownBy(DocumentRelocationServiceTest.this::relocate)
                    .isInstanceOf(IdentityException.class)
                    .hasMessageContaining("不能迁入已归档空间")
                    .satisfies(ex -> assertConflict(ex, IdentityConflictReason.TARGET_ARCHIVED));
            verifyNothingWasRelocated();
        }

        @Test
        @DisplayName("目标空间就是当前归属：拒绝，且例外授权不被清除")
        void targetEqualToCurrentOwnerIsRejectedWithoutClearing() {
            when(mapper.findOwningSpaceIdForUpdate(DOC)).thenReturn(TARGET);

            assertThatThrownBy(DocumentRelocationServiceTest.this::relocate)
                    .isInstanceOf(IdentityException.class)
                    .hasMessageContaining("已在目标空间")
                    .satisfies(ex -> assertConflict(ex, IdentityConflictReason.ALREADY_IN_TARGET));
            verify(mapper, never()).clearAcl(anyLong());
            verifyNothingWasRelocated();
        }

        @Test
        @DisplayName("请求自身就把源与目标写成同一个：请求错误，不读库")
        void sameSourceAndTargetInTheRequestIsBadRequest() {
            assertThatThrownBy(() -> service.relocate(DOC, TARGET, TARGET, null, null, "req-1", null, 0))
                    .isInstanceOf(IdentityException.class)
                    .hasMessageContaining("相同");

            verifyNoInteractions(mapper);
        }

        @Test
        @DisplayName("文档不存在或已删除：未找到，鉴权根本不会发生")
        void missingDocumentIsNotFound() {
            when(mapper.findOwningSpaceIdForUpdate(DOC)).thenReturn(null);

            assertThatThrownBy(DocumentRelocationServiceTest.this::relocate)
                    .isInstanceOf(IdentityException.class)
                    .hasMessageContaining("文档不存在");
            verify(authorizer, never()).requireSpaceManager(anyLong());
            verifyNothingWasRelocated();
        }

        /**
         * 迁出已归档空间是正当的抢救动作，所以服务端<b>不读</b>源空间的归档标记。
         *
         * <p>断言"没去读"比断言"读了但放行"更贴近这条规则：一旦有人为了对称而加上
         * 源空间校验，这个测试就会失败。
         */
        @Test
        @DisplayName("从已归档空间迁出：搬迁成功，源空间的归档状态根本不被查询")
        void relocatingOutOfAnArchivedSpaceIsAllowed() {
            when(spaces.findRawById(SOURCE)).thenReturn(space(SOURCE, true));

            DocumentRelocationResult result = relocate();

            assertThat(result.toSpaceId()).isEqualTo(TARGET);
            verify(mapper).updateDocumentPlacement(DOC, TARGET, null, 0);
            verify(spaces, never()).findRawById(SOURCE);
        }
    }

    // ================= 2.4 清除例外与回执摘要 =================

    @Nested
    @DisplayName("搬迁必须清除文档在原空间下获得的全部例外授权")
    class ClearingExceptions {

        @Test
        @DisplayName("带例外的文档被搬迁：例外全部清除，回执告知条数与摘要")
        void exceptionsAreClearedAndReported() {
            when(mapper.listAclEntries(DOC)).thenReturn(List.of(
                    new DocumentAclEntry("3:5", "view"),
                    new DocumentAclEntry("1:8", "download")));
            when(mapper.clearAcl(DOC)).thenReturn(2);

            DocumentRelocationResult result = relocate();

            assertThat(result.clearedExceptionCount()).isEqualTo(2);
            assertThat(result.clearedExceptions()).containsExactly("3:5:view", "1:8:download");
            assertThat(result.clearedExceptionsTruncated()).isFalse();
            verify(mapper).clearAcl(DOC);
        }

        @Test
        @DisplayName("文档 comment 例外随搬迁一并清除，目标空间不保留原评论资格")
        void commentExceptionsAreClearedLikeEveryOtherException() {
            when(mapper.listAclEntries(DOC)).thenReturn(List.of(
                    new DocumentAclEntry("1:8", "comment"),
                    new DocumentAclEntry("1:8", "view")));
            when(mapper.clearAcl(DOC)).thenReturn(2);

            DocumentRelocationResult result = relocate();

            assertThat(result.clearedExceptionCount()).isEqualTo(2);
            assertThat(result.clearedExceptions()).containsExactly("1:8:comment", "1:8:view");
            verify(mapper).clearAcl(DOC);
        }

        @Test
        @DisplayName("无例外的文档被搬迁：成功，条数为零而不是报错")
        void documentWithoutExceptionsReportsZero() {
            when(mapper.listAclEntries(DOC)).thenReturn(List.of());
            when(mapper.clearAcl(DOC)).thenReturn(0);

            DocumentRelocationResult result = relocate();

            assertThat(result.clearedExceptionCount()).isZero();
            assertThat(result.clearedExceptions()).isEmpty();
            assertThat(result.toSpaceId()).isEqualTo(TARGET);
        }

        /**
         * "搬迁后在新空间重新授出例外"这条场景的可单测部分。
         *
         * <p>{@code DocumentAclService} 的鉴权依据是 {@code findOwningSpaceId} 查出的
         * <b>当前</b>归属。所以只要搬迁把权威归属推进到目标空间、并把旧例外清空，
         * 之后的授予就必然按目标空间的 manage 判定，且不会被残留记录遮住。
         * 端到端那一半需要真实 PG，属于 design 里列出的未验证需求。
         */
        @Test
        @DisplayName("搬迁把权威归属交给目标空间，新授出的例外因此依据目标空间判定")
        void authoritativeOwnershipMovesSoLaterGrantsAreJudgedByTheTargetSpace() {
            when(mapper.listAclEntries(DOC)).thenReturn(List.of(new DocumentAclEntry("3:5", "view")));
            when(mapper.clearAcl(DOC)).thenReturn(1);

            relocate();

            verify(mapper).updateDocumentPlacement(DOC, TARGET, null, 0);
            verify(mapper).clearAcl(DOC);
        }

        @Test
        @DisplayName("例外多于上限：摘要被截断且标记置位，条数仍是真实值")
        void summaryBeyondTheLimitIsTruncatedAndFlagged() {
            int total = DocumentRelocationService.CLEARED_SUMMARY_LIMIT + 3;
            List<DocumentAclEntry> many = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                many.add(new DocumentAclEntry("1:" + i, "view"));
            }
            when(mapper.listAclEntries(DOC)).thenReturn(many);
            when(mapper.clearAcl(DOC)).thenReturn(total);

            DocumentRelocationResult result = relocate();

            assertThat(result.clearedExceptions())
                    .hasSize(DocumentRelocationService.CLEARED_SUMMARY_LIMIT);
            assertThat(result.clearedExceptionsTruncated()).isTrue();
            assertThat(result.clearedExceptionCount())
                    .as("条数是真实清除量，不受摘要上限影响")
                    .isEqualTo(total);
        }
    }

    // ================= 2.5 密级不变与受阻回执 =================

    @Nested
    @DisplayName("搬迁不得改变文档密级")
    class SecurityLevelIsUntouched {

        @Test
        @DisplayName("请求夹带密级变更：拒绝并说明密级调整需要独立操作，什么都不动")
        void requestCarryingASecurityLevelIsRejected() {
            assertThatThrownBy(() -> service.relocate(DOC, SOURCE, TARGET, (short) 3, null, "req-1", null, 0))
                    .isInstanceOf(IdentityException.class)
                    .hasMessageContaining("密级")
                    .hasMessageContaining("独立");

            verifyNoInteractions(mapper);
        }

        @Test
        @DisplayName("目标空间存在密级不足的主体：搬迁成功，回执告知受阻数不为零")
        void principalsBelowClearanceAreReported() {
            when(mapper.listViewPrincipals(TARGET))
                    .thenReturn(List.of(new SpaceViewPrincipal(PrincipalType.DEPT.code(), OUTSIDE_DEPT)));
            when(acls.listAclUserIds(PrincipalType.DEPT.code(), OUTSIDE_DEPT)).thenReturn(List.of(5L));
            when(acls.findUserClearance(5L)).thenReturn((short) 1);
            when(acls.countDocumentAboveClearance(DOC, (short) 1)).thenReturn(1L);

            DocumentRelocationResult result = relocate();

            assertThat(result.toSpaceId()).isEqualTo(TARGET);
            assertThat(result.clearanceBlockedPrincipalCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("目标空间全部主体密级足够：受阻数为零")
        void allPrincipalsClearedReportsZero() {
            when(mapper.listViewPrincipals(TARGET))
                    .thenReturn(List.of(new SpaceViewPrincipal(PrincipalType.DEPT.code(), OUTSIDE_DEPT)));
            when(acls.listAclUserIds(PrincipalType.DEPT.code(), OUTSIDE_DEPT)).thenReturn(List.of(6L));
            when(acls.findUserClearance(6L)).thenReturn((short) 4);
            when(acls.countDocumentAboveClearance(DOC, (short) 4)).thenReturn(0L);

            assertThat(relocate().clearanceBlockedPrincipalCount()).isZero();
        }

        @Test
        @DisplayName("混合密级的部门只要有成员够格就不计入受阻，与授权接口同口径")
        void mixedClearanceDepartmentIsNotCountedAsBlocked() {
            when(mapper.listViewPrincipals(TARGET))
                    .thenReturn(List.of(new SpaceViewPrincipal(PrincipalType.DEPT.code(), OUTSIDE_DEPT)));
            when(acls.listAclUserIds(PrincipalType.DEPT.code(), OUTSIDE_DEPT))
                    .thenReturn(List.of(5L, 6L));
            when(acls.findUserClearance(5L)).thenReturn((short) 1);
            when(acls.findUserClearance(6L)).thenReturn((short) 4);
            when(acls.countDocumentAboveClearance(DOC, (short) 4)).thenReturn(0L);

            assertThat(relocate().clearanceBlockedPrincipalCount())
                    .as("取主体内最高密级，而不是把混合密级的部门整条判为受阻")
                    .isZero();
        }

        /**
         * 冗余副本与权威归属必须一起提交或一起回滚。
         *
         * <p>两者分开提交会留出一个窗口：权威归属已经是目标空间，分块仍按原空间参与
         * 检索——于是原空间的人搜得到、读不到，这正是本变更要消灭的那种不一致。
         */
        @Test
        @DisplayName("分块同步与归属更新在同一事务内，且发生在归属更新之后")
        void chunkSyncSharesTheOwnershipTransaction() throws Exception {
            relocate();

            InOrder ordered = inOrder(mapper);
            ordered.verify(mapper).updateDocumentPlacement(DOC, TARGET, null, 0);
            ordered.verify(mapper).syncChunkSpace(DOC, TARGET);

            Method relocate = DocumentRelocationService.class.getMethod("relocate",
                    long.class, long.class, long.class, Short.class, Long.class, String.class, Long.class, long.class);
            assertThat(relocate.isAnnotationPresent(Transactional.class))
                    .as("半完成的搬迁就是一次越权，所以四步必须共享一个事务边界")
                    .isTrue();
        }
    }

    // ================= 2.6 审计 =================

    @Nested
    @DisplayName("搬迁必须产生不可变审计记录")
    class Auditing {

        @Test
        @DisplayName("成功搬迁的审计含操作者、文档、原空间、目标空间与被清除例外摘要")
        void successfulRelocationIsAudited() {
            when(mapper.listAclEntries(DOC)).thenReturn(List.of(new DocumentAclEntry("3:5", "view")));
            when(mapper.clearAcl(DOC)).thenReturn(1);

            relocate();

            verify(audit).appendSuccess(eq(AuditScope.DOCUMENT_RELOCATE), eq("req-1"), eq(ADMIN), eq(DOC),
                    eq("relocate"), contains("\"spaceId\":" + SOURCE), contains("\"spaceId\":" + TARGET));
            verify(audit).appendSuccess(eq(AuditScope.DOCUMENT_RELOCATE), anyString(), anyLong(), anyLong(),
                    anyString(), contains("\"clearedExceptions\":[\"3:5:view\"]"),
                    contains("\"clearedExceptionCount\":1"));
        }

        @Test
        @DisplayName("因资格不足被拒：不产生成功审计")
        void forbiddenRelocationLeavesNoSuccessRecord() {
            when(authorizer.requireSpaceManager(TARGET))
                    .thenThrow(IdentityException.forbidden("无权管理该空间"));

            assertThatThrownBy(DocumentRelocationServiceTest.this::relocate)
                    .isInstanceOf(IdentityException.class);

            verify(audit, never()).success(any(), anyString(), any(), any(),
                    anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("因目标空间不可用或归属声明不符被拒：不产生成功审计")
        void rejectedByStateLeavesNoSuccessRecord() {
            when(spaces.findRawById(TARGET)).thenReturn(space(TARGET, true));
            assertThatThrownBy(DocumentRelocationServiceTest.this::relocate)
                    .isInstanceOf(IdentityException.class);

            when(spaces.findRawById(TARGET)).thenReturn(space(TARGET, false));
            when(mapper.findOwningSpaceIdForUpdate(DOC)).thenReturn(ELSEWHERE);
            assertThatThrownBy(DocumentRelocationServiceTest.this::relocate)
                    .isInstanceOf(IdentityException.class);

            verify(audit, never()).success(any(), anyString(), any(), any(),
                    anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("重放不产生第二条记录")
        void replayWritesNoSecondRecord() {
            relocate();
            clearInvocations(mapper, audit);
            when(mapper.findOwningSpaceIdForUpdate(DOC)).thenReturn(TARGET);
            document.setSpaceId(TARGET);
            document.setMetadataVersion(1L);

            relocate();

            verify(audit, never()).success(any(), anyString(), any(), any(),
                    anyString(), anyString(), anyString());
        }
    }
}
