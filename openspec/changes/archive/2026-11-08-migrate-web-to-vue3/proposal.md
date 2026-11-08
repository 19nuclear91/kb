# Proposal

## Why

团队决定将前端技术栈统一到 Vue 3 生态。`kb-web` 目前基于 React 19、React Router 7 与 Ant Design 6，约 100 个组件/逻辑模块和 100 个测试文件；继续在 React 上叠加功能会扩大后续迁移成本，因此在功能相对稳定、上一轮路由拆分刚完成的时点整体替换框架层，同时保持全部用户可观察行为不变。

## What Changes

- **BREAKING（开发侧）** 将 `kb-web` 的视图层由 React 19 + `react-dom` 替换为 Vue 3，组件统一使用单文件组件 `<script setup lang="ts">`；移除 `react`、`react-dom`、`@vitejs/plugin-react`、`@types/react*`。
- **BREAKING（开发侧）** 路由由 `react-router-dom` 替换为 `vue-router` 5（HTML5 history 模式），保留全部现有路径、登录返回地址规则、`/auth/sso/result` 独立入口、管理守卫与页面级懒加载/失败恢复语义。
- **BREAKING（开发侧）** UI 组件库由 Ant Design 6 替换为 Element Plus，保留中文语言包、主题主色与圆角等视觉令牌；页面文案、可访问名称、角色与交互状态（禁用、加载、待核对）保持一致，具体视觉细节随组件库变化。
- 全局响应式状态中，会话与管理资格通过 Pinia store 暴露，未读通知与企业 SSO 流程沿用既有按 Session 的注册表并以组合式函数桥接；现有与框架无关的 TS 核心（`Session`、`UnreadPoller`、`FavoriteStore`、`DocumentOperations`、`SsoFlow`、各 `api.ts`/`logic.ts`）保持原实现，仅替换 React 绑定层。页面局部作用域状态（如单个文档的操作协调）改用 `provide/inject`。
- React hooks（`useResource`、`useAdminWrite`、`useAdminResource`、`useCursorList`、`useProgress`、`useFavorites`、`useSso` 等）改写为等价的 Vue composables，保持请求标识绑定、互斥、epoch 隔离、迟到响应丢弃与中止语义。
- **BREAKING（开发侧）** 测试由 `@testing-library/react` 迁移到 `@testing-library/vue`，组件测试文件由 `*.test.tsx` 改为 `*.test.ts`，断言语义与覆盖场景逐条保留；纯逻辑测试原样保留。
- 类型检查改用 `vue-tsc`；由于 `vue-tsc` 尚不支持 TypeScript 7，工程改为使用 TypeScript 6 系列。
- 更新包体分析脚本中的模块路径与隔离检查、`kb-web/README.md`、根 `README.md`、`架构决策记录.md` 及仍在使用的文档中的测试命令与技术栈描述；历史归档与验证记录不改写。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

无。现有 `user-web-*`、`admin-web-*` 等规格均为与框架无关的行为描述，本次迁移不改变任何需求或场景，因此本变更设置 `skip_specs: true`。

## Impact

- 代码：`kb-web/src` 下全部 `.tsx` 组件与 React hooks，入口 `main.tsx`、`App.tsx`、`SessionProvider.tsx`、`routing/*`、`admin/access.tsx`、`admin/hooks.ts`、`notifications/unread.tsx`、`documents/OperationContext.tsx` 等绑定层；纯 TS 模块不改或仅做最小适配。
- 依赖：新增 `vue`、`vue-router`、`pinia`、`element-plus`、`@vitejs/plugin-vue`、`vue-tsc`、`@testing-library/vue`、`@vue/test-utils`；移除 React 相关依赖与 `antd`；`typescript` 由 7.x 调整为 6.x；重新生成 `package-lock.json`。
- 构建与工具：`vite.config.ts`、`tsconfig.json`、`npm run typecheck`/`build` 脚本、`scripts/bundle-report.mjs` 页面模块映射与隔离规则；需在迁移前后重新建立包体基线并比较。
- 文档：`kb-web/README.md`、根 `README.md`、`架构决策记录.md`、`docs/` 中引用组件测试文件名或 React 渲染描述的运行说明。
- 团队协作：迁移期间冻结 `kb-web` 新功能；Vue 版本在并行目录实现，React 版本保持可构建、可发布直至一次性切换。
- 不影响：后端服务、网关、API 协议、数据库、部署拓扑与静态托管方式（仍为同源静态资源、页面路径回退到 `index.html`）。
- 范围外：新增业务功能、交互重设计、SSR/SSG、Service Worker、端到端浏览器测试体系、React 与 Vue 长期共存的渐进式微前端方案。
