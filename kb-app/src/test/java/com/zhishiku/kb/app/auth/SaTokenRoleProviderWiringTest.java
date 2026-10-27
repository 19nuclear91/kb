package com.zhishiku.kb.app.auth;

import cn.dev33.satoken.stp.StpInterface;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 功能角色接线的装配级防线。
 *
 * <p>Sa-Token 找不到 {@link StpInterface} 实现时并不报错，而是回落到
 * {@code StpInterfaceDefaultImpl}——它的 {@code getRoleList} 字节码就是
 * {@code return new ArrayList<>()}。后果是 {@code checkRole("ROLE_ADMIN")} 恒为假，
 * 全部管理端点对任何人 403，而日志里什么异常都没有。
 *
 * <p>这个缺陷真实存在过，且在 65 个测试全绿的情况下活了下来：当时所有相关用例都
 * {@code mock(IdentityManagementAuthorizer.class)}，恰好把唯一接触 Sa-Token 的那一层
 * 盖住了。行为层面的断言在 {@code SaTokenRoleProviderTest}；这里守的是<b>注册</b>——
 * 实现类还在、且 Spring 还能发现它。
 *
 * <p>做法与 {@code ModuleBoundaryTest} 一致：ArchUnit 静态分析，不启动 Spring 上下文，
 * 因此不需要 PostgreSQL 或 Redis。<b>注意不用 {@code classes().that()...should()} 规则</b>：
 * 没有匹配类时规则是否失败取决于 ArchUnit 的空规则策略，而"没有匹配类"恰恰是本测试
 * 唯一要抓的情况，不能把它交给默认值。
 */
class SaTokenRoleProviderWiringTest {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.zhishiku.kb.app");
    }

    private static List<JavaClass> stpInterfaceImplementations() {
        return productionClasses.stream()
                .filter(javaClass -> !javaClass.isInterface())
                .filter(javaClass -> javaClass.isAssignableTo(StpInterface.class))
                .toList();
    }

    @Test
    @DisplayName("必须存在 StpInterface 实现——否则角色判定静默恒为假")
    void anImplementationMustExist() {
        assertThat(stpInterfaceImplementations())
                .as("""
                        kb-app 里找不到 StpInterface 实现。
                        Sa-Token 会回落到返回空角色列表的默认实现，于是
                        checkRole("ROLE_ADMIN") 恒为假，/api/admin/** 对所有人 403，
                        且不会抛出任何异常。""")
                .isNotEmpty();
    }

    @Test
    @DisplayName("实现必须被 Spring 发现，光有类不够")
    void implementationMustBeRegisteredWithSpring() {
        for (JavaClass implementation : stpInterfaceImplementations()) {
            boolean discoverable = implementation.isAnnotatedWith(Component.class)
                    || implementation.isMetaAnnotatedWith(Component.class);

            assertThat(discoverable)
                    .as("""
                                    %s 实现了 StpInterface 但没有 Spring 组件注解，
                                    容器不会注册它，Sa-Token 仍然拿不到角色。
                                    若改用 @Bean 工厂方法注册，请扩展本测试而不是删掉它。""",
                            implementation.getName())
                    .isTrue();
        }
    }
}
