# 企业智能知识库系统

基于 PostgreSQL（ParadeDB）的企业内部知识库，支持文档管理、权限受控的混合检索与 RAG 问答。

管理界面提供知识空间生命周期、空间与文档例外授权、文档搬迁和密级调整，入口及离线验证范围见[知识空间与文档管理](docs/admin-space-document-web.md)。

审计查询入口为 `/admin/audits`，需要独立 `audit:read`，支持管理与登录事件筛选、固定窗口续页和安全详情，见[审计查询管理页面](docs/admin-audit-web.md)。

AI 用量与调用审计位于 `/admin/ai/usage`、`/admin/ai/audits`，分别要求 `ai:usage:read`、`ai:audit:read`。采集默认关闭，覆盖启用后的单轮与多轮聊天，区分真实 token 与额度结算；见[页面说明](docs/admin-ai-usage-web.md)、[协议](docs/ai-usage-api.md)和[升级运维](docs/ai-usage-operations.md)。仅完成离线验证，未运行真实迁移或第三方服务。

架构决策与取舍全部记录在 **[《架构决策记录.md》](架构决策记录.md)**，配套架构图见 `架构图-v3.png` / `架构图-v3.html`。
本 README 只讲怎么跑起来、以及**哪些东西是真的能用的**。

用户端位于独立的 **[kb-web](kb-web/README.md)**：本地登录与改密、文档中心、授权搜索及可配置的私有会话界面。会话缺省关闭，模型未配置时提示暂不可用。部署与回退见 **[用户端运维说明](docs/user-web-operations.md)**；本次验证范围为内存单元测试、类型检查和静态构建。

---

## 一、当前状态：能跑什么，不能跑什么

P0 阶段交付的是骨架。下面这张表比任何描述都重要——
把"已搭好架子"误当成"功能已完成"，是这类项目最常见的返工来源。

| 能力                          | 状态                                            | 说明                                                                                                                                                                                                                                                                      |
|-----------------------------|-----------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Maven 多模块构建                 | ✅ 已验证                                         | 测试包含权限谓词、HTTP 状态、环境隔离、文件检查与独立 JVM 解析；真实 PG 烟测需另配环境                                                                                                                                                                                                                      |
| 权限模型 DDL                    | ⚠️ 已写完，**未在真实 PG 上执行过**                       | V1/V2/V3/V6 迁移脚本，含开发种子数据                                                                                                                                                                                                                                                |
| 带权限下推的混合检索 SQL              | ⚠️ 已写完，**未在真实 PG 上跑过**                        | 本机无 Docker、无 psql                                                                                                                                                                                                                                                       |
| ParadeDB BM25 + pgvector 索引 | ⚠️ 语法已核对官方文档，**未实际建过索引**                      | 同上                                                                                                                                                                                                                                                                      |
| 模块边界约束（ArchUnit）            | ✅ 已验证                                         | 3 条规则通过                                                                                                                                                                                                                                                                 |
| 任务队列（领取/租约/重试）              | ⚠️ 代码完成，**未连库、未压测**                           | SQL 逻辑见 `ParseTaskMapper` 注释                                                                                                                                                                                                                                            |
| 对象存储与文档上传                   | ✅ 代码已实现，**未连 MinIO 实测**                       | S3 兼容接口；上传事务失败会尽力清理孤儿原件                                                                                                                                                                                                                                                 |
| 文档列表/详情/进度/版本               | ✅ 已实现，**未连真实 PG 实测**                          | 列表、版本历史、新版本上传、解析状态和失败原因                                                                                                                                                                                                                                                 |
| 文档重试/删除                     | ✅ 已实现，**未连真实 PG/MinIO 实测**                    | 失败任务重置；删除为软删除并尽力清理原件，迟到 Worker 不得重新发布                                                                                                                                                                                                                                   |
| 文档下载/预览                     | ✅ 已实现，**未连 MinIO 实测**                         | 下载使用 `download` 权限并以附件返回；预览使用 `view` 权限，仅允许 PDF、UTF-8 TXT/Markdown，其他格式返回 415；响应附带 CSP sandbox 与 nosniff                                                                                                                                                                |
| 对象存储对账（孤儿/缺失）               | ✅ 代码已实现，**未在真实桶/库上运行过**                       | kb-app 周期扫描 `documents/` 前缀：孤儿对象（桶有、库无存活引用）默认只报告，`delete-enabled` 显式开启后按护栏删除；缺失抽样只读报告；单飞 advisory lock、宽限期、删除前复核、单轮上限；配置与启用步骤见[对账运维文档](docs/storage-reconciliation-operations.md)                                                                                       |
| 持久化实体与数据库约束                 | ✅ 已补齐                                         | Lombok + MyBatis 持久化实体；V5 补齐版本归属、对象 key、分块和任务约束                                                                                                                                                                                                                         |
| OpenAPI 接口文档                | ✅ 已接入                                         | Knife4j `/doc.html`、`/v3/api-docs`；上传、检索和 SSE 参数/出参已有注解                                                                                                                                                                                                                 |
| 文档解析与分块 | ⚠️ 第三方服务待接入；分块与发布代码保留 | 所有格式统一由第三方服务抽取内容，本地解析引擎已移除。未接入时暂停领取任务且不消耗重试次数，详见[接入说明](docs/third-party-document-parsing.md) |
| embedding                   | ✅ 写入侧与查询侧适配器已实现                               | 默认 `local` 离线确定性向量；生产可切换 `remote`，两侧模型和维度必须一致                                                                                                                                                                                                                           |
| 混合检索完整链路                    | ✅ 已接入                                         | 查询 embedding → BM25/向量双路召回 → SQL 内 RRF → rerank → top-k；默认使用本地确定性适配器                                                                                                                                                                                                    |
| 离线检索质量评测                    | ✅ 已实现，组件单测与跳过测试的打包已验证                         | 独立 `kb-eval` Java CLI，按文档版本计算质量、重复率及基线退步，输出 JSON/CSV/Markdown 和门禁退出码；使用与验证范围见[评测说明](docs/retrieval-evaluation.md)。未执行完整 CLI 回放或真实服务验证                                                                                                                                   |
| 分块幂等写入与版本发布                 | ✅ 代码已实现，**未连 PG 实测**                          | 事务内删除并重写同一版本分块，租约校验后原子发布                                                                                                                                                                                                                                                |
| AI 服务启动与响应式栈                | ✅ **已实测**                                     | Netty 起在 8082，`/actuator/health` 返回 UP                                                                                                                                                                                                                                  |
| SSE 流式接口                    | ✅ **已实测**                                     | 返回 `delta` + `done` 后连接正常关闭，不挂死                                                                                                                                                                                                                                         |
| SSE 逐字增量到达                  | ⚠️ 单测已覆盖组装逻辑，**端到端未验证**                       | 需 kb-app + PG 才走到有内容的分支，见 5.4                                                                                                                                                                                                                                           |
| Sa-Token 会话鉴权               | ✅ 已接入，**未连 Redis 实测**                         | 网关、kb-app、kb-ai 共享 `satoken` 会话；细粒度权限仍在 kb-app                                                                                                                                                                                                                          |
| 网关旧身份头净化                    | ⚠️ 已实现，**未实测**                                | `InboundHeaderSanitizeFilter`                                                                                                                                                                                                                                           |
| rerank 适配                   | ✅ 最小接口与远程适配器已实现                               | 默认本地 token overlap；可配置兼容 `/rerank` 服务，失败退回 RRF 顺序                                                                                                                                                                                                                       |
| AI 部门配额与审计                  | ⚠️ 代码已实现，**未连 Redis 实测**                      | kb-app 解析可信部门主体；kb-ai 用 Redis Lua 原子维护部门请求窗口、可续期并发租约和 token 预留/结算，释放与审计写入具备有界重试，审计事件按 ID 幂等写入有界 Redis 列表；Redis 不可用时拒绝请求，内存审计只允许显式开发配置                                                                                                                                   |
| 统一身份/部门模型                   | ✅ 代码已实现，**未连真实 PG 实测**                        | V8 迁移补充稳定部门编码、用户/部门启停状态、乐观锁、外部身份唯一映射和 append-only 审计；`kb-app` 从服务端会话解析主体，管理 API 逐操作判定功能权限，身份更新使用版本字段和 `X-Request-Id`                                                                                                                                                    |
| 空间与授权管理 API                 | ✅ 代码已实现，**未连真实 PG 实测**                        | `/api/admin/spaces` 与 `/api/admin/documents/{id}/acl`；空间为授权主单位，文档 ACL 只能扩权；manage 授出要求 `space:grant-manage`；归档空间退出读权限位但保留管理可达性；均带 `X-Request-Id` 幂等与审计。归档对读路径的全链路生效尚未实测（见 5.7）                                                                                          |
| 文档搬迁                        | ✅ 代码已实现，**未连真实 PG/MinIO/Worker 实测**           | `PATCH /api/admin/documents/{id}/space`；要求源空间与目标空间**双侧 manage**，同一事务内清空该文档的全部例外 ACL 并同步 `kb_doc_chunk.space_id`，与 Worker 的分块发布争同一把文档行锁；拒绝迁入已归档空间，允许从已归档空间迁出。原空间成员的检索与读取隔离、以及与发布的并发串行化尚未实测（见 5.8）                                                                        |
| 角色与密级管理                     | ✅ 功能权限、用户与文档密级调整、角色分配和管理员引导已实现，**未连真实 PG 实测** | 功能资格每次按持久化角色权限并集精确匹配。用户密级调整使用 `identity:clearance:write`，拒绝调整自己；文档密级管理独立使用 `document:security-level:write`，要求原密级和目标密级均不超过操作者当前密级，带独立版本、幂等和审计，并同步全部版本分块。接口、迁移与无外部依赖测试见[文档密级管理](docs/document-security-level.md)。角色 CRUD、`users/{userId}/roles` 和 `kb.bootstrap.*` 保留既有语义 |
| 真实 chat 模型调用 | ✅ 通用适配与可配置多轮估算已实现，仅离线单元验证 | OpenAI-compatible Chat Completions 默认关闭。多轮默认 verified，缺严格策略时 503；显式 estimated 可使用本地估算及配置窗口，接受实际 token 超估算。未联调真实供应商，见[模型接入说明](docs/chat-model-adapter.md) |
| 认证与 SSO                     | ✅ 本地登录、账号管理及三家浏览器企业登录代码已实现，**仅离线验证**          | 钉钉／企业微信／飞书连接默认关闭；扫码成员核验后仅登录已有绑定账号，本人新绑定须当前会话及本次本地密码。无自动建号、工作台免登或离职同步；真实 PG／Redis／供应商／浏览器未验证。接口与配置见 [企业 SSO](docs/enterprise-sso-api.md)、[配置与回退](docs/enterprise-sso-configuration.md)、[验证记录](docs/enterprise-sso-verification.md)。生产关闭 dev-auth，离职仍由本平台管理员停用账号；本地入口保留   |

