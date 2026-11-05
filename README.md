# 企业智能知识库系统

面向企业内部的知识库，提供知识空间、文档版本与审核、权限受控的混合检索和 RAG 问答。业务数据与检索索引统一存储在 PostgreSQL / ParadeDB，原件使用 S3 兼容对象存储；用户端与管理端由独立的 React 应用提供。

当前仓库包含 7 个 Maven 模块和 1 个前端工程。本文按现有代码、配置和迁移整理；各功能的离线验证记录见对应文档，不能据此认定真实数据库、第三方服务和完整部署链路已验收。

## 功能概览

| 范围 | 当前实现 | 说明 |
| --- | --- | --- |
| 登录与身份 | 本地登录、强制改密、账号与部门管理、角色与功能权限、外部身份绑定 | 钉钉、企业微信、飞书浏览器 SSO 默认关闭，只登录已有绑定账号，不自动建号。见[身份管理](docs/admin-identity-web.md)、[企业 SSO](docs/enterprise-sso-configuration.md) |
| 空间与授权 | 空间创建、归档、授权、批量授予与撤销，文档例外授权 | 空间为授权主单位；主体支持用户、角色和部门树。见[空间与文档管理](docs/admin-space-document-web.md) |
| 文档生命周期 | 原件上传、多版本、处理状态、失败重试、软删除、搬迁、密级调整 | 用户端删除与管理端删除分别校验编辑和空间管理资格；搬迁会清空文档例外授权。见[用户端说明](kb-web/README.md)、[文档密级管理](docs/document-security-level.md) |
| 文档资料 | 标题、人工摘要、空间分类树、全局标签，以及文档列表组合筛选 | 分类属于空间，标签可跨空间使用；资料修改作用于整份文档。见[资料与目录](docs/document-metadata-web.md) |
| 审核与协作 | 空间发布策略、固定版本送审/通过/驳回/撤回、个人收藏、版本评论与回复、站内通知 | 解析完成、审核通过和发布是不同状态；评论和审核分别需要对应资格。见[版本审核](docs/document-review.md)、[收藏](docs/document-favorites.md)、[评论](docs/version-comments.md)、[通知](docs/in-app-notifications.md) |
| 文件阅读 | PDF、UTF-8 TXT/Markdown 预览，以及独立授权的原件下载 | docx/xlsx/pptx 原生只读预览通过可选 ONLYOFFICE 服务提供，默认关闭；doc/xls/ppt 不进入该预览流程。见[Office 预览](docs/office-preview.md) |
| 混合检索 | BM25、向量召回、SQL 内 RRF 融合、rerank、top-k，支持空间/分类/标签/类型/时间组合筛选 | 权限、密级与发布状态在召回时过滤；支持超时、并发与响应大小限制及降级。见[搜索筛选](docs/search-filtering-api.md)、[检索韧性](docs/search-resilience.md) |
| 引用与会话 | 固定文档/版本/分块引用，SSE 单轮接口，私有多轮会话与历史、停止生成、额度结算 | 多轮会话及真实模型调用默认关闭；历史回答读取会重新校验来源权限。见[引用溯源](docs/citation-provenance.md)、[会话历史](docs/conversation-history.md)、[模型接入](docs/chat-model-adapter.md) |
| 解析与索引运维 | 数据库任务队列、领取租约、心跳、退避重试、死信重放、结构化分块、Embedding profile 与重建 | **第三方解析适配器尚未提供**；缺少适配器时不领取普通解析任务。见[解析接入](docs/third-party-document-parsing.md)、[索引运维页面](docs/embedding-parse-operations-web.md) |
| 审计与运营 | 管理/登录审计查询、AI 用量与调用审计、知识库运营报表、对象存储对账、Prometheus 指标与可选追踪 | AI 用量采集与追踪默认关闭，对象存储对账默认只报告、不删除。见[审计页面](docs/admin-audit-web.md)、[AI 用量](docs/admin-ai-usage-web.md)、[运营报表](docs/knowledge-operations-reports.md) |
| 检索评测 | 通过真实搜索 API 采集结果，离线计算质量指标、基线差异及质量门禁 | `kb-eval-collector` 负责采集，`kb-eval` 负责离线评测，均为独立 Java CLI。见[采集说明](docs/retrieval-result-collection.md)、[评测说明](docs/retrieval-evaluation.md) |

