# Java 代码规范治理

## 约定

- 主代码和测试代码均禁止普通、静态通配符导入；使用显式 import。
- 代码中的类型优先使用简单类名。同名冲突可保留全限定类名；字符串形式的反射类名、SQL 映射命名空间和文档引用不做替换。
- 所有 record 使用 Javadoc 描述用途，并使用 `@param` 描述每个组件。优先保留原有业务说明，补充单位、版本和租约身份语义。
- HTTP 入参、出参及嵌套 record 使用类型级和组件级 `@Schema`；文档不得擅自新增实际不存在的校验规则。
- `kb-common.embedding` 保持纯 JDK 契约。管理接口通过 `EmbeddingProfileView` 暴露同名字段，不向核心契约引入 Swagger 注解依赖。

## 自动检查

不使用单元测试约束 README、运维文档的措辞、章节编号或 Javadoc 排版。常规 Java 代码风格由代码审查保证；已归档的 [`knowledge-gap-feedback-loop` 任务](../openspec/changes/archive/2026-11-14-knowledge-gap-feedback-loop/tasks.md)另要求运行代码规范检查，范围见下段。

2026-10-27 删除了原 `JavaSourceConventionTest`（用编译器语法树扫描全部源文件，包含 Javadoc/Schema 文档断言）。2026-11-14 为知识问题单任务新增同名、不同范围的纯代码检查：仅解析本变更相关主代码的显式 import、大括号和多行体，不检查注释、全限定类名、Javadoc、Schema 或任何说明文档。它不代表全库旧规范检查已恢复或全部源文件已通过；下文旧记录保留其历史含义。

保留的检查都针对运行时行为或契约，而不是源文件文本：`RecordOpenApiSchemaTest` 通过真实 Swagger 转换器验证密级和 Embedding
接口模型（含嵌套引用），并比较核心配置与视图 DTO 的 JSON，防止字段契约漂移；`ModuleBoundaryTest` 用 ArchUnit 检查编译后类之间的模块依赖。

```powershell
mvn -o -pl kb-app -am test '-Dtest=RecordOpenApiSchemaTest,ModuleBoundaryTest,EmbeddingAdminReadTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

## 验证记录（2026-10-08）

- 全模块主代码及测试代码编译通过。
- 全模块回归执行 706 项：701 通过、2 失败、3 跳过；随后新增的 JSON 兼容性用例在定向测试中通过，统计不与前轮叠加。
- 两个失败分别为 `RoleResolutionDocumentationTest.readmeMarksRoleAndClearanceAsImplementedButUnverified` 与
  `ArchivedDocumentAccessTest.everyReadStatementAndBothRetrievalBranchesKeepTheArchiveGuard`。前者期望 README
  包含旧定位文字，后者未提供当前向量分支所需的配置身份，导致只生成 BM25 分支。
- 将这两个测试的治理前备份复制到隔离项目后，同样复现失败。本次不调整其断言，不修改 README、检索 SQL 或业务逻辑来规避失败。
- 全模块回归为收集所有模块结果使用了 `-Dmaven.test.failure.ignore=true`，其 `BUILD SUCCESS` 不代表测试全部通过。
- 日志：`target/java-style/tests-all-modules.log`、`target/java-style/baseline-failures-2.log`、
  `target/java-style/final-focused-2.log`、`target/java-style/convention-final.log`。
- 未执行真实 PostgreSQL、MinIO、Redis 或模型服务验收。

以上为治理完成时的历史结果；后续文案测试清理已删除 `RoleResolutionDocumentationTest`。构建产物中的历史日志会被 `mvn clean`
清理。2026-10-11 的文本断言测试清理删除了 `ArchivedDocumentAccessTest`
中唯一文本断言用例及全仓库其余同类文本断言测试（见末节），此前为绕过该既存失败而临时排除测试类的打包命令随之作废；项目从未设置永久排除项。清理前排除两个测试类的打包曾验证通过：执行
701 项，698 通过、3 跳过、0 失败，五个模块打包成功；不代表完整测试集全绿。

## 文案测试清理验证（2026-10-08）

删除三个纯文案测试类，共 12 个测试；搬迁控制器测试去掉 2 个文案与排版测试，并将剩余元数据检查改为反射验证。Embedding
迁移测试仅去掉运维文案断言，保留迁移与注册安全检查。离线测试白名单同步清理。

执行 `mvn -o clean test '-Dmaven.test.failure.ignore=true'`，确保已删除测试的旧 class 不再运行：共 694 项，690 通过、1 失败、3
跳过。唯一失败仍为既有的 `ArchivedDocumentAccessTest.everyReadStatementAndBothRetrievalBranchesKeepTheArchiveGuard`
；为收集全部模块结果而忽略失败，不能将构建成功理解为全绿。日志为 `target/documentation-test-cleanup.log`。

## 文本断言测试清理（2026-10-11）

按项目要求删除全部"针对文档/文本"的测试：读取源码、mapper XML、迁移 SQL、注解 SQL
值或配置文件并断言文本内容的用例整类移除，只保留执行行为的测试。共删除纯文本断言测试类 18 个（kb-app 15、kb-worker
2、kb-gateway 1，36 个用例），并从 14 个混合测试类中剥离文本断言（整方法删除 8 个、方法内尾随断言剥离 8 处、3 个方法改名），此前长期失败的
`ArchivedDocumentAccessTest.everyReadStatementAndBothRetrievalBranchesKeepTheArchiveGuard`
随该清理一并删除。保留范围：执行行为的用例、反射式接口元数据与序列化契约（`DocumentRelocationControllerDocsTest`、
`RecordOpenApiSchemaTest`）、架构与规范检查（`ModuleBoundaryTest`、`JavaSourceConventionTest`）及类路径能力检查（
`NoDatabaseAccessTest`）。被删与被改文件的原件备份在 `D:\tmp\kb-removed-tests`；`README.md`、
`docs/document-security-level.md` 与 `SpaceWriteServiceTest` 中引用已删测试的表述同步修正。

执行 `mvn -o install '-Dmaven.test.failure.ignore=true'`：kb-common 35、kb-app 570、kb-gateway 5、kb-worker 113、kb-ai 88，合计
811 项，0 失败，3 跳过（两个 PostgreSQL 冒烟测试按设计以 assumption 跳过）；五个模块构建与打包成功。日志为
`D:\tmp\kb-build-2026-10-11.log`。