| 权限解析请求内去重 | ✅ 单测已覆盖，**未在真实 PG 上执行过任何一条权限查询** | 同一请求内主体只解析一次（
`PermissionService.principalOf` 返回的句柄，寿命不超过一次调用栈，不是缓存）；`resolve(handle, ...)` 按权限位各查一次
ACL。查询计数为读代码所得而非实测；跨请求缓存仍未实现，引入前须先修订功能授权与身份部门的「变更必须在下一次请求即生效」要求 |

### 关于当前的占位实现

P0 1–4 已补齐归档文档例外 ACL 过滤、HTTP 错误状态、PATCH 跨域预检、开发/生产配置隔离和文件安全处理。
配置与兼容性变更见 [P0 修复与部署说明](docs/p0-hardening.md)。未显式选择 profile 时默认使用 `prod`，缺少生产配置会拒绝启动。

AI 可通过 `kb.ai.chat-model` 显式启用通用 OpenAI-compatible 文本模型，服务端注入地址、密钥和模型名；既有部署方 `ChatModelClient` / `MultiTurnModel` 扩展仍分别优先。
默认关闭且未注入模型时，旧单轮保留占位兼容；启用后的真实调用失败不会回退占位。多轮预算默认 `verified`，需要匹配的 `ChatTokenBudget`；显式设置 `KB_CHAT_BUDGET_MODE=estimated` 和已知模型窗口后，可按 UTF-8 字节规则估算输入并保留安全余量。估算不代表真实上界或费用上限，真实模型兼容与参数校准仍待完成。有效材料被预算裁空时返回 `INPUT_BUDGET_EXCEEDED`，前端沿用通用失败提示；真实 usage 完整保存，超预留也不截断。配置、严格流终结及回退见[模型接入说明](docs/chat-model-adapter.md)。文档解析统一通过第三方服务接入，当前服务尚未确定。
Worker 未装配第三方适配器时暂停领取解析任务，健康检查显示解析不可用；接入后由服务抽取内容，Worker 继续分块、生成向量并在租约有效时事务性发布。见[第三方文档解析接入](docs/third-party-document-parsing.md)。

---

## 二、环境要求

| 组件                 | 版本            | 本机状态        |
|--------------------|---------------|-------------|
| JDK                | 21            | ✅ 21.0.4    |
| Maven              | 3.9+          | ✅ 3.9.8     |
| PostgreSQL         | 16 + ParadeDB | ❌ **未安装**   |
| MinIO（或任意 S3 兼容存储） | —             | ❌ 未安装，代码已接入 |
| Redis              | 7             | ❌ 未安装       |
| Docker             | —             | ❌ **本机未安装** |

`docker-compose.yml` 已写好，但**本机无 Docker，从未执行过**。
在有 Docker 的机器上应该是 `docker compose up -d` 就能起，但这句话未经验证。

---

## 三、启动步骤

### 1. 起中间件

```bash
docker compose up -d        # paradedb / redis / minio
```

没有 Docker 就自行安装 PostgreSQL 16 并装上 `pg_search` 与 `pgvector` 扩展，
然后建库建用户：

```sql
CREATE DATABASE kb;
CREATE USER kb WITH PASSWORD 'kb_dev_pwd';
GRANT ALL PRIVILEGES ON DATABASE kb TO kb;
```

### 2. 起业务主应用（它负责建表）

```powershell
$env:SPRING_PROFILES_ACTIVE = 'dev'
mvn install -DskipTests
mvn -pl kb-app spring-boot:run
```

Flyway 会自动执行 `kb-app/src/main/resources/db/migration/` 下的正式迁移，当前至 V14。V8
会为历史用户和部门回填稳定编码、启用状态和版本值，并创建外部身份映射及变更审计表；不会重编号用户、部门、角色或 ACL。V14
新增文档密级版本及专用权限，部署兼容说明见[文档密级管理](docs/document-security-level.md)。

