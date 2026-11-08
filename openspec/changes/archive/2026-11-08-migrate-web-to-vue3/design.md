# Design

## Context

动机与范围见 [proposal.md](proposal.md)。影响方案的现状与约束：

- `kb-web/src` 约 100 个实现文件、100 个测试文件。业务逻辑已大量下沉为与框架无关的 TS：`session.ts`（`Session`，`subscribe/getSnapshot`）、`notifications/unread.tsx` 中的 `UnreadPoller`、`favorites/store.ts`、`documents/operations.ts`、`documents/deletion.ts`、`sso/flow.ts`、`stream.ts`、各 `api.ts`/`logic.ts`。React 只通过 `useSyncExternalStore`（7 处）、`createContext`（4 处：会话、管理资格、文档操作、评论）和 hooks 与之绑定。
- 页面大量使用 `key={...}` 按 epoch/目标重挂载以丢弃旧状态（约 105 处），并依赖 `useRef` 同步锁实现双击互斥、请求标识绑定和迟到响应丢弃。
- 路由：`BrowserRouter` + `Routes`，受保护布局内用 `<Navigate>` 按会话 phase 重定向；`AdminGuard` 在页面加载边界之外；`PageLoadBoundary` = 错误边界 + `Suspense`，以 `epoch:pathname` 为 key；页面模块经 `lazyPage()` 包装，加载失败抛 `PageModuleError`。规格要求：确认身份并通过守卫后才下载页面、加载中导航与退出可用、失败不自动刷新/重试/重放写入。
- antd 使用面集中：Button(53)、Alert(48)、Space(28)、Tag(19)、Input(15)、Form(11)、Descriptions/Table(9)、Modal/Spin(8)、Drawer(7)、Select(6)、Empty(5)、Checkbox、Pagination、ConfigProvider；无 message/notification 命令式 API。
- 测试：52 个测试通过 `vi.mock('../SessionProvider', () => ({ useSession: () => context }))` 替换会话；35 处依赖 `.ant-*` 类名，35 处用 `/重\s*试/` 适配 antd 两字按钮自动插空格；`space-pages.test.tsx` 含 301 次选择且有 30 秒限制。
- 当前 `typescript` 为 7.0.2；TypeScript 7 尚无稳定编程 API，`vue-tsc`/Volar 只能依赖 TypeScript 6。
- `scripts/bundle-report.mjs` 以 `src/**.tsx` 路径登记路由页面模块并执行隔离检查（登录不含 `BindingPanel`，列表不含评论/审核/Office 预览等）；`user-web-session`、`admin-web-access` 规格中的懒加载与隔离要求在迁移后仍然有效。
- 已确认：迁移期间冻结 `kb-web` 新功能；采用并行目录、一次切换；包体门槛为入口/登录、文档列表累计 gzip 超过迁移前基线 10% 时需说明并确认。

## Goals / Non-Goals

**Goals:**

- 所有现有规格场景在 Vue 版本上保持成立；页面文案、路由、可访问名称/角色与交互状态逐项等价。
- 与框架无关的 TS 核心零逻辑改动，迁移风险集中在可单测的绑定层。
- 测试逐文件、逐用例对应迁移，用例标题不变，可审计“无删减、无放宽”。
- 迁移期间 React 版本始终可构建、可发布，切换与回退都是一次静态资产切换。

**Non-Goals:**

- 不追求像素级还原 antd 视觉；不重新设计交互或信息架构。
- 不引入 TSX/JSX 写 Vue 组件，不引入 Options API。
- 不引入自动导入（`unplugin-auto-import`/`unplugin-vue-components`）等隐式全局符号。
- 不在本次调整业务模块拆分、不修改 API 协议或服务端。

## Decisions

### 1. 并行目录开发，最后一次切换

Vue 版本在 `kb-web/src-vue/` 中实现，React 版本 `kb-web/src/` 冻结。迁移期间增加独立的 `vite.vue.config.ts`、`tsconfig.vue.json`、`index.vue.html` 及 `dev:vue`/`typecheck:vue`/`test:vue`/`build:vue` 脚本，两套工程各自类型检查、测试和构建。切换任务中删除 `src/` 与 React 配置，将 `src-vue/` 重命名为 `src/`，脚本与配置合并回默认名称。