权限同时包含功能资格与资源授权。拥有管理权限不自动获得文档阅读、下载、编辑或审核资格，文档可见性还受密级、空间归档和版本发布状态约束。功能权限按持久化 permission code 精确匹配，不依据角色名称放行；相关目录见 [Permission.java](kb-app/src/main/java/com/zhishiku/kb/app/auth/model/Permission.java)。

## 架构与模块

| 模块 | 默认端口 | 职责 |
| --- | --- | --- |
| `kb-common` | — | 公共 DTO、协议、权限与配置约束等，不承载业务数据访问 |
| `kb-app` | 8081 | 业务主应用；身份、授权、文档、检索、会话持久化和管理 API；唯一执行 Flyway 迁移的服务 |
| `kb-gateway` | 8080 | API 路由、Sa-Token 会话校验、客户端身份头净化、SSE 转发及可选 Office 预览代理 |
| `kb-worker` | 8083 | 异步解析、分块、向量生成、版本发布和索引重建；访问 PostgreSQL 与对象存储，不执行 Flyway |
| `kb-ai` | 8082 | RAG 编排、模型调用、流式输出及部门配额；基于 WebFlux / Netty，**不连接数据库**，经 `kb-app` 获取授权材料与持久化会话 |
| `kb-eval` | — | 离线检索质量评测与报告 CLI |
| `kb-eval-collector` | — | 核对会话身份、串行调用搜索 API 并输出评测输入的 CLI |
| `kb-web` | 5173（Vite 默认） | React / TypeScript / Ant Design 用户端与管理端；独立安装和构建，不属于 Maven reactor |

浏览器通过同源 `/api/**` 访问网关；网关将业务请求转发到 `kb-app`，将 `/api/ai/**` 转发到 `kb-ai`。网关、应用与 AI 通过 Redis 共享 Sa-Token 会话。当前路由使用配置的服务地址，不依赖注册中心。

架构背景见[架构决策记录](架构决策记录.md)，配套图见[架构图](架构图-v3.html)。历史设计与实际实现有差异时，以当前代码和配置为准。

## 环境要求

| 依赖 | 仓库要求或配置 |
| --- | --- |
| JDK | 21，Maven 编译目标为 Java 21 |
| Maven | 3.9+ |
| Node.js / npm | Node.js 24+、npm 11+，使用 `kb-web/package-lock.json` 安装 |
| 后端框架 | Spring Boot 3.3.5、Spring Cloud 2023.0.3、MyBatis-Plus 3.5.7、Sa-Token 1.46.0 |
| PostgreSQL | 需要 `pg_search` 与 `pgvector`；迁移使用 `USING paradedb`、`pdb.jieba` 和 `VECTOR(1024)` |
| Redis | 本地编排为 Redis 7，应用默认使用 database 1 |
| 对象存储 | S3 兼容服务，本地编排为 MinIO |
| 终端 | 本文命令使用 PowerShell 7+，默认从仓库根目录执行 |

[根目录 Compose](docker-compose.yml) 只启动 ParadeDB、Redis 和 MinIO，不包含 Java 服务、前端、模型或解析服务。ParadeDB 与 MinIO 使用 `latest` 标签；部署时应固定实际验证过的镜像版本，并核对数据库扩展与迁移语法兼容性。

## 本地开发启动

### 1. 启动中间件

```powershell
docker compose up -d
docker compose ps
```

本地配置与 Compose 对应如下，仅供开发使用：

