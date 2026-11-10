# 用户端静态托管与回退

向量重建与解析死信页面的入口、权限、维护影响和结果核对规则见 [运维管理页面说明](embedding-parse-operations-web.md)。

本文提供部署约定，不执行部署、服务启动或外部链路测试。用户操作与安装见 [kb-web/README.md](../kb-web/README.md)，新增只读接口见 [资源操作能力查询](user-resource-capabilities-api.md)，收藏接口及 V20 迁移见 [个人文档收藏](document-favorites.md)。

## 构建与公开配置

使用 Node.js 24+ 与 npm 11+，在 `kb-web` 执行：

```powershell
npm ci
npm run typecheck
npm run build
```

静态产物位于 `kb-web/dist`，独立于 Maven 构建。提交的锁文件固定依赖。`VITE_CONVERSATION_ENABLED` 在构建时写入公开资源，缺省 false，只接受明确 `true`；修改后重新构建。它不代表模型就绪或访问授权，禁止将密码、令牌、模型密钥写入任何 VITE 变量。开发配置参考 `.env.example`，`KB_WEB_GATEWAY` 仅控制 Vite 的 `/api` 代理，生产由同源反向代理处理。

app 与 AI 的 `kb.conversation.enabled` 应先统一，再对齐前端开关。未提供多轮模型时保留生成 503 提示，不设置虚假生产回答，不回退旧单轮占位服务。SSE 每事件 1 MiB、累计回答 64000 个 code point、引用 256 项，若运维调整后端限额须同步客户端 `src/stream.ts` 限界。问题上限为 8000 个 code point。客户端上传限额 50 MiB，调整 `KB_MAX_UPLOAD_SIZE` 时同步客户端提示与校验。

企业 SSO 验证仅执行 [SSO 前端说明](enterprise-sso-web.md) 中明确列出的独立单元测试文件，不使用全量自动发现入口。

## 同源路由与响应边界

### 搜索限界与 SSE 检索错误

构建变量 `VITE_SEARCH_MAX_QUERY_CODEPOINTS` 默认 8000，可设正整数收紧搜索词上限；非法配置回落默认值。客户端按 code point 检查且不截断，NUL/孤立代理项禁止发送；app 实际限制仍为最终判定。搜索专用 400/413/429/503 提示与文件上传、部门 AI 配额分开，保留真实状态和 `Retry-After`，不自动重试。

AI error 的可选白名单 code `SEARCH_UNAVAILABLE` 显示固定“检索暂不可用，请稍后重试。”，旧或未知 code 保持通用错误流程。页面继续核对服务器持久终态，不从错误事件推造 FAILED 或成功回答。页面隐藏、离开、身份代次变化会废弃旧结果与提示；客户端取消只表示停止等待，不表示服务端工作或供应商计算实际结束。

2026-10-31 本组显式单元测试 `npx vitest run src/search.test.tsx src/stream.test.ts src/assistant.test.tsx src/lifecycle.test.tsx`：4 文件、46 项通过，全部使用会话/API 替身与模拟 DOM。

同轮 `npm run typecheck` 与 `npm run build` 均通过；构建生成 dist，报告单包超过 1200 kB 的体积提示。

建议将 `dist` 内容托管在站点根路径，以 HTTPS 提供服务。先匹配 `/api/**` 转发到现有网关并保留原 HTTP 状态、Content-Type、认证头和 SSE 轮次响应头，再处理静态资源和 SPA 页面回退。只有页面路径可回退 `index.html`；API 的 401/403/404/503 不得转换为 200 HTML。缺失静态资源返回真实 404，避免把 JS 请求回退为页面。

`/favorites` 为受保护的 SPA 页面，直接访问与刷新需要页面回退；登录及必要改密完成后可返回此路径。收藏入口不依赖会话功能开关或管理员资格。四个 `/api/documents/**` 收藏端点沿用现有网关路由及 PUT/DELETE CORS 配置，保留 JSON 正文和真实状态码。

`/notifications` 同样是受保护的 SPA 页面，需要页面回退。三个 `/api/notifications/**` 端点由网关 prod/dev 的 kb-app 业务路由转发（PUT 已在现有 CORS 方法内），成功与提前拒绝均为 `no-store`；静态托管不得缓存或改写这些响应。详见 [站内通知](in-app-notifications.md)。

