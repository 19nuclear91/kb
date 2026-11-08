# Tasks

验证范围：仅执行类型检查、Vitest/jsdom 单元测试（内存路由、API 替身、可控 Promise）、生产构建与包体报告；不启动后端、数据库、Redis、MinIO 或外部服务。下文 `test:vue` 指在 `kb-web` 目录执行 `npm run test:vue -- --run <文件>`，迁移文件的用例标题与数量须与原 `*.test.tsx` 一致，差异登记到 `test-inventory.md`。

## 1. 基线与冻结

- [x] 1.1 在团队约定处登记 `kb-web` 冻结起点提交；将 `typescript` 改为 6.x 最新稳定版并更新锁文件，验证 React 版 `npm run typecheck`、`npm test -- --run`、`npm run build` 全部通过且用例总数与切换前一致。
- [x] 1.2 生成 React 版用例清单（每个测试文件的用例标题列表与计数）写入变更目录 `test-inventory.md`；验证清单文件数与 `src/**/*.test.{ts,tsx}` 文件数一致。
- [x] 1.3 按 `kb-web/README.md` 的包体流程对 React 版构建并生成正式基线，保存为变更目录 `bundle-baseline.json`（含锁文件摘要、Node/npm、公开开关）；验证 `npm run bundle:report -- --check-isolation` 通过。

## 2. 并行工程与工具链

- [x] 2.1 安装 `vue`、`vue-router`、`pinia`、`element-plus`、`@vitejs/plugin-vue`、`vue-tsc`、`@testing-library/vue`、`@vue/test-utils`、`unplugin-element-plus`（精确版本），新增 `vite.vue.config.ts`、`tsconfig.vue.json`、`index.vue.html`、`src-vue/env.d.ts` 及 `dev:vue`/`typecheck:vue`/`test:vue`/`build:vue` 脚本；验证空壳应用 `typecheck:vue` 与 `build:vue` 成功，且 React 版 1.1 的三项命令仍通过。
- [x] 2.2 将纯 TS 核心（`api.ts`、`session.ts`、`logic.ts`、`stream.ts`、`search.ts`、`navigation.ts`、`types.ts`、各领域 `api.ts`/`store.ts`/`flow.ts`/`storage.ts`/`operations.ts`/`deletion.ts`/`capabilities.ts`/`notice.ts`/`types.ts`、`test/fixtures*.ts`）按原路径复制到 `src-vue/`，`UnreadPoller` 从 `unread.tsx` 拆为纯 TS 模块；复制对应纯逻辑 `*.test.ts`；验证这些测试在 `test:vue` 下全部通过且用例与清单一致。
- [x] 2.3 编写 `src-vue/test/setup.ts`（沿用 matchMedia/ResizeObserver/fetch 禁止替身与 cleanup）与 `test/render.ts`（`renderApp()` 挂载 Pinia、`createMemoryHistory` 路由与 Session 替身）及 `chooseOption` 等 Element Plus 交互辅助；验证一个示例组件测试可通过辅助渲染并完成 Select 选择与 Dialog 关闭。
- [x] 2.4 在 `styles.css` 迁移全局样式并以 CSS 变量覆盖 Element Plus 主色及派生色、圆角和字体，根组件提供 `ElConfigProvider` 中文语言包；新增扫描 `src-vue/**/*.vue` 禁止 `v-html` 的测试并验证其通过且对含 `v-html` 的夹具失败。

## 3. 桥接层、stores 与 composables

- [x] 3.1 实现 `useExternalStore` 桥接与 `stores/session`、`SessionProvider.ts` 的 `useSession()` facade（焦点/可见性刷新与挂起、handoff 语义），迁移 `session.test.ts` 相关绑定用例及 `lifecycle.test.tsx`；验证 `test:vue` 通过，且替身 `context` 用 `reactive()` 即可驱动组件更新。
- [x] 3.2 实现 `stores/adminAccess`（`attach/detach`、`loadSpaces`、epoch 作废、未 attach 时不放行）与 `useAdminAccess()`，迁移 `admin/access.test.tsx` 中资格读取与 `admin/access-spaces.test.tsx`；验证用例全部通过。
- [x] 3.3 实现 `useUnreadCount()`、`UnreadBadge` 与 `useSso()`（直接桥接按 Session 的注册表），迁移 `notifications/unread.test.tsx`、`sso/provider.test.tsx`；验证轮询的挂起、离线、epoch 切换用例通过。
- [x] 3.4 将 `useResource`、`useProgress`、`useFavorites`、文档操作 provide/inject（`useDocumentOperations`）改写为 composables，迁移 `favorites/store.test.ts`（如有绑定）与 `documents/operations.test.tsx`、`documents/write-coordination.test.tsx`；验证迟到响应丢弃、中止与互斥用例通过。
- [x] 3.5 改写 `admin/hooks.ts`（`useAdminWrite`、`useAdminResource`、`useCursorList`，保留 `Binding` 非响应式与 `deniedReads`/`sending` WeakMap），迁移 `admin/hooks.test.tsx`；验证 14 项及清单内全部用例通过。
- [x] 3.6 将 `components.tsx` 拆为 `PageHeader`、`Feedback`、`SafeText`、`References`、`TurnCard` SFC，新增 `KbAlert`（不可关闭、action 插槽）与 `KbSpin`，迁移 `components.test.tsx` 中通用组件用例；验证用例通过。该文件其余用例分别随 5.1（登录、改密、账户）、6.2（预览、引用）、8.1（搜索）补入同一 `components.test.ts`。

