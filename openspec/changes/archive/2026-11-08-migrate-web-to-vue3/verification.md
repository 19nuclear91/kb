# Verification

## 归档（2026-11-08）

- `spec-driven` 变更的 proposal、design、tasks 均完成，任务 37/37；`skip_specs: true`，无规格增量，无需同步主规格。
- 已归档至 `openspec/changes/archive/2026-11-08-migrate-web-to-vue3/`，保留 `.openspec.yaml`、测试清单及三份包体报告。README 与架构记录链接、当前包体比较命令及清单比对脚本已适配归档路径。
- 业务验证沿用下方已完成的 1130 项测试和构建证据；归档未重新执行业务测试。
- 归档后 `openspec list --json` 无活动变更；归档路径下的清单脚本对最终报告再次比对通过（88/88 原文件、1104 项）。文档差异检查通过，主规格无改动。

## 文档与收尾（任务 13.2）

- 已更新两份 README 的技术栈、类型检查/测试命令、包体流程和工程约定；架构决策记录补记 Vue 3 技术栈决策。现行说明同步 Vue 文本插值、CSP 样式要求、管理守卫与生命周期测试路径；审计、AI 用量与 SSO 文档提供当前 `.test.ts` 回归命令，带日期的历史验证记录保留。
- 文档中的安装、类型检查、全量测试、构建及包体命令已在最终工程执行成功；新增定向命令涉及的 22 个文件由 `npm exec -- vitest list --filesOnly ...` 确认选择正确，均已包含在最终 1130 项通过报告中，无需重复运行。
- `git diff --check` 与 `openspec validate migrate-web-to-vue3 --strict --json` 通过；apply 返回 `all_done`（37/37）。`npm ls --depth=0` 依赖完整，最终锁摘要与构建报告一致。人工浏览器走查与发布未执行；本轮结果为模拟 DOM 单元测试、类型检查与静态构建/包体核验。

## 默认工程切换（任务 13.1）

- 已将 Vue 源码统一到 `kb-web/src`，默认 `index.html` / Vite / TypeScript 配置及 npm 脚本均切换。React、Ant Design 及其路由/测试/类型/构建依赖已移除，`npm ci` 按最终锁文件安装成功，源码及锁文件无旧框架导入或依赖。
- `npm run typecheck`、`npm test -- --run`、`npm run build`、`npm run bundle:test` 与包体隔离/增长检查全部通过；最终测试报告 `vue-final.json` 为 93 文件、1130 项通过，无跳过。最终目录下再次清单比对：88/88 原文件、1104 项一致。
- 301 次实际选择约 3.20 秒通过，继续使用原 30 秒上限；51 主体用例约 0.48 秒通过，原 15 秒上限不变。
- 最终报告：[bundle-final.json](bundle-final.json)。入口/登录累计 gzip 126,896 字节（-43.28%），文档列表 232,444（-34.52%），详情 256,878（-33.11%）。与并行树的微小差异来自入口路径/哈希引用变化；所有隔离检查与 10% 门槛继续通过。

复现命令（`kb-web`，切换后）：

```powershell
npm ci
npm run typecheck
npm test -- --run
npm run build
npm run bundle:test
npm run bundle:report -- --baseline ../openspec/changes/archive/2026-11-08-migrate-web-to-vue3/bundle-baseline.json --allow-lock-change --new-scenarios lifecycle --check-isolation --check-growth --out .bundle-analysis/current-report.json
```

## 切换前包体与全量核验（任务 12.1、12.2）

