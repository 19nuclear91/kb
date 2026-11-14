package com.zhishiku.kb.common.config;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 仅解析本变更相关主代码的 import、大括号和多行体；不检查注释、Schema 或说明文档。 */
class JavaSourceConventionTest {
    private static final Set<String> RELATED = Set.of("ProductionSettings", "KnowledgeGapEndpoints", "NotificationContract",
            "Permission", "GlobalExceptionHandler", "PrivateApiResponse", "KnowledgeGapResponseFilter", "NotificationResponseFilter",
            "PublishedDocumentMapper", "PublishedDocumentReference", "PublishedDocumentQueryService", "KnowledgeGapResponsePolicy",
            "NotificationResponsePolicy", "NotificationDerivationMapper", "NotificationRow", "NotificationTarget", "NotificationType",
            "NotificationDerivationScheduler", "NotificationDerivationService", "NotificationDerivationTransaction", "NotificationMetrics",
            "UserNotificationService", "NotificationController", "NotificationInput", "NotificationSourceFact", "NotificationTargetJson");

    @Test
    void gapAndAffectedProductionSourcesUseExplicitImportsAndMultilineBracedBodies() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("kb-common/src"))) {
            root = root.getParent();
            assertThat(root).as("必须在项目内执行源码检查").isNotNull();
        }
        var sources = new ArrayList<Path>();
        for (String module : List.of("kb-common", "kb-app", "kb-gateway")) {
            try (var paths = Files.walk(root.resolve(module + "/src/main/java"))) {
                sources.addAll(paths.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> path.toString().contains("knowledgegap")
                                || path.getFileName().toString().startsWith("KnowledgeGap")
                                || path.getFileName().toString().startsWith("GapNotification")
                                || RELATED.contains(path.getFileName().toString().replace(".java", ""))).toList());
            }
        }
        assertThat(sources).hasSizeGreaterThan(40);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("源码 AST 检查需要 JDK").isNotNull();
        var failures = new ArrayList<String>();
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none"), null,
                    manager.getJavaFileObjectsFromPaths(sources));
            var positions = Trees.instance(task).getSourcePositions();
            for (CompilationUnitTree unit : task.parse()) {
                new TreeScanner<Void, Void>() {
                    private void problem(Tree tree, String rule) {
                        failures.add(unit.getSourceFile().getName() + ":"
                                + unit.getLineMap().getLineNumber(positions.getStartPosition(unit, tree)) + " " + rule);
                    }

                    private void multiline(BlockTree body) {
                        if (body != null && unit.getLineMap().getLineNumber(positions.getStartPosition(unit, body))
                                == unit.getLineMap().getLineNumber(positions.getEndPosition(unit, body) - 1)) {
                            problem(body, "方法或控制流体不得压缩为单行");
                        }
                    }

                    private void braced(StatementTree body) {
                        if (body instanceof BlockTree block) {
                            multiline(block);
                        } else {
                            problem(body, "控制流必须使用大括号");
                        }
                    }

                    @Override
                    public Void visitImport(ImportTree tree, Void unused) {
                        if (tree.getQualifiedIdentifier().toString().endsWith(".*")) {
                            problem(tree, "禁止通配符 import");
                        }
                        return super.visitImport(tree, unused);
                    }

                    @Override
                    public Void visitMethod(MethodTree tree, Void unused) {
                        multiline(tree.getBody());
                        return super.visitMethod(tree, unused);
                    }

                    @Override
                    public Void visitIf(IfTree tree, Void unused) {
                        braced(tree.getThenStatement());
                        if (tree.getElseStatement() != null && !(tree.getElseStatement() instanceof IfTree)) {
                            braced(tree.getElseStatement());
                        }
                        return super.visitIf(tree, unused);
                    }

                    @Override
                    public Void visitForLoop(ForLoopTree tree, Void unused) {
                        braced(tree.getStatement());
                        return super.visitForLoop(tree, unused);
                    }

                    @Override
                    public Void visitEnhancedForLoop(EnhancedForLoopTree tree, Void unused) {
                        braced(tree.getStatement());
                        return super.visitEnhancedForLoop(tree, unused);
                    }

                    @Override
                    public Void visitWhileLoop(WhileLoopTree tree, Void unused) {
                        braced(tree.getStatement());
                        return super.visitWhileLoop(tree, unused);
                    }

                    @Override
                    public Void visitDoWhileLoop(DoWhileLoopTree tree, Void unused) {
                        braced(tree.getStatement());
                        return super.visitDoWhileLoop(tree, unused);
                    }
                }.scan(unit, null);
            }
        }
        assertThat(failures).isEmpty();
    }
}
