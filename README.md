# 企业智能知识库系统

面向企业内部的知识库，提供知识空间、文档版本与审核、权限受控的混合检索和 RAG 问答。业务数据与检索索引统一存储在 PostgreSQL / ParadeDB，原件使用 S3 兼容对象存储；用户端与管理端由独立的 Vue 3 应用提供。

当前仓库包含 **8 个 Maven 模块、1 个前端工程**，以及独立的监控告警和可选 Office 预览部署配置。本文按现有代码、配置和迁移整理；各功能的离线验证记录见对应文档，不能据此认定真实数据库、第三方服务和完整部署链路已验收。

## 功能概览

| 范围 | 当前实现 | 说明 |
| --- | --- | --- |
| 登录与身份 | 本地登录、强制改密、认证代次、账号与部门管理、角色与功能权限、外部身份绑定 | 钉钉、企业微信、飞书浏览器 SSO 默认关闭，只登录已有绑定账号，不自动建号。见[身份管理](docs/admin-identity-web.md)、[企业 SSO](docs/enterprise-sso-configuration.md)、[认证撤销](docs/authentication-epoch.md) |
| 员工生命周期 | 钉钉 Stream 离职事件、独立员工纳管、持久停权、人工复职及本地应急保障 | 默认关闭且独立于 SSO；本地员工账号也可显式纳管，普通启用不能替代复职。见[生命周期运维](docs/lifecycle-operations.md)、[协调发布](docs/lifecycle-rollout.md) |
| 页面加载与恢复 | 按身份和路由加载业务及管理页面，拆分文档列表/详情与登录/账户依赖 | 加载状态和异常局限于页面区域，导航与退出保持可用；失败后可主动刷新，不自动重放写入。见[前端路由加载](kb-web/README.md#路由加载与恢复) |
| 空间与授权 | 空间创建、归档、授权、批量授予与撤销，文档例外授权 | 空间为授权主单位；主体支持用户、角色和部门树。见[空间与文档管理](docs/admin-space-document-web.md) |
| 文档生命周期 | 原件上传、多版本、处理状态、失败重试、独立操作资格重核、软删除、搬迁、密级调整 | 新版本上传被拒后暂停并重核，不自动重传；用户端删除与管理端删除分别校验编辑和空间管理资格；搬迁会清空文档例外授权。见[用户端说明](kb-web/README.md)、[文档密级管理](docs/document-security-level.md) |
| 批量文件导入 | 本地多选／拖拽、固定清单、逐项幂等受理、本人持久任务、跨登录人工恢复 | `/imports` 与 `/imports/:importId`；同名同内容不同项允许新建，响应未知先核对，未完成文件需重选匹配。见[导入 API](docs/document-import-api.md)、[导入运维](docs/document-import-operations.md) |
| 文档资料 | 标题、人工摘要、空间分类树、全局标签，以及文档列表组合筛选 | 分类属于空间，标签可跨空间使用；资料修改作用于整份文档。见[资料与目录](docs/document-metadata-web.md) |
| 审核与协作 | 空间发布策略、固定版本送审/通过/驳回/撤回、个人收藏、版本评论与回复、站内通知 | 解析完成、审核通过和发布是不同状态；评论和审核分别需要对应资格。见[版本审核](docs/document-review.md)、[收藏](docs/document-favorites.md)、[评论](docs/version-comments.md)、[通知](docs/in-app-notifications.md) |
| 文件阅读 | PDF、UTF-8 TXT/Markdown 预览，以及独立授权的原件下载 | 下载拒绝不等于阅读拒绝，重核保持原版本且不自动打开文件；docx/xlsx/pptx 原生只读预览通过可选 ONLYOFFICE 服务提供，默认关闭；doc/xls/ppt 不进入该预览流程。见[Office 预览](docs/office-preview.md) |
| 混合检索 | BM25、向量召回、SQL 内 RRF 融合、rerank、top-k，支持空间/分类/标签/类型/时间组合筛选 | 权限、密级与发布状态在召回时过滤；支持超时、并发与响应大小限制及降级。见[搜索筛选](docs/search-filtering-api.md)、[检索韧性](docs/search-resilience.md) |
| 引用与会话 | 固定文档/版本/分块引用，SSE 单轮接口，私有多轮会话与历史、停止生成、额度结算 | 多轮会话及真实模型调用默认关闭；历史回答读取会重新校验来源权限。见[引用溯源](docs/citation-provenance.md)、[会话历史](docs/conversation-history.md)、[模型接入](docs/chat-model-adapter.md) |
| 解析与索引运维 | 数据库任务队列、领取租约、心跳、退避重试、死信重放、结构化分块、Embedding profile 与重建、有界调用与失租取消 | 解析／重建共享业务槽位，探测独立限时；**第三方解析适配器尚未提供**，缺少时不领取普通解析任务。见[解析接入](docs/third-party-document-parsing.md)、[索引运维页面](docs/embedding-parse-operations-web.md)、[Embedding 调用限界](docs/embedding-rebuild-operations.md) |
| 审计与运营 | 管理/登录审计查询、AI 用量与调用审计、知识库运营报表、对象存储对账 | AI 用量采集默认关闭，对象存储对账默认只报告、不删除。见[审计页面](docs/admin-audit-web.md)、[AI 用量](docs/admin-ai-usage-web.md)、[运营报表](docs/knowledge-operations-reports.md) |
| 监控与告警 | Prometheus 指标、可选追踪、可信数据库快照、独立结算监控、29 类聚合告警、三组 Grafana 面板及钉钉投递 | 独立部署，不接入业务事务；通知采用受控模板与管理入口，不逐任务外发。见[遥测运维](docs/telemetry-operations.md)、[监控告警](docs/monitoring-alerts-operations.md)、[生命周期监控](docs/lifecycle-operations.md#监控语义与排障顺序)、[监控部署](docs/monitoring-deployment-operations.md) |
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
| `kb-alert-notifier` | 8090（通知） / 9090（管理） | 独立钉钉群机器人适配器；接收 Alertmanager 聚合通知，不依赖业务模块、数据库、Redis 或 Sa-Token；两个端口仅供受控内网使用 |
| `kb-eval` | — | 离线检索质量评测与报告 CLI |
| `kb-eval-collector` | — | 核对会话身份、串行调用搜索 API 并输出评测输入的 CLI |
| `kb-web` | 5173（Vite 默认） | Vue 3 / TypeScript 6 / Element Plus，vue-router 5 与 Pinia；独立安装和构建，不属于 Maven reactor |

浏览器通过同源 `/api/**` 访问网关；网关将业务请求转发到 `kb-app`，将 `/api/ai/**` 转发到 `kb-ai`。网关、应用与 AI 通过 Redis 共享 Sa-Token 会话。当前路由使用配置的服务地址，不依赖注册中心。

受保护请求同时核对当前启用事实和服务端认证代次：app 直接查询，gateway / AI 经独立签名的内部认证接口查询，不跨请求缓存通过结果。三个服务必须配置相同的 `KB_AUTH_INTERNAL_SECRET`（至少 32 UTF-8 字节），gateway / AI 的 `KB_APP_BASE_URL` 指向固定内部 app；该检查不依赖 SSO、生命周期订阅或多轮会话开关。缺少配置或身份依赖不可用时失败关闭，不能仅凭 Redis 中仍有令牌继续访问。

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
| 独立监控（可选） | Prometheus 3.5.0、Alertmanager 0.28.1、Grafana 12.1.1；镜像引用见[固定版本清单](ops/monitoring/versions.env) |
| 终端 | 本文命令使用 PowerShell 7+，默认从仓库根目录执行 |

[根目录 Compose](docker-compose.yml) 只启动 ParadeDB、Redis 和 MinIO，不包含 Java 服务、前端、模型或解析服务。ParadeDB 与 MinIO 使用 `latest` 标签；部署时应固定实际验证过的镜像版本，并核对数据库扩展与迁移语法兼容性。

[监控 Compose](ops/monitoring/compose.yml) 与根目录开发编排相互独立，使用固定版本和 digest，不会随根目录的 `docker compose up -d` 自动启动。监控服务不包含业务应用或其数据库，部署前必须准备独立配置、实际管理目标、凭据文件和已发布的通知适配器镜像，详见下文。

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

既有单文件上传路径在桶不存在时会尝试建桶，需要相应存储权限；读取与批量导入专用 PUT 不会自动建桶，批量导入部署需事先准备既有配置桶。

### 2. 构建并启动业务应用

```powershell
mvn install '-DskipTests'
mvn -pl kb-app spring-boot:run '-Dspring-boot.run.profiles=dev'
```

第一条命令安装模块间依赖并打包，跳过测试；成功后再运行第二条。等待 `kb-app` 完成 Flyway 迁移和 Embedding profile 登记，再启动 Worker。

正式迁移位于 [db/migration](kb-app/src/main/resources/db/migration)，当前最高版本为 **V36**，包含 V18.1；V29–V35 引入人工启用状态、认证代次、生命周期登记/事件/权限、轮次代次及输出停止证据，V36 新增批量导入任务、文件项和上传尝试账本。V3/V6 位于 [db/dev](kb-app/src/main/resources/db/dev)，仅 `dev` profile 加载。不要在已有生产库上切换到 `dev` 来补种子，也不要用 Flyway repair 跳过迁移问题；存量系统升级须遵守[生命周期协调发布](docs/lifecycle-rollout.md)及[导入升级与回退](docs/document-import-operations.md#对账升级与回退)。

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

上述四个业务服务未显式选择 profile 时默认使用 `prod`，不会自动采用本地数据库、存储和 Redis 配置。统一启动检查仅对显式 `dev` 豁免生产规则；`test`、`staging` 等其他 profile 也需要满足生产要求。`dev` 与 `prod` 不能同时启用，模块自身的模型、Embedding 和会话配置校验仍会执行。`kb-alert-notifier` 不使用这套开发默认配置，需按独立监控说明提供完整参数与 secret 文件。

生产排障允许为普通业务包设置 `DEBUG` / `TRACE`。SQL Mapper、MyBatis、Spring JDBC 和 Sa-Token 的敏感日志仍受限制，包括从父级包继承的调试级别；`sa-token.is-log=true` 仍会阻止启动。检查入口与配置示例见[启动配置检查说明](docs/p0-hardening.md#启动配置检查与排障日志)。

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
- **监控通知需要单独部署。** 应用指标不等于告警链路；未配置 Prometheus、Alertmanager 和通知适配器时不会自动发送钉钉。关闭会话或 AI 用量采集也不会停止 app 对历史结算账本的只读采样。

## 功能配置

### 批量文件导入

从文档中心“批量导入文件”或主导航“我的导入任务”进入。每批固定一个空间和密级，文件标题可逐项编辑；同一项重试返回原受理回执，不同项及新批次允许同名同内容的新文档。响应未知先核对持久任务，未完成文件重新选择并匹配名称、大小和 SHA-256 后由用户主动继续。

| 配置 | 默认值 | 作用 |
| --- | ---: | --- |
| `KB_IMPORT_MAX_ITEMS` / `KB_IMPORT_MAX_TOTAL_BYTES` | 100 / 1073741824（1 GiB） | 单批项数与总字节上限 |
| `KB_IMPORT_MAX_CONCURRENT` / `KB_IMPORT_MAX_CONCURRENT_PER_USER` | 8 / 2 | 每实例及每用户每实例实际在途上限，容量不足返回 429 |
| `KB_IMPORT_ATTEMPT_TIMEOUT_SECONDS` / `KB_IMPORT_STORAGE_TIMEOUT_SECONDS` | 120 / 60 | 尝试期限与完整存储调用预算（秒） |
| `KB_IMPORT_RECOVERY_INTERVAL_MS` / `KB_IMPORT_RECOVERY_BATCH_SIZE` | 30000 / 100 | 到期尝试恢复间隔（毫秒）与单轮上限 |

单文件默认 50 MiB，`KB_MAX_UPLOAD_SIZE` 与 `KB_MAX_UPLOAD_BYTES` 必须等值；`KB_MAX_UPLOAD_REQUEST_SIZE` 默认 51 MiB，`KB_UPLOAD_TEMP_DIRECTORY` 可指定上传临时卷。浏览器最多并行上传两项，网关导入响应等待 150 秒、前端单项等待 180 秒。参数范围、入站体限时与磁盘配置见[导入运维](docs/document-import-operations.md)。

上传资格撤销时清理整个任务的旧文件描述和本地选择，迟到响应不能恢复这些内容；重新取得资格后仍需重选并主动继续。已受理回执与固定版本的解析、审核、发布及阅读资格分别处理。对象对账同时保护存活版本和尚未持久失效的 ACTIVE 尝试，单纯期限到达不会解除保护。

### Embedding、rerank 与解析

`kb-app` 和 `kb-worker` 使用显式的 `kb.embedding.profiles` 清单。两侧 profile ID、模型来源、revision、维度和转换语义必须一致；当前存储固定为 1024 维，不支持仅改配置就跨维度切换。开发本地向量与本地 token overlap rerank 用于确定性开发验证，不代表真实语义检索效果。

外部 Embedding 配置、profile 指纹、首次激活、模型切换和失败恢复见 [Embedding 运维](docs/embedding-rebuild-operations.md)。rerank 默认 `local`，可通过 `KB_RERANK_PROVIDER`、`KB_RERANK_BASE_URL`、`KB_RERANK_API_KEY`、`KB_RERANK_MODEL` 配置远程兼容服务；失败时按既定降级规则保留 RRF 顺序。

第三方解析需要在 Worker 提供实现 `DocumentParser` 的 Spring Bean，尚无可直接填写 URL 即启用的内置供应商协议。结构化分块、向量生成、任务租约与发布仍由 Worker 处理；死信与恢复见[解析任务运维](docs/parse-task-recovery-operations.md)。

#### Worker Embedding 调用限界

普通解析、向量重建和每个 profile 的就绪探测使用统一的请求／响应限额与严格 JSON 校验。运行限额通过 `kb.worker.embedding` 绑定，不参与 profile 指纹；非法值阻止 Worker 启动。

| 环境变量 | 默认值 | 作用 |
| --- | ---: | --- |
| `KB_WORKER_EMBEDDING_MAX_REQUEST_BYTES` | 1048576（1 MiB） | 单次 HTTP 请求实际 JSON UTF-8 字节上限 |
| `KB_WORKER_EMBEDDING_MAX_RESPONSE_BYTES` | 4194304（4 MiB） | 响应正文字节上限 |
| `KB_WORKER_EMBEDDING_MAX_JSON_DEPTH` | 64 | 响应 JSON 最大深度 |
| `KB_WORKER_EMBEDDING_REBUILD_TIMEOUT_SECONDS` | 300 | 单个领取批次的完整重建生成预算 |
| `KB_WORKER_EMBEDDING_PROBE_TIMEOUT_SECONDS` | 10 | 每个 profile 的独立探测预算 |
| `KB_TASK_TIMEOUT_SECONDS` | 900 | 原 `kb.worker.task-timeout-seconds`，覆盖普通解析整条流水线 |

- HTTP 子批同时受 profile 的 `batch-size` 与编码字节限制；所有单条输入先预检，任一单条超限则首发前失败。子批共享父截止，全部成功后才交付整份文档或重建批次，保留原数据库原子提交边界。
- 每实例固定一个解析／重建共享业务槽位、一个独立探测槽位，领取前准入且无业务等待队列。超时、失租或关闭触发取消，底层工作与取消动作实际退出后才释放容量。
- 网络故障、超时、HTTP 408／429／5xx 使用任务级退避；超限、协议错误和认证拒绝使解析进入死信、重建进入 `BLOCKED`。非 200 响应正文不消费，传输层不自动重试或跟随重定向。
- `kb.worker.embedding.execution.stops{purpose,reason}` 在首次逻辑停止时即时计数；结合 `.occupied`／`.lingering` 定位仍占用的执行。`calls`／`call.duration` 按完整 Embedding 调用返回计量，具体口径与限额调优见 [Embedding 运维](docs/embedding-rebuild-operations.md)。

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

网关基础配置与 `application-dev.yml` 均将 `/api/conversations/**` 转发到 app，将 `/api/ai/**` 转发到 AI，并保持 `/internal/**` 不对浏览器转发。路由存在不代表会话已启用，仍需完成上述开关与服务配置。外层反向代理还需关闭 SSE 缓冲，并允许足够的连接空闲时间。

### 钉钉员工离职与认证撤销

生命周期订阅使用独立的 `kb.identity.lifecycle.dingtalk` 配置，默认 `enabled=false`，只接收一个配置企业内部应用的 Stream 事件。成员核验、受管关系、持久限制和恢复任务不随接收开关关闭；SSO 绑定不等于纳管，平台故障也不代表所有员工离职。

启用前按[钉钉接入配置](docs/dingtalk-stream-adapter.md)核对企业、内部应用凭据、`client-id` 与事件 `unified-app-id`、成员可见范围和 `user_leave_org` 订阅；不能假定两个应用标识相同。秘密通过受控渠道注入，不复用 SSO、Office、轮次协议或通知机器人密钥。

- **先应急、再纳管、最后启用订阅。** 合格的非受管候选本人使用本地密码会话、完成改密并再次验证密码后登记；系统保护最后一个合格保障的全部资格收缩路径，轮换先建后换。见[应急保障](docs/lifecycle-emergency.md)。
- **四项权限分别分配。** `identity:lifecycle:read`、`identity:lifecycle:manage`、`identity:lifecycle:restore`、`identity:emergency:manage` 互不蕴含，普通账号写资格或管理员角色名称不能代替。受控接口见[生命周期管理 API](docs/lifecycle-management-api.md)。
- **普通启停与复职分开。** 离职限制需独立核对和确认解除；人工停用仍保留，旧令牌永久失效。普通管理写入及相关冲突重读会刷新独立生命周期摘要；未知结果保留原绑定，放弃后须重读事实并重新核验确认，不自动重发。见[管理页面](docs/admin-identity-web.md#员工生命周期管理补充)。
- **输出停止与结算分开。** 所有单轮、多轮和占位输出受最长 10 秒的独立身份许可约束，失败或过期先停止输出并取消上游，再按原执行受限收尾。内部可信撤销与依赖未知使用不同归因，不能把任意 401/403 都记为已确认离职。

访问撤销目标从有效事件可靠持久接收起算为 30 秒，不包含钉钉投递前延迟；不能以 Redis 清理或费用结算完成替代停止输出，也不能承诺撤回已交付内容或供应商停止计费。真实跨实例时限尚未实测，接入、监控证据和发布边界见[生命周期运维](docs/lifecycle-operations.md)与[协调发布方案](docs/lifecycle-rollout.md)。

### 其他可选能力

| 能力 | 默认行为与启用入口 |
| --- | --- |
| 企业 SSO | 三家连接均关闭；配置回调、企业身份与已有账号绑定后启用，见[SSO 配置](docs/enterprise-sso-configuration.md) |
| Office 原生预览 | app / gateway 的 `KB_OFFICE_PREVIEW_ENABLED=false`；需要隔离预览 Origin、服务密钥及 ONLYOFFICE，前端配置 `VITE_OFFICE_PREVIEW_ORIGIN`。独立编排见 [ops/office/compose.yml](ops/office/compose.yml)，部署步骤见[Office 预览](docs/office-preview.md) |
| AI 用量采集 | `kb.ai.observability.enabled=false`；app / AI 需要一致开关及独立签名密钥，见[AI 用量运维](docs/ai-usage-operations.md) |
| 对象存储对账 | 周期扫描，默认只报告；删除需显式启用 `KB_STORAGE_RECONCILIATION_DELETE_ENABLED`，见[存储对账](docs/storage-reconciliation-operations.md) |
| 链路追踪 | `KB_TELEMETRY_TRACING_ENABLED=false`；启用需 OTLP 接收地址，Prometheus 指标独立于追踪开关，见[遥测运维](docs/telemetry-operations.md) |

服务端密钥与内部地址通过受控配置注入，`VITE_*` 会进入浏览器构建产物，只放公开配置。

## 监控与钉钉告警

监控由 [ops/monitoring/compose.yml](ops/monitoring/compose.yml) 独立交付，职责如下：

```text
app / Worker / AI / gateway 管理端口
                  |
                  v
              Prometheus ----> Grafana
                  |
                  v
              Alertmanager ----> kb-alert-notifier ----> 钉钉群机器人
```

### 采样、告警与面板

- Worker 的 parse / embedding、app 的 settlement 使用来源级完整快照，导出可用性和最后成功时间；默认每 30 秒采样，监控 SQL 默认超时 3 秒。抓取不查询数据库，采样失败保留旧值但标记不可用，不伪造空队列。
- 数据库 Gauge 先按实例筛选存活与新鲜样本，再在同一环境、部署内取 `max` 去重；Counter 逐序列处理重置后汇总，直方图合桶后计算分位数。一个部署 ID 只代表一套业务数据库。
- 持久结算直接监控 `PENDING`、`REVIEW` 和最老终结年龄，包含退避中或已领取的 PENDING，排除正常 `RESERVED` / `SETTLED`；会话及 AI 观测开关关闭后仍监控遗留账本。
- 固定目录包含 29 类告警，覆盖预期服务失联、指标契约缺失、采样故障、解析与索引、检索与模型、结算及通知链路；新增 9 类生命周期告警区分连接、积压、应急、输入拒绝、事件冲突、依赖故障、许可逾期、已确认撤销超时和目标不可确认。维护状态、低流量、取消与正常拒绝按各自规则处理，阈值是可调整初值，不是已验证的生产 SLO。

| 预置面板 | 固定 UID | 主要内容 |
| --- | --- | --- |
| 服务总览 | `kb-overview` | 存活、指标契约、采样新鲜度、规则求值、通知结果与容量 |
| 解析与索引 | `kb-parse-index` | 队列、死信、租约、解析器本地配置、索引模式与重建进度 |
| 检索与 AI | `kb-search-ai` | 业务结果、召回延迟、降级、模型首字、SSE 首内容、功能开关与独立结算 |

三组共 38 个面板，支持环境与部署切换；未部署、失联、不可用、关闭和无流量分别表示。模型首字与 SSE 首内容 P95 独立统计，SSE 首内容可能是 citation，不能解释成模型首字。数据口径与每条规则的定位方法见[监控告警运维](docs/monitoring-alerts-operations.md)，分桶与采样配置见[遥测运维](docs/telemetry-operations.md)。

### 部署与通知边界

1. 从 [settings.example.json](ops/monitoring/settings.example.json) 准备实际部署配置，填写环境、部署 ID、预期服务、可达的应用管理地址和两个 HTTPS 管理根地址。示例地址不能直接用于部署；容器内的 localhost 不是宿主机应用。
2. 准备独立入站 Bearer、钉钉 token、加签 secret 和 Grafana 密码的只读文件。机器人凭据与企业 SSO 凭据无关，不能用业务用户 satoken 代替通知认证，也不要把 secret 内容放入普通配置、镜像或版本库。
3. 按[监控部署说明](docs/monitoring-deployment-operations.md)渲染发布配置、构建并发布通知适配器镜像，再部署固定版本组件。第三方镜像已固定 tag+digest；自建 `KB_NOTIFIER_IMAGE` 必须提供真实发布引用，仓库未交付已构建的适配器镜像。
4. 首期只支持单套监控、每个机器人一个适配器实例。三个数据组件各有持久卷；UI 仅发布到宿主机回环地址，Grafana 禁止匿名访问且不启用第二套告警引擎；适配器不发布宿主端口、不经业务网关转发。凭据轮换、升级、卷保留及回退按部署说明执行。

适配器仅通过内部 `POST /internal/alerts/dingtalk` 接收独立 Bearer 认证的聚合通知。消息只含固定运维摘要、数量和受控管理入口，不转发原始注解、正文或用户标识，管理页面仍执行原有鉴权。只有钉钉成功 HTTP 响应且 `errcode` 为整数零才确认 204；外部失败或容量不足返回 503，重试、分组、静默和重复提醒由 Alertmanager 负责。适配器无持久队列或永久去重，回执丢失可能重复，重启会重置内存限流窗口。

本地 health、静态配置通过或容器启动不代表真实送达；整套监控或钉钉出口失效时，同一链路不能保证通知自身。详细接口、限制与排障见[通知适配器运维](docs/alert-notifier-operations.md)，验证范围见[监控验证记录](docs/monitoring-verification.md)。

## 页面与接口入口

页面位于前端地址，导航按当前资格显示：

| 页面 | 路径 |
| --- | --- |
| 文档中心、知识搜索、收藏 | `/documents`、`/search`、`/favorites` |
| 批量文件导入、本人任务详情 | `/imports`、`/imports/:importId` |
| 版本审核、站内通知、个人账户 | `/reviews`、`/notifications`、`/account` |
| AI 助手 | `/assistant` |
| 用户、部门、角色 | `/admin/users`、`/admin/departments`、`/admin/roles` |
| 员工生命周期、事件处置与应急保障 | `/admin/identity/lifecycle`（账号详情另有独立权限面板） |
| 空间、文档管理、全局标签 | `/admin/spaces`、`/admin/documents`、`/admin/document-tags` |
| 向量重建、解析死信 | `/admin/embedding`、`/admin/parse/dead-letters` |
| 管理与登录审计 | `/admin/audits` |
| AI 用量、调用审计、运营概览 | `/admin/ai/usage`、`/admin/ai/audits`、`/admin/reports/operations` |

业务 API 统一经网关 `http://localhost:8080/api/**` 访问。搜索使用 `POST /api/search` 的 JSON 请求体，不使用 URL 查询参数。文档上传使用 `POST /api/documents` 的 multipart 请求体。管理写入通常需要 `X-Request-Id`，有版本前置条件的操作还需读取最新版本值。

批量导入使用 `POST /api/document-imports` 创建固定 JSON 清单，再逐项上传文件。发布顺序为全实例暂停对象删除 → app 迁移与升级 → 恢复清理配置 → gateway → web；旧 app 删除扫描不可与新导入混跑。回退保留账本和受理历史，并在回退旧 app 期间保持删除关闭。

开发环境可直连 `kb-app` 或 `kb-ai` 的 `/doc.html`、`/v3/api-docs` 查看接口定义；当前网关没有转发这些文档路径。生产环境应按部署网络策略限制接口文档和管理端点访问。

## 构建与验证

以下为开发者按需执行的检查命令，不表示本次 README 更新重新运行了测试或外部服务验证。**后端全量测试含 HTTP 监听型用例和可选真实 PostgreSQL 用例，不是纯离线检查入口**；受限环境及监控专项回归应使用对应文档中的测试白名单。

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

### 批量文件导入核验

`batch-document-import` 已于 2026-11-08 同步 3 份主规格并归档，新增 27 条、修改 3 条需求，任务 **38/38** 完成，独立核验为 **0 CRITICAL / 0 WARNING / 0 SUGGESTION**。行为契约见[导入主规格](openspec/specs/document-imports/spec.md)，30 条需求与 80 个场景映射及撤权竞争复核见[归档核验报告](openspec/changes/archive/2026-11-08-batch-document-import/verification-review.md)。

实施阶段后端显式离线白名单 **178 项**、前端白名单 **125 项**通过；权限竞争修复后导入模块 **9 个文件 / 43 项**通过，最终独立核验另有 **2 项**实际 Session/API 配合内存传输实验通过。各组有重叠，分别记录、不累计；类型检查、生产构建和包体隔离通过，完整命令及边界见[实施验证记录](openspec/changes/archive/2026-11-08-batch-document-import/verification.md)。同步后主规格普通校验 **60/60** 通过，含 `admin-web-access` 的既有长文本警告。

### Worker Embedding 调用限界核验

`harden-worker-embedding-calls` 已于 2026-11-07 同步三份主规格并归档，包含 17 条新增、4 条修改要求；任务 **34/34** 完成，核验为 **0 CRITICAL / 0 WARNING / 0 SUGGESTION**。行为契约见 [Worker 调用限界主规格](openspec/specs/worker-embedding-resilience/spec.md)，21 条需求与 76 个场景的代码／测试映射见[归档核验报告](openspec/changes/archive/2026-11-07-harden-worker-embedding-calls/verification.md)。

初次统一离线回归 **55 类 / 241 项**通过（common 22、app 36、Worker 183）；W1 即时停止观测与 W2 三方竞争修复后的影响回归 **20 类 / 103 项**通过，两次均为 0 失败／错误／跳过，结果分别记录、不累加。完整命令与测试白名单见[离线验证记录](docs/embedding-offline-validation.md)，同步及 README 更新沿用这些已完成的执行证据。

三份目标主规格严格校验通过，全库 59 份主规格普通校验通过，含 `admin-web-access` 的既有超长描述提示。离线组件采用内存仓储和 HTTP Session 替身；真实网络终止、数据库锁／事务回滚、供应商计费与部署联调属于报告中明确列出的验证边界。

### 钉钉员工离职变更核验

`dingtalk-employee-offboarding` 已同步 7 份主规格并归档，含 36 项新增及 3 项修改要求。行为契约见[生命周期主规格](openspec/specs/enterprise-account-lifecycle/spec.md)，独立核验和修复历史见[归档报告](openspec/changes/archive/2026-11-06-dingtalk-employee-offboarding/verification.md)。

归档前最新独立运行：后端显式离线白名单 **87 类 / 493 项**、前端相关白名单 **7 文件 / 141 项**通过，类型检查和生产构建通过，监控配置行为 **163 项**通过。核验结论为 **0 CRITICAL / 0 WARNING / 1 SUGGESTION**；104 个场景的代码/直接或组合证据映射不等于全部经过动态验收。这些不是全量测试，本次同步、归档和 README 更新未重新运行业务测试。

监控输入生成不等于规则执行：最新核验因缺少 `promtool`，规则语法和合成时序执行仍为 **Not verified**；没有执行真实数据库迁移/并发、企业联调、跨实例 30 秒实测或部署演练。归档保留上述限制，不将其改写为全部验证通过。同步时 7 份目标主规格严格校验通过、58 份主规格普通校验通过；全库严格校验仍有一项与本变更无关的 `admin-web-access` 既有超长描述警告，未改动该规格。

### 前端路由懒加载与包体分析

当前前端已迁至 Vue 3，类型检查使用 `vue-tsc`，组件测试使用 `@testing-library/vue`，测试文件为 `*.test.ts`。原 88 文件、1104 项用例完整保留，另新增 26 项框架绑定与边界测试；变更已于 2026-11-08 归档，迁移前后包体及验证结果见[迁移记录](openspec/changes/archive/2026-11-08-migrate-web-to-vue3/verification.md)。

登录、强制改密和 SSO 结果页随入口直接可用，业务及管理页面在当前身份和路由资格通过后按需加载，不因菜单展示或悬停主动预取。文档列表不再依赖详情专用评论、版本审核与 Office 预览界面，登录和改密不再依赖账户绑定面板。

首轮优化使用同一锁文件、Node/npm、生产模式和公开构建开关比较，累计冷启动 gzip 字节如下；统计包含入口、目标页面及其静态共享依赖，并按文件去重。

| 场景 | 拆分前 | 拆分后 | 降幅 |
| --- | ---: | ---: | ---: |
| 入口 / 登录 | 409,707 | 223,276 | 45.50% |
| 文档列表 | 409,707 | 354,522 | 13.47% |
| 文档详情 | 409,707 | 383,591 | 6.37% |

在 `kb-web` 目录执行 `npm run bundle:test`、`npm run build` 和 `npm run bundle:report -- --check-isolation --out .bundle-analysis/current-report.json`，可校验报告脚本、生成生产资产并检查依赖隔离。与历史基线比较的完整命令见[前端构建与包体分析](kb-web/README.md#构建与包体分析)；manifest、模块映射和临时报告保存在本地 `.bundle-analysis`，不随 `dist` 发布。

变更已同步主规格并归档，完整包体数据和离线测试证据见[实施记录](openspec/changes/archive/2026-11-06-frontend-route-lazy-loading/verification.md)与[核验报告](openspec/changes/archive/2026-11-06-frontend-route-lazy-loading/verification-review.md)。这些字节数据不代表真实网络首屏耗时；验证未启动前端、真实浏览器或外部组件，也未执行说明文档内容断言测试。

### 会话路由与文档资格恢复回归

以下定向回归覆盖 dev/prod 会话路由边界、下载与上传独立资格重核、迟到响应隔离，以及空间批量撤销的 300/301 条边界；不会启动监听服务或真实浏览器。`-o` 要求 Maven 依赖已缓存。

```powershell
mvn -o -pl kb-gateway -am test '-Dtest=ConversationRouteTest,SearchRouteTest,ReviewRouteTest,NotificationRouteTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
if ($LASTEXITCODE -ne 0) {
    throw '网关配置回归失败'
}

Push-Location .\kb-web
try {
    npm test -- --run src/documents/recovery.test.ts src/admin/space-pages.test.ts
    if ($LASTEXITCODE -ne 0) {
        throw '文档资格恢复或空间授权回归失败'
    }
} finally {
    Pop-Location
}
```

本次修复已同步至[会话主规格](openspec/specs/user-web-conversations/spec.md)和[文档主规格](openspec/specs/user-web-documents/spec.md)。[2026-11-06 归档验证记录](openspec/changes/archive/2026-11-06-fix-conversation-routing-and-document-recovery/verification.md)记载完整前端 84 个文件、1038 项通过及相邻网关 7 项通过；这些是模拟 DOM 与进程内配置验证，不代表实际网络 SSE、浏览器下载或外部服务验收。

### 监控专项离线验证

先准备 Maven 本地依赖缓存，以及[工具清单](ops/monitoring/versions.json)指定的 Windows amd64 promtool 3.5.0、amtool 0.28.1 和 Compose CLI 2.39.2。工具目录结构见[监控部署说明](docs/monitoring-deployment-operations.md)，脚本不自动下载工具。

```powershell
# 独立通知模块编译、测试和打包；-o 要求依赖已缓存
mvn -o -pl kb-alert-notifier package
if ($LASTEXITCODE -ne 0) {
    throw '通知模块验证失败'
}

# 改成已准备工具的实际目录；检查结果写入系统临时目录
$tools = 'C:\tools\kb-monitoring'
$scratch = Join-Path ([IO.Path]::GetTempPath()) 'kb-monitoring-checks'
pwsh -NoProfile -File .\ops\monitoring\scripts\verify.ps1 -ToolDirectory $tools -ScratchDirectory $scratch
if ($LASTEXITCODE -ne 0) {
    throw '监控静态检查失败，请查看 verification-report.json'
}
```

统一入口校验工具版本/哈希、生成配置、合成规则时序、面板查询、Alertmanager 路由及 Compose 语义，不连接 Docker daemon、不启动外部服务、不发送真实通知。缺工具或前置失败会明确失败/标记未执行；默认生成的是含无效 secret 和不可发布镜像占位值的静态 fixture，**不能用于部署**。业务指标与遥测的 Java 白名单、缺工具分支命令及分次验证结果见[监控验证记录](docs/monitoring-verification.md)。

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
- **协调发布身份撤销协议。** V29–V35 升级前排空旧 app / gateway / AI 及在途生成、Office 连接和旧写入，配置独立认证秘密后再恢复流量；无可信代次的历史令牌统一重登，不回填代次。不能直接回退到绕过新门禁的旧二进制，也不能通过关闭订阅解除既有限制。见[生命周期发布与止损](docs/lifecycle-rollout.md)。
- **引导首个管理员。** 新的正式库通过 `KB_BOOTSTRAP_ADMIN_USERNAME`、`KB_BOOTSTRAP_ADMIN_PASSWORD` 创建管理员，可配置显示名、字典内密级与部门，首次登录强制改密。存在有效管理员时引导跳过，不重置既有密码或恢复已撤销权限；默认引导密级为 0，管理资格不授予文档阅读权。
- **隔离管理网络。** 四个业务服务配置独立的 `KB_MANAGEMENT_PORT` 与明确的 `KB_MANAGEMENT_ADDRESS`；管理端点没有应用内认证，需由网络层限制访问。健康探针和指标抓取改用管理端口，不为容器访问便利放宽为通配绑定，见[遥测运维](docs/telemetry-operations.md)。通知适配器使用独立的 `KB_NOTIFIER_*` 端口与地址配置。
- **部署前端静态产物。** `npm run build` 生成 `kb-web/dist`；先上传新哈希资产，再原子切换 HTML，并为已打开的标签页保留旧资产，回退时也保留被回退版本的资产。HTML 禁止缓存或要求重新验证，哈希资产可长期缓存；页面路由回退到 `index.html`，缺失 JS/CSS 返回真实 404，`/api/**` 保留后端真实状态和 SSE 流。详见[前端运维](docs/user-web-operations.md)。
- **按顺序启用告警。** 先升级提供新指标契约的应用，再部署独立监控并启用规则；回退旧应用前停用依赖新契约的规则，保留通用存活监控，不能把缺失指标当作零或恢复。监控阈值、网络、凭据和持久数据按[监控部署说明](docs/monitoring-deployment-operations.md)管理。

## 目录导航

```text
kb-common/          公共模型、协议和约束
kb-app/             业务服务与 Flyway 迁移
kb-gateway/         API 网关
kb-worker/          解析、索引与重建任务
kb-ai/              模型适配、RAG 和流式会话
kb-alert-notifier/  独立钉钉聚合告警适配器
kb-web/             用户端与管理端
kb-eval/            离线检索评测
kb-eval-collector/  检索结果采集
db/                 数据库验证脚本
docs/               API、功能、运维与验证记录
ops/office/         可选 Office 预览部署配置
ops/monitoring/     独立监控编排、规则、面板、配置生成与离线校验
openspec/           当前规格与归档变更
docker-compose.yml  本地开发中间件
```
