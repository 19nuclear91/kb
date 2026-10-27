# Proposal

## Why

知识库已有登录、文档、授权检索和私有会话接口，但缺少供普通用户使用的浏览器入口，现有能力仍依赖接口工具操作。需要交付能够连接现有后端的最小用户端，并补齐上传目标选择及文档操作按钮所需的服务端能力查询。

## What Changes

- 新增独立 `kb-web` 工程，采用 React、TypeScript、Vite、Ant Design，提供面向 PC 浏览器的中文用户端。
- 提供本地登录、强制改密、自助改密、身份查看和退出；统一处理身份失效、业务拒绝及敏感数据清理。
- 提供文档中心、空间筛选、上传弹窗、版本详情、解析状态、指定版本预览和下载、新版本上传及失败重试；搜索覆盖当前用户全部可见文档，引用使用固定版本抽屉。
- 提供私有会话列表、新建、删除、历史、流式问答、停止生成及引用查看；区分会话未启用、模型或依赖暂不可用和正常无结果，不接入具体生产模型。
- 新增两个只读后端入口：`GET /api/auth/upload-spaces` 返回按当前空间 edit 资格过滤的上传候选，`GET /api/documents/{documentId}/capabilities` 返回当前可查看文档的独立操作能力。
- 建立前端类型检查、静态构建、进程内单元测试及运行说明。验证仅使用 Mock/Stub、模拟 DOM 和内存响应；移除集成、端到端、真实浏览器测试及真实服务联调任务，不编写说明文档、配置示例或 Javadoc 文案测试。
- 首期不包含企业扫码及本人绑定页面、管理后台、文档删除与元数据编辑、Office 转换预览、OCR、会话改名/分支/共享、流断点续传或浏览器离线内容缓存。既有后端相应能力保持原契约。

## Capabilities

### New Capabilities

- `user-web-session`：浏览器应用入口、本地会话生命周期、改密门禁、请求适配、部署开关和敏感数据处理。
- `user-web-documents`：用户端文档浏览、上传与版本状态、授权搜索、固定版本引用及文件读取交互。
- `user-web-conversations`：用户端私有会话管理、历史展示、流式轮次、幂等重试及取消后的状态核对。
- `user-resource-capabilities`：上传空间最小候选与当前可查看文档的操作能力查询，保持授权独立及下一请求即时生效。

### Modified Capabilities

无。复用 `user-authentication`、`space-authorization`、`functional-authorization`、`search-query-confidentiality`、`citation-provenance`、`conversation-history` 和 `multi-turn-chat` 的既有后端契约；本变更新增浏览器行为及两个只读查询，不修改这些能力的授权、状态转换和协议。

## Impact

- 新增 `kb-web/` 及其 npm 锁文件、前端静态构建和单元测试配置；独立于六个现有 Maven 模块构建，不引入 Playwright 或端到端测试配置。
- `kb-app` 的 space、document 包新增最小查询 DTO、service/controller 和必要 mapper；复用 `PermissionService`、现有文档授权谓词和查询参数/游标设施，不新增功能权限、数据库迁移或跨请求权限缓存。
- 网关现有 `/api/auth/**`、`/api/documents/**`、`/api/conversations/**` 和 `/api/ai/**` 路由可覆盖调用；开发服务器代理到网关，生产采用同源静态托管与 SPA 回退。
- 前端以显式会话开关匹配 app/AI 部署配置；本变更的开发验证不启动完整应用或 HTTP 监听，不运行/连接 PostgreSQL、Redis、MinIO、容器、嵌入式数据库或真实模型/供应商服务。说明文档继续交付，部署与回退只提供说明，不要求实际执行。
- 登录、文档和搜索的 `R<T>`、会话直接 DTO、SSE/JSON 双响应、文件流分别适配；问题、令牌和正文不进入 URL、遥测或持久内容缓存。
