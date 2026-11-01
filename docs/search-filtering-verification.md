# 组合搜索筛选离线验证记录

日期：2026-11-01。变更：`search-combined-filtering`。

## 首轮完整离线回归结果

以下是复核前的完整回归证据；后续三个复核问题的修正与最新定向回归见文末“复核修正”。

| 检查 | 执行数 | 失败 | 错误 | 跳过 | 结果 |
| --- | ---: | ---: | ---: | ---: | --- |
| kb-app 单元、组件与架构测试 | 193 | 0 | 0 | 0 | 通过 |
| kb-gateway 内存路由/过滤器测试 | 3 | 0 | 0 | 0 | 通过 |
| kb-ai 内存检索协议测试 | 50 | 0 | 0 | 0 | 通过 |
| kb-web 指定 14 个文件 | 100 | 0 | 0 | 0 | 通过 |
| TypeScript 类型检查 | — | — | — | — | 通过 |
| Vite 静态构建 | — | — | — | — | 通过 |
| OpenSpec strict | — | — | — | — | 通过 |
| git diff --check | — | — | — | — | 通过 |

后端总计 246 项。Maven 最终日志为工作区 `search-filtering-backend.log`，详细报告位于各模块 `target/surefire-reports/`。构建输出在 `kb-web/dist/`；Vite 提示主 chunk 大于 1200 kB，构建成功。

## 实际命令与明确名单

在仓库根目录使用 PowerShell 执行。最后一轮使用 clean 去除移动包路径前的旧 class，再编译运行明确名单；未执行整个仓库的测试集。

```powershell
mvn -o -pl kb-app,kb-gateway,kb-ai -am clean test '-Dtest=SearchFiltersTest,SearchFilterInputTest,SearchInputTest,SearchTextOnlyControllerTest,SearchFilterServiceTest,SearchServiceTest,SearchFallbackTest,QueryConsistencyTest,SearchFileTypeTest,SearchOptionReaderTest,SearchOptionInputTest,SearchOptionServiceTest,SearchOptionExecutionTest,SearchOptionMappingTest,SearchMetricsTest,SearchTransactionTest,SearchResponseProtectionTest,SearchPropertiesTest,SearchJdbcTest,SearchHttpTransportTest,SearchFailureTest,SearchFacadeTest,SearchExecutorTest,SearchExecutorRaceTest,SearchCitationMappingTest,SearchBudgetTest,SearchAdmissionTest,ModuleBoundaryTest,SearchRouteTest,RetrievalClientTest,RetrievalPropertiesTest,MetadataInputTest,MetadataCommandsTest,MetadataAccessTest,MetadataQueryTest,MetadataQueryControllerTest,MetadataControllerTest,CategoryControllerTest,CategoryReadTest,CategoryWriteTest,TagServiceTest,TagControllerTest,MetadataServiceTest,RelocationContextTest,SpaceQueryServiceTest,DocumentRelocationServiceTest,DocumentRelocationControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never' *> '.\search-filtering-backend.log'
```

在 `kb-web/` 目录执行：

```powershell
npm exec vitest run src/search/api.test.ts src/search/selectors.test.tsx src/search/page.test.tsx src/search.test.tsx src/components.test.tsx src/favorites/api.test.ts src/favorites/store.test.ts src/favorites/button.test.tsx src/favorites/documents.test.tsx src/favorites/lifecycle.test.tsx src/favorites/page.test.tsx src/documents/metadata/api.test.ts src/documents/metadata/components.test.tsx src/documents/metadata/query-page.test.tsx
npm run typecheck
npm run build
```

仓库根目录的结构检查：

```powershell
openspec validate search-combined-filtering --strict
git diff --check
```

## 场景与证据

| 场景 | 主要测试 |
| --- | --- |
| 精确长整数、重复/未知字段、空值、分类互斥、时间与 Unicode、实际输入字节上限 | SearchFiltersTest、SearchFilterInputTest、SearchInputTest、SearchOptionInputTest、MetadataInputTest |
| 选择范围不替代权限、Embedding/rerank/profile 降级保持筛选 | SearchFilterServiceTest、SearchFallbackTest、QueryConsistencyTest、SearchServiceTest |
| 空数组/旧请求/降级成功的版本头、固定 R 数组 | SearchFilterInputTest、SearchFilterServiceTest、SearchTextOnlyControllerTest |
| 候选最小投影、分页用途绑定、全局停用标签、隐藏上下文先于树版本反馈 | SearchOptionReaderTest、SearchOptionServiceTest、SearchOptionMappingTest |
| 共享并发、截止、延迟退出、连接/取消/响应限界和固定指标 | SearchOptionExecutionTest、SearchExecutorTest、SearchExecutorRaceTest、SearchJdbcTest、SearchTransactionTest、SearchMetricsTest、SearchFacadeTest |
| 搜索及候选的认证、改密、错误 no-store 与 canary 日志脱敏 | SearchResponseProtectionTest、SearchTextOnlyControllerTest、SearchFailureTest |
| 主路径/候选目标路由、提前拒绝、Retry-After/版本头透传、CORS 暴露 | SearchRouteTest |
| AI 保持原三个字段及无筛选兼容、无服务令牌补齐 | RetrievalClientTest、RetrievalPropertiesTest |
| 前端长整数和纳秒、缺失/冲突版本头取消正文、候选 404 | search/api.test.ts |
| 分类祖先、分页/409、旧空间迟到页、全局停用标签和原用途端点 | search/selectors.test.tsx、documents/metadata/components.test.tsx |
| 草稿/摘要分离、清除不自动提交、换空间保留标签、例外文档、错误独立 | search/page.test.tsx、search.test.tsx |
| 隐藏、离页、身份变化、退出、改密、恢复不重发、未经确认材料不查收藏 | search/page.test.tsx、favorites/lifecycle.test.tsx |
| 同文档收藏去重、撤权清理、原排序与固定版本引用 | favorites/documents.test.tsx、components.test.tsx、SearchCitationMappingTest |
| 用户确认的分层修复、当前空间管理资格与归档事实 | ModuleBoundaryTest、MetadataAccessTest、SpaceQueryServiceTest、所列元数据/搬迁测试 |

