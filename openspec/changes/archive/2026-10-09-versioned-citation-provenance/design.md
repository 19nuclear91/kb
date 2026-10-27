# Design

## Context

动机见 `proposal.md`，外部行为见 `specs/citation-provenance/spec.md`。当前实现对本设计的约束如下：

- `kb_doc_chunk` 已有 `doc_id`、`version_id`、分块正文和来源字段；`kb_document_version` 提供 `version_no`。
  `V2__search_schema.sql` 的分块主键及版本外键足以支持按身份查询，V7 的历史来源字段允许为空。
- `kb-app/src/main/resources/mapper/SearchMapper.xml` 使用显式构造器映射。两路候选已限制当前已发布版本、密级、ACL
  和空间未归档，但最终投影及 `ChunkHit` 没有版本字段。
- `kb-ai` 的 `RetrievalClient` 只转换少量引用字段；`ChatService.boundContext` 按 Unicode code point 预算裁剪并重建对象，是另一个元数据丢失点。
  `citation` 在模型输出之前发送，不能据此判断模型实际使用了什么。
- `kb-worker` 的 `ChunkPublisher` 在同版本发布时删除并重建分块，新 ID 不延续旧 ID；正常新版本发布不会删除其他版本分块。当前用户
  retry 主要针对失败任务，本变更不新增任意成功版本重解析入口。
- `DocumentMapper.findFile` 已支持历史版本，按文档当前空间、当前密级、未删除状态和指定权限位鉴权。文件读取不要求 `published`
  或当前版本；检索要求两者。本设计保留这一区别，不借引用功能收紧或放宽既有文件状态规则。
- `PermissionService` 每次重新解析范围，主体停用等身份失败降级为 `denyAll`；无资源读取通常统一为 HTTP `404`
  。登录和强制改密分别由现有拦截器处理。
- Tika 目前抽取并规范化纯文本；`TextChunker.sourceRange` 合并来源块范围，页码来自首个来源块。这些数值不能作为原文件的精确坐标使用。

## Goals / Non-Goals

**Goals:**

- 用同一次检索的分块版本事实贯通 `kb-app` 与 `kb-ai`，不增加逐条回查当前版本的请求。
- 在一个受保护的文档读取入口内区分“有完整原分块”和“没有匹配分块但原版本可访问”。
- 使升级、上下文裁剪、历史版本和授权变化均有可测试的明确结果，不把展示元数据当成身份或授权。

**Non-Goals:**

- 不保存回答时的文档标题、prompt、片段或权限快照；`docTitle` 不是历史标题证据。
- 不新增来源坐标空间、OCR、页内坐标或证据签名，不修改解析、分块与 Embedding 发布机制。
- 不实现前端、不改全局响应封装、不引入跨模块 Web DTO 依赖，也不扩大 `download`、`view` 或管理权限的含义。
- 按确认后的验证范围，不创建或执行集成测试、端到端测试、数据库执行及部署联调任务；不运行 H2、PostgreSQL/ParadeDB、Redis、MinIO
  或真实模型服务。业务行为要求保持不变，单元测试通过不代表真实环境已验证。

## Decisions

### D1. 固定三元组，展示字段不参与定位

使用 `docId + versionId + chunkId` 作为调用方保存的引用身份。`versionNo`、`docTitle`、`chunkIdx` 和来源字段只用于展示及人工核对；不作为查询替代键。

在检索最终投影中加入 `c.version_id`，并连接 `kb_document_version v`，同时约束 `v.id = c.version_id` 和
`v.doc_id = c.doc_id` 后读取 `v.version_no`。两路候选、RRF、重排及原权限过滤不改变。结果与分块处在同一条查询的数据库快照中，不通过后续读取
`d.current_version_id` 填充。

扩展 `ChunkHit` 的显式构造器映射，保留旧 9 参数和现有完整参数形态的兼容构造器；兼容构造器没有版本信息时只填 `null`
，不能推断身份。使用生产 `resultMap` 与 mock `ResultSet` 的映射单元测试，验证带有版本数据的夹具被正确映射为非空版本字段，不连接数据库。