保留 `satoken` 认证头和 `X-Conversation-Id`、`X-Turn-Id` 响应头，文件请求同样经 `/api` 转发。SSE 路由关闭代理缓冲和结果缓存，允许增量传输；连接中断需及时传递到上游，避免退出或离开页面后仍持有生成订阅。超时与请求体限制应与现有服务对齐，不以延长连接掩盖依赖失败。

`index.html` 建议禁止缓存或要求重新验证；带内容指纹的公开静态资源可长期缓存。所有认证/文档/收藏/搜索/会话/引用/文件响应禁止共享缓存；反向代理不能覆写后端 `Cache-Control: no-store`。收藏成功、失败及网关提前拒绝响应都需要保留该响应头。前端不注册 service worker，不提供受保护内容的离线缓存。

## 登录来源与 X-Real-IP

kb-app 的 `POST /api/auth/login` 只把入口 Nginx 写入的 `X-Real-IP` 当作登录来源，用于来源节流和失败审计；不读取 `X-Forwarded-For`、`Forwarded`，也不使用连接地址（连接地址是网关地址）。kb-gateway 原样透传这个头，不做可信代理处理。头缺失、出现多次、带端口或不是单个 IP 字面量时，来源按未知处理，此时只执行账户节流，行为与引入来源节流之前相同。

### Nginx 配置

在代理 `/api/` 的 location 中，用 Nginx 观察到的对端地址覆盖客户端自带的同名头：

```nginx
location /api/ {
    proxy_set_header X-Real-IP $remote_addr;
    proxy_pass http://kb-gateway;
}
```

`proxy_set_header` 只有在当前 location 一条都没写时才会继承上一层的配置。SSE 等为 `/api/` 子路径单独写过 `proxy_set_header`（例如 `Connection`）的 location，必须再写一遍 `X-Real-IP` 这一行；否则这些路径会把客户端自带的值原样转发下去。

### 访问前提

- Nginx 是唯一入口：网关端口只允许 Nginx 访问，kb-app 端口只允许网关访问。能绕过 Nginx 直接访问网关或 app 的人可以伪造 `X-Real-IP`，既能绕过来源节流，也能冒用他人地址让对方被节流。
- 如果将来在 Nginx 前面再加负载均衡或 CDN，必须同时配置 Nginx 的 realip 模块（`set_real_ip_from` 填可信的上游地址，`real_ip_header` 填上游写入的头）。否则 `$remote_addr` 会变成负载均衡的地址，全体用户共用一个来源计数，会被集体节流。

### 发布、回退与上线核对

1. 先修改 Nginx，再发布 kb-app。旧 kb-app 会忽略这个头；如果顺序反过来，在 Nginx 改好之前，客户端自带的 `X-Real-IP` 会被原样转发并被采信。
2. 回退时先回退 kb-app，再视情况回退 Nginx。没有数据库迁移；Redis 中的来源计数键（`kb:auth:login:source-fail:*`）随 TTL 过期，不需要清理。
3. 上线后，从外部带一个伪造的 `X-Real-IP` 发起一次密码错误的登录，确认最新一条失败审计中的 `sourceIp` 是真实客户端地址，而不是伪造值或网关地址：

```sql
SELECT created_at, after_state ->> 'username' AS username, after_state ->> 'sourceIp' AS source_ip
FROM sys_identity_audit
WHERE operation_scope = 'AUTH_LOGIN_FAILURE'
ORDER BY created_at DESC, id DESC
LIMIT 1;
```

### 阈值与排障

| 配置 | 默认值 | 说明 |
| --- | ---: | --- |
| `KB_LOGIN_SOURCE_MAX_FAILURES` | 20 | 同一来源在窗口内的凭据失败次数，达到后拒绝该来源的所有登录尝试；最小为 1，非法值阻止启动 |
| `KB_LOGIN_LOCK_DURATION` | 15m | 与账户节流共用的窗口和锁定时长，到期自动解除 |
| `KB_LOGIN_MAX_FAILURES` | 5 | 账户节流阈值，不变 |

来源阈值要明显高于账户阈值：同一 NAT 或 VPN 出口下，一个人反复输错可能让共用这个出口的其他人暂时无法登录。登录成功不会清除来源计数，只能等它过期。被来源节流拒绝时，用户看到的仍是"用户名或密码错误"，与凭据失败相同。用户反映"密码正确却登录不了"时，可以先按来源聚合失败审计，判断是不是共用出口被节流，再决定调高阈值还是等待过期。

来源节流和账户节流拒绝的请求都不写审计，只有进入凭据校验的失败会留下记录。下面的只读示例 SQL 按来源和用户名聚合最近 24 小时的登录失败，本次没有实际执行，使用前请在测试库核对：