| 服务 | 地址 | 默认配置 |
| --- | --- | --- |
| PostgreSQL | `localhost:5432` | 数据库 `kb`，用户 `kb`，密码 `kb_dev_pwd` |
| Redis | `localhost:6379` | 无密码，应用使用 database 1 |
| MinIO S3 | `http://localhost:9000` | Access Key `kbminio`，Secret Key `kbminio_dev_pwd`，桶 `kb-documents` |
| MinIO 控制台 | `http://localhost:9001` | 使用上述 MinIO 凭据 |

上传路径在桶不存在时会尝试建桶，需要相应存储权限；读取路径不会自动建桶。

### 2. 构建并启动业务应用

```powershell
mvn install '-DskipTests'
mvn -pl kb-app spring-boot:run '-Dspring-boot.run.profiles=dev'
```

第一条命令安装模块间依赖并打包，跳过测试；成功后再运行第二条。等待 `kb-app` 完成 Flyway 迁移和 Embedding profile 登记，再启动 Worker。

正式迁移位于 [db/migration](kb-app/src/main/resources/db/migration)，当前最高版本为 **V28**，包含 V18.1。V3/V6 位于 [db/dev](kb-app/src/main/resources/db/dev)，仅 `dev` profile 加载。不要在已有生产库上切换到 `dev` 来补种子，也不要用 Flyway repair 跳过迁移问题。

### 3. 分别启动其他服务

在仓库根目录分别打开终端，按需运行以下命令，每条命令占用一个终端：

```powershell
# 网关：前端的 API 入口
mvn -pl kb-gateway spring-boot:run '-Dspring-boot.run.profiles=dev'
```

```powershell
# Worker：解析队列和向量重建
mvn -pl kb-worker spring-boot:run '-Dspring-boot.run.profiles=dev'
```

```powershell
# AI：需要使用问答时启动
mvn -pl kb-ai spring-boot:run '-Dspring-boot.run.profiles=dev'
```

服务未显式选择 profile 时默认使用 `prod`，不会自动采用本地数据库、存储和 Redis 配置。`dev` 与 `prod` 不能同时启用。

### 4. 为开发账号设置浏览器登录密码

开发种子提供 `alice`、`bob`、`carol`、`dave`，但**没有本地密码**。浏览器使用用户名密码登录，不提供按用户 ID 登录的入口。首次在开发种子库中，可通过开发 API 为管理员 `alice`（ID 为 1）设置临时密码：

```powershell
$base = 'http://localhost:8080'
$login = Invoke-RestMethod -Method Post -Uri "$base/api/auth/dev-login?userId=1"
$headers = @{}
$headers[$login.data.tokenName] = $login.data.tokenValue
$headers['X-Request-Id'] = [guid]::NewGuid().ToString()
$temporaryPassword = Read-Host '输入 alice 临时密码（至少 8 位）' -MaskInput
$passwordBody = @{ newPassword = $temporaryPassword } | ConvertTo-Json
Invoke-RestMethod -Method Patch -Uri "$base/api/admin/identity/users/1/password" `
    -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $passwordBody | Out-Null
Remove-Variable temporaryPassword, passwordBody
```

成功后用 `alice` 和刚设置的临时密码登录前端，并按提示完成强制改密。此步骤只用于首次设置；已有密码时直接登录。`dev-login` 仅适用于隔离的开发环境，正式环境使用本地凭据或已配置的企业 SSO。

开发种子中的原件 key（如 `dev/1.md`）只是数据库记录，Compose 不会上传对应文件，不能用这些记录验证下载和预览。请上传实际文件验证文件访问。

### 5. 启动前端

另开终端，从仓库根目录执行：

```powershell
Set-Location .\kb-web
npm ci
npm run dev
```

访问 Vite 输出的地址，默认是 `http://localhost:5173`。开发服务器将 `/api` 代理到 `http://localhost:8080`，可通过 `KB_WEB_GATEWAY` 覆盖。公开构建配置参考 [.env.example](kb-web/.env.example)，完整页面说明见 [kb-web/README.md](kb-web/README.md)。

### 6. 理解默认启动后的状态