> **V3/V6 是开发种子数据**，已移动到 `db/dev`，只有 `dev` profile 加载。
> 脚本内容与版本不变，保留已有开发库的校验和；生产不自动删除既有种子数据，也不自动修复 Flyway 历史。

### 3. 起其余模块

```powershell
$env:SPRING_PROFILES_ACTIVE = 'dev'  # 每个新终端均需设置
mvn -pl kb-gateway spring-boot:run   # :8080
mvn -pl kb-worker  spring-boot:run   # :8083
mvn -pl kb-ai      spring-boot:run   # :8082
```

端口分配：网关 8080 / 主应用 8081 / AI 8082 / Worker 8083（仅健康检查）。

> **迁移只由 kb-app 执行**。Worker 的 Flyway 是关闭的，不要打开——
> 多个部署单元同时迁移会互相抢锁，且版本推进责任说不清。

### 3.1 上传文档

`kb-app` 提供 `POST /api/documents` multipart 接口。调用者需要对目标空间拥有
`edit` 权限；开发环境可使用 V3/V6 中研发中心/财务部的种子授权。

先获取开发令牌（生产环境关闭 `kb.dev-auth.enabled`，改用本地登录或 SSO/OIDC 建立会话）：

```bash
TOKEN=$(curl -s -X POST "http://localhost:8081/api/auth/dev-login?userId=1" \
  | jq -r '.data.tokenValue')
```

然后携带 Sa-Token 上传：

```bash
curl -X POST "http://localhost:8081/api/documents" \
  -H "satoken: $TOKEN" \
  -F "spaceId=1" \
  -F "title=架构说明" \
  -F "securityLevel=1" \
  -F "file=@./docs/architecture.md"
```

上传原件写入 S3 兼容存储后，文档、版本和解析任务在一个数据库事务中创建。
Worker 会自动领取任务。默认 embedding provider 是 `local`，需要调用云端或本地
OpenAI-compatible 服务时设置 `KB_EMBEDDING_PROVIDER=remote`，并填写
`KB_EMBEDDING_BASE_URL`、`KB_EMBEDDING_API_KEY`、`KB_EMBEDDING_MODEL`；向量维度
必须与 V2 的 `VECTOR(1024)` 一致。

---

## 四、模块职责

```
kb-common     公共模型。PermissionScope / PrincipalType / R / ApiException
              ★ 无 Spring 运行时依赖；仅包含 JDK 类型、Lombok 编译期注解和 OpenAPI 注解
kb-app    :8081  业务主应用（模块化单体）。唯一持有完整数据库权限的服务
kb-gateway:8080  网关。路由 + Sa-Token 会话校验 + 旧身份头净化 + 流式放行
kb-worker :8083  解析索引 Worker。CPU/内存密集，独立扩缩容
kb-ai     :8082  RAG 编排与流式对话。**没有数据库连接，且不应有**；跑在 Netty 上
```

### 关于 kb-common 的依赖边界

不是洁癖，是踩过的坑（详见 5.2 的说明）。公共模块被每个部署单元依赖，
它声明的任何东西都会渗进所有单元——而 `spring-boot-starter-web` 渗进 kb-ai 的后果，
是把一个精心选择的响应式服务悄悄变回了 servlet 服务，且表面看不出任何异常。

往 `kb-common/pom.xml` 加依赖前，先问一句：是真的每个模块都需要，
还是只有某一个模块需要？后者应该加在那个模块自己的 pom 里。
目前保留的 Lombok 仅用于编译期生成访问器，OpenAPI 依赖仅提供共享响应模型的注解，
不会引入 Spring MVC、WebFlux 或 Servlet 容器。

### 身份与部门管理

内部用户 ID 是权限、配额和审计共同使用的稳定主键。部门授权沿物化路径向下继承，用户首期只有一个主部门；移动用户或部门后，下一次请求按数据库中的新祖先链解析。

管理接口位于 `/api/admin/identity`（`/api/identity` 保留兼容别名），所有请求都需要 Sa-Token
会话，并逐操作判定下表中的功能权限。权限来自全部角色绑定的并集，每次请求实时查询，变更无需等待会话过期。写请求需要
`requestId` 或 `X-Request-Id`；身份归属、启停和密级更新还需要当前 `version`。有启用子部门或主部门用户的部门不能停用，停用用户或部门的资源权限解析拒绝全部资源。

| 操作              | 功能权限                       |
|-----------------|----------------------------|
| 用户列表、详情         | `identity:user:read`       |
| 创建用户、转部门、启停     | `identity:user:write`      |
| 重置密码            | `identity:user:password`   |
| 调整他人密级          | `identity:clearance:write` |
| 角色列表、详情、权限目录    | `identity:role:read`       |
| 分配、解除角色         | `identity:role:assign`     |
| 角色创建、改名、删除、权限变更 | `identity:role:write`      |
| 部门查询            | `identity:dept:read`       |
| 部门创建、改名、移动、启停   | `identity:dept:write`      |
| 外部身份绑定、解绑       | `identity:external:write`  |
| 身份审计查询          | `identity:audit:read`      |
| 解析死信查询          | `parse:deadletter:read`    |
| 解析死信重放          | `parse:deadletter:replay`  |
| 创建空间            | `space:create`             |
| 绕过空间 ACL 管理所有空间 | `space:manage-all`         |
| 授予、撤销空间 manage  | `space:grant-manage`       |

例如，转移用户主部门时使用服务端已验证的会话，不要发送 `X-User-Id`、`X-Dept-Id`、`X-Role-Ids` 或 `X-Clearance-Level` 作为身份声明：

```bash
curl -X PATCH "http://localhost:8081/api/admin/identity/users/1/department" \
  -H "satoken: $ADMIN_TOKEN" \
  -H "X-Request-Id: move-user-1-$(date +%s)" \
  -H "Content-Type: application/json" \
  -d '{"departmentId":2,"version":0}'
```

外部 SSO provider 与 subject 只在服务端绑定表中查找；管理响应、审计摘要和日志不回显完整 subject 或会话 token。具体厂商的
OIDC 回调仍由后续认证适配层负责，开发态数字用户登录仍可用于本地联调。

### 本地登录与账号管理

生产不再依赖 dev 登录：`POST /api/auth/login` 以用户名 + 密码建立 Sa-Token 会话。
密码用 BCrypt 存储；同一账户连续失败达到阈值即节流，节流期间的响应与凭据错误
完全一致（不暴露账户是否存在、是否被锁定）；成功与失败都写审计，摘要不含密码或令牌。
未认证（会话缺失或失效）统一返回 401，已认证但无资格返回 403。

```bash
# 登录：响应 data 含 tokenName / tokenValue / loginId / mustChangePassword / user
curl -X POST "http://localhost:8081/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"<初始密码>"}'

# 会话主体：user、roles（角色 ID 列表）与 mustChangePassword，全部由服务端解析
curl "http://localhost:8081/api/auth/me" -H "satoken: $TOKEN"

# 自助改密：校验原密码与新密码强度，成功后清除强制改密标记
curl -X POST "http://localhost:8081/api/auth/password" \
  -H "satoken: $TOKEN" -H "Content-Type: application/json" \
  -d '{"oldPassword":"<当前密码>","newPassword":"<新密码>"}'

# 登出
curl -X POST "http://localhost:8081/api/auth/logout" -H "satoken: $TOKEN"
```

