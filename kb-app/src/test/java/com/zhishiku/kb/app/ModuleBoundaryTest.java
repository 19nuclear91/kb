package com.zhishiku.kb.app;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.Architectures;
import com.zhishiku.kb.app.auth.service.PermissionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块边界校验。
 *
 * <p>模块边界就是《架构决策记录.md》v1 图里的微服务边界。保住它，
 * 未来要拆分才是重构级而非重写级工作量。
 *
 * <p><b>重要前提</b>：ArchUnit 跑在测试阶段，<b>不提供编译期隔离</b>。
 * CI 必须真的执行测试，这些规则才有约束力。
 */
class ModuleBoundaryTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.zhishiku.kb.app");
    }

    @Test
    @DisplayName("共享 embedding 契约仅依赖 JDK 与同包类型")
    void embeddingContractsMustRemainFrameworkIndependent() {
        JavaClasses contracts = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.zhishiku.kb.common.embedding");
        noClasses().should().dependOnClassesThat()
                .resideOutsideOfPackages("java..", "com.zhishiku.kb.common.embedding..")
                .check(contracts);
    }

    @Test
    @DisplayName("共享审核发布契约仅依赖 JDK 与同包类型")
    void reviewContractsMustRemainFrameworkIndependent() {
        JavaClasses contracts = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.zhishiku.kb.common.review");
        noClasses().should().dependOnClassesThat()
                .resideOutsideOfPackages("java..", "com.zhishiku.kb.common.review..")
                .check(contracts);
    }

    @Test
    @DisplayName("模块之间只能通过 service 层交互，不得直接访问其他模块的 mapper")
    void modulesMustNotReachIntoOtherModulesMappers() {
        for (String module : List.of("document", "space", "collaboration", "conversation", "search", "notification", "comment")) {
            for (String target : List.of("document", "space", "collaboration", "notification", "comment")) {
                if (!module.equals(target)) {
                    noClasses().that().resideInAPackage("..app." + module + "..")
                            .should().dependOnClassesThat().resideInAPackage("..app." + target + ".mapper..")
                            .check(classes);
                }
            }
        }
        noClasses().that().resideInAPackage("..app.aiusage..")
                .should().accessClassesThat().resideInAnyPackage("..app.auth.mapper..", "..app.conversation.mapper..")
                .check(classes);
        noClasses().that().resideInAPackage("..app.space..")
                .should().accessClassesThat().resideInAPackage("..app.auth.mapper..")
                .check(classes);
        noClasses().that().resideInAPackage("..app.audit..")
                .should().accessClassesThat().resideInAPackage("..app.auth.mapper..")
                .check(classes);
        noClasses().that().resideInAPackage("..app.conversation..")
                .should().accessClassesThat().resideInAPackage("..app.document.mapper..")
                .check(classes);
        noClasses()
                .that().resideInAPackage("..app.search..")
                .should().accessClassesThat().resideInAPackage("..app.document.mapper..")
                .orShould().accessClassesThat().resideInAPackage("..app.file.mapper..")
                .because("跨模块直接读写对方的数据访问层，会让模块边界名存实亡，未来无法拆分")
                .check(classes);
    }

    @Test
    @DisplayName("通知模块只经 service 契约与同源授权片段取得规则：不引用任何其他模块 mapper，也不调用源业务写服务")
    void notificationsDoNotReachIntoSourceModules() {
        noClasses().that().resideInAPackage("..app.notification..")
                .should().dependOnClassesThat(DescribedPredicate.describe(
                        "其他模块的 mapper",
                        (JavaClass type) -> type.getPackageName().contains(".mapper")
                                && type.getPackageName().startsWith("com.zhishiku.kb.app.")
                                && !type.getPackageName().startsWith("com.zhishiku.kb.app.notification.")))
                .check(classes);
        noClasses().that().resideInAPackage("..app.notification..")
                .should().dependOnClassesThat().resideInAnyPackage("..app.document..", "..app.collaboration..",
                        "..app.space..", "..app.conversation..", "..app.comment..")
                .because("派生失败不得改变源审核或评论；通知只读取协作事实并按当前授权过滤")
                .check(classes);
    }

    @Test
    @DisplayName("评论只经 document 与 collaboration 的 service 契约写入事实，不调用通知、检索、会话或 Embedding")
    void commentsStayDecoupledFromNotificationAndKnowledgeSources() {
        noClasses().that().resideInAPackage("..app.comment..")
                .should().dependOnClassesThat().resideInAnyPackage("..app.notification..", "..app.search..",
                        "..app.conversation..", "..app.embedding..", "..app.aiusage..", "..app.parse..", "..app.storage..",
                        "..app.space..")
                .because("评论提交不得等待通知消费者，正文不得进入索引、Embedding、RAG 或会话来源")
                .check(classes);
        noClasses().that().resideInAPackage("..app.comment..")
                .should().dependOnClassesThat(DescribedPredicate.describe(
                        "其他模块的 mapper",
                        (JavaClass type) -> type.getPackageName().contains(".mapper")
                                && type.getPackageName().startsWith("com.zhishiku.kb.app.")
                                && !type.getPackageName().startsWith("com.zhishiku.kb.app.comment.")))
                .check(classes);
        noClasses().that().resideOutsideOfPackage("..app.comment..")
                .should().dependOnClassesThat().resideInAPackage("..app.comment..")
                .because("其他模块不读取评论正文；通知仅以 SQL 片段引用回复是否可读")
                .check(classes);
    }

    @Test
    @DisplayName("web 层不得直接调用 mapper，必须经过 service")
    void controllersMustNotUseMappersDirectly() {
        noClasses()
                .that().resideInAPackage("..web..")
                .should().accessClassesThat().resideInAPackage("..mapper..")
                .because("绕过 service 层意味着绕过权限解析，这是越权漏洞的常见来源")
                .check(classes);
    }

    @Test
    @DisplayName("分层依赖方向：web → service → mapper，不得反向")
    void layersMustNotBeInverted() {
        Architectures.layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Web").definedBy("..web..")
                .layer("Service").definedBy("..service..")
                .layer("Mapper").definedBy("..mapper..")
                .whereLayer("Web").mayNotBeAccessedByAnyLayer()
                .whereLayer("Service").mayOnlyBeAccessedByLayers("Web")
                .whereLayer("Mapper").mayOnlyBeAccessedByLayers("Service")
                .check(classes);
    }

    @Test
    @DisplayName("主体句柄不得被存为字段：请求内复用不得退化成跨请求缓存")
    void principalHandlesMustNotBeStoredAsFields() {
        noFields().should().haveRawType(PermissionService.PrincipalHandle.class)
                .because("句柄一旦成为字段就会跨请求存活，复用就退化为缓存，"
                        + "与权限事实变更必须在下一次请求即生效的要求冲突")
                .check(classes);
        for (var type : classes) {
            for (var field : type.getFields()) {
                assertThat(field.getType().getAllInvolvedRawTypes())
                        .as("主体句柄不能保存在字段或泛型容器中：%s", field.getFullName())
                        .noneMatch(raw -> raw.isEquivalentTo(PermissionService.PrincipalHandle.class));
            }
        }
    }
}
