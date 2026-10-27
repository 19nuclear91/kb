# 版本化引用溯源

## 引用身份

`POST /api/search` 使用 JSON 请求体传递 `query`、`candidates` 和 `topK`；每个命中通过 `docId + versionId + chunkId` 标识来源。
`versionId` 取自命中分块的所属版本，`versionNo` 为同一版本的展示编号；后续发布新版本不能改变已返回的三元组。

检索仍只返回当前已发布、满足密级及 view 授权且所属空间未归档的分块。版本字段不会使历史版本自动进入检索结果。

| 字段                                            | JSON 类型   | 语义                    |
|-----------------------------------------------|-----------|-----------------------|
| `docId`、`versionId`、`chunkId`                 | 整数        | 文档、版本和分块的固定身份         |
| `versionNo`                                   | 整数        | 版本的展示编号，不用于定位         |
| `docTitle`                                    | 字符串       | 检索时的文档标题，不是历史标题快照     |
| `chunkIdx`                                    | 整数        | 分块序号，不可替代 chunkId 定位  |
| `pageNo`                                      | 整数或 null  | 首个来源块页码，不是整个分块覆盖页范围   |
| `headingPath`、`structureType`、`sourceLocator` | 字符串或 null | 解析器提供的章节、结构和来源提示      |
| `sourceLineStart`、`sourceLineEnd`             | 整数或 null  | 解析器报告的来源行范围           |
| `sourceCharStart`、`sourceCharEnd`             | 整数或 null  | 解析器报告的来源字符范围，不是原件精确坐标 |

来源未知时保持 `null`，不伪造页码 0、标题或定位范围。来源范围可能覆盖整个来源块，不能用于声称 PDF/Office 原件逐字高亮。
`sourceLocator` 仅为数据，不作为可执行定位表达式或下载地址。

新增版本字段不改变既有检索 JSON 字段名称和类型。兼容构造器缺少版本信息时保留 `null`；生产检索从数据库同一次查询中提供版本身份，不用文档当前版本进行事后补齐。

## 指定版本片段查询

`GET /api/documents/{documentId}/versions/{versionId}/citations/{chunkId}`

三个路径参数均为正的 64 位整数。请求仍需现有 Sa-Token 登录身份，强制改密状态不能绕过门禁；不接收客户端声明的用户、空间或密级作为授权依据。

查询按文档当前密级、当前空间、view 授权和未删除状态判断。空间归档、角色/部门变化、撤权、升密或搬迁会影响后续查询。历史版本不必是当前版本；文件读取与检索的发布状态规则不混用。

授权结果使用 HTTP 200、`R` 成功封装以及 `Cache-Control: no-store`。以下展示封装内的 `data`：

```json
{
  "status": "AVAILABLE",
  "docId": 10,
  "docTitle": "说明",
  "versionId": 20,
  "versionNo": 1,
  "chunkId": 30,
  "chunk": {
    "chunkIdx": 0,
    "content": "完整存储正文",
    "pageNo": null,
    "headingPath": null,
    "structureType": null,
    "sourceLineStart": null,
    "sourceLineEnd": null,
    "sourceCharStart": null,
    "sourceCharEnd": null,
    "sourceLocator": null
  },
  "previewPath": "/api/documents/10/versions/20/preview",
  "downloadPath": null
}
```

没有匹配分块时仍须先通过原版本 view 检查，然后返回：

```json
{
  "status": "CHUNK_UNAVAILABLE",
  "docId": 10,
  "docTitle": "说明",
  "versionId": 20,
  "versionNo": 1,
  "chunkId": 30,
  "chunk": null,
  "previewPath": "/api/documents/10/versions/20/preview",
  "downloadPath": null
}
```

此状态只表示当前没有匹配片段，不证明这个 ID 曾属于该版本，也不证明永久删除。其他文档或版本的 chunkId 与不存在的 ID
一样不会返回其实际归属。同版本重解析更换 ID 后，不按相同 chunkIdx 猜测继任分块；发布新版本也不会把旧引用改指向最新版本。