纯 TS 核心在迁移起点整体复制到 `src-vue/`（同名同路径），之后只在 Vue 树内改动；切换时 git 可按重命名识别这些文件。冻结期如必须修复 React 线上缺陷，同一修复须同步到 `src-vue/` 并在任务中记录。

备选：原地逐模块替换——中间状态整体无法类型检查和构建，且 React/Vue 测试替身路径冲突；微前端共存——引入两套运行时与长期双栈，超出范围。

### 2. 依赖与工具链

- 运行时：`vue`、`vue-router`（5.x，运行时 API 与 4.x 兼容；4.x 已停止更新，不使用其内置文件路由插件）、`pinia`、`element-plus`；开发：`@vitejs/plugin-vue`、`vue-tsc`、`@testing-library/vue`、`@vue/test-utils`、`unplugin-element-plus`。实施时选当时最新稳定版，沿用项目惯例在 `package.json` 写精确版本并提交锁文件。
- `typescript` 由 7.0.2 改为 6.x 最新稳定版，迁移起点即切换，并先验证 React 工程在 TS 6 下 `typecheck`/测试/构建结果不变，避免把编译器差异混入迁移。待 `vue-tsc` 正式支持 TS 7 后另行升级。
- Element Plus 组件在每个 SFC 中显式具名导入（`import { ElButton } from 'element-plus'`），由官方 `unplugin-element-plus` 按导入注入对应样式，保证 JS/CSS 都按页面拆分；不使用全量 `app.use(ElementPlus)` 与全量 CSS，以免抵消上一轮路由拆分的冷启动收益。
- 中文语言包通过根组件 `<ElConfigProvider :locale="zhCn">` 提供。主题令牌（主色 `#4f46e5`、圆角 10px、字体）在 `styles.css` 以 CSS 变量覆盖 `--el-color-primary` 及其 light/dark 派生值、`--el-border-radius-base`、`--el-font-family`，不引入 Sass。

### 3. 组件写法与目录约定

- 组件统一为 `<script setup lang="ts">` SFC，`defineProps`/`defineEmits` 使用类型声明；每个页面/组件一个 `.vue` 文件，原 `.tsx` 中多组件文件按组件拆分到同目录（如 `components.tsx` → `ui/PageHeader.vue`、`ui/Feedback.vue`、`ui/SafeText.vue` 等，或保留领域目录）。
- 原 `key={...}` 重挂载一律改为 `:key`，保持相同 key 组成，不以 `watch` 手工重置替代——这是 epoch/目标隔离语义的关键载体。
- 同步锁、请求代次、`AbortController` 等非渲染状态使用普通变量或 `let`（setup 作用域闭包）而非 `ref`，避免响应式代理改变对象身份（如 `Binding` 作为 `WeakMap` 键）。需要传入外部核心类的对象一律 `markRaw`/`shallowRef`。
- 文本只用插值渲染；禁止 `v-html`、`innerHTML` 与 `:href` 拼接令牌。新增静态检查测试扫描 `src-vue/**/*.vue` 中的 `v-html`，替代原文档中“通过 React 渲染不执行 HTML”的保证。

### 4. 外部 store 桥接与状态分层

统一桥接函数 `useExternalStore(subscribe, getSnapshot)`：以 `shallowRef` 保存快照，`onScopeDispose` 退订，语义对应 `useSyncExternalStore`（订阅时立即重读一次快照，防止挂载间隙丢失变化）。