- `npm run build:vue`（含类型检查）通过；`vue-complete.json`：93 文件、1130 项全部通过，无失败/跳过。全量清单比对（未使用 `-Partial`）覆盖原 88/88 文件、1104 项，标题与数量一致，另有 5 文件、26 项新增测试。
- 对 88 个文件做 TypeScript AST 静态核对：显式超时参数全部相同；86 个文件的 `expect`/`waitFor` 数量相同。`admin/access` 新增一次等待按钮解除禁用的断言；`admin/users` 的下拉存在性等待移入公共 `openSelect`，仍等待并检查列表存在。均已登记到 `test-inventory.md`。
- 冻结复查：`git log 31e68cc5d8c717f46ee86cd7e4f888120759df51..HEAD -- kb-web/src` 与该起点的 `git diff --stat -- kb-web/src` 均为空，无待同步修复。
- 包体脚本 9 项测试通过；Vue 路由映射支持并行/最终目录及 SFC 子模块。入口保护、登录绑定面板、列表详情专用模块、管理与普通页面交叉内联检查全部通过。
- 正式候选：[bundle-candidate.json](bundle-candidate.json)。Node/npm、production 模式、公开开关与逐文件压缩口径均与 React 基线相同；框架依赖变化通过显式 `--allow-lock-change` 登记前后锁摘要。原 26 场景逐项比较；新增 `lifecycle` 场景显式登记无旧基线，仅记录候选值，不虚构差值。

| 场景 | React 累计 gzip | Vue 累计 gzip | 变化 | 10% 门槛 |
| --- | ---: | ---: | ---: | --- |
| 入口 / 登录 | 223,725 | 126,906 | -43.28% | 通过 |
| 文档列表 | 354,973 | 232,483 | -34.51% | 通过 |
| 文档详情 | 384,051 | 256,940 | -33.10% | 仅记录 |

复现命令（`kb-web`，切换前）：

```powershell
npm run bundle:test
npm run build:vue
npm run bundle:report -- --dist dist-vue --analysis .bundle-analysis-vue --html index.vue.html --baseline ../openspec/changes/migrate-web-to-vue3/bundle-baseline.json --allow-lock-change --new-scenarios lifecycle --check-isolation --check-growth --out ../openspec/changes/migrate-web-to-vue3/bundle-candidate.json
```

## AI 用量与运营概览（任务 11.3）

- AI 用量/调用审计页面与查询组合式函数、运营概览与查询组合式函数全部迁移；原 4 文件、37 项通过，清单标题/数量一致，类型检查通过。
- 保留总览与分组固定窗口、每页独立 asOf、未知用量与真实零、采集覆盖、大整数、事件身份关联、资格拒绝清理、隐藏后先核对权限再默认读取。
- 定向报告 `vue-reports.json`；开始 12.x 全量清单与包体门槛核验。

## 审计查询（任务 11.2）

- 查询组合式函数原 22 项、页面原 20 项通过，清单比对标题/数量一致；类型检查通过。
- 保留固定微秒窗口、续页互斥与异常页拒绝、403 整页清理与跨重挂载拒绝记忆、空间事实不干扰审计查询、详情身份核对、安全文本显示与敏感草稿清理。
- 定向报告 `vue-audits.json`；后续全量验证将包含本次改动。

## 11.1 完成时全量验证

- `vue-11-1.json`：87 文件、1051 项全部通过、无跳过；清单部分比对 82/88 原文件、1025 项一致，生产构建通过。301 次选择在全量环境约 3.7 秒通过。

## 运维管理页面（任务 11.1）

- 向量模型目录/全局状态/本实例状态、作业轮询及发起/恢复确认、解析死信筛选/分页/安全摘要/重放已迁移。
- `operations-pages.test.ts` 原 28 项全部通过；标题/数量一致，TypeScript AST 核对原 25 个用例定义的 `expect` 调用数量逐项相同。保留同步双击、精确 epoch/version、非重叠轮询、隐藏暂停、迟到响应隔离及原请求标识重试。
- 定向报告 `vue-operations-pages.json`；类型检查通过。最终全量与构建结果待登记。

## 空间与文档管理（任务 10.1、10.2；完成 6.2 联合验收）