## 4. 路由外壳与页面加载边界

- [x] 4.1 实现 `routing/errorSource.ts` 错误来源分类（兼容开发文字 info 与生产 `runtime-<code>`），并为渲染、setup、生命周期、异步加载器、事件处理器、watcher 六类来源编写双模式单测；验证事件处理器与 watcher 错误不被边界接管。
- [x] 4.2 实现 `lazyPage()`（挂载时加载、立即显示占位、结果按模块缓存、失败转 `PageModuleError`、无重试与超时；不使用 `defineAsyncComponent`，见 design）、`lazyPages.ts`、`PageLoadBoundary.vue`（`:key` 为 epoch:规范化路径、固定分类日志、刷新与返回入口），迁移 `routing/PageLoadBoundary.test.tsx`、`routing/lazy-routes.test.tsx`；验证加载中导航/退出可用、A/B 迟到不串、失败不自动刷新。
- [x] 4.3 实现路由表（同步外壳、404 页）、`App.vue`、`ProtectedLayout`、`Navigation`、`Redirect`（携带 `history.state.from`）、`AdminGuard`、`AdminNavigation`、`main.ts`，迁移 `routing.test.tsx`、`navigation.test.tsx`、`main.test.tsx`、`admin/access.test.tsx` 中守卫与导航部分、`admin/operations-routing.test.tsx`、`admin/aiUsageRoutes.test.tsx`、`admin/operationsReportsRoutes.test.tsx`、`admin/operationsReportsAccess.test.tsx`、`admin/aiUsageAccess.test.tsx`、`admin/audit-access.test.tsx`；验证未授权路由不下载页面模块。

## 5. 认证、SSO 与账户

- [x] 5.1 迁移 `LoginPage`、`ChangePasswordPage`、`AccountPage`、`auth/PasswordForm`、`sso/EnterpriseLogin`、`sso/BindingPanel`、`SsoResultPage`（`ElForm` 校验、提交后清除密码字段、同步锁），迁移 `sso/login.test.tsx`、`sso/enterprise-login.test.tsx`、`sso/bindings.test.tsx`、`sso/result.test.tsx`、`sso/route.test.tsx`；验证同一次交换不因重挂载/聚焦重发。

## 6. 文档中心与详情

- [x] 6.1 迁移 `DocumentsPage`、`UploadDialog`、`ProgressCard`、`documents/metadata` 下 `Filters`、`Selectors`、`MetadataPanel`，以及 `favorites/FavoriteButton`，迁移 `documents.test.tsx`、`documents/metadata/components.test.tsx`、`documents/metadata/query-page.test.tsx`、`favorites/button.test.tsx`、`favorites/documents.test.tsx`、`favorites/lifecycle.test.tsx`；验证用例通过。
- [x] 6.2 迁移 `DocumentDetailPage`、`DeleteControls`、`FileAccess`、`CitationDrawer`、`office/VersionPreview`（Blob URL 关闭释放），迁移 `documents/recovery.test.tsx`、`documents/controls.test.tsx`、`office/preview.test.tsx`、`office/legacy-preview.test.tsx`、`office/citation.test.tsx`；验证 `README` 中“文档资格恢复回归”命令对应的 Vue 文件完整通过且不放宽超时。
- [x] 6.3 迁移 `Favorites` 页面，迁移 `favorites/page.test.tsx`；验证续页互斥与离页中止用例通过。

## 7. 评论、审核与通知

- [x] 7.1 迁移 `comments/CommentPanel`（上下文改 provide/inject）与 `comments/hooks`，迁移 `comments/panel.test.tsx`、`comments/lifecycle.test.tsx`；验证用例通过。
- [x] 7.2 迁移 `reviews/VersionReviewPanel`、`reviews/PolicyPanel`、`reviews/lifecycle`、`Reviews` 页面，迁移 `reviews/page.test.tsx`、`reviews/policy.test.tsx`、`reviews/documents.test.tsx`、`reviews/lifecycle.test.tsx`、`reviews/file-lifecycle.test.tsx`、`reviews/command-lifecycle.test.tsx`；验证用例通过。
- [x] 7.3 迁移 `Notifications` 页面与 `notifications/TargetPanel`、`UnreadBadge`，迁移 `notifications/page.test.tsx`；验证用例通过。