- **Pinia（应用级单例）**：`stores/session`（持有 `markRaw(Session)` 与快照，对应 `SessionProvider` 的上下文；焦点/可见性监听由根部 `SessionHost.vue` 负责）、`stores/adminAccess`（对应 `AdminAccessProvider`）。`Session` 实例仍由根组件创建或由测试注入，store 不自行 `new Session`。
- **按 Session 的既有注册表**：未读轮询（`pollerFor(session)`）与企业 SSO 流程（`getSsoFlow(session)`）本身已是以 Session 为键的 WeakMap 单例，组合式函数 `useUnreadCount()`、`useSso()` 直接桥接，不再包一层 Pinia store。
- **`adminAccess` 生命周期**：Pinia 是全局的，而原 Provider 随 `ProtectedLayout` 挂载并带 `loadSpaces` 参数。store 提供 `attach({ loadSpaces })`/`detach()`，由布局组件在挂载、`loadSpaces` 变化和卸载时调用；未 attach 时等价于原 `emptyAdminAccess`（`unavailable`、不放行），epoch 变化时作废并重读，保持“资格读取以会话 epoch 为界、缺省不放行”。
- **provide/inject（页面子树作用域）**：`DocumentOperations`（每个文档详情一份）和评论面板上下文，因为它们的生命周期与页面实例绑定，不应成为全局单例。缺省注入值与原 `createContext` 默认值一致。
- **组合式函数 facade**：保留 `useSession()`、`useAdminAccess()`、`useDocumentOperations()`、`useSso()`、`useUnreadCount()` 等原名，返回值形状与原 hook 一致（`{ session, state }` 等），组件和测试无需直接依赖 Pinia。facade 模块路径固定（`src-vue/SessionProvider.ts` 导出 `useSession`），使 52 处 `vi.mock('../SessionProvider')` 的替身方式可以保留，仅把替身 `context` 改为 `reactive()` 以驱动更新。
- **不解构 facade 返回值**：`setup` 只执行一次，组件与组合式函数保留返回对象（如 `const auth = useSession()`），在渲染、`computed`、`watch` 或事件处理中读取 `auth.session`、`auth.state.epoch`。这样无论真实 store 原地更新快照，还是测试替身整体替换 `context.state`，读取都是当前值；`useAdminAccess()` 等以 `liveView` 代理转发到当前计算值。

### 5. Hooks → composables 的语义对应

`useResource`、`useAdminWrite`、`useAdminResource`、`useCursorList`、`useProgress`、`useFavorites`、`reviews/lifecycle`、`comments/hooks`、`*Query.ts` 等逐个改写为 composables，规则：

- React 中“`key` 变化时 effect 清理并重新执行”改为 `watch(key, run, { immediate: true })` + `onWatcherCleanup`/`onScopeDispose` 中止；“渲染期 `state.key === key` 才使用旧结果”的防迟到判断保留为 `computed`，不能简化掉。
- `useRef(latest)` 保存最新回调的模式改为普通闭包变量在每次参数变化时更新，或直接接收 `MaybeRefOrGetter` 并在调用时 `toValue`。
- `useAdminWrite` 的绑定表、`sending` WeakMap、`deniedReads` 按 session 对象的缓存原样保留；`Binding` 对象不放入响应式容器。
- 评论上下文以 `shallowReactive` 包装持有者的绑定 Map，仅追踪条目增删，使删除同正文评论后“待核对”提示同步失效；条目 `Binding` 保持原始对象身份，`sending` WeakMap 与外部持有者仍使用同一底层表。
- 空间批量写入同样以 `shallowReactive(Map)` 保存跨弹窗/资格重核的未决绑定，仅追踪增删而不代理 `Binding`；资源草稿与回执使用按 Session/epoch 隔离的浅响应式字符串 Map，在同身份重挂载后恢复、换号后清空。
- 每个 composable 迁移后先迁对应的纯 hook 测试（如 `admin/hooks.test.tsx` → `hooks.test.ts`，用最小宿主组件或 `effectScope` 驱动），再迁使用它的页面。

### 6. 路由、守卫与页面加载边界