- 空间目录/详情、批量授予/撤销、单条文档例外、文档管理索引/面板、搬迁/密级/删除、分类/标签管理已迁移；`space-pages` 原 90 项、索引原 13 项、分类标签原 11 项全部通过，标题/数量比对一致。
- `space-pages` 的 301 次实际选择用例约 2.9 秒通过（原上限 30 秒）；51 主体用例保留原 15 秒限制。按 TypeScript AST 核对三个测试文件，每个原用例定义的 `expect` 调用数量与迁移文件相同。
- 文档资格恢复验收按 README 对应的完整 Vue 文件执行（无名称筛选）：`documents/recovery.test.ts` + `admin/space-pages.test.ts` 全部通过；连同 `admin/access.test.ts`、索引和分类标签，共 5 文件、156 项通过。报告 `vue-10-targeted.json`；`npm run typecheck:vue` 通过。
- `admin/access.test.ts` 已补回最后 1 项，原 15 项完整通过；已登记待补清零，尚未迁移的其他原文件不计作完成。
- 资源草稿、回执和未决绑定按 Session/epoch/资源在内存隔离；绑定表仅浅响应式追踪增删。10.x 完成时全量 86 文件、1023 项全部通过、无跳过（`vue-10.json`）；清单比对 81/88 个原文件、997 项一致；生产构建通过。

## 本轮汇总（任务 9.1–9.3）

- Vue 全量：83 文件、908 项全部通过，无跳过；报告 `C:\Users\Burning\AppData\Local\Temp\opencode\vue-9.json`。
- 清单部分比对：78/88 个原文件、882 项标题/数量一致且通过；仅剩 `admin/access.test.ts` 随 10.2 登记的 1 项待补。其余 10 个原测试文件仍待迁移，不计作已验证。
- `npm run build:vue`（含 `vue-tsc`）通过，`git diff --check` 未发现空白错误。任务进度 27/37，尚未执行最终切换；包体隔离与 10% 门槛仍待 12.1。

## 员工生命周期（任务 9.3）

- 生命周期页面、事件/员工项、本人应急保障、纳管/退出/复职/应急弹窗全部迁移；原 27 项测试全部通过，`npm run typecheck:vue` 通过。
- 保留读取与写入资格分离、准确员工标识核验、过期证明禁用、身份/权限/目标/隐藏中止与清理、未知提交按原标识重试、放弃后重读再核验等断言。
- 定向报告 `vue-admin-lifecycle.json`；全量与构建结果见本轮汇总。

## 身份管理页面（任务 9.2）

- 角色原 10 项、部门原 9 项、账号原 25 项全部迁移并通过；补回管理资格原文件中随 9.2 登记的 4 项，当前该文件 14 项通过，仅余 10.2 的 1 项待补。
- 定向报告 `vue-admin-identity.json`：3 文件、49 项通过；部门与多主体选择回归另有 13 项通过。最新全量与构建结果见本轮汇总。
- 生命周期 scope 的 key 直接置于插槽，确保刷新代次、权限事实、身份和目标变化会卸载旧内容；账号原 6 项生命周期联动断言均通过。
- 角色勾选事件使用 `v-on` 绑定具名处理器，既兼容 Element Plus 声明的单参数类型，又保留运行时原生事件以复原服务端勾选事实；确认前与锁定期间均不显示乐观勾选。

## 管理公共组件（任务 9.1）

- `AdminModal`、`WriteNotice`、`Note`、`PasswordFields`、`settle`、`OperationDialog` 与单/多主体选择组件已迁移。
- 多主体选择原 4 项全部通过，类型检查通过；覆盖跨类型累积、大整数标识、游标失败与迟到响应。

## AI 助手迁移（任务 8.2）

- 原 17 项助手用例全部通过，保留 SSE 结束/停止后的持久状态核对、原绑定重试、隐藏/换号清理与删除先关闭流的断言。
- Vue 全量：78 文件、829 项全部通过，无跳过；清单部分比对 73/88 文件、803 项一致，管理页面仍有 5 项已登记待补。
- `npm run build:vue`（含类型检查）通过。

## 搜索及文档联动（任务 8.1、6.1）

- 搜索原 40 项、收藏与文档联动原 6 项及通用组件最后 1 项搜索用例已迁移；6.1 全部测试依赖已补齐。
- Vue 全量：77 文件、812 项全部通过，无跳过；清单部分比对 72/88 文件、786 项一致，仍待补的 5 项均为管理页面用例。
- 生产构建与类型检查通过；测试保持原超时，差异详见清单。