```sql
SELECT after_state ->> 'sourceIp' AS source_ip,
       after_state ->> 'username' AS username,
       count(*) AS failures,
       max(created_at) AS last_failure
FROM sys_identity_audit
WHERE operation_scope = 'AUTH_LOGIN_FAILURE'
  AND created_at >= now() - interval '24 hours'
  AND after_state ? 'sourceIp'
GROUP BY 1, 2
ORDER BY failures DESC
LIMIT 50;
```

全局审计查询接口（`/api/admin/audits`）和审计页面不返回 `sourceIp`，现有的 `loginUsername` 筛选也不变。旧的按目标审计接口 `GET /api/admin/identity/audits` 会原样返回 `after_state`：持有 `identity:audit:read` 的人用 `targetType=auth` 和用户 ID 查询时，能看到该用户登录失败记录里的 `sourceIp`，见[管理查询 API](admin-query-api.md)。来源 IP 属于个人信息，和现有审计存放在同一张表、按同一套保留策略处理；保留期限需由运维和合规另行确定，本次没有新增清理机制。

## 企业 SSO 固定结果页

前端页面、网关和回调使用同一公开 Origin。`KB_SSO_PUBLIC_ORIGIN` 不带页面路径，`KB_SSO_RESULT_PAGE_URI` 为该 Origin 加 `/auth/sso/result`，三家 callback 继续指向各自 `/api/auth/sso/{connectionId}/callback`。生产使用 HTTPS；开发同源代理保留浏览器 Origin，与后端显式开发配置匹配。

静态托管为 `/auth/sso/result` 返回 SPA 页面并设置 `Cache-Control: no-store`、`Referrer-Policy: no-referrer`。网关应保留浏览器 Cookie 和每条 Set-Cookie，不改写 __Host- Cookie 的约束；绑定请求继续携带原 satoken。前端只对明确 SSO POST 使用 same-origin Cookie，普通业务仍为 omit，不能配置任意跨域认证目标。

结果页先处理一次性交接，再恢复 me 核对；交接期间不会展示旧业务内容。页面聚焦或恢复不重发交换，退出、凭据替换和真正离开认证路由会废弃旧操作。交换后响应丢失的恢复方式见 [企业 SSO 前端](enterprise-sso-web.md)。认证和回调路径不记录原始 URI/query、请求体、Cookie、令牌或平台响应。

发布时先保证已有 SSO 后端接口版本兼容，再发布静态页面和匹配的结果地址配置；回退时先关闭企业连接并处理有界在途流程，再恢复旧静态产物。本地登录保留，绑定与审计不删除。以上只交付部署及回退说明，不安排外部组件运行、真实扫码或联调验收任务。

## 浏览器安全与日志

可从以下 CSP 开始，按托管域与浏览器 PDF 实际策略调整，不放宽远程脚本来源：

```text
default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data: blob:; font-src 'self'; frame-src blob:; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'
```

Element Plus 的组件 CSS 按需打包，弹窗与下拉定位等运行时内联样式沿用上述样式许可；脚本没有 `unsafe-inline`。PDF 使用临时 Blob iframe，文本通过 Vue 插值显示，禁止 `v-html`。正文没有可执行 HTML、远程图片或自动外链加载。设置 `Referrer-Policy: no-referrer` 与 `X-Content-Type-Options: nosniff`。根据组织要求补充 HTTPS/HSTS。

令牌仅保存在当前标签页 sessionStorage，仍可被同源脚本读取。不得注入不可信同源脚本或将前端静态目录作为用户上传文件目录。访问日志及代理诊断禁止记录 satoken、Authorization、请求体、搜索词、问题、答案、引用或密码；关闭这类请求的 body tracing。前端不写正文或凭据遥测。文件取得后无法撤回用户已经保存的副本。

## 发布与回退顺序

业务页面按路由下载，登录、改密和 SSO 结果页直接包含在入口中。等待或资源失败只影响页面内容区，导航、退出及原有资格核对继续可用。失败后可离开页面或主动“刷新页面”；刷新会丢失未保存输入，不自动重放任何业务写入或 SSO 交换。客户端停止等待不代表服务端取消已提交操作。

静态资源的发布和保留规则：

