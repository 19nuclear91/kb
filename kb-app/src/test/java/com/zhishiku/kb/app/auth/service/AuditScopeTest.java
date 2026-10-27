package com.zhishiku.kb.app.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 操作域命名规范的回归守卫。
 *
 * <p>守的不是风格，而是一条安全不变式：幂等键是
 * {@code (operation_scope, request_id)} 且 request_id 由客户端提供，
 * 所以裸操作域会让两个实体共用同一个幂等命名空间，进而让一个实体的
 * 写操作被当成另一个实体的重试、并返回错误的实体。
 */
class AuditScopeTest {

    @Test
    void roleChangesHaveDistinctRoleNamespaces() {
        var scopes = List.of(AuditScope.ROLE_CREATE, AuditScope.ROLE_RENAME,
                AuditScope.ROLE_DELETE, AuditScope.ROLE_PERMISSION_GRANT, AuditScope.ROLE_PERMISSION_REVOKE);
        assertThat(scopes).doesNotHaveDuplicates();
        for (var scope : scopes) {
            assertThat(scope.name()).startsWith("ROLE_");
            assertThat(scope.targetType()).isEqualTo("role");
            assertThat(scope).isNotIn(AuditScope.USER_ROLE_ASSIGN, AuditScope.USER_ROLE_REVOKE,
                    AuditScope.DEPARTMENT_CREATE, AuditScope.SPACE_CREATE);
        }
    }

    /**
     * 已登记的实体前缀。新增实体时在此登记，而不是绕开规范。
     */
    private static final Set<String> ENTITY_PREFIXES =
            Set.of("USER", "EXTERNAL", "DEPARTMENT", "SPACE", "DOC", "DOCUMENT", "PARSE",
                    "AUTH", "ROLE", "SSO");

    @Test
    @DisplayName("每个操作域都必须带已登记的实体前缀，不得是裸动词")
    void everyScopeCarriesARegisteredEntityPrefix() {
        for (AuditScope scope : AuditScope.values()) {
            String name = scope.name();
            assertThat(name)
                    .as("操作域 %s 必须形如 <实体>_<操作>", name)
                    .contains("_");
            String prefix = name.substring(0, name.indexOf('_'));
            assertThat(ENTITY_PREFIXES)
                    .as("操作域 %s 的实体前缀 %s 未登记——裸动词会造成跨实体幂等碰撞", name, prefix)
                    .contains(prefix);
        }
    }

    @Test
    @DisplayName("同一实体类型下的操作域必须共用同一前缀")
    void scopesOfTheSameTargetTypeShareAPrefix() {
        // SSO 按认证意图区分操作域，仍以内部 user 为目标；生命周期操作继续保持原前缀不变式。
        var byTarget = Arrays.stream(AuditScope.values())
                .filter(scope -> scope != AuditScope.SSO_LOGIN && scope != AuditScope.SSO_BIND)
                .collect(Collectors.groupingBy(AuditScope::targetType,
                        Collectors.mapping(s -> s.name().substring(0, s.name().indexOf('_')),
                                Collectors.toSet())));

        byTarget.forEach((targetType, prefixes) -> assertThat(prefixes)
                .as("实体 %s 的操作域前缀不一致: %s", targetType, prefixes)
                .hasSize(1));
        assertThat(AuditScope.SSO_LOGIN.targetType()).isEqualTo("user");
        assertThat(AuditScope.SSO_BIND.targetType()).isEqualTo("user");
    }

    @Test
    @DisplayName("每个操作域都要声明目标实体类型")
    void everyScopeDeclaresATargetType() {
        for (AuditScope scope : AuditScope.values()) {
            assertThat(scope.targetType())
                    .as("操作域 %s 的 targetType", scope)
                    .isNotBlank();
        }
    }

    /**
     * 文档搬迁的操作域不能退化成裸动词，也不能复用 {@code DOC_ACL_*} 的 {@code doc-acl}。
     *
     * <p>搬迁的目标实体是文档本身而不是它的例外授权名单：按文档检索审计时，
     * 若搬迁事件挂在 {@code doc-acl} 上，就会从"这篇文档发生过什么"的结果里漏掉
     * ——而搬迁恰好是让这篇文档的全部例外一次性失效的那个动作。
     */
    @Test
    @DisplayName("文档搬迁的操作域带 DOCUMENT_ 前缀，目标实体是 document")
    void documentRelocateCarriesDocumentPrefixAndTargetType() {
        assertThat(AuditScope.DOCUMENT_RELOCATE.name())
                .as("搬迁操作域必须形如 DOCUMENT_<操作>")
                .startsWith("DOCUMENT_");
        assertThat(AuditScope.DOCUMENT_RELOCATE.targetType())
                .as("搬迁的目标实体是文档，不是文档的例外授权名单")
                .isEqualTo("document");
    }

    /**
     * 用户密级调整挂在用户上，且升降共用一个操作域。
     *
     * <p>两条断言各守一件事。前缀与 targetType 守的是检索：密级变更的目标实体是用户
     * 本身，若挂到别的实体上，"这个账户的密级被谁改过"就查不出来——而密级恰好是
     * 检索侧那条"与"关系里唯一会凭一次写操作放宽可见范围的因子。
     *
     * <p>"只有一个密级操作域"守的是幂等：幂等键是
     * {@code (operation_scope, request_id)}，拆成升/降两个域就把方向写进了键，
     * 同一 request_id 的重试若被判成反方向便不再命中首次结果，一次重试变成一次新写入。
     */
    @Test
    @DisplayName("用户密级调整带 USER_ 前缀、目标实体是 user，且升降不拆成两个域")
    void userClearanceChangeCarriesUserPrefixAndTargetType() {
        assertThat(AuditScope.USER_CLEARANCE_CHANGE.name())
                .as("密级调整操作域必须形如 USER_<操作>")
                .startsWith("USER_");
        assertThat(AuditScope.USER_CLEARANCE_CHANGE.targetType())
                .as("密级调整的目标实体是用户账户本身")
                .isEqualTo("user");

        var clearanceScopes = Arrays.stream(AuditScope.values())
                .map(AuditScope::name)
                .filter(name -> name.contains("CLEARANCE"))
                .toList();
        assertThat(clearanceScopes)
                .as("""
                        密级调整只能有一个操作域。拆成升/降会把方向写进幂等键
                        (operation_scope, request_id)，使同一 request_id 的重试
                        在反方向上不再命中首次结果，变成一次新的写入。
                        方向应从审计明细的前后密级读出，不参与幂等判定。""")
                .containsExactly("USER_CLEARANCE_CHANGE");
    }

    /**
     * 登录成败分成两个操作域，目标实体是 auth。
     *
     * <p>分开而不是靠 status 过滤：request_id 由服务端生成，成败事件永不共享
     * 幂等键，查询"最近的失败尝试"时语义直接落在 scope 上；若合并成一个域，
     * 这个查询就多了一层易被改错的过滤条件。
     */
    @Test
    @DisplayName("登录成败各占一个操作域，目标实体是 auth")
    void authLoginScopesAreSeparateAndCarryAuthTargetType() {
        assertThat(AuditScope.AUTH_LOGIN_SUCCESS.name()).startsWith("AUTH_");
        assertThat(AuditScope.AUTH_LOGIN_FAILURE.name()).startsWith("AUTH_");
        assertThat(AuditScope.AUTH_LOGIN_SUCCESS.targetType()).isEqualTo("auth");
        assertThat(AuditScope.AUTH_LOGIN_FAILURE.targetType()).isEqualTo("auth");
    }
}