**替代方案：** 只使用 `docId + chunkIdx` 或回查最新版本会使新发布内容冒充旧引用；为每次回答保存快照则引入本轮不需要的存储及保留策略。均不采用。

### D2. 使用增量字段扩展，区分身份完整性与片段可用性

既有字段名称和 JSON 类型不变。新增或贯通字段如下：

| 字段                                                                  | 类型与空值                       | 使用位置及含义                     |
|---------------------------------------------------------------------|-----------------------------|-----------------------------|
| `versionId`                                                         | 64 位整数；兼容旧响应时可为 `null`      | 检索、内部参考、SSE；唯一版本身份          |
| `versionNo`                                                         | 整数；旧上游未提供时可为 `null`         | 检索、内部参考、SSE；该版本的展示编号        |
| `chunkIdx`                                                          | 整数，可为 `null`                | 内部参考及 SSE 补齐已有检索字段，不用于回退定位  |
| `pageNo`                                                            | 整数，可为 `null`                | 保留旧字段，语义为解析器报告的首个来源块页码      |
| `headingPath`、`structureType`、`sourceLocator`                       | 字符串，可为 `null`               | 保留已有来源提示，不解析为可直接跳转的 URL     |
| `sourceLineStart`、`sourceLineEnd`、`sourceCharStart`、`sourceCharEnd` | 整数，可为 `null`                | 原样保留已有来源范围，不重算其精度或坐标系       |
| `provenanceStatus`                                                  | `VERSIONED` 或 `UNVERSIONED` | SSE；表示是否具备完整三元组，不表示查询当前一定成功 |
| `contextTruncated`                                                  | 布尔值                         | 内部参考及 SSE；仅标记本轮上下文预算是否裁剪该片段 |

`RetrievalClient.Citation` 和 `ChatEvent.Citation` 分别保留内部正文与公开元数据；SSE 不增加正文。沿用本地 DTO，不为这些字段新增
`kb-common` 共享 Web 模型。旧构造调用通过委托构造器迁移，不能借测试夹具制造默认版本 ID。

转换规则按条处理：

1. `docId`、`chunkId` 必须是支持范围内的正整数；错误条目丢弃，不能退化为 0。
2. `versionId` 缺失或为 `null` 时保留普通参考材料，标为 `UNVERSIONED`；旧事件连状态字段都没有时，文档要求消费方同样按缺少版本处理。
3. 明确给出但类型错误、非正数或溢出的身份值属于损坏条目，不能作为“旧响应”接受。逐条过滤，不能使其他正常条目一同失败。
4. `versionNo` 不参与身份判定；合法 `versionId` 但旧上游没有版本号时，仍可标为 `VERSIONED`。缺失的可选来源用 `null` 表示，避免
   `String.valueOf(null)` 产生伪来源。
5. 不增加旧引用解析接口，不查询最新版本替旧事件补齐身份。检索失败仍沿用现有空结果降级，调用者令牌仍原样转发，不补服务账号。

**替代方案：** 强制所有响应必须携带新字段会使滚动升级时旧 `kb-app` 与新 `kb-ai` 不能协作；仅省略字段而不说明降级又容易诱使调用方补最新版本。采用显式降级状态。

### D3. 元数据随预算裁剪保留，但不形成 prompt 快照

先得到预算内的参考列表，再从同一列表生成 SSE 和调用模型适配器，保持当前执行顺序。裁剪只替换 `content`、设置
`contextTruncated`，其余身份及来源字段不动；无裁剪时为 `false`。继续使用 code point 预算，避免切断代理对。

完全超出预算的分块不发送引用。被部分裁剪的分块仍指向完整存储分块，来源范围仍属于原分块，不根据前缀长度调整成貌似精确的原件区间。引用核对接口不返回
`contextTruncated`，因为它没有对应的会话上下文。

