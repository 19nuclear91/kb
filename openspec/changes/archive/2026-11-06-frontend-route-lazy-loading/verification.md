# 实施验证：frontend-route-lazy-loading

日期：2026-11-06。实现及离线验证已完成，17/17 项任务完成。本记录替代实施前的缺失项核对，不将当时的 0/17 结果作为本次实施结论。尚未归档。

后续 `openspec-verify-change` 独立核验结果见 [核验报告](verification-review.md)：W1（默认渲染异常诊断未脱敏）已按用户指示修复并复核，当前为 0 条 CRITICAL、0 条 WARNING、0 条 SUGGESTION。下文保留实施过程，并更新为修复后的候选包体。

## 实现与要求对应

| 要求 | 实现及证据 |
| --- | --- |
| 业务页面按路由与身份加载 | `src/routing/lazyPages.ts` 的模块顶层静态 import 路径；`App.tsx` 保留认证门禁和直接加载的登录/改密/SSO 结果页；路由用例验证匿名、身份未确认、改密和故障不调用加载器 |
| 列表、详情、账户独立 | 删除旧共包入口，拆为独立页面及 `auth/PasswordForm`、`documents/UploadDialog`、`ProgressCard`、`useProgress`；9 处提取函数体与 HEAD 比较完全一致；真实构建检查列表不含评论、版本审核操作、Office 预览，认证不含绑定面板 |
| 局部加载状态 | `PageLoadBoundary` 在内容区提供 status，外壳/导航/退出位于边界之外；按规范化 pathname 和 epoch 重置，MemoryRouter 用例覆盖已加载旧页切到延迟新页时不残留 |
| 显式失败恢复 | 模块拒绝与页面渲染异常分开显示固定文案；不输出异常 URL/正文/堆栈；仅用户点击刷新，提示丢失未保存输入，不重试原 lazy Promise，不自动重放写入 |
| 当前身份与资源作用域 | 原资源级 key、请求取消及清理保留；受控 Promise 验证成功/失败晚到、退出、换号、改密、身份故障、隐藏/恢复、同模块 A/B 目标；旧目标不挂载或读取资料 |
| 管理路由资格 | 原 AdminGuard 外置，资格确认后才渲染懒加载页；菜单显示/悬停/聚焦不预取，缓存成功不越过后续拒绝；AI 两入口共享模块且分别授权，运营报表不新增空间目录依赖，资源详情仍保留内部核对 |
| 管理加载期间资格变化 | 下载中撤权、身份变化、旧成功/失败晚到、管理 A/B 切页、失败后导航/退出及主动刷新用例；管理页面已有不确定写入恢复保持原行为 |

初次实施新增 3 个页面边界用例、21 个受控路由用例，以及 2 个原管理测试文件中的加载器门禁用例。W1 修复将页面边界用例扩充至 5 个，并新增 1 个真实应用入口用例，验证固定诊断分类及无原始异常输出。真实业务单元测试另行回归上传、删除、审核、预览/下载、SSO 和管理写入；受控加载用例的页面探针用于判断挂载和资料请求，不声称覆盖真实网络加载。

## 构建基线与条件

正式基线在任何运行时代码/页面拆分前由当前源码重新构建，未使用探索阶段的旧 dist。生成基线时只有分析配置、报告脚本、npm 命令、README 及规划文件改动；具体工作区状态记录在 JSON 的 `provenance.worktree`。

- 源码修订：`3f40e0c360f0ba4b4bba5b53f31405ff001c2259`；候选为其上的本次工作区实现。
- Node `v24.22.1`，npm `11.19.1`，Vite `8.3.4`，React `19.3.1`，React Router DOM `7.18.4`，TypeScript `7.0.2`，Vitest `5.0.5`。
- 锁文件 SHA-256：`42272b5caed29eb71991978e9d325a6d43a17b68bb917ed289a7b6f34c1e411e`，依赖和锁文件未变。
- `production`；公开白名单 `VITE_CONVERSATION_ENABLED`、`VITE_NOTIFICATION_POLL_MS`、`VITE_OFFICE_PREVIEW_ORIGIN`、`VITE_SEARCH_MAX_QUERY_CODEPOINTS` 均未设置，使用现有缺省值；未收集私有环境变量。
- 未手工合并 vendor，继续由 Vite/Rolldown 自动共享；告警阈值仍为 1200 kB。生产 sourcemap 关闭，manifest 和有效源模块清单移至 `.bundle-analysis`，不保留在公开 dist 内。
- 基线 [bundle-baseline.json](bundle-baseline.json)，候选 [bundle-candidate.json](bundle-candidate.json)。二者含文件摘要、JS/CSS 分项、冷启动累计集合、增量集合与压缩字节。

入口集合取 HTML 的 script、stylesheet、modulepreload 与递归静态依赖；路由集合再并入目标实现及静态依赖，不遍历动态页面。最终文件去重，逐文件 gzip level 9 / Brotli quality 11 后求和。基线单包路由全部计入入口，不能按缺少动态 chunk 记成零。