| HTTP 状态 | 语义                                                   |
|---------|------------------------------------------------------|
| 200     | 原版本可查看，`data.status` 为 AVAILABLE 或 CHUNK_UNAVAILABLE |
| 400     | 路径 ID 格式、范围或正整数约束不合法                                 |
| 401     | 登录身份缺失或失效                                            |
| 403     | 当前会话必须先完成密码修改                                        |
| 404     | 文档/版本不存在、已删除、归属不符或当前无权查看，统一隐藏存在性                     |

失败沿用 `R` 的 `code=500`、`data=null`，与 HTTP 状态区分；不得将 404 当成可安全展示原版本入口的片段失效状态。

## 原版本文件入口

- `previewPath` 始终固定原版本，但不保证该格式支持预览；既有预览接口仍可能返回 415。
- `downloadPath` 仅在独立 download 检查通过时返回，否则为 `null`。view、download、manage 不互相替代。
- 两个入口再次访问时均重新鉴权，拿到路径不形成持续授权。路径不含存储键、签名直链或令牌。
- 查询返回完整存储分块，不保存回答时的 prompt 或历史标题快照；不会触发重解析、Embedding 或模型生成。

## AI 引用与 SSE

`POST /api/ai/chat` 使用 JSON 请求体 `{"question":"问题"}`，响应为 `text/event-stream`。保持原有 `citation`、`delta`、
`done`、`error` 四种事件，不使用 `R` 包装。当前编排先发送预算内的 citation，再发送占位或模型增量；正常结束发 done，失败发
error 后结束，客户端取消则停止处理，不补发终止事件。

以下为示意事件帧，usage 为示例，不代表真实模型验收结果：

```text
event: citation
data: {"docId":10,"docTitle":"说明","pageNo":2,"chunkId":30,"versionId":20,"versionNo":1,"chunkIdx":0,"headingPath":"权限","structureType":"PARAGRAPH","sourceLineStart":10,"sourceLineEnd":18,"sourceCharStart":120,"sourceCharEnd":340,"sourceLocator":"section:access","provenanceStatus":"VERSIONED","contextTruncated":true}

event: delta
data: {"text":"回答增量"}

event: done
data: {"promptTokens":37,"completionTokens":11,"elapsedMs":120}

```

`citation` 保留原来的 `docId`、`docTitle`、`pageNo`、`chunkId` 及其类型，新增版本、分块序号和来源字段；不包含分块正文、原始存储地址或调用者令牌。正文仅在
AI 内部参考列表传递。能够忽略未知字段的旧 DTO 仍可读取原字段；严格拒绝新增字段的消费方不在此兼容保证内。

| 字段或状态                          | 语义                                   |
|--------------------------------|--------------------------------------|
| `versionId`                    | 64 位整数或 null；不是当前版本的动态指针             |
| `versionNo`、`chunkIdx`         | 整数或 null，仅展示，不替代固定身份                 |
| `provenanceStatus=VERSIONED`   | 具备合法的文档、版本和分块三元组；不保证再次查询时原片段仍存在或仍有权限 |
| `provenanceStatus=UNVERSIONED` | 旧上游没有版本身份，仅可作普通参考，不可构造版本级查询路径        |
| `contextTruncated`             | 布尔值，仅表示本轮预算是否裁剪过该片段正文                |

旧上游缺少或显式返回 null 的 `versionId` 时，新 AI 不补 0、不查询最新版本补身份，仍可发出普通引用。例如缺少版本和来源的旧响应转换为：

```text
event: citation
data: {"docId":10,"docTitle":"旧来源","pageNo":null,"chunkId":30,"versionId":null,"versionNo":null,"chunkIdx":null,"headingPath":null,"structureType":null,"sourceLineStart":null,"sourceLineEnd":null,"sourceCharStart":null,"sourceCharEnd":null,"sourceLocator":null,"provenanceStatus":"UNVERSIONED","contextTruncated":false}

```

已保存的旧事件即使没有 `provenanceStatus`，消费方也必须按缺少版本身份处理，不能用当前版本补齐。只有合法 `versionId` 而缺
`versionNo` 时仍为 VERSIONED。明确损坏的身份值（非整数类型、非正数、溢出），或缺少合法 docId/chunkId
的条目会逐条丢弃，不影响同一响应中的正常条目。缺失来源保持 null，不变成字符串 `"null"`。

## 上下文裁剪边界