- `vue-router` 5 + `createWebHistory()`，路径与当前一一对应，含 `/:pathMatch(.*)*` 404 页。
- **不使用 vue-router 的懒加载路由组件**：`component: () => import()` 会在导航确认前下载并阻塞导航，既无法在内容区显示加载态，也会在 `AdminGuard` 判定前下载管理页面，违反现有规格。路由记录引用同步的轻量外壳，外壳内按“受保护布局 → `AdminGuard` → `PageLoadBoundary` → 异步页面”的原顺序渲染。
- 会话门禁保持组件级：`ProtectedLayout` 依据 `state.phase` 渲染 `<Redirect>`（挂载/目标变化时 `router.replace`，携带 `history.state.from`），与原 `<Navigate replace state>` 等价。不改用 `router.beforeEach` 做身份守卫——会话 phase 会在无导航时变化（退出、401、后台恢复），且 `loading` 阶段不应阻塞导航。
- `lazyPage()` 返回自管状态的页面组件：挂载时才调用 loader，加载中立即显示内容区占位（等同 `delay: 0`），结果（含失败）按模块缓存、不重试、无超时；失败统一为 `PageModuleError`，只有仍挂载的实例在渲染中抛出，交由页面边界按渲染来源接管。不使用 `defineAsyncComponent`：其 loader 失败若发生在组件卸载后（A/B 切页、退出），没有存活的边界接管，会成为未处理错误（开发构建抛出、生产构建 `console.error`），而 React.lazy 对此静默。
- `PageLoadBoundary.vue` 以 `onErrorCaptured` 实现错误边界，外层 `:key="epoch:normalizedPath"` 与原实现一致；捕获后按原分类显示“页面资源加载失败/页面显示异常”，记录固定分类日志（替代 `createRoot.onCaughtError`），并返回 `false` 阻止继续传播。
- **错误来源过滤**：Vue 的 `errorCaptured` 还会捕获组件事件处理器、watcher 回调中的错误，而 React 错误边界不会。为保持“业务操作失败不变成整页异常”，边界只接管渲染、setup、生命周期钩子与异步组件加载器来源的错误，其余来源放行给原有业务错误处理。来源判定集中在 `routing/errorSource.ts`，同时兼容开发构建的文字 `info` 与生产构建的 `runtime-<code>` 形式，并对两种形式单测。
- 不使用 Vue 实验性的 `<Suspense>`。

### 7. Element Plus 映射与共享包装

原 antd 用法直接映射：Button→`ElButton`（`type="primary"`、`native-type="submit"`、`loading`、`disabled`）、Space→`ElSpace`、Tag→`ElTag`（颜色映射到 `type`）、Input/Input.Password→`ElInput`（`type="password"`、`show-password`）、Form→`ElForm`+`rules`+`validate()`、Descriptions→`ElDescriptions`、Table→`ElTable`+`ElTableColumn` 作用域插槽、Modal→`ElDialog`、Drawer→`ElDrawer`、Select→`ElSelect`、Empty→`ElEmpty`、Checkbox、Pagination 同名组件。

差异较大的点用少量共享包装组件统一处理，避免逐页各自修补：

- `KbAlert`：`ElAlert` 默认可关闭而 antd 默认不可关闭，包装固定 `:closable="false"`，并提供 `action` 插槽（ElAlert 无 action 属性）。
- `KbSpin`：纯 CSS 加载指示 + `role="status"`，替代 antd `Spin`，不引入图标库依赖。
- `Feedback`、`PageHeader`、`SafeText`、`References`、`TurnCard` 等原 `components.tsx` 组件改为 SFC，接口不变。
- 弹窗/抽屉：`KbDialog`、`KbDrawer` 以 `open`/`close` 完全受控，经 `before-close` 同步接收关闭请求、由父组件决定去留（对应 antd 的 `open` + `onCancel`，提交中可拒绝关闭），并用 `v-if` 关闭即卸载。Element Plus 的 `close`/`update:modelValue`/`destroy-on-close` 都依赖过渡钩子，不用于关闭语义；这样内容释放不受动画影响，保持原关闭清理语义（如 Blob URL 释放、待核对绑定保留在外部表中）。

### 8. 测试迁移

- 测试栈：Vitest + jsdom + `@testing-library/vue`；共享 `test/render.ts` 提供挂载 Pinia、内存路由（`createMemoryHistory`）和注入 `Session` 替身的 `renderApp()`，替代 `MemoryRouter` 包裹。
- 每个 `*.test.tsx` 对应一个同名 `*.test.ts`，用例标题与数量保持一致；纯 `.test.ts` 原样复制。迁移前记录每个文件的用例清单，切换前比对，差异逐条写入验证记录并说明原因。
- Vue 更新异步：`fireEvent` 后改为 `await`；但原用例中“同步连续点击两次只发一次请求”等测试同步锁语义的断言保持同步，不以 `waitFor` 放宽。
- `.ant-*` 选择器改为角色/标签/文本查询；必要时使用 `el-*` 类名并集中封装在测试辅助函数中。`/重\s*试/` 类正则可保留（对无空格文本同样匹配）。
- `ElSelect`、`ElDialog` 等使用 teleport 的组件，统一通过测试辅助函数 `chooseOption(label, option)` 等交互，避免各测试依赖其内部 DOM。
- 保留 `space-pages.test.tsx` 的 301 次选择与 30 秒限制；如 Element Plus 在 jsdom 中超时，先优化测试辅助与组件渲染，不放宽限制。