## 包体对比

单位为字节，累计包含 JS 与 CSS；基线所有场景 raw 为 1,322,490、gzip 为 409,707、Brotli 为 333,156。HTML 本身不计入 JS/CSS 大小。

| 场景 | 候选 raw | 基线 gzip | 候选 gzip | gzip 差值 | 候选 Brotli | 文件数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| entry | 678,869 | 409,707 | 223,276 | -186,431 | 192,856 | 2 → 5 |
| login | 678,869 | 409,707 | 223,276 | -186,431 | 192,856 | 2 → 5 |
| password | 678,869 | 409,707 | 223,276 | -186,431 | 192,856 | 2 → 5 |
| sso | 678,869 | 409,707 | 223,276 | -186,431 | 192,856 | 2 → 5 |
| documents | 1,077,848 | 409,707 | 354,522 | -55,185 | 307,104 | 2 → 18 |
| detail | 1,154,346 | 409,707 | 383,591 | -26,116 | 332,391 | 2 → 24 |
| account | 720,177 | 409,707 | 238,385 | -171,322 | 206,327 | 2 → 9 |
| search | 773,353 | 409,707 | 259,639 | -150,068 | 224,795 | 2 → 15 |
| favorites | 1,010,252 | 409,707 | 328,791 | -80,916 | 284,664 | 2 → 12 |
| reviews | 764,235 | 409,707 | 256,719 | -152,988 | 222,196 | 2 → 16 |
| notifications | 788,781 | 409,707 | 265,667 | -144,040 | 229,827 | 2 → 17 |
| assistant | 754,831 | 409,707 | 252,204 | -157,503 | 218,390 | 2 → 12 |
| users | 1,084,619 | 409,707 | 354,061 | -55,646 | 307,251 | 2 → 16 |
| departments | 821,167 | 409,707 | 272,936 | -136,771 | 237,321 | 2 → 13 |
| roles | 740,948 | 409,707 | 246,575 | -163,132 | 213,506 | 2 → 12 |
| spaces | 729,302 | 409,707 | 242,284 | -167,423 | 209,841 | 2 → 12 |
| spaceDetail | 774,623 | 409,707 | 259,876 | -149,831 | 224,516 | 2 → 17 |
| adminDocuments | 729,074 | 409,707 | 242,327 | -167,380 | 209,781 | 2 → 12 |
| documentPanel | 766,595 | 409,707 | 256,243 | -153,464 | 221,579 | 2 → 15 |
| embedding | 1,066,867 | 409,707 | 349,592 | -60,115 | 303,127 | 2 → 16 |
| deadLetters | 1,080,673 | 409,707 | 354,622 | -55,085 | 307,758 | 2 → 17 |
| audits | 1,050,098 | 409,707 | 343,220 | -66,487 | 297,585 | 2 → 14 |
| aiUsage | 1,058,890 | 409,707 | 346,159 | -63,548 | 300,235 | 2 → 14 |
| aiAudits | 1,058,890 | 409,707 | 346,159 | -63,548 | 300,235 | 2 → 14 |
| operations | 698,357 | 409,707 | 230,856 | -178,851 | 199,384 | 2 → 7 |
| tags | 749,745 | 409,707 | 250,237 | -159,470 | 216,535 | 2 → 14 |

入口/登录累计 gzip 下降 **45.50%**，列表下降 **13.47%**，详情下降 **6.37%**。候选无超过原阈值的大 chunk。拆分增加请求文件数；这些数值说明构建资源大小变化，不是浏览器加载耗时或用户体验测量。

## 实际命令与结果

以下命令在 `kb-web` 下执行，OpenSpec 和 Git 检查在仓库根目录执行：

```powershell
npm run bundle:test
npm run typecheck
npm run build
# 拆分前执行一次，之后保留基线文件
npm run bundle:report -- --out ../openspec/changes/frontend-route-lazy-loading/bundle-baseline.json
# 实施后执行
npm run bundle:report -- --baseline ../openspec/changes/frontend-route-lazy-loading/bundle-baseline.json --check-isolation --check-reduction --out ../openspec/changes/frontend-route-lazy-loading/bundle-candidate.json
```

报告脚本的 5 个 Node 单元测试通过：旧单包、菱形共享依赖、环、动态模块/HTML preload、资源或映射缺失、摘要不一致、隔离/同条件比较和 CLI 非零退出。类型检查、真实构建及结构/累计 gzip 门槛已通过。

首次实施最终回归：**62 个文件、747 项 Vitest/jsdom 测试全部通过**，耗时 238.36 秒；另有 5 项报告脚本 Node 测试通过。该阶段生产构建及报告通过。W1 修复后又执行类型检查、3 个明确文件的 27 项单元测试、生产构建及报告，全部通过；最新候选包体见上表。按下列明确名单使用 `npm test -- --run --maxWorkers=1 $verificationTests`，不使用全量自动发现，不启动应用：