## 8. 搜索与 AI 助手

- [x] 8.1 迁移 `Search` 页面与 `search/FilterFields`，迁移 `search.test.tsx`、`search/page.test.tsx`、`search/selectors.test.tsx`；验证草稿不自动搜索、身份切换清理用例通过。
- [x] 8.2 迁移 `Assistant` 页面（SSE 流、停止、匹配重试），迁移 `assistant.test.tsx`；验证用例通过且正文按文本渲染。

## 9. 身份管理页面

- [x] 9.1 迁移 `admin/ui`、`OperationDialog`、`PrincipalPicker`、`MultiPrincipalPicker`，迁移 `admin/MultiPrincipalPicker.test.tsx`；验证用例通过。
- [x] 9.2 迁移 `UsersPage`、`DepartmentsPage`、`RolesPage`，迁移 `admin/users.test.tsx`、`admin/departments.test.tsx`、`admin/roles.test.tsx`；验证用例通过。
- [x] 9.3 迁移 `admin/lifecycle` 下 `LifecyclePage`、`UserLifecyclePanel`、`dialogs`、`scope`，迁移 `admin/lifecycle/lifecycle.test.tsx`；验证用例通过。

## 10. 空间与文档管理页面

- [x] 10.1 迁移 `SpacesPage`、`SpaceDetailPage`、`AclPanel`、`spaceUi`，迁移 `admin/space-pages.test.tsx`；验证 301 次选择用例在原 30 秒限制内通过，全部用例与清单一致。
- [x] 10.2 迁移 `DocumentsIndexPage`、`DocumentPanelPage`、`DocumentTaxonomy`，迁移 `admin/documents-index.test.tsx`、`admin/taxonomy.test.tsx`；验证用例通过。

## 11. 运维、审计与报表页面

- [x] 11.1 迁移 `EmbeddingPage`、`ParseDeadLettersPage`，迁移 `admin/operations-pages.test.tsx`；验证用例通过。
- [x] 11.2 迁移 `AuditsPage`、`admin/auditQuery`，迁移 `admin/audits.test.tsx`、`admin/auditQuery.test.tsx`；验证用例通过。
- [x] 11.3 迁移 `AiUsagePages`、`admin/aiUsageQuery`、`OperationsReportsPage`、`admin/operationsReportsQuery`，迁移 `admin/aiUsagePages.test.tsx`、`admin/aiUsageQuery.test.tsx`、`admin/operationsReportsPages.test.tsx`、`admin/operationsReportsQuery.test.tsx`；验证用例通过。

## 12. 包体与一致性核验（切换前）

- [x] 12.1 更新 `bundle-report.mjs` 路由模块映射与隔离规则为 Vue 页面路径，同步调整 `bundle-report.test.mjs`；对 Vue 版构建并与 `bundle-baseline.json` 比较，验证隔离检查通过、入口/登录与文档列表累计 gzip 不超过基线 10%（超出时提交构成分析并取得确认），结果写入 `verification.md`。
- [x] 12.2 比对 `test-inventory.md`：每个原测试文件均有对应迁移文件，用例标题与数量一致；列出新增 `waitFor`、超时调整及任何差异并说明原因；对照冻结起点检查 `src/` 是否有新提交需要同步。验证 `npx vitest run` 下 `src-vue` 全量通过。

## 13. 切换与文档

- [x] 13.1 删除 `src/`、React 依赖（`react`、`react-dom`、`react-router-dom`、`antd`、`@vitejs/plugin-react`、`@types/react*`、`@testing-library/react`）及 React 配置，将 `src-vue/` 重命名为 `src/`，合并 `vite.config.ts`、`tsconfig.json`、`index.html` 与脚本（`typecheck` 改为 `vue-tsc --noEmit`）；验证源码中无 `react`/`antd` 导入，`npm run typecheck`、`npm test -- --run`、`npm run build`、`npm run bundle:test`、`npm run bundle:report -- --check-isolation` 全部通过。
- [x] 13.2 更新 `kb-web/README.md`（技术栈、命令、测试文件路径、工程约定中的 hooks/上下文描述）、根 `README.md` 技术栈描述与测试命令、`架构决策记录.md` 新增前端技术栈变更记录；检索 `docs/` 中可执行的测试命令与“React 渲染”类说明并更新，带日期的历史验证记录保持原样；验证文档中列出的命令按写法可执行。

## Workflow follow-up

- 关键页面人工走查（登录、文档列表/详情、搜索、助手、主要管理页）由实施者在本地开发服务器连接测试网关执行，不作为自动化门槛。
- 发布按现有静态托管流程：先上传新哈希资产再原子切换 HTML，保留最后一个 React 构建资产用于回退。
- 满足评审要求后归档本变更并同步规格（本变更无规格增量）。