- **上传可以受理，但新文件不会自动完成解析。** 仓库尚未提供第三方 `DocumentParser` 适配器，所有格式（包括 TXT/Markdown）都需要外部内容抽取。缺少适配器时 Worker 暂停领取，不消耗解析重试次数，健康状态为 `OUT_OF_SERVICE`。见[第三方解析接入](docs/third-party-document-parsing.md)。
- **配置了向量模型不等于向量检索已启用。** 开发配置注册 `dev-local-v1`（1024 维确定性本地向量），首次控制状态为 `UNINITIALIZED`。需显式发起重建并完成校验进入 `ACTIVE`；此前检索使用 BM25，普通解析还受索引状态门禁限制。即使空库也需要初始化，见[Embedding 初始化与重建](docs/embedding-rebuild-operations.md)。
- **AI 助手默认不生成多轮回答。** 前端、应用和 AI 的会话开关默认关闭，模型适配器也默认关闭。旧单轮接口在没有模型 Bean 时保留确定性占位应答，前端助手不使用该占位接口；启用步骤见下节。
- **进程启动不代表所有依赖就绪。** 显式 `dev` 下可从各服务业务端口访问 `/actuator/health`；Worker 未配置解析器时的非就绪状态是当前预期行为。

## 功能配置

### Embedding、rerank 与解析

`kb-app` 和 `kb-worker` 使用显式的 `kb.embedding.profiles` 清单。两侧 profile ID、模型来源、revision、维度和转换语义必须一致；当前存储固定为 1024 维，不支持仅改配置就跨维度切换。开发本地向量与本地 token overlap rerank 用于确定性开发验证，不代表真实语义检索效果。

外部 Embedding 配置、profile 指纹、首次激活、模型切换和失败恢复见 [Embedding 运维](docs/embedding-rebuild-operations.md)。rerank 默认 `local`，可通过 `KB_RERANK_PROVIDER`、`KB_RERANK_BASE_URL`、`KB_RERANK_API_KEY`、`KB_RERANK_MODEL` 配置远程兼容服务；失败时按既定降级规则保留 RRF 顺序。

第三方解析需要在 Worker 提供实现 `DocumentParser` 的 Spring Bean，尚无可直接填写 URL 即启用的内置供应商协议。结构化分块、向量生成、任务租约与发布仍由 Worker 处理；死信与恢复见[解析任务运维](docs/parse-task-recovery-operations.md)。

### AI 模型与多轮会话

| 配置 | 生效位置 | 用途 |
| --- | --- | --- |
| `KB_CHAT_MODEL_ENABLED` | AI | 默认 `false`，启用通用 OpenAI-compatible 文本流适配器 |
| `KB_CHAT_MODEL_BASE_URL` / `KB_CHAT_MODEL_API_KEY` / `KB_CHAT_MODEL_NAME` | AI | 配置真实模型 API 根地址、密钥和模型名 |
| `KB_CONVERSATION_ENABLED` | app / AI | 默认 `false`，启用持久化多轮会话 |
| `KB_CONVERSATION_SECRET` | app / AI | 两侧使用相同内部签名密钥，至少 32 UTF-8 字节 |
| `KB_APP_BASE_URL` / `KB_AI_BASE_URL` | 按调用方向配置 | AI 访问 app；app 启用会话时访问 AI；网关配置两者 |
| `VITE_CONVERSATION_ENABLED` | 前端构建 | 默认 `false`，启用会话界面；修改后需重新构建或重启开发服务器 |
| `KB_CHAT_BUDGET_MODE` | AI | 默认 `verified`，需要匹配模型身份的严格 token 预算实现；显式 `estimated` 使用本地估算 |
| `KB_CHAT_BUDGET_CONTEXT_WINDOW_TOKENS` | AI | `estimated` 模式必须填写实际模型窗口，并满足预留与输出预算约束 |