处于强制改密状态的会话只放行登录、登出、`GET /api/auth/me` 与 `POST /api/auth/password`
（开发态登录入口同在白名单），其余 `/api/**` 请求一律 403（`PASSWORD_CHANGE_REQUIRED`），
直到完成自助改密。管理员重置密码后目标用户立即回到该状态。

管理员创建账号与分配角色（会话需持有各操作所需权限，见上表；写请求必须携带 `X-Request-Id`）：

```bash
# 建号：初始密码只在请求体中出现一次，创建后强制首次改密；用户名全局唯一
curl -X POST "http://localhost:8081/api/admin/identity/users" \
  -H "satoken: $ADMIN_TOKEN" -H "X-Request-Id: create-user-bob" \
  -H "Content-Type: application/json" \
  -d '{"username":"bob","displayName":"鲍勃","departmentId":2,"clearanceLevel":1,"initialPassword":"<初始密码>"}'

# 角色目录 → 分配 / 解除（重复分配幂等；解除最后一名启用的全局管理员返回 409）
curl "http://localhost:8081/api/admin/identity/roles" -H "satoken: $ADMIN_TOKEN"
curl -X POST "http://localhost:8081/api/admin/identity/users/2/roles" \
  -H "satoken: $ADMIN_TOKEN" -H "X-Request-Id: grant-role-to-2" \
  -H "Content-Type: application/json" -d '{"roleId":1}'
curl -X DELETE "http://localhost:8081/api/admin/identity/users/2/roles/1" \
  -H "satoken: $ADMIN_TOKEN" -H "X-Request-Id: revoke-role-from-2"

# 管理员重置密码：不校验原密码；重置后目标用户被强制改密拦截，直到其自助改密
curl -X PATCH "http://localhost:8081/api/admin/identity/users/2/password" \
  -H "satoken: $ADMIN_TOKEN" -H "X-Request-Id: reset-password-2" \
  -H "Content-Type: application/json" -d '{"newPassword":"<新密码>"}'
```

角色 CRUD 与权限管理（以下为 PowerShell 示例，`$ADMIN_TOKEN` 对应会话需持有所需权限）：

```powershell
$base = 'http://localhost:8081/api/admin/identity'
$createdRole = curl.exe -s -X POST "$base/roles" -H "satoken: $ADMIN_TOKEN" -H 'X-Request-Id: role-create-1' -H 'Content-Type: application/json' --data-raw '{"code":"ROLE_AUDIT_READER","name":"审计只读"}' | ConvertFrom-Json
$roleId = $createdRole.data.roleId
curl.exe -X PATCH "$base/roles/$roleId" -H "satoken: $ADMIN_TOKEN" -H 'X-Request-Id: role-rename-1' -H 'Content-Type: application/json' --data-raw '{"name":"身份审计员"}'
curl.exe "$base/permissions" -H "satoken: $ADMIN_TOKEN"
curl.exe -X PUT "$base/roles/$roleId/permissions" -H "satoken: $ADMIN_TOKEN" -H 'X-Request-Id: role-grant-1' -H 'Content-Type: application/json' --data-raw '{"permission":"identity:audit:read","granted":true}'
curl.exe "$base/roles/$roleId" -H "satoken: $ADMIN_TOKEN"
curl.exe -X PUT "$base/roles/$roleId/permissions" -H "satoken: $ADMIN_TOKEN" -H 'X-Request-Id: role-revoke-1' -H 'Content-Type: application/json' --data-raw '{"permission":"identity:audit:read","granted":false}'
curl.exe -X DELETE "$base/roles/$roleId" -H "satoken: $ADMIN_TOKEN" -H 'X-Request-Id: role-delete-1'
```

新角色权限为空；`code` 唯一冲突返回 409，修改既有 `code` 返回 400。删除为硬删除，
仍有用户绑定或空间、文档 ACL 引用时返回 409 并说明需要先撤销的引用。
权限变更一次提交一个目录内 code 和 `granted` 布尔值；未知 code 返回 400。
解除用户角色、停用用户、移除角色权限均使用同一固定事务锁，在锁内按变更后的用户权限并集判定：
系统必须保留至少一名同时持有 `identity:role:assign` 与 `identity:role:write` 的启用用户，否则返回 409。

首个管理员引导（空库部署）：同时设置用户名与密码后启动 kb-app，启动时自动确保
`ROLE_ADMIN` 角色行、主部门、管理员账号（强制首次改密）与角色绑定存在。`ROLE_ADMIN`
是默认绑定全部 16 项权限的普通角色行，不是硬编码资格标记；V13 为既有行显式绑定全集，
引导为新创建的行写入全集。若既有行的权限集不全，仅记 WARN，不自动修复。
两项凭据都为空则跳过，密码少于最小长度则启动失败。已存在满足上述管理资格不变量的启用用户时零动作，
无需该用户持有名为 `ROLE_ADMIN` 的角色；配置的管理员已存在但被停用只记 WARN，不自动重新启用。

| 环境变量                                 | 默认    | 说明                      |
|--------------------------------------|-------|-------------------------|
| `KB_BOOTSTRAP_ADMIN_USERNAME`        | 空     | 与密码同时为空即跳过引导            |
| `KB_BOOTSTRAP_ADMIN_PASSWORD`        | 空     | 少于最小长度（默认 8）时启动失败，不静默降级 |
| `KB_BOOTSTRAP_ADMIN_DISPLAY_NAME`    | 系统管理员 | 管理员显示名                  |
| `KB_BOOTSTRAP_ADMIN_CLEARANCE_LEVEL` | 0     | 初始密级，必须存在于密级字典          |
| `KB_BOOTSTRAP_DEPARTMENT_CODE`       | HQ    | 管理员主部门编码，缺则创建根部门        |
| `KB_BOOTSTRAP_DEPARTMENT_NAME`       | 总部    | 上述部门名称                  |

其余登录相关配置：`KB_PASSWORD_MIN_LENGTH`（默认 8，建号、自助改密与管理重置共用）、
`KB_BCRYPT_STRENGTH`（默认 10）、`KB_LOGIN_MAX_FAILURES`（默认 5）、
`KB_LOGIN_LOCK_DURATION`（默认 15m，到期自动解锁）。

### 为什么 kb-ai 没有数据库依赖

这是一道刻意设置的安全边界，不是遗漏。
`kb-ai/pom.xml` 里没有 `postgresql` 和 `jdbc` 依赖，
所以它**物理上无法**绕过权限直读 `kb_doc_chunk`。
语料只能经 `RetrievalClient` 调用 kb-app 的授权检索接口取得，并透传 Sa-Token 会话。

RAG 系统最常见的越权模式就是：搜索页权限做得滴水不漏，
AI 问答却用服务账号取全量数据，然后指望模型"只说该说的"。
模型不是访问控制机制。往那个 pom 加数据库依赖前，请先确认你没在拆这道边界。

---

## 五、验证清单

### 5.1 构建（✅ 已通过）

```bash
mvn -B clean package
# ModuleBoundaryTest    3 passed   模块依赖方向
# ChatControllerTest    8 passed   SSE 终止 / 单次订阅 / 心跳 / Sa-Token 头
# NoDatabaseAccessTest  1 passed   kb-ai 类路径无数据库访问能力
# ReactiveStackTest     2 passed   kb-ai 跑在 Netty 而非 Tomcat
```

后两个测试守的是两道**架构约束**，不是功能：
一道是"AI 服务不能直连数据库"，一道是"AI 服务必须跑响应式栈"。
两者都曾被依赖传递悄悄破坏过（见下），注释拦不住，所以钉成了测试。

