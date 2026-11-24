package com.zhishiku.kb.app.search.term;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** 仅检查编译后依赖，不运行应用，也不检查源文件、文档或 SQL 文本。 */
class TermMaintenanceBoundaryTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.zhishiku.kb.app");

    @Test
    void onlyTwoManualWriteServicesCanInvokeTerminologyAuditProducer() {
        noClasses().that().doNotHaveFullyQualifiedName(TermAudit.class.getName())
                .and().doNotHaveFullyQualifiedName(TermWriteService.class.getName())
                .and().doNotHaveFullyQualifiedName(CorrectionWriteService.class.getName())
                .should().dependOnClassesThat().haveFullyQualifiedName(TermAudit.class.getName()).check(CLASSES);
    }

    @Test
    void ordinarySearchAndAssistanceCannotCallMaintenanceOrPersistentAudit() {
        noClasses().that().resideInAPackage("..search..").and().resideOutsideOfPackage("..search.term..")
                .should().dependOnClassesThat().haveFullyQualifiedName(TermWriteService.class.getName()).check(CLASSES);
        noClasses().that().resideInAPackage("..search..").and().resideOutsideOfPackage("..search.term..")
                .should().dependOnClassesThat().haveFullyQualifiedName(CorrectionWriteService.class.getName()).check(CLASSES);
        noClasses().that().resideInAPackage("..search..").and().resideOutsideOfPackage("..search.term..")
                .should().dependOnClassesThat().haveFullyQualifiedName(IdentityAuditService.class.getName()).check(CLASSES);
    }
}