仅打开开关不足以保证生成可用：默认 `verified` 缺少匹配预算策略时，多轮生成返回 503；选择 `estimated` 意味着接受估算误差，不能把估算或预留额度当作真实 token 用量。模型协议、限制和预算见[模型接入](docs/chat-model-adapter.md)，跨服务配置见[会话配置](docs/conversation-configuration.md)。

当前网关的 `application-dev.yml` 覆盖了基础路由列表，其中业务路由未包含 `/api/conversations/**`。在开发环境启用多轮会话时，需要在网关开发路由中补齐该路径；基础 `application.yml` 已包含。外层反向代理还需关闭 SSE 缓冲，并允许足够的连接空闲时间。

### 其他可选能力

| 能力 | 默认行为与启用入口 |
| --- | --- |
| 企业 SSO | 三家连接均关闭；配置回调、企业身份与已有账号绑定后启用，见[SSO 配置](docs/enterprise-sso-configuration.md) |
| Office 原生预览 | app / gateway 的 `KB_OFFICE_PREVIEW_ENABLED=false`；需要隔离预览 Origin、服务密钥及 ONLYOFFICE，前端配置 `VITE_OFFICE_PREVIEW_ORIGIN`。独立编排见 [ops/office/compose.yml](ops/office/compose.yml)，部署步骤见[Office 预览](docs/office-preview.md) |
| AI 用量采集 | `kb.ai.observability.enabled=false`；app / AI 需要一致开关及独立签名密钥，见[AI 用量运维](docs/ai-usage-operations.md) |
| 对象存储对账 | 周期扫描，默认只报告；删除需显式启用 `KB_STORAGE_RECONCILIATION_DELETE_ENABLED`，见[存储对账](docs/storage-reconciliation-operations.md) |
| 链路追踪 | `KB_TELEMETRY_TRACING_ENABLED=false`；启用需 OTLP 接收地址，Prometheus 指标独立于追踪开关，见[遥测运维](docs/telemetry-operations.md) |

服务端密钥与内部地址通过受控配置注入，`VITE_*` 会进入浏览器构建产物，只放公开配置。

## 页面与接口入口

页面位于前端地址，导航按当前资格显示：

| 页面 | 路径 |
| --- | --- |
| 文档中心、知识搜索、收藏 | `/documents`、`/search`、`/favorites` |
| 版本审核、站内通知、个人账户 | `/reviews`、`/notifications`、`/account` |
| AI 助手 | `/assistant` |
| 用户、部门、角色 | `/admin/users`、`/admin/departments`、`/admin/roles` |
| 空间、文档管理、全局标签 | `/admin/spaces`、`/admin/documents`、`/admin/document-tags` |
| 向量重建、解析死信 | `/admin/embedding`、`/admin/parse/dead-letters` |
| 管理与登录审计 | `/admin/audits` |
| AI 用量、调用审计、运营概览 | `/admin/ai/usage`、`/admin/ai/audits`、`/admin/reports/operations` |

业务 API 统一经网关 `http://localhost:8080/api/**` 访问。搜索使用 `POST /api/search` 的 JSON 请求体，不使用 URL 查询参数。文档上传使用 `POST /api/documents` 的 multipart 请求体。管理写入通常需要 `X-Request-Id`，有版本前置条件的操作还需读取最新版本值。

开发环境可直连 `kb-app` 或 `kb-ai` 的 `/doc.html`、`/v3/api-docs` 查看接口定义；当前网关没有转发这些文档路径。生产环境应按部署网络策略限制接口文档和管理端点访问。

## 构建与验证

以下为可执行的开发检查命令，不表示本次 README 更新重新运行了全量测试或外部服务验证。

```powershell
# 从仓库根目录执行后端测试与打包
mvn clean verify
```

```powershell
# 前端类型检查、单元测试与静态构建
Push-Location .\kb-web
try {
    npm ci
    npm run typecheck
    npm test -- --run
    npm run build
} finally {
    Pop-Location
}
```