- HTML 使用 `Cache-Control: no-store` 或强制重新验证；认证和 SSO 路径继续要求 `no-store`、`Referrer-Policy: no-referrer`。带内容哈希的公开 JS/CSS 可使用长期缓存，例如 `public, max-age=31536000, immutable`。
- 先上传完整的新哈希资产，再原子切换 HTML。旧资产至少保留上一版本，并覆盖最长允许的已打开标签页使用窗口；不要发布时清空整个 assets 目录。
- 回退时先确认旧 HTML 引用的所有资产仍可用，再切换 HTML；保留被回退版本的哈希资产，避免仍打开新版本的标签页后续加载失败。
- 缺失 JS/CSS 必须返回真实 404 和正确响应类型，禁止回退为 200 HTML。只有页面路径使用 SPA 回退，API 保持真实响应。
- 不增加全局自动刷新或失败后循环重试。旧标签页遇到已清理资源时，由用户选择刷新；刷新沿用现有身份重核，不恢复未保存草稿，不把代码缓存当作资源授权。
- `.bundle-analysis` 的 manifest、模块清单和报告只用于本地分析，不作为公开发布资产；不发布源码映射。验证命令见 [用户端 README](../kb-web/README.md)，这些部署规则不要求启动服务器或浏览器验证。

1. 先发布包含 `/api/auth/upload-spaces` 和 `/api/documents/{documentId}/capabilities` 的后端。这两个入口不新增 DDL、角色或权限，也不修改既有写入接口。
2. 再发布前端静态产物与同源代理配置；会话功能按 app/AI 的配置对齐。保留前一版静态产物用于回退。
3. 只回退前端时恢复旧静态产物或撤下入口，新增只读后端可保留。若需同时回退后端，应先撤下依赖新增接口的前端。
4. 回退不删除文档、会话、用户或凭据，不自动重放上传、重试解析和生成请求。

### 文档删除发布与结果核对

1. 先发布新增 `canDelete` 投影的 kb-app，再发布用户端静态产物。该字段复用已有 edit 判定并保留 view 门禁，不新增权限、数据库迁移或服务端删除回执。现有 `/api/documents/**` 路由继续转发无请求体的 `DELETE /api/documents/{documentId}`，不得把 API 响应转换为 SPA 页面。
2. 新前端只有读到明确的 `canDelete=true` 才开放详情页入口；遇到旧服务缺少字段或能力查询故障时禁用。删除作用于整份文档及全部版本，无法通过选中历史版本缩小范围；确认前重新读取事实，并协调本页已有写入。
3. 仅当前 DELETE 的合法 HTTP 200 成功封装确认删除成功。随后清理详情、释放预览并 replace 返回文档中心；成功提示只在当前会话短期内存中消费一次，列表加载失败不推翻已确认的删除。
4. 网络中断、15 秒客户端等待到期、响应正文损坏及可能发生在提交后的服务异常均保留“结果未确认”。用户主动核对只读取详情与能力：404 仅表示当前不可访问，200 仅表示查询时仍可访问，均不能代替原请求回执；核对失败继续未确认，不自动重发 DELETE。新的删除必须重新读取事实并再次明确确认。
5. 退出、换用户、强制改密、切换文档、隐藏或离开详情使旧回调失效。取消本地等待不证明服务端回滚，恢复后重新确认身份和资源，不恢复旧确认或自动重发。

回退时恢复旧前端即可撤下入口，新增后端字段兼容旧前端；回退后端后新前端会因缺少能力字段禁用删除。版本回退不会恢复已删除的文档，不自动重建版本、重放请求或提供业务撤销。对象存储清理仍遵循原删除流程与既有对账机制，不能把成功提示解释为原件立即物理擦除。

### 文档收藏发布

1. 由 kb-app 单点执行 `V20__document_favorites.sql`，发布支持四个收藏端点的 kb-app，并发布包含收藏 `no-store` 策略的 kb-gateway。网关路由范围保持 `/api/documents/**`。
2. 后端与网关就绪后发布前端静态产物，托管允许 `/favorites` 页面回退。收藏读取按当前权限重新过滤；撤权或归档后隐藏，权限恢复且未取消时恢复可见。状态故障与写入结果不确定时使用页面“核对收藏状态”，不自动重发收藏指令。
3. 回退时先恢复旧前端，再按需回退 kb-app/kb-gateway；保留收藏表、本人关系及已应用的 Flyway 历史。核对旧程序与 V20 历史的兼容性，不以自动 repair、删除迁移历史或删除收藏数据处理版本差异。

收藏用户操作、接口边界和本地检查记录见 [个人文档收藏](document-favorites.md)。