### 5.2 检索韧性与 AI SSE

搜索默认共享 8 秒预算：Embedding 最多 2 秒、数据库召回最多 3 秒、rerank 最多 1.5 秒，预留 500 毫秒准备响应。每 app 实例最多 8 个实际在途请求，同一用户最多 2 个；用户超限 429、实例满载 503，附 Retry-After。超时响应不会提前释放仍运行的工作。输入、传输、降级和指标见 [检索限界与降级](docs/search-resilience.md)。

AI 检索独立等待 10 秒。拒绝、超时或协议故障以固定错误结束，不再伪装为无结果：

```text
event:error
data:{"message":"检索暂不可用，请稍后重试。","code":"SEARCH_UNAVAILABLE"}
```

只有授权检索合法成功返回空列表时，单轮才保留原无结果事件：

```
event:delta
data:{"text":"未找到与问题相关的内容。"}

event:done
data:{"promptTokens":0,"completionTokens":0,"elapsedMs":351}
```

上述为协议示例。多轮检索故障进入既有 FAILED 与 NOT_INVOKED/零 token 结算；匹配重放不再次召回，持久终态未确认时读取服务端事实。本变更通过显式单元测试名单、内存替身、类型检查及静态构建验证，范围与命令记录在检索说明中。

启动日志应为 `Netty started on port 8082`。
**若出现 `Starting Servlet engine: Apache Tomcat` 就是回归了**——
说明又有依赖把 servlet 栈传递进来了，`ReactiveStackTest` 会先失败。

> 这个坑真实发生过：`kb-common` 原先声明了 `spring-boot-starter-web`（而它一行 Spring 代码都没用），
> Tomcat 因此传递进 kb-ai，Spring Boot 把应用判定为 SERVLET 类型。
> 接口表面上完全正常——Spring MVC 也支持返回 `Flux`——
> 但"长连接不占容器线程"这个选 WebFlux 的唯一理由已经没了，
> 要到并发上量才会以"系统卡住"的形式暴露。

### 5.3 ParadeDB 实际行为（⚠️ 必做，尚未做）

```bash
psql -h localhost -U kb -d kb -f db/verify_paradedb.sql
```

重点看三件事：

1. **装上的 ParadeDB 是哪个版本**。0.25.0 起索引访问方法从 `bm25` 改名为 `paradedb`
   （`bm25` 保留为别名）。V2 迁移用的是新名字，老版本会建不出来。
2. **jieba 中文分词的实际效果**。脚本里对同一段中文分别用 `pdb.jieba` 和
   `chinese_compatible` 跑一遍做对比。这是整个技术选型里风险最高的一项——
   用**你们自己的业务文档**去试，不要只看脚本里的示例句。
3. **EXPLAIN ANALYZE 里权限条件是否真的下推进了索引**。
   如果 `space_id` / `security_level` 变成了索引外的过滤，
   意味着先召回再过滤，在权限稀疏的场景下召回质量会塌掉。

### 5.4 权限边界（⚠️ 必做，尚未做）

```bash
psql -h localhost -U kb -d kb -f db/verify_permission.sql
```

7 个用例，每个都印了预期结果，逐条对照。覆盖：

- 密级与 ACL 是**与**关系（过了 ACL 但密级不够仍然看不到）
- 部门授权**只向下生效**，不向上传递
- 空主体集合 = 看不到任何东西（**绝不能退化成不过滤**）
- 文档级例外能扩权、不能收紧（首期已知边界）

这个脚本应该进 CI。权限回归是那种"改一行 SQL 就悄悄破掉"的东西。

### 5.5 SSE 逐字增量与权限透传（⚠️ 必做，尚未做）

5.2 只验证了"接口能通、流会正常关闭"。**逐字增量到达尚未验证**——
在没有 kb-app 和 PG 的情况下，召回为空，只会发出一条 delta，
观察不到增量效果。要走到有内容的分支需要先把 PG 和 kb-app 起起来。

```bash
# 直连 AI 服务
curl -N -X POST "http://localhost:8082/api/ai/chat" \
  -H "satoken: $TOKEN" -H "Content-Type: application/json" \
  -d '{"question":"测试"}'
# 经过网关，首字节延迟应与上面接近
curl -N -X POST "http://localhost:8080/api/ai/chat" \
  -H "satoken: $TOKEN" -H "Content-Type: application/json" \
  -d '{"question":"测试"}'
```

应该看到字一个一个地出现。若所有内容一次性涌出，说明链路上有一层在缓冲
（`kb-gateway/src/main/resources/application.yml` 里列了三个常见来源）。

**换用户应看到不同的引用集合**，这是权限透传是否生效的黑盒验证：

```bash
# 分别用 userId=1、3、4 调用 /api/auth/dev-login，得到 TOKEN_1/TOKEN_3/TOKEN_4
curl -N -H "satoken: $TOKEN_1" ...   # alice，能看到技术空间
curl -N -H "satoken: $TOKEN_3" ...   # carol，只能看到财务文档
curl -N -H "satoken: $TOKEN_4" ...   # dave，应该什么都召回不到
```

三个用户返回相同引用 = 身份没透传成功或检索退化成了无过滤查询，属于严重越权，
立即停下来排查。

### 5.6 网关旧身份头净化（⚠️ 未做端到端验证）

```bash
# 伪造旧身份头，应被网关剥离并打 WARN；真正的 satoken 由网关校验
curl -X POST -H "satoken: $TOKEN_3" -H "X-Dev-User-Id: 4" \
  -H "Content-Type: application/json" -d '{"query":"测试","candidates":50,"topK":8}' \
  "http://localhost:8080/api/search"
```

经网关后 kb-app 不应收到这个头。若收到了，任何人都能把自己声明成任意用户。

### 5.7 空间与授权管理（⚠️ 必做，尚未做）

管理接口位于 `/api/admin/spaces` 与 `/api/admin/documents/{documentId}/acl`，
已纳入网关的 kb-app 路由（漏挂会退化成 404）。
所有写请求需要 `X-Request-Id`，缺失返回 400；重复提交是幂等的。

> **前置：`$ADMIN_TOKEN` 对应用户需持有 `space:create`、`space:manage-all` 和 `space:grant-manage`。**
> 功能资格由 `sys_user_role` 与 `sys_role_permission` 实时解析；V3 的 alice 绑定了 `ROLE_ADMIN`，
> V13 为该角色写入全部权限。仅有角色绑定、但角色没有所需权限时仍返回 403。

```bash
# 建空间（需 space:create）
curl -X POST "http://localhost:8081/api/admin/spaces" \
  -H "satoken: $ADMIN_TOKEN" -H "X-Request-Id: create-space-1" \
  -H "Content-Type: application/json" \
  -d '{"code":"tech","name":"技术空间","description":null}'

# 授予部门 view（返回体里的 clearanceBlockedPrincipalCount 才是重点）
curl -X POST "http://localhost:8081/api/admin/spaces/1/acl" \
  -H "satoken: $ADMIN_TOKEN" -H "X-Request-Id: grant-dept-2-view" \
  -H "Content-Type: application/json" \
  -d '{"principalType":"DEPT","principalId":2,"permission":"view"}'
```

要逐条确认的事实，以及为什么：

1. **`clearanceBlockedPrincipalCount` 不为 0 时，授权确实写进去了但当下看不到。**
   这个回执是 ACL 与密级"与"关系的唯一可见信号。若它恒为 0，
   说明统计口径坏了，会让人误以为授权已经生效。