仓库包含权限、业务状态、Mapper 映射、模块边界、AI 无数据库依赖、响应式链路及前端模拟 DOM 等测试。真实 PostgreSQL 烟测由 `KB_TEST_POSTGRES_URL`、`KB_TEST_POSTGRES_USER`、`KB_TEST_POSTGRES_PASSWORD` 指向专用测试库；未配置 URL 时相关用例跳过，跳过不代表数据库行为已验证。

[db/verify_paradedb.sql](db/verify_paradedb.sql)、[db/verify_permission.sql](db/verify_permission.sql)、[db/verify_identity_department.sql](db/verify_identity_department.sql) 提供数据库检查脚本，部分依赖开发种子或创建测试数据，使用前阅读脚本并选择测试库。真实迁移、索引行为、权限隔离、并发发布、SSE 转发和供应商兼容性需要在目标环境另行验证；各专项文档保留其具体离线验证范围。

### 检索结果采集与离线评测

```powershell
# 构建两个独立 CLI
mvn -pl 'kb-eval,kb-eval-collector' -am package '-DskipTests'
java -jar .\kb-eval-collector\target\kb-eval-collector-0.1.0-SNAPSHOT.jar --help
java -jar .\kb-eval\target\kb-eval-0.1.0-SNAPSHOT.jar --help
```

`kb-eval-collector` 使用受控会话调用现有身份与搜索 API，输出最终有序结果，不自动登录、重试或采样内部算法阶段。准备场景、标注和部署配置后，按[采集说明](docs/retrieval-result-collection.md)运行。

`kb-eval` 读取文件进行离线评测，按文档版本计算检索质量、重复率和基线退步，输出 JSON / CSV / Markdown 报告；质量门禁退出码为 0（通过或未配置门禁）、1（未达标）、2（本地错误）。示例位于 [kb-eval/examples/demo](kb-eval/examples/demo)，使用合成数据，不能作为真实模型质量证明。

## 正式部署要点

- **显式提供生产配置。** app / Worker 需要 `KB_DB_URL`、`KB_DB_USERNAME`、`KB_DB_PASSWORD`、S3 配置和 Embedding profile 清单；app / gateway / AI 需共享 Redis 会话配置，网关配置下游地址与明确的 `KB_CORS_ALLOWED_ORIGINS`。生产启动会校验配置，不使用 `dev` 绕过。
- **由 app 单点迁移。** 正式环境只加载 `db/migration`，不加载 V3/V6。升级先核对对应功能的迁移、权限目录和读写协议兼容性；Worker 的 Flyway 保持关闭。
- **引导首个管理员。** 新的正式库通过 `KB_BOOTSTRAP_ADMIN_USERNAME`、`KB_BOOTSTRAP_ADMIN_PASSWORD` 创建管理员，可配置显示名、字典内密级与部门，首次登录强制改密。存在有效管理员时引导跳过，不重置既有密码或恢复已撤销权限；默认引导密级为 0，管理资格不授予文档阅读权。
- **隔离管理网络。** 各服务配置独立的 `KB_MANAGEMENT_PORT` 与明确的 `KB_MANAGEMENT_ADDRESS`；管理端点没有应用内认证，需由网络层限制访问。健康探针和指标抓取改用管理端口，见[遥测运维](docs/telemetry-operations.md)。
- **部署前端静态产物。** `npm run build` 生成 `kb-web/dist`，托管时页面路由回退到 `index.html`，`/api/**` 保留后端真实状态和 SSE 流。详见[前端运维](docs/user-web-operations.md)。

## 目录导航

```text
kb-common/          公共模型、协议和约束
kb-app/             业务服务与 Flyway 迁移
kb-gateway/         API 网关
kb-worker/          解析、索引与重建任务
kb-ai/              模型适配、RAG 和流式会话
kb-web/             用户端与管理端
kb-eval/            离线检索评测
kb-eval-collector/  检索结果采集
db/                 数据库验证脚本
docs/               API、功能、运维与验证记录
ops/office/         可选 Office 预览部署配置
openspec/           当前规格与归档变更
docker-compose.yml  本地开发中间件
```