- 先按 Unicode code point 总预算筛选参考列表，再用同一列表生成 citation 并传给模型适配器；不会切断代理对。
- 完整保留的片段 `contextTruncated=false`，只保留前缀的片段为 true，预算完全排除的片段不发送引用。
- 裁剪只改内部正文和裁剪标记，版本、页码、章节及来源范围不变；范围仍属于完整原分块，不按前缀长度重新计算。
- 空正文不消耗预算，未被预算排除时仍保留身份；null 正文在预算处理时按空串处理，不伪造来源或裁剪标记。
- 查询裁剪过的引用返回完整存储正文，而不是当轮 prompt 快照；查询响应没有与特定会话相关的裁剪标记。
- citation 只表示本轮参考材料，不证明模型实际采用、答案逐句受到支持或生成已经成功。使用占位应答或模型随后失败时同样如此。

usage 和配额流程不因增加引用字段而改变：模型最终累计 usage 进入 done 并用于结算；占位应答不虚构 token
消耗；失败、超时或取消沿用既有释放逻辑。检索仍只透传调用者身份，失败按无结果降级，不增加服务账号或直连数据库能力。

## 验证范围

本变更只运行显式选择的进程内单元测试与静态检查。Mapper 测试使用生产映射和 mock JDBC 结果集，SQL 只检查生成结构及参数，不执行查询。外部调用用
mock/stub 替换，不启动数据库、Redis、MinIO、模型服务或 HTTP 监听端口。

单元测试通过不表示数据库执行、文件访问、跨服务集成或部署效果已经验证。既有集成测试和默认构建配置不因本变更被删除或绕过。

## 验证记录（2026-10-08）

最终定向回归在 JDK 21、Maven 离线模式下运行。以下名单与实际执行的 `-Dtest` 参数一致，不包含数据库执行或 HTTP 监听测试：

```powershell
$tests = @(
    'JavaSourceConventionTest',
    'SearchCitationMappingTest', 'SearchProfileSqlTest', 'SearchServiceTest', 'LocalRerankClientTest',
    'CitationSchemaTest', 'DocumentCitationMapperTest', 'DocumentCitationServiceTest',
    'DocumentCitationControllerTest', 'DocumentCitationAuthorizationTest', 'DocumentCitationLifecycleTest',
    'SaTokenConfigTest', 'GlobalExceptionHandlerTest', 'ModuleBoundaryTest',
    'RetrievalClientTest', 'ChatEventCitationTest', 'ChatServiceAdapterTest', 'ChatServiceLifecycleTest',
    'ChatControllerTest', 'ChatQuotaAuditServiceTest', 'RedisChatAuditSinkTest',
    'NoDatabaseAccessTest', 'ReactiveStackTest'
)
mvn -o -pl kb-app,kb-ai -am test "-Dtest=$($tests -join ',')" '-Dsurefire.failIfNoSpecifiedTests=false'
openspec validate versioned-citation-provenance --strict
```

| 模块        | 执行数 | 失败 / 错误 / 跳过 |
|-----------|-----|--------------|
| kb-common | 1   | 0 / 0 / 0    |
| kb-app    | 57  | 0 / 0 / 0    |
| kb-ai     | 86  | 0 / 0 / 0    |
| 合计        | 144 | 0 / 0 / 0    |

- `JavaSourceConventionTest` 检查各模块源码的显式 import、record Javadoc 和 Schema 组件说明；模块边界、AI
  无数据库依赖和响应式类路径检查通过。
- 生产 Mapper 映射、生成 SQL、mock 权限/生命周期分支、独立控制器调用与 JSON/Swagger 字段检查通过。没有建立数据库连接，也没有执行
  SQL。
- AI 的内存响应 stub、版本降级、损坏身份过滤、Unicode 裁剪、SSE 事件顺序和终止、usage 与 mock Redis
  租约结算检查通过。异常和超时日志来自测试主动注入的夹具。
- OpenSpec 严格校验通过。以上统计只计最终一轮，不累加实施期间重复执行的测试。
- 未运行全量测试、`ArchivedDocumentAccessTest` 或 `QuotaPrincipalClientIntegrationTest`；既有基线失败不视为已修复。未运行
  H2、PostgreSQL/ParadeDB、Redis、MinIO、真实模型、完整应用或 SSE 网络联调。