2. **用只持有空间 `manage` 的账号授予 `manage`，必须返回 403。**
   manage 的分量等同于该空间上的全部权限，允许它被自我授出等于允许管理资格扩散。
3. **归档后该空间的文档必须从检索、列表、详情、下载和预览里同时消失，
   但空间管理员仍能提交取消归档。** 后者是这一条的另一半：
   `manage` 的可见范围不排除已归档空间，否则一旦归档就再也回不来。
4. **文档例外只能扩权。** 给一个空间外主体开例外后，该主体应能（在密级允许时）
   看到这一篇；对 `manage` 或 `comment`/`review` 的例外请求必须被拒，
   而不是静默接受。

`4.7`（归档对全部读路径生效）在本变更里**没有端到端实测**——
它需要真实的 PG、MinIO 与 kb-app 三者同时在位。上面的第 3 条就是补这一项的步骤。

### 5.8 文档搬迁（⚠️ 必做，尚未做）

搬迁接口是 `PATCH /api/admin/documents/{documentId}/space`，与 5.7 的例外授权接口同属
`/api/admin/documents/{id}/...` 一族。它要求**源空间与目标空间双侧的 `manage`**——
`edit` 不足以把一篇文档推送给另一个空间的全部受众。写请求同样需要 `X-Request-Id`
（或请求体里的 `requestId`），缺失返回 400；重复提交返回首次结果，不会第二次清例外。
`$ADMIN_TOKEN` 需持有 `identity:clearance:write`，用户名或角色 code 本身不授予密级调整资格。

```bash
# 把文档 10 从空间 1 迁到空间 2（fromSpaceId 是前置条件，不是提示）
curl -X PATCH "http://localhost:8081/api/admin/documents/10/space" \
  -H "satoken: $ADMIN_TOKEN" -H "X-Request-Id: relocate-doc-10-to-2" \
  -H "Content-Type: application/json" \
  -d '{"fromSpaceId":1,"toSpaceId":2}'

# 回执（data 部分）
# {
#   "documentId": 10,
#   "fromSpaceId": 1,
#   "toSpaceId": 2,
#   "clearedExceptionCount": 3,
#   "clearedExceptions": ["3:5:view", "2:9:download", "1:12:edit"],
#   "clearedExceptionsTruncated": false,
#   "clearanceBlockedPrincipalCount": 1,
#   "replayed": false
# }

# 夹带密级必须被拒（400），搬迁不是改密级的入口
curl -X PATCH "http://localhost:8081/api/admin/documents/10/space" \
  -H "satoken: $ADMIN_TOKEN" -H "X-Request-Id: relocate-doc-10-smuggle" \
  -H "Content-Type: application/json" \
  -d '{"fromSpaceId":1,"toSpaceId":2,"securityLevel":1}'
```

要逐条确认的事实，以及为什么：

1. **`clearedExceptionCount` 是"被清除的例外授权条数"，`clearedExceptions` 是它们的
   `principalType:principalId:permission` 摘要，两者都不可恢复。** 这是调用方唯一一次
   看到这批授权的机会——搬迁提交后表里已经没有它们了。摘要最多 50 条，超出时
   `clearedExceptionsTruncated` 置 `true`，此时条数仍然是全量值而摘要不是。
   若条数恒为 0 而原空间确实开过例外，说明清理没有真的执行，那正是本能力要堵的漏洞。
2. **`clearanceBlockedPrincipalCount` 不为 0 不代表搬迁失败。** 它只说明目标空间里
   有主体的密级低于该文档密级，因而拿到了 `view` 也看不到这一篇。搬迁本身已经成功，
   文档归属已经是目标空间。把它当失败信号会导致运维反复重试一个已经完成的操作。
3. **原空间的成员必须同时搜不到、也读不到这篇文档。** 这两条是分开的路径，必须分别试：
   检索按 `kb_doc_chunk.space_id` 过滤，列表/详情/下载/预览按 `kb_document.space_id` 过滤。
   只验一侧会漏掉本能力的核心风险——分块的空间归属是一份冗余副本，
   不同步它，文档就会继续按**原空间**被搜到，同时在新空间里搜不到。
4. **迁入已归档空间必须被拒（409），从已归档空间迁出必须允许。** 前者如果放过，
   文档会对所有人立即消失（归档空间退出全部读权限位），而操作者看到的是一次成功回执。
   后者是归档空间清空内容的唯一出路，拦掉它等于让归档变成单向的死胡同。
5. **声明的 `fromSpaceId` 与实际归属不符时返回 409，而不是照搬。** 它是并发前置条件：
   两个管理员同时搬同一篇文档时，后到的那个必须被拒绝并重新读取归属，
   否则第二次搬迁会在第一次的结果上继续，把文档推到谁也没打算放它的地方。
6. **搬迁与解析发布并发时必须串行。** 一边搬迁、一边让 Worker 发布同一篇文档的新版本，
   结束后 `kb_doc_chunk.space_id` 必须全部等于目标空间。两者争同一把文档行锁
   （见[运维文档](docs/parse-task-recovery-operations.md)），缺锁的症状是新分块带着旧
   `space_id` 落地。

这三条——第 3、4、6——在本变更里**只被实现、没有被验证**：它们需要真实的 PG、MinIO
和 Worker 三者同时在位，本机都不具备。

### 5.9 角色权限模型（⚠️ 必做，**代码已实现，待真实环境执行**）

> **代码已实现，以下验证仍需真实中间件执行。** 它们来自变更
> `openspec/changes/role-crud-and-functional-permissions`（功能资格从单一硬编码
> `ROLE_ADMIN` 改为角色→权限集的 RBAC）。该变更的 `tasks.md` 刻意只收录能在无
> PG/Redis/MinIO 环境下执行的任务，下面这些需要真实中间件，因此记在这里。
> **下列验证尚未执行，单元测试通过不等于真实迁移、并发与端到端验证完成。**

```bash
# 前置：真实 PG（含 ParadeDB）已起，kb-app 已完成 V1–V13 迁移
psql -h localhost -U kb -d kb -c "SELECT permission FROM sys_role_permission
  WHERE role_id = (SELECT id FROM sys_role WHERE code = 'ROLE_ADMIN') ORDER BY 1;"
```

1. **V13 迁移实际落库，`ROLE_ADMIN` 的 16 条权限绑定一条不少。** 上面的 `SELECT`
   结果必须与 `Permission` 枚举完全相等。少一条的症状是"升级后管理员静默失去某项能力"——
   最难归因的那一类故障。迁移脚本的文本断言测试只能保证脚本里写了，不能保证它跑过。
2. **并发移除最后两名满足条件者的资格，至少一个被拒。** 两个并发事务分别从两个角色
   移除 `identity:role:assign` / `identity:role:write`，结束后系统内必须仍存在同时持有
   两项权限的启用用户。这是三条守卫里**唯一靠真实数据库才能证伪**的一条：单元测试只能
   验到"先锁后判"的调用顺序，固定键 `pg_advisory_xact_lock` 是否真的串行化了并发事务，
   离线无法验证。守卫失效的后果是管理面彻底锁死，只能靠重启 + `kb.bootstrap.*` 救回来。
