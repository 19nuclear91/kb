package com.zhishiku.kb.app.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产代码不得使用 Sa-Token 的 permission 语义。
 *
 * <p><b>这个测试守的是一颗哑雷，不是一条洁癖规则。</b>
 * {@code SaTokenRoleProvider.getPermissionList} 固定返回空列表——当前正确，因为
 * 没有任何代码用它。但一旦有人写下第一行 {@code StpUtil.checkPermission("doc:read")}，
 * 他会得到<b>静默恒假</b>的判定：不抛异常、不打日志，只是永远拒绝。
 * 那与本变更修复的角色接线缺陷完全同形，而且同样能被 mock 掉而测试全绿。
 *
 * <p>所以把它拦在这里。真要引入 permission 语义，<b>先让这个测试失败</b>，
 * 再去 {@code SaTokenRoleProvider} 实现真实的权限来源，不要绕过它。
 *
 * <p>本项目的细粒度权限不走框架层的字符串权限：空间 ACL、文档例外和密级都下推到
 * SQL 里过滤（见 {@code PermissionService} 与 {@code SearchMapper}）。那是刻意的选择
 * ——权限必须和检索在同一条 SQL 里成立，不能先召回再过滤。
 */
class SaTokenPermissionApiGuardTest {

    /**
     * Sa-Token 的包前缀。限定 owner 才不会误报同名的业务类（PermissionService 等）。
     */
    private static final String SA_TOKEN_PACKAGE = "cn.dev33.satoken";

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.zhishiku.kb.app");
    }

    @Test
    @DisplayName("不得调用 Sa-Token 的 permission API——它当前恒返回空，判定会静默恒假")
    void productionCodeMustNotUseSaTokenPermissionApi() {
        List<String> violations = productionClasses.stream()
                .flatMap(javaClass -> javaClass.getMethodCallsFromSelf().stream())
                .filter(SaTokenPermissionApiGuardTest::isSaTokenPermissionCall)
                .map(call -> call.getOriginOwner().getName()
                        + " -> " + call.getTarget().getOwner().getSimpleName()
                        + "." + call.getTarget().getName())
                .distinct()
                .sorted()
                .toList();

        assertThat(violations)
                .as("""
                        发现对 Sa-Token permission API 的调用。
                        SaTokenRoleProvider.getPermissionList 固定返回空列表，因此这些调用
                        会静默恒为假——不报错、只是永远拒绝。
                        要用 permission 语义，先在 SaTokenRoleProvider 实现真实来源，
                        再放开本断言；不要直接删掉它。
                        若本意是文档或空间级权限，应走 PermissionService 的 SQL 下推。""")
                .isEmpty();
    }

    private static boolean isSaTokenPermissionCall(JavaMethodCall call) {
        JavaClass owner = call.getTarget().getOwner();
        return owner.getPackageName().startsWith(SA_TOKEN_PACKAGE)
                && call.getTarget().getName().contains("Permission");
    }

    @Test
    @DisplayName("角色 API 仍然可用，本断言只针对 permission")
    void roleApiRemainsAllowed() {
        List<JavaMethodCall> roleCalls = new ClassFileImporter().importClass(RoleApiFixture.class)
                .getMethodCallsFromSelf().stream()
                .filter(call -> call.getTarget().getOwner().getPackageName().startsWith(SA_TOKEN_PACKAGE))
                .filter(call -> call.getTarget().getName().contains("Role"))
                .toList();

        assertThat(roleCalls)
                .as("角色 API 不在禁用范围内，不要求生产代码必须使用它")
                .isNotEmpty();
        assertThat(roleCalls).noneMatch(SaTokenPermissionApiGuardTest::isSaTokenPermissionCall);
    }

    /**
     * 仅供字节码分类测试使用，不执行，也不向生产代码引入无用调用。
     */
    static class RoleApiFixture {
        boolean hasRole() {
            return StpUtil.hasRole("ROLE_TEST");
        }
    }
}