## SQL 人工审查范围

审阅 `SearchMapper.xml`、`SearchOptionMapper.xml`，并对照现有文档元数据规则和表关系：

- BM25 与向量在各自 LIMIT 前引用相同的发布、当前版本、空间归档、ACL、文档和分块密级谓词及完整筛选。用户 spaceId 与授权 spaceIds 独立；文档级例外保留。
- 分类递归限定实际空间及八层结构，召回使用 EXISTS；标签逐项 EXISTS 全部匹配，关系不会复制候选或排名。
- 类型 JOIN 指向 `d.current_version_id`，不取最高版本号或待审文件；更新时间使用 `d.updated_at >= updatedFrom`、`< updatedTo`。分类、标签、搬迁及删除/归档均读取当前资料。
- 搜索候选依赖当前已发布版本的实际分块；不要求向量 profile，维护时仍贡献 BM25 候选。分类只补必要祖先，标签不限制 enabled，不使用正文关键词或其他组合条件收窄。
- 分层分页按 sortOrder/id、标签按 id，页大小加一判断后续；路径只查询当前页节点的祖先。共享片段的内部 ACL 引用使用完整命名空间，供跨 Mapper 引用。
- 无新增 DDL、迁移、向量重建。SQL 审查没有执行 SQL、验证索引计划或真实数据库隔离。

## 实施中发现与修正

首轮综合后端检查为 104 项，其中 2 项架构失败、2 项新增映射测试错误。架构失败来自既有元数据服务引用 web 工具、Mapper 引用 service 规则类型，以及文档模块直接引用空间 Mapper；用户确认扩展范围后修复，保留架构约束。映射测试错误来自内存结果行使用无序 Map，已改为与查询列序一致的 LinkedHashMap。

`MetadataInput` 的非 HTTP 解码移至 support，`MetadataBody` 保留请求适配；`DocumentFileTypes` 移至 model；`MetadataAccess` 经空间 service 读取可管理事实。补充当前权限、归档及统一缺失投影的行为测试。最终 clean 回归的全部 246 项通过。

前端首次类型检查发现 4 处测试使用了 ByRoleOptions 不支持的 exact 参数，已修正；最终指定测试、类型检查与构建通过。

全部证据来自进程内单元/组件/架构测试、内存 Mock/Stub、受控时钟或流、模拟 DOM、编译和结构检查。未启动应用、监听 HTTP 端口、真实浏览器、数据库、Redis、MinIO、Docker 或模型服务；未编写文档内容断言。Mapper 结果映射测试只消费替身结果行，结论限于输入、编排、参数及投影，不能替代真实查询行为的证明。

## 复核修正

同日 OpenSpec 复核的三项行为探针暴露以下问题，现已修正并纳入正式回归：

1. 候选授权在原 AUTHORIZATION 预算内执行 `principalOf → requireEnabled → resolve`，同一请求复用同一主体。停用身份返回 403/no-store，并在目录读取和事务建立前拒绝；启用用户的合法空目录仍为 200，下一请求再次确认启用状态。
2. `PermissionService` 的身份解析失败仅记录固定 stage、枚举 category 和 status，不记录异常消息或原因链。`SearchResponseProtectionTest` 使用真实权限服务与控制器、内存身份/Mapper 替身捕获整个 app 日志链，验证 canary 不进入日志或响应。
3. `TagPicker` 的回车只提交标签名称查询，阻止外层表单提交；输入法确认文字时不查询。`search/page.test.tsx` 验证原正文结果、摘要与已选标签保持原状；`search/selectors.test.tsx` 覆盖 search/filter/edit 三种用途与输入法场景。

最新定向回归：后端 49 项、前端 55 项全部通过，失败/错误/跳过均为 0；类型检查和静态构建通过。上述数量是本轮明确名单，不与首轮总数相加。后端日志：`search-filtering-fixes-backend.log`。

仓库根目录执行：

```powershell
mvn -o -pl kb-app -am test '-Dtest=SearchOptionServiceTest,SearchResponseProtectionTest,SearchOptionReaderTest,SearchOptionInputTest,SearchOptionExecutionTest,PermissionServiceTest,SearchServiceTest,SearchFilterServiceTest,SearchTextOnlyControllerTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never' *> '.\search-filtering-fixes-backend.log'
```

`kb-web/` 目录执行：

```powershell
npm exec vitest run src/search/selectors.test.tsx src/search/page.test.tsx src/search.test.tsx src/documents/metadata/components.test.tsx src/favorites/documents.test.tsx
npm run typecheck
npm run build
```