本变更仅以类型检查、静态构建和内存单元/架构检查为验收范围，不声明真实网关、数据库、对象存储、模型或浏览器链路已验证。不提供需要外部组件的执行任务，也不为本文、README 或配置示例编写文案测试。

## 本次实施检查记录

2026-10-18 在 Windows PowerShell、Node.js 24.21.0、npm 11.19.0 环境完成：

| 工作目录 | 实际命令 | 结果 |
| --- | --- | --- |
| `kb-web` | `npm ci --no-audit --no-fund` | 安装成功 |
| `kb-web` | `npm run typecheck` | 通过 |
| `kb-web` | `npm test -- --run` | 10 个单元测试文件、80 项测试全部通过 |
| `kb-web` | `npm run build` | 通过，生成 `dist` 静态产物 |
| 项目根目录 | 下列 Maven 白名单命令 | 22 项测试通过，失败/错误/跳过均为 0 |

```powershell
mvn -o -pl kb-app -am test '-Dtest=UploadSpaceQueryServiceTest,DocumentCapabilitiesServiceTest,UserResourceControllerTest,UserResourceMappingTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
```

前端测试使用内存请求、受控流、虚拟时间与模拟 DOM；后端仅运行独立 service/controller、无连接 MyBatis 映射与 ModuleBoundaryTest。未执行全量旧测试、JavaSourceConventionTest、集成测试、文档文案测试或任何需要外部组件的检查。未启动开发服务器、网关、浏览器、数据库、Redis、MinIO、容器和真实模型服务。

## 文档删除本地检查记录（2026-10-30）

本次 `user-web-document-deletion` 使用以下白名单执行隔离单元测试，未启动应用或外部服务：

```powershell
mvn -pl kb-app -am test '-Dtest=DocumentCapabilitiesServiceTest,DocumentManagementServiceTest,DocumentCapabilitiesSerializationTest,UserResourceControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
```

后端 24 项测试通过，失败、错误、跳过均为 0；包括删除能力投影、DTO 实际序列化，以及原删除流程的任务、审核和存储清理单元回归。

在 `kb-web` 执行：

```powershell
npm test -- --run src/documents/capabilities.test.ts src/documents/operations.test.tsx src/documents/deletion.test.ts src/documents/controls.test.tsx src/documents/notice.test.ts src/documents/write-coordination.test.tsx src/session.test.ts src/favorites/store.test.ts
npm run typecheck
npm run build
```

前端 8 个测试文件、107 项单元测试通过，类型检查及生产构建通过。其中新增 11 个恢复回归用例：未提交删除的核对恢复后清除旧提示并释放删除锁，保留普通写入记录；在途核对作废后可以主动重试，迟到成功或失败响应不能覆盖新事实，已提交删除的未确认结果及明确拒绝事实继续保留。构建提示主包超过既有 1200 kB 警告阈值，不影响产物生成。删除控制器、协调逻辑和确认界面以 Mock 传输、上下文或回调隔离，未挂载完整页面或真实业务子组件进行联合回归。

本次未执行集成测试、文档内容断言或人工文档核对，未运行 DB、Redis、MinIO、网关、开发服务器、浏览器 E2E 或真实模型服务；本地单元测试结果不表示真实部署链路已验收。
# Office 原生预览

可选 Office 服务启用后，文档详情、审核工作台和版本引用统一支持 docx/xlsx/pptx 的只读在线预览。PDF、TXT、Markdown 继续使用原预览抽屉；doc/xls/ppt 不转换预览。原件下载始终独立检查下载权限，有 view 无 download 也可阅读。部署参数与网络隔离见 [Office 预览运维说明](office-preview.md)。

点击预览后先显示“正在打开文档”，只有当前查看器确认 ready 才显示可阅读状态。Office 窗口占用大部分可用屏幕，工作表切换、横向滚动和幻灯片翻页由原生查看器提供；引用仍锁定引用时的版本，来源页码只作提示，不承诺与原生分页一一映射。

预览信息和会话创建各等待最多 15 秒，超时提示打开结果未确认，允许主动重试；关闭页面或主动取消不会提示为超时。连接旧后端时，Office 显示服务不支持或目标不可访问，PDF/文本仍可通过原有受保护入口阅读。

默认会话最长 30 分钟，活动租约 45 秒。隐藏页面、身份重核、切换版本、退出或改密立即清理当前预览，返回后需主动打开。服务未部署、格式不支持、权限改变或打开失败时显示明确提示；“重试预览”才建立新请求，网络结果未知不会自动重复创建。关闭请求失败不保留文档显示，由短租约最终回收。