### 9. 构建与包体验证

- 迁移起点（TS 6 切换后）对 React 版重新建立正式基线，存入变更目录 `bundle-baseline.json`，沿用上一轮报告口径。
- `bundle-report.mjs` 的路由模块映射改为 Vue 页面入口路径，隔离检查保持同等约束（登录不含账户绑定；列表不含评论、版本审核、Office 预览；管理与普通页面互不内联）。
- 门槛：隔离检查必须通过；入口/登录、文档列表累计 gzip 不得超过基线 10%，超出须在验证记录中给出构成分析并经确认后方可切换；其余路由记录差异。
- 跨栈比较仅经显式 `--allow-lock-change` 放开锁文件相等要求，报告同时登记两个锁摘要；Node/npm、模式、公开开关、压缩口径仍须一致。补入旧报告未单列的 `lifecycle` 场景时以 `--new-scenarios lifecycle` 标记无基线，不影响原 26 场景及 10% 门槛。默认比较继续要求相同锁文件和场景。

## Risks / Trade-offs

- [Element Plus 与 antd 交互细节差异（焦点管理、Select 搜索、表单校验时机）导致行为偏差] → 共享包装集中处理差异；以迁移后的逐条测试和关键页面人工走查确认文案、禁用与加载状态一致。
- [`errorCaptured` 与 React 错误边界捕获范围不同] → 来源过滤 + 双模式单测；事件处理器错误不升级为整页异常。
- [响应式代理改变对象身份或引入额外更新，破坏 WeakMap 键、同步锁与迟到丢弃] → 非渲染状态不进 `ref/reactive`，外部核心对象 `markRaw`；对应 hook 测试先于页面迁移。
- [Vue 异步更新使部分原同步断言失效，迁移者倾向用 `waitFor` 放宽] → 用例清单比对 + 评审时专门检查新增 `waitFor` 与超时修改。
- [冻结期间线上缺陷修复需双写] → 修复须同步到 `src-vue` 并在任务中登记；切换前对比冻结起点以来 `src/` 的提交。
- [Element Plus 体积使入口/登录变大] → 显式按需导入 + 按组件样式；超出 10% 门槛需确认。
- [TypeScript 回退到 6 后类型行为差异] → 起点单独切换并验证 React 版不变，与框架迁移解耦。
- [jsdom 下 `ElTable` 等组件的布局计算或 teleport 导致测试脆弱/变慢] → 测试辅助集中封装；测试只断言角色、文本与状态，不断言布局。

## Migration Plan

1. 冻结 `kb-web` 新功能；切换 TypeScript 6 并验证 React 版；建立 React 包体与用例清单基线。
2. 搭建 `src-vue` 并行工程与工具链，复制纯 TS 核心与纯逻辑测试。
3. 依次迁移：桥接与 stores/composables → 路由外壳与边界 → 认证与账户 → 文档 → 评论/审核/通知 → 搜索与助手 → 各管理页面；每步迁移对应测试并通过 `typecheck:vue`/`test:vue`。
4. 包体与隔离验证、用例清单比对、关键页面人工走查（本地开发服务器连接测试网关，由实施者按需执行，不作为自动化门槛）。
5. 切换：删除 React 源码、依赖与配置，`src-vue` → `src`，合并脚本，更新文档；全量 `typecheck`/`test`/`build`/包体报告。
6. 部署沿用现有静态托管流程：先上传新哈希资产，再原子切换 `index.html`，保留旧资产供已打开标签页使用。会话存储键（`kb.local-session`）与 SSO 存储键保持不变，用户无需重新登录，进行中的企业授权可跨发布完成。
7. 回退：重新发布切换前最后一个 React 构建（保留其资产），无后端或数据迁移。代码层面回退为 revert 切换提交。
