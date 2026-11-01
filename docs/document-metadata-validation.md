# 文档资料与筛选：离线验证记录

日期：2026-11-01。对应变更：`document-metadata-and-filtering`。

## 首次实施回归结果

| 检查 | 实际结果 |
| --- | --- |
| Java 明确类白名单 | kb-app 249 项、kb-gateway 2 项，共 251 项通过，0 失败、0 错误、0 跳过 |
| Maven 静态打包 | 全部 7 个 reactor 项目成功，测试显式跳过 |
| Vitest 明确文件白名单 | 25 个文件、318 项通过 |
| TypeScript | npm run typecheck 通过 |
| 前端静态构建 | npm run build 通过 |
| OpenSpec 结构 | openspec validate document-metadata-and-filtering --strict 通过 |
| 工作区差异检查 | git diff --check 通过 |

Maven 打包输出了依赖重叠的 shade 提示，前端构建输出了主包大小提示；两项构建退出码均为 0。

## Java 白名单

实际执行以下命令；测试使用仓储/权限替身、内存数据结构、独立 MockMvc 和事务协作器替身：

```powershell
mvn -pl kb-app,kb-gateway -am test '-Dtest=MetadataModelTest,MetadataInputTest,MetadataCommandsTest,MetadataAuditTest,MetadataAccessTest,CategoryReadTest,CategoryWriteTest,CategoryControllerTest,TagPermissionTest,TagServiceTest,TagControllerTest,MetadataServiceTest,MetadataControllerTest,MetadataQueryTest,MetadataQueryControllerTest,RelocationContextTest,PermissionCatalogTest,AdminBootstrapRunnerTest,IdentityManagementFeedbackTest,DocumentCapabilitiesSerializationTest,DocumentCapabilitiesServiceTest,UserResourceControllerTest,SpaceWriteServiceTest,DocumentRelocationControllerTest,DocumentRelocationServiceTest,AuditSummaryProjectorTest,SpaceFunctionalPermissionTest,SpaceDepartmentAuditIsolationTest,DocumentAdminQueryServiceTest,RolePermissionWriteTest,DocumentManagementServiceTest,ReviewResponsePolicyTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn package '-DskipTests'
```

白名单包含新资料/目录测试，以及独立权限与引导、能力序列化、空间生命周期、管理错误投影、搬迁、审计和网关缓存策略回归。补充测试覆盖锁后撤权/搬迁分支、原资料回执重放、审计异常传播及只读事务参数传递。

初次汇总发现 UserResourceControllerTest 的旧响应字段数断言仍为 5，已改为包含 canEditMetadata 的 6 字段并增加该能力断言；上述完整白名单随后全部通过。

## 前端白名单

以下命令在 `kb-web` 目录执行，使用模拟 DOM、Mock 请求和受控 Promise：

```powershell
npm run test -- --run src/documents/metadata/api.test.ts src/documents/metadata/components.test.tsx src/documents/metadata/query-page.test.tsx src/admin/taxonomy.test.tsx src/admin/space-pages.test.tsx src/admin/hooks.test.tsx src/admin/spaceApi.test.ts src/admin/auditApi.test.ts src/admin/audits.test.tsx src/admin/auditQuery.test.tsx src/admin/audit-access.test.tsx src/documents.test.tsx src/reviews/documents.test.tsx src/reviews/lifecycle.test.tsx src/documents/operations.test.tsx src/documents/write-coordination.test.tsx src/documents/deletion.test.ts src/documents/controls.test.tsx src/documents/capabilities.test.ts src/documents/notice.test.ts src/favorites/documents.test.tsx src/admin/access.test.tsx src/admin/access-spaces.test.tsx src/navigation.test.tsx src/routing.test.tsx
npm run typecheck
npm run build
```

覆盖资料编辑与历史版本、停用标签保留、保存删除互斥、会话和迟到响应、筛选草稿/分页及无 URL/存储关键词、分类树失效、标签权限与重试、无正文查看资格下搬迁、显式未分类、新上下文缺失、审计有限数组和现有标量展示。

## 验证边界

迁移 V27/V28、分类复合外键、只追加回执、查询 SQL、锁顺序与事务边界完成静态审查。未运行应用、HTTP 监听、真实浏览器、数据库、缓存、容器、对象存储或模型服务；未执行迁移、SQL、真实事务/锁竞争、查询计划或部署演练。

替身测试证明已覆盖的输入、业务分支和调用生命周期，不证明真实数据库约束、快照、回滚或跨实例并发。说明文档、注释和示例未作为内容断言或执行验收对象；OpenSpec 仅做结构校验。

## 验证反馈修复（2026-11-01）

修复验证报告的三项偏差：登录拦截器异常保持 HTTP 401；搬迁响应丢失后，读取新归属仍可凭原分类、版本和请求标识匹配重试；具体冲突提示在归属刷新及管理资格重核造成的重挂载后保留。

本轮定向回归结果：

| 检查 | 实际结果 |
| --- | --- |
| Java 明确类白名单 | kb-app 6 项、kb-gateway 2 项，共 8 项通过 |
| Vitest 明确文件白名单 | 5 个文件、91 项通过 |
| Maven 静态打包 | 7 个 reactor 项目成功，测试显式跳过 |
| 前端类型检查及静态构建 | npm run build 内的 tsc --noEmit 和 vite build 均通过 |

Java 回归使用独立 MockMvc、SaInterceptor 与静态会话替身，验证控制器执行前的登录拒绝、固定错误及 no-store。前端回归覆盖显式 null 和大整数分类的原绑定重试、读取期间禁用、原源/目标/当前空间撤权，以及完整管理页面五类冲突的恢复、跨文档和身份隔离。

```powershell
mvn -pl kb-app,kb-gateway -am test '-Dtest=MetadataControllerTest,MetadataQueryControllerTest,CategoryControllerTest,TagControllerTest,RelocationContextTest,ReviewResponsePolicyTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn package '-DskipTests'
```

以下命令在 `kb-web` 目录执行：

```powershell
npm run test -- --run src/admin/space-pages.test.tsx src/admin/taxonomy.test.tsx src/admin/hooks.test.tsx src/admin/spaceApi.test.ts src/documents/metadata/components.test.tsx
npm run build
```

本轮按受影响范围执行上述白名单，未重新执行首次实施的完整白名单；上方 251/318 为首次实施记录。数据库运行时验证边界继续适用。