模型未引用某材料、使用占位应答或模型稍后出错，都不改变已发送 citation 的“本轮参考来源”含义。既有 `done`、`error`
、usage、超时和取消处理不改。

**替代方案：** 在模型输出后推测实际引用或持久保存裁剪前后全文，会变成逐句归因或证据快照项目；本轮只提供诚实的参考身份与裁剪标记。

### D4. 文档模块提供单条受保护的引用解析

在现有 `DocumentController` 增加 GET 路由，调用专用 `DocumentCitationService`，数据查询放入 `DocumentMapper`。保持
`web -> service -> mapper`；`search` 不调用 `document.mapper`，`kb-ai` 不增加数据库访问。

新增只读投影查询以文档及版本为主体：

- 连接 `kb_document_version v` 和 `kb_document d`，同时匹配请求的 `documentId` 和 `versionId`。
- 使用 `d.status <> 'deleted'`、`d.security_level <= clearance` 和已有 `documentAccessPredicate`，权限位固定为 `view`，不附加
  current-version 或 published 限制。
- `LEFT JOIN kb_doc_chunk c`，在连接条件中同时匹配请求 `chunkId`、`c.doc_id = d.id` 和 `c.version_id = v.id`。
- 用可空的 `matched_chunk_id` 区分有片段与无片段。授权父行不存在就返回 404；父行存在且分块为空就进入 `CHUNK_UNAVAILABLE`。

这使“可见原版本 + 分块是否匹配”在一条读取中形成结果，避免先读不受保护的分块再补授权，或因 INNER JOIN
丢失可提供的原版本入口。查询不涉及向量、解析任务或对象存储。

服务每次调用 `PermissionService.resolve(userId, PERM_VIEW)`；其 `denyAll` 行为使已停用用户或主部门走相同的不可见
404，不另行暴露主体或资源信息。登录 401 和强制改密 403 沿用现有拦截器，不增加白名单。

**替代方案：** 通过搜索接口回查会丢失历史版本，依赖活跃向量配置且无法精确区分失效；无条件按 chunkId 读取再鉴权增加泄露风险。选择文档模块的带权限
LEFT JOIN。

### D5. 用成功解析结果表达缺失片段，不声称永久删除

响应采用 `ResponseEntity<R<DocumentCitationView>>`，正常解析均为 HTTP 200 和 `R.ok(...)`。DTO 的顶层字段为 `status`、
`docId`、`docTitle`、`versionId`、`versionNo`、`chunkId`、`chunk`、`previewPath`、`downloadPath`。`chunkId`
在失效响应中只是回显请求值，不证明其存在过。

可用结果示意：

```json
{
  "code": 200,
  "message": "ok",
  "data": {
    "status": "AVAILABLE",
    "docId": 1001,
    "docTitle": "架构说明",
    "versionId": 2001,
    "versionNo": 1,
    "chunkId": 4001,
    "chunk": {
      "chunkIdx": 3,
      "content": "用于核对的完整存储分块。",
      "pageNo": null,
      "headingPath": "权限设计",
      "structureType": "PARAGRAPH",
      "sourceLineStart": 10,
      "sourceLineEnd": 18,
      "sourceCharStart": null,
      "sourceCharEnd": null,
      "sourceLocator": null
    },
    "previewPath": "/api/documents/1001/versions/2001/preview",
    "downloadPath": null
  }
}
```

`CHUNK_UNAVAILABLE` 保留同一顶层形状，`chunk=null`，不返回客户端携带的旧正文、旧页码或其他分块信息。`docTitle` 使用当前可见文档标题，
`versionNo` 使用指定版本事实，不声称是回答时保存的标题快照。

| 条件                         | HTTP / 业务结果               | 可返回内容                       |
|----------------------------|---------------------------|-----------------------------|
| 原文档及版本可查看，三元组匹配            | 200 / `AVAILABLE`         | 完整分块、来源、原版本业务入口             |
| 原文档及版本可查看，无匹配分块            | 200 / `CHUNK_UNAVAILABLE` | 原版本身份、按权限提供的入口，`chunk=null` |
| 文档/版本不存在、已删除、归属错误、无当前 view | 404 / 既有失败封装              | 统一错误消息，不含 `data` 中的引用详情     |
| 身份门禁或参数不合法                 | 401、403 或 400 / 既有失败封装    | 不含引用详情                      |