```powershell
$verificationTests = @(
  'src/routing/PageLoadBoundary.test.tsx', 'src/routing/lazy-routes.test.tsx', 'src/routing.test.tsx',
  'src/session.test.ts', 'src/lifecycle.test.tsx', 'src/navigation.test.tsx', 'src/components.test.tsx',
  'src/sso/api.test.ts', 'src/sso/bindings.test.tsx', 'src/sso/enterprise-login.test.tsx', 'src/sso/flow.test.ts',
  'src/sso/login.test.tsx', 'src/sso/provider.test.tsx', 'src/sso/result.test.tsx', 'src/sso/route.test.tsx',
  'src/documents.test.tsx', 'src/documents/recovery.test.tsx', 'src/documents/deletion.test.ts',
  'src/documents/controls.test.tsx', 'src/documents/operations.test.tsx', 'src/documents/write-coordination.test.tsx',
  'src/documents/metadata/query-page.test.tsx', 'src/documents/metadata/components.test.tsx',
  'src/favorites/documents.test.tsx', 'src/favorites/lifecycle.test.tsx', 'src/favorites/page.test.tsx',
  'src/reviews/documents.test.tsx', 'src/reviews/file-lifecycle.test.tsx', 'src/reviews/lifecycle.test.tsx',
  'src/reviews/command-lifecycle.test.tsx', 'src/reviews/page.test.tsx', 'src/reviews/policy.test.tsx',
  'src/comments/panel.test.tsx', 'src/comments/lifecycle.test.tsx',
  'src/office/preview.test.tsx', 'src/office/legacy-preview.test.tsx', 'src/office/citation.test.tsx',
  'src/search.test.tsx', 'src/search/page.test.tsx', 'src/search/selectors.test.tsx', 'src/assistant.test.tsx',
  'src/notifications/page.test.tsx', 'src/notifications/unread.test.tsx',
  'src/admin/access.test.tsx', 'src/admin/access-spaces.test.tsx', 'src/admin/operations-routing.test.tsx',
  'src/admin/operations-pages.test.tsx', 'src/admin/space-pages.test.tsx', 'src/admin/documents-index.test.tsx',
  'src/admin/users.test.tsx', 'src/admin/departments.test.tsx', 'src/admin/roles.test.tsx', 'src/admin/taxonomy.test.tsx',
  'src/admin/audit-access.test.tsx', 'src/admin/audits.test.tsx', 'src/admin/hooks.test.tsx',
  'src/admin/aiUsageRoutes.test.tsx', 'src/admin/aiUsageAccess.test.tsx', 'src/admin/aiUsagePages.test.tsx',
  'src/admin/operationsReportsRoutes.test.tsx', 'src/admin/operationsReportsAccess.test.tsx', 'src/admin/operationsReportsPages.test.tsx'
)
npm test -- --run --maxWorkers=1 $verificationTests
```

实施过程中的文档拆分回归曾在双 worker 执行时出现 1 个既有等待断言超时，`src/documents/recovery.test.tsx` 单独重跑 27 项通过；没有修改业务行为或放宽超时。本轮最终回归使用单 worker。

```powershell
openspec validate frontend-route-lazy-loading --strict
git diff --check
```

两项均通过；OpenSpec validate 只检查规划有效性，不替代实现与测试证据。

## W1 修复后补充验证

`main.tsx` 的 React root 已注册 `reportCaughtError`，诊断只输出固定分类，不输出原始 Error 或附带信息。以下测试覆盖实际入口接线、模块失败及 Error/对象/字符串渲染异常脱敏，3 个文件、27 项通过；此前完整业务回归未重复执行。

```powershell
npm run typecheck
npm test -- --run --maxWorkers=1 src/main.test.tsx src/routing/PageLoadBoundary.test.tsx src/routing/lazy-routes.test.tsx
npm run build
npm run bundle:report -- --baseline ../openspec/changes/frontend-route-lazy-loading/bundle-baseline.json --check-isolation --check-reduction --out ../openspec/changes/frontend-route-lazy-loading/bundle-candidate.json
```

## 验证边界

全程为本地类型检查、静态构建、构建资源分析、Node/Vitest/jsdom 单元测试、MemoryRouter 和会话/API 替身。未启动前端开发/预览服务、真实浏览器、后端、数据库、Redis、MinIO、SSO、模型或 Office 外部组件；未编写或执行针对 README、设计、运维等说明文档的内容断言测试。

刷新入口由替身验证调用和无附带业务请求；真实浏览器刷新、HTTP 缓存/CDN 行为、网络加载时序和服务端在途写入不是此次离线验证结果。README 与运维说明已交付缓存、资产保留、真实 404、发布/回退约定，未执行部署验收。变更尚未归档。