## 通知迁移（任务 7.3）

- `notifications` 与 `routing.test.ts` 共 4 文件、30 项全部通过；原通知路由跳过已启用，登记待补降至 6 项。
- 固定评论/审核目标、游标续页、标记已读互斥、未确认重试与身份切换沿用原断言。
- `npm run build:vue`（含类型检查）通过。

## 审核迁移（任务 7.2）

- 审核组件原 39 项全部通过，涵盖固定版本/轮次、独立文件能力、私有意见、资格失效、未确认写入原绑定重试及发布策略冲突重读。
- Vue 全量：72 文件、757 项，756 通过，1 项通知路由待 7.3 启用。清单部分比对 67/88 文件、731 项一致；类型检查通过。

## 文档与评论迁移（任务 6.x、7.1）

- 第 6 组实施后 Vue 全量：64 文件、685 项，684 通过，1 项已登记的通知路由待补跳过。清单部分比对 59/88 文件、659 项通过；已补回 3 项文件/引用用例，登记待补降至 7 项。
- `documents/recovery.test.ts` 原 27 项全部通过，未放宽超时；6.2 的 README 验收还包含 10.1 的 `admin/space-pages.test.ts`，故 6.2 保持未勾选。6.1 尚待 `favorites/documents.test.ts` 的搜索页面联动测试。
- 评论原 33 项全部通过；连同评论 API、管理 hooks 与文档写入协调用例共 5 文件、59 项，清单比对一致。
- 修复评论迁移中的两项行为差异：外部绑定 Map 的增删通知（浅层代理，Binding 身份保持不变）；拒绝到重核期间的连续禁用（原 MutationObserver 断言通过）。
- `npm run build:vue`（包含 `vue-tsc`）通过。完整包体门槛仍由 12.1 核验。

## 认证与账户迁移（任务 5.1）

- 用户已确认第 4 组的自管 `lazyPage` 与按任务补回用例的安排。
- Vue 测试：53 个文件、581 项，其中 580 通过，1 项通知路由用例依赖 7.3，仍按登记跳过。新增的表单校验测试验证空值/不匹配不发送、连续提交互斥和成功清理密码。
- 清单部分比对：48/88 个原文件、555 项；待补登记从 13 项降为 10 项，未放宽原用例超时。
- `npm run typecheck:vue`、`npm run build:vue` 通过；账户绑定仍在账户页面独立 chunk，完整包体门槛留待 12.1 核验。

## 冻结起点（任务 1.1）

- `kb-web` 冻结起点提交：`31e68cc5d8c717f46ee86cd7e4f888120759df51`（2026-11-07 13:11:26 +0800）。冻结期间 `kb-web/src` 的任何修复须同步到 `kb-web/src-vue` 并在此登记。
- 环境：Node.js v24.21.0、npm 11.19.0。
- TypeScript 7.0.2 → 6.0.3：切换前 `npm run typecheck` 通过、Vitest 88 个文件 1104 项全部通过；切换后 `npm run typecheck`、`npx vitest run`（88 个文件 1104 项全部通过）与 `npm run build` 均通过，用例总数不变。

冻结期间同步登记：

| 日期 | `src/` 提交 | 同步到 `src-vue` | 说明 |
| --- | --- | --- | --- |
| — | — | — | 暂无 |

## React 包体基线（任务 1.3）

- 文件：[bundle-baseline.json](bundle-baseline.json)，TypeScript 6.0.3 下按 `kb-web/README.md` 流程生成（`npm run bundle:test` 5 项通过 → `npm run build` → `npm run bundle:report -- --check-isolation`，隔离检查通过）。
- 构建身份：revision `31e68cc`（工作树含 TypeScript 版本修改）、锁文件 SHA-256 `e6b0d74b…5791861`、Node v24.21.0、npm 11.19.0、production 模式，公开开关全部未设置。
- 门槛相关累计 gzip（字节）：entry/login 223,725；documents 354,973；detail 384,051（仅记录）。10% 上限分别为 246,097 与 390,470。