3. **角色删除后 ACL 表确无残留主体行。** 删除一个双侧无引用的角色后：
   ```sql
   SELECT * FROM kb_space_acl    WHERE principal_key = '2:<被删角色 id>';
   SELECT * FROM kb_document_acl WHERE principal_key = '2:<被删角色 id>';
   ```
   两条都必须为空。`principal_id` 是多态列、**没有外键**，所以这件事不由数据库保证，
   只由删除路径的双侧前置条件保证。留下残留行的后果是标识重用后新角色凭空继承旧授权。
4. **端到端职责分离。** 建一个只绑定 `identity:audit:read` 的角色分配给某用户，
   确认该用户能读审计，但在身份写、空间管理、死信重放三条路径上**全部**得到 403。
   三条里只要有一条放行，说明权限细分没有真的生效——最可能的原因是实现时把
   `isGlobalManager()` 原地改成了"持有任意管理权限"，那是一次静默扩权。
5. **升级前后既有 `ROLE_ADMIN` 持有者的可用范围不变。** 对同一组管理端点在升级前后
   各调一轮，响应码必须一致。这条是向后兼容承诺的黑盒验证：`ROLE_ADMIN` 在新模型里
   只是一个权限集为全集的普通角色行，若其可用范围缩小，说明 16 条绑定没写全（同第 1 条）。
6. **建空间时随附的 manage 授权确实在同一事务内落库。** 建空间后立即查
   `kb_space_acl`，创建者的 manage 记录必须存在。单元测试只能断言方法带
   `@Transactional`，断不了回滚行为。缺它的症状是建出一个无人可管理的空间。

### 5.10 对象存储对账（⚠️ 必做，**代码已实现，未在真实环境执行**）

> 对账的进程内单元测试只覆盖编排逻辑（mock `ObjectStorage`、mapper、`DataSource`/`Statement`），
> **未在真实 PostgreSQL/MinIO 上运行过对账、删除或缺失检测**。以下步骤必须在部署环境执行，
> 单测通过不代表对账已验证。

已通过的进程内验证：`StorageReconciliationServiceTest`（18 个用例：分页 IN 比对、宽限期、
前缀防御、删除前复核、单轮上限、列举/数据库失败零删除、空存活引用拒绝、缺失与检查失败
分别计数、汇总日志与键打印上限）、`StorageReconciliationSchedulerTest`（异常不外传、每轮
一次）、`S3ObjectStorageTest`（mock `S3Client`，不连桶）、`StorageReconciliationPropertiesTest`
（默认值）及静态检查。

```powershell
# 前置：kb-app 的 S3 凭据含前缀内 s3:ListBucket；日志 com.zhishiku.kb=INFO
# 报告模式（默认）跑满至少一个周期（首轮延迟默认 5 分钟、间隔 1 小时）后，日志中应有：
#   对账轮次完成: 扫描=N 孤儿=N 删除=0 删除失败=0 缺失=N 检查失败=N
#   孤儿对象: key=…（每轮至多 100 条）
```

要逐条确认的事实：

1. **报告模式下删除数必须恒为 0**，`孤儿对象` 清单与库中存活引用的对照关系逐条可解释。
2. **存活引用为空而桶中存在孤儿时必须整体拒绝删除**，并出现“疑似连接到错误数据库”的
   WARN；误连空库时若真的执行了删除，是灾难性数据丢失。
3. **开启删除后，被删除的键必须在下一轮扫描中消失**，且 `kb_document_version` 中不存在
   任何 `status <> 'deleted'` 的文档引用它；删除失败的键必须出现在下一轮候选中（自然重试）。
4. **多实例同时运行时只有一台执行对账**，其余实例每轮记“对账单飞锁被其他实例持有”。
   会话级 advisory lock 的真实互斥行为无法离线验证。

开启删除的步骤与回滚见[对账运维文档](docs/storage-reconciliation-operations.md)：先给凭据
加前缀内 `s3:ListBucket`，报告模式观察至少一个周期，确认清单后设置
`KB_STORAGE_RECONCILIATION_DELETE_ENABLED=true`（建议初期保持较小的
`max-deletes-per-cycle`）；回滚只需恢复为 `false` 并重启 kb-app。

---

## 六、上生产前的硬性检查项

这一节不是建议，是拦截项。

- [x] `kb.dev-auth.enabled` 默认 `false`；只有显式 `dev` 开启，生产启动检查拒绝开启开发登录。
- [ ] 接入真实 SSO/OIDC，在认证回调中建立 Sa-Token 会话。
- [x] V3/V6 种子已隔离到 `db/dev`，生产只加载 `db/migration`；已有库的数据清理需单独审查。
- [x] **首个管理员引导机制已落地。** 移除 V3 后，空库部署设置
  `KB_BOOTSTRAP_ADMIN_USERNAME` 与 `KB_BOOTSTRAP_ADMIN_PASSWORD` 再启动，
  即自动创建 `ROLE_ADMIN` 角色行、主部门与首个管理员（首次登录强制改密），
  为新建的角色行显式绑定全部权限。`ROLE_ADMIN` 是普通角色行；已有满足管理资格不变量的
  启用用户时重复启动零修改，既有角色权限残缺只告警、不覆盖。生产部署时必须实际配置这两个变量，否则引导按"未配置"跳过。
- [x] 正式配置的数据库密码、对象存储密钥和模型 API key 由外部注入；本地示例凭据只在 dev 配置与开发 Compose 中。
- [x] 生产 CORS 使用 `KB_CORS_ALLOWED_ORIGINS` 的明确来源清单；通配仅允许在 dev 中，PATCH 预检已支持。
- [ ] 对象存储：私有桶 + 最小权限凭证 + 短时效签名 URL（且签发前先做业务鉴权）。
  按 hash 秒传时，**不能让用户凭 hash 拿到别人的文档**。
- [ ] 对账凭据：kb-app 的 S3 凭据增加前缀内 `s3:ListBucket`（删除模式复用既有 `s3:DeleteObject`
  ，不需要桶级管理权限）；默认报告模式下缺该权限只导致对账每轮失败告警，主业务不受影响。
- [x] 权限缓存：当前**不存在跨请求的权限缓存**（请求内主体只解析一次，句柄寿命不超过一次调用栈，不构成缓存），
  本项不适用。将来若引入跨请求缓存，前置条件是：先修订 `functional-authorization` 与
  `identity-department` 中「变更必须在下一次请求即生效」的要求、先确定可接受的撤销延迟，
  再为部门/角色/密级变更与空间 ACL 变更实现失效策略。
- [ ] 授权检索**不得**走异步只读副本——复制延迟会让旧 ACL 短暂生效。
- [x] 按部门的请求配额、可续期并发租约、token 预算预留/实际 usage 结算与幂等调用审计代码已接入 Redis；仍需在部署环境验证
  Redis 脚本和故障拒绝策略。
- [ ] 日志里不得无差别记录文档正文与用户查询词。
  `/api/ai/chat` 与 `/api/search` 使用 POST JSON 承载问题和检索词；
  `/api/search` 的 GET 已停用，POST URL 查询参数返回 400。应用返回 405
  无法阻止前置代理记录已经收到的 URL。生产部署需检查代理和网关访问日志
  不记录原始 URI、查询字符串、请求体及认证令牌；已有日志按保留策略处理。
- [ ] 答案缓存若要做，key 必须按权限范围隔离。同一个问题，
  不同权限的用户不能命中彼此的缓存。