404 复用“文档或版本不存在，或当前用户无权访问”这类统一说明。保持 `R` 的失败 `code=500` 约定，不把业务 code 误当 HTTP
状态，不在本变更重构全局异常处理。

**替代方案：** 410 暗示资源曾存在且永久消失，但项目没有 tombstone 可以证明；统一 404 则无法向有权用户表达安全的原版本回退。采用授权父资源下的显式解析状态。

### D6. 文件入口不是授权凭据，查看与下载分别计算

只有原版本 `view` 查询成功才构造任何返回数据。`previewPath` 固定为
`/api/documents/{documentId}/versions/{versionId}/preview`，不进行内容嗅探，不打开对象文件，因此不保证格式可预览；真正访问时仍可能由既有策略返回
415。

`downloadPath` 通过独立的 `PermissionService.resolve(userId, PERM_DOWNLOAD)` 和 `DocumentMapper.findFile`
可见性查询判定。查询无结果则为 `null`，不能因缺少 download 而使 view 引用查询整体失败；基础设施异常不得被吞成“无权限”。即使内部文件模型含
`objectKey`，也只用于内部判定，禁止直接序列化该模型到引用响应。

两个路径都是相对业务 URL，不含 Sa-Token、签名地址或原始存储地址。文件请求继续执行自身的实时权限检查与
`Cache-Control: no-store`。新引用的两种 HTTP 200 响应同样设置 `Cache-Control: no-store`，不使用缓存回复绕过鉴权；错误响应不附带敏感数据。

授权更新提交之后开始的新请求必须读取新事实；已经开始处理的读取沿用既有请求边界，不承诺撤回已发出的内容、停止下载或逐 token
重鉴权。并发重解析时可读到旧快照中的匹配分块或提交后的无匹配状态，但不能把新分块配上旧 ID。

**替代方案：** 直接发签名下载地址会将撤权时效转移到 URL 有效期；依据 view 一并发放下载则违反既有权限分离。继续复用业务入口。

### D7. 来源提示按现状消费，失效不反向改造解析

字段原样流转，未知值保持 `null`。不把 `pageNo` 解释为整个分块覆盖的页范围，也不宣称 `sourceCharStart/End` 是原 PDF、Office
或规范化前文件的字符偏移。章节和来源标识作为纯数据输出，不渲染为 HTML 或执行定位表达式。

同版本重解析改变 ID 后，旧查询自然成为 `CHUNK_UNAVAILABLE`。发布新版本、更新文档标题或只重建 Embedding
不改固定三元组；正文存在时仍可核对，不以当前 Embedding profile 或维护状态作为查询前置条件。

**替代方案：** 稳定 chunkId、按内容哈希追踪或按相同 chunkIdx 猜测继任分块，都将新增解析代际身份和迁移问题；本轮接受明确失效，不改变生命周期。

### D8. 验证限定为单元测试与静态检查

- 映射测试只加载生产 MyBatis `resultMap`，以 mock `ResultSet` 检查构造器顺序、类型、空值和版本字段；SQL 测试生成 `BoundSql`
  并核对参数映射、三元组连接及权限条件，不建立数据库连接或执行查询。
- BM25 单路、向量双路和新引用查询分别检查 SQL 生成结果，沿用 `SearchProfileSqlTest` 的无数据库方式。权限条件的结构检查不等价于证明生产
  SQL 的实际执行效果。
- 服务单元测试以 mock `PermissionService`、Mapper
  返回值及异常模拟历史版本、失效和当前授权变化，核对每请求重新解析权限、三元组参数、结果分支与固定版本路径；不组合真实的授权写入、搬迁、发布或文件服务进行验证。
- 控制器、参数校验、拦截器和异常处理分别直接调用并 mock 下游依赖；JSON/Swagger 与路由元数据在进程内检查。不启动完整 Spring
  应用、不监听端口、不发真实 HTTP 请求，也不将独立单元测试描述为完整请求链路已验证。
- AI 客户端用内存 `ExchangeFunction` stub 提供响应和捕获请求，模型与 Redis 客户端使用 mock/stub。上下文、Unicode、事件顺序、超时和取消用
  `StepVerifier` 验证，不连接 kb-app、模型服务或 Redis，不建立 SSE 网络连接。
- 保留新的单元级 SQL 保护检查，不再把 `ArchivedDocumentAccessTest` 的数据库执行或既有失败修复纳入本变更任务。现有集成测试不删除、默认构建配置不改，不以绕过旧失败宣称项目全绿。
- 新公开 DTO 和 record 继续遵守 Javadoc、`@Schema`、显式 import 规范；静态模块边界和进程内序列化检查可定向运行，不增加只约束文案措辞的测试。

每组只显式选择已核对的单元测试类或方法，不执行无筛选的全量测试命令；不因环境变量恰好存在而连到外部服务。实施记录只列实际单元测试和静态检查结果，失败或跳过不能记为通过。数据库、文件访问、跨服务集成和部署效果不属于本次完成条件，也不保留为待执行验收项。

**替代方案：** 真实数据库、H2 与跨服务联调能提供更强的执行证据，但超出本次确认的任务范围，因此移除，不用 mock 的通过结果冒充这些验证。

## Risks / Trade-offs

- [旧片段可能无法再次读取] -> 状态明确为无匹配分块，仅在原版本仍可访问时给入口；不承诺快照或逐字复现。
- [现有来源字段精度不足或全部为空] -> 保留字段供人工核对，所有契约和示例明确提示语义；不把这作为新增解析开发的隐性前提。
- [较旧消费方严格拒绝未知字段] -> 兼容保证只覆盖能忽略未知字段的 JSON 客户端；以进程内旧 DTO 和响应夹具检查兼容，不声称已验证实际部署的新旧服务组合。
- [LEFT JOIN 或权限复用时遗漏约束] -> 同时约束三元组，父资源授权放在生产 SQL 中；用 SQL 生成检查与 mock
  分支测试降低风险，实际数据库执行效果仍未验证。
- [权限检查与撤权并发] -> 按既有每请求读取边界处理，后续点击再次鉴权，不声称能收回已发送内容。
- [响应查询增加一次 download 权限检查] -> 仅对已获 view 的原版本执行一次轻量文件可见性查询，不访问对象存储或模型，不建立权限缓存。
- [缩减验证范围留下执行层风险] -> 记录本次仅覆盖单元逻辑与静态检查；不把既有基线失败视为已修复，不把数据库、Redis、文件服务或跨服务效果视为已验收。

## Migration Plan

以下仅为实现所需的兼容与回滚设计说明，不安排实际部署、外部服务启动或发布联调验收任务。

1. 不新增 Flyway 迁移、不修改分块主键、不补跑历史解析。本次交付范围为编码、单元测试、静态检查及接口说明。
2. 建议的升级顺序仍为先 `kb-app` 后 `kb-ai`；旧 `kb-ai` 可忽略新字段，新 `kb-ai` 遇到旧响应明确返回 `UNVERSIONED`
   。本次用各自单元测试中的新旧响应夹具检查，不部署两个服务联调。
3. 客户端按 `provenanceStatus` 决定能否构造版本查询路径，按查询 `status` 决定展示片段或原版本入口。以进程内 JSON/SSE
   字段检查和接口文档说明这些规则，不新增前端查看器或真实文件访问测试。
4. 回滚顺序建议为先 `kb-ai` 后 `kb-app`，无数据回滚。若接口因回滚不存在，调用方只能提示功能不可用，不把 404
   自动解释为片段失效，也不能改用当前版本路径。已经保存的旧无版本引用仍不自动补齐。