- [x] 后端追踪开关、内部 W3C 传播、供应商隔离和四单元 Prometheus 导出已落地，追踪默认关闭；
  生产必须设置独立管理端口及绑定地址，并同步迁移健康探针。配置、OpenMetrics 与回退见[遥测运维](docs/telemetry-operations.md)。

---

## 七、目录结构

```
pom.xml                      父 POM（注意 -parameters，MyBatis 映射 record 依赖它）
docker-compose.yml           中间件（未验证，本机无 Docker）
架构决策记录.md               ★ 所有决策与取舍的来源
架构图-v3.png / .html         架构图
生成架构图.py                 架构图生成脚本（Pillow）

db/
  verify_paradedb.sql        ParadeDB 实际行为验证（手动执行）
  verify_permission.sql      权限边界验证（手动执行，建议进 CI）

kb-common/                   公共模型与权限原语（无 Spring 运行时依赖）
kb-app/
  src/main/resources/db/migration/
    V1__core_schema.sql      部门/用户/角色/空间/文档/ACL/密级
    V2__search_schema.sql    分块表 + BM25 索引 + HNSW 索引 + 任务表
    V4__ingestion_idempotency.sql  解析任务唯一约束
    V5__document_integrity.sql     文档/版本/分块/任务完整性约束与审计时间
    V7__parse_output_metadata.sql  解析器版本、解析问题、结构与来源元数据
    V8__identity_department_model.sql 统一身份/部门字段、外部身份映射和变更审计
  src/main/resources/db/dev/
    V3__dev_seed.sql         开发种子数据，仅 dev 加载
    V6__dev_upload_permission.sql  开发种子上传权限，仅 dev 加载
  src/main/resources/mapper/
    SearchMapper.xml         ★ 权限下推的混合检索 SQL，全系统最关键的一段
    SpaceMapper.xml          空间查询与管理写操作（view 与 manage 两个独立谓词）
  src/test/.../ModuleBoundaryTest.java       模块依赖方向约束
kb-gateway/
  .../filter/InboundHeaderSanitizeFilter.java  ★ 剥离客户端伪造的身份头
kb-worker/
  .../parse/model/ParsedDocument.java        统一结构块、解析器版本和解析问题模型
  .../parse/DefaultParsePipeline.java        S3 → 第三方解析接口 → 中间模型 → 结构切块 → embedding
  .../parse/ChunkPublisher.java              租约校验、元数据和分块幂等事务发布
kb-ai/
  .../chat/RetrievalClient.java              ★ 语料唯一入口，透传 Sa-Token 会话
  .../chat/ChatController.java               SSE 组装（含单次订阅的坑）
  src/test/.../NoDatabaseAccessTest.java     ★ 守"不得直连数据库"
  src/test/.../ReactiveStackTest.java        ★ 守"必须跑响应式栈"
```

---

## 八、下一步

按依赖顺序，不建议打乱：

1. **起 PG/MinIO 跑通验证脚本和上传链路**。ParadeDB 的中文分词效果是整个选型的前提，
   若不达标，方案要改，越早知道越好。
2. **摸清文档格式分布**。采样统计各格式占比与体量，
   再确定第三方解析服务的格式、OCR、表格与版面能力要求。
3. **接入第三方解析服务并用真实文档集评估解析、分块和 embedding**，确认扫描件、表格和中文术语的效果。
4. **验证文档管理接口**：列表、详情、进度、重试、删除、版本、下载和预览的真实权限边界。
   连同 5.7 里的空间归档链路——归档空间必须同时退出检索、列表、详情、下载和预览，
   这是本变更留下的唯一未实测项。
5. **文档密级与角色权限模型**：用户密级调整
   （`PATCH /api/admin/identity/users/{userId}/clearance`）、角色分配与解除
   （`POST` / `DELETE /api/admin/identity/users/{userId}/roles`）与首个管理员引导
   （`kb.bootstrap.*`，见第六节）、角色 CRUD 与权限集管理均已落地；文档密级调整也已实现，
   见[文档密级管理](docs/document-security-level.md)。本次仅执行无外部依赖验证，未实测数据库迁移、并发或端到端检索。
6. **配置并核验真实 chat 服务**：通用文本适配及显式多轮估算已实现；后续填写真实模型窗口并校准预算参数，验证供应商兼容与实际 usage；如需严格上界，另行提供已核验的扩展。见[模型接入说明](docs/chat-model-adapter.md)。
7. **接认证**：本地用户名密码登录已落地（见"本地登录与账号管理"），生产仍需接入
   企业 SSO/OIDC 并由认证回调建立会话，同时关闭 dev-auth（第六节）。
8. **对象存储对账**
   ：孤儿识别、护栏删除（默认关闭）、缺失抽样、调度与运维文档均已落地，启用与回滚见[对账运维文档](docs/storage-reconciliation-operations.md)
   ；真实桶上的删除效果与多实例单飞尚未验证（见 5.10）。
9. **接入监控与追踪平台**：按[遥测运维](docs/telemetry-operations.md)配置各单元管理端口、健康探针与 Prometheus 抓取；
   在 Tempo 或 Collector 就绪后显式开启追踪，配置 OpenMetrics exemplar 存储与 Grafana 关联。平台部署与真实网络联调仍由部署环境完成。

《架构决策记录.md》第 10 节"仍待收集"列了还需要向业务方确认的信息，
其中"文档格式与扫描件/表格比例"和"密级分几级、与用户 clearance 如何对应"
会直接影响上面第 2、3 步的实现。
解析任务恢复、死信处置和 Worker 指标见 [运维文档](docs/parse-task-recovery-operations.md)。

## Embedding 一致性与重建

app/Worker 使用显式不可变 profile 清单，旧单 provider
环境变量需迁移；操作及配置见 [Embedding 重建运维](docs/embedding-rebuild-operations.md)。管理入口为 `/api/admin/embedding`
，读与写分别要求 `embedding:read`、`embedding:rebuild`，不授予正文访问权。

首次升级必须排空并停止全部旧 app/Worker，不支持新旧二进制混部。新 app 负责 V15-V17 迁移，旧向量身份保持 NULL，控制状态为
UNINITIALIZED；空库也需要显式重建和校验才激活。维护时只走受权限保护的 BM25，上传排队；基于当前版本已有正文重算向量，包含归档内容，不改分块
ID、权限或历史版本。

重建有独立租约、批次提交、断点恢复与实际覆盖/双侧就绪门禁。BLOCKED
保留进度且无即时回滚，缺分块必须按运维文档走删除退出范围或独立审查的数据修复，不能在维护中重放普通解析绕过门禁。仅执行离线单测和静态检查；真实迁移、数据库锁/回滚、崩溃恢复、多实例部署和检索效果不在本次验证范围内，不能将单测通过视为生产验证完成。

## 离线检索质量评测

`kb-eval` 只读本地清单、标注与有序结果，不连接业务服务或中间件；默认 k=5/10/20。按 `docId + versionId` 计算
Hit、Recall、MRR、nDCG、文档版本重复率，空结果预期另算符合率。输出目录必须不存在，质量门禁失败仍保留完整报告。格式、指标、阈值、PowerShell
用法与明确单测白名单见[评测说明](docs/retrieval-evaluation.md)，合成文件见 `kb-eval/examples/demo`。

```powershell
mvn -pl kb-eval -am package '-DskipTests'
```

本次仅执行进程内组件单元测试及跳过测试的 Maven 打包检查；没有集成测试、JAR
进程烟测、外部组件运行或文档内容测试。合成数据的质量结果不能作为线上检索、算法、答案或实时权限验收。
