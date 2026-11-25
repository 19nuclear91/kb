# 企业术语词典管理契约

## 交付状态

2026-11-24 已实现 12 个管理端点、严格输入、目录／详情／共享含义分页、独立写服务、首次回执、安全审计、网关保护及管理页面；增强入口和知识搜索页已接入。app 的路径级 no-store 在路由、认证及正文解码前生效，包含提前拒绝、未知路径和错误派发。**尚未部署或进行 HTTP 联调**。

本次只验证独立单测、Mock／内存替身及无连接映射，迁移未执行。真实数据库约束、锁、快照隔离、提交、回滚和网络效果未验证；不以离线测试代替这些事实。

2026-11-24 已同步主规格并[归档](../openspec/changes/archive/2026-11-24-search-correction-and-enterprise-terminology/archive-record.md)。[归档核验报告](../openspec/changes/archive/2026-11-24-search-correction-and-enterprise-terminology/verify-report.md)记录的 W4 已于 2026-11-25 修复：前端可接受明确替换与另一处合法扩展混合的完整响应。见[修复验证](search-terminology-w4-fix.md)；管理输入、回执及本文契约未变。

## 身份与输入边界

增强查询的完整基础字段与双版本协议另见[搜索筛选 API](search-filtering-api.md)，执行预算见[检索限界](search-resilience.md)。

- 管理路径统一位于 `/api/admin/search-terms`。读取要求当前精确 `search:term:read`，创建、编辑、启停及本人回执核对要求当前精确 `search:term:write`，互不蕴含。角色名称、标签权限、空间 manage 和审计权限不能替代。
- 沿用有效会话、主体／主部门启用及必要改密门禁。客户端不提交操作者、权限或内部比较键；公开搜索提示不能用于管理授权。
- 写入要求 `X-Request-Id`，按首尾 Unicode 空白修剪后的 1～128 个 code point 校验，不接受控制字符或非法代理项。标识在整个词典域唯一，不能通过更换维护操作避开绑定冲突。
- 名称、别名、错误表达均修剪 Unicode 首尾空白并做 NFC，长度 1～64 个 code point；释义 1～256。控制字符、非法代理项、内部重复关系均拒绝；内部空格保留，展示大小写保留。比较键仅折叠 ASCII A～Z。
- 每组 0～20 个别名，类型仅为 `SYNONYM`、`ABBREVIATION`、`FORMER_NAME`。同组别名不能与标准名称或其他别名规范等价；不同组共享别名合法，也允许某组别名等于另一组标准名称。
- 所有管理输入严格拒绝未知／重复字段、尾随 JSON、错误类型、非法 ID／版本。写入字段及查询的 enabled、limit、cursor 不接受显式 null；nameQuery 省略、null 或规范化空白表示不限。实际正文限额 32 KiB，不依赖 Content-Length；超过限额只读取至第 32769 字节即拒绝，不预先加载完整大正文。
- 所有路径拒绝任何 URL 查询字符串，条件只能放在 POST JSON 正文。正文端点要求 `Content-Type: application/json`；成功和失败均为 `Cache-Control: no-store`。
- 正文 ID 与 expectedVersion 为精确 JSON 整数，不接受数字字符串、小数或浮点舍入；ID 为 1～Long.MAX_VALUE，版本为 0～Long.MAX_VALUE。路径 `{id}` 使用正十进制数字，不接受符号、空白、前导零或溢出。所有返回身份、版本和修订均为十进制字符串；客户端不能先转为 JavaScript Number 再提交。

## 完整管理端点

相对根路径 `/api/admin/search-terms`；除两个 GET 详情外均接收 JSON 正文。读取和写入资格精确且独立。

| 方法及相对路径 | 当前资格 | 输入／响应 |
| --- | --- | --- |
| POST `/terms/query` | read | 名称、状态、limit、cursor → 当前术语页 |
| GET `/terms/{id}` | read | 当前完整组及修订 |
| POST `/terms` | write | 完整内容、X-Request-Id → 首次回执 |
| PUT `/terms/{id}` | write | 完整内容、expectedVersion、X-Request-Id → 首次回执 |
| PATCH `/terms/{id}/enabled` | write | enabled、expectedVersion、X-Request-Id → 首次回执 |
| POST `/terms/{id}/meanings/query` | read | expression、limit、cursor → 当前启用含义页 |
| POST `/corrections/query` | read | 名称、映射自身状态、limit、cursor → 当前错词页 |
| GET `/corrections/{id}` | read | 当前映射、最小目标及修订 |
| POST `/corrections` | write | wrongExpression、termId、X-Request-Id → 首次回执 |
| PUT `/corrections/{id}` | write | wrongExpression、termId、expectedVersion、X-Request-Id → 首次回执 |
| PATCH `/corrections/{id}/enabled` | write | enabled、expectedVersion、X-Request-Id → 首次回执 |
| POST `/commands/query` | write | requestId → 本人首次回执，不执行维护 |

## 当前目录、详情与含义

### 目录查询和签名续页

`POST /api/admin/search-terms/terms/query` 示例：

```json
{ "nameQuery": "OA", "enabled": true, "limit": 20 }
```

- `{}` 合法：不限名称或状态，limit 默认 20；limit 为 1～100 的整数。nameQuery 先修剪、NFC，再仅折叠 ASCII 大小写，最多 64 个 code point；按标准名称或别名字面包含匹配，`%`、`_` 与反斜杠不是通配符。错词目录仅匹配错误表达。
- enabled 省略为不限；true／false 只筛选该目录实体自身状态。错词目录的 enabled=true 不代表目标启用或建议当前可用。
- 目录按稳定 ID 升序，以 limit+1 判断后续页，不返回全局数量、文档关联、搜索历史或命中数。行、关系、目标、歧义标志及 dictionaryRevision 来自同一个短 REPEATABLE READ 快照，不跨请求缓存。
- nextCursor 仅当 hasMore=true 时存在，否则为 null。续页携带原已提交 nameQuery、enabled、limit 及该 cursor；不要用未提交草稿，也不要改页大小。更改条件、目录或页大小须重新从首项查询。
- 游标至多 4096 字符，用途隔离签名绑定当前用户、目录、条件、limit、修订和最后 ID；含义查询另绑定目标术语。伪造或换条件为 400，受信游标修订已变化为 409 DICTIONARY_CHANGED，不拼接新旧页。
- 空词典或真正末页为成功空 items、hasMore=false、nextCursor=null。密钥、修订或查询不可用为 503，不能显示成空目录。第一页也要求签名密钥可用；详情读取不需要分页密钥。

术语页的完整 R 包装示例（合成数据，不是线上内容）：

```json
{
  "code": 200,
  "message": "ok",
  "data": {
    "items": [{
      "termId": "9007199254740993",
      "canonicalName": "办公自动化",
      "description": "企业办公流程与系统的检索用语",
      "canonicalAmbiguous": false,
      "aliases": [{ "text": "OA", "kind": "ABBREVIATION", "ambiguous": false }],
      "enabled": true,
      "version": "3",
      "createdAt": "2026-11-24T00:00:00Z",
      "updatedAt": "2026-11-24T01:00:00Z"
    }],
    "hasMore": false,
    "nextCursor": null,
    "dictionaryRevision": "12"
  }
}
```

canonicalAmbiguous 与每个别名的 ambiguous 表示该表达目前是否关联至少两个启用组，不从当前返回页推断，也不因标准名称身份优先选择。停用组不贡献歧义，但其现存名称和别名仍参与维护冲突检查。列表不嵌入全部关联含义，按需使用下面的独立分页查询。

### 当前详情

`GET /api/admin/search-terms/terms/9007199254740993` 返回 R 包装，data 为 `{ "term": <上述完整术语项>, "dictionaryRevision": "12" }`。完整当前组最多包含 20 个别名，含当前启停及版本；授权后目标缺失为 404，损坏关系或读取失败为 503，不截断损坏事实作成功。

`GET /api/admin/search-terms/corrections/{id}` 的 data 为 `{ "correction": <当前错词项>, "dictionaryRevision": "12" }`。`POST /api/admin/search-terms/corrections/query` 使用同样的 Page 结构，单项例子：

```json
{
  "correctionId": "9007199254740994",
  "wrongExpression": "报消",
  "target": {
    "termId": "9",
    "canonicalName": "报销",
    "description": "费用报销的检索用语",
    "enabled": false,
    "version": "4"
  },
  "enabled": true,
  "available": false,
  "version": "1",
  "createdAt": "2026-11-24T00:00:00Z",
  "updatedAt": "2026-11-24T01:00:00Z"
}
```

enabled 是映射自身状态，target.enabled 是目标状态，available 为两者合取；目标停用不意味着映射被关闭或删除。目标最小投影不包含别名清单或文档事实。

### 表达共享含义

`POST /api/admin/search-terms/terms/9/meanings/query` 示例：

```json
{ "expression": "PM", "limit": 20 }
```

expression 必填、非空，使用相同的表达规范化与比较键。先核对当前读取资格，再确认表达是目标组现存标准名称或别名；不属于目标为 400，目标不存在为 404。目标可以停用，但返回的含义仅来自当前启用组。

data 使用相同 Page 包装，每个 items 项只含 termId、canonicalName、description、version，例如 `{ "termId": "9", "canonicalName": "项目管理", "description": "项目执行与协调", "version": "2" }`。每个组只返回一次，标准名称和他组别名碰撞同样参与。limit 默认 20、最大 100，续页绑定目标与规范化表达；不要把任一页当作全部共享含义。无资格时不枚举目标、释义或关联组。

**以上均为当前读值，不是原命令回执。** 当前版本与原首次成功版本可能不同；即使当前字段与原草稿相同，也不能据此结清未知写结果。

## 术语组写入

### 创建

`POST /api/admin/search-terms/terms`，`X-Request-Id` 示例 `term-create-001`：

```json
{
  "canonicalName": "办公自动化",
  "description": "企业办公流程与系统的检索用语",
  "aliases": [
    { "text": "OA", "kind": "ABBREVIATION" },
    { "text": "旧办公系统", "kind": "FORMER_NAME" }
  ]
}
```

创建初始 enabled=true、version=0，产生新的稳定 termId。标准名称键全局唯一，停用组仍占用；错词已占用的键不能成为名称或别名。创建不接受 expectedVersion、enabled 或内部 key 字段。

### 完整内容编辑

`PUT /api/admin/search-terms/terms/{id}`：正文与创建相同，另必须提供数值 `expectedVersion`。aliases 为完整新集合，不是增量补丁；空数组移除全部别名。保留稳定 ID、创建时间和现有 enabled，实体 version 与词典修订各推进一次。别名顺序不构成新意图或实际变化，规范化后的展示大小写变化可以构成修改。

### 独立启停

`PATCH /api/admin/search-terms/terms/{id}/enabled`：

```json
{ "enabled": false, "expectedVersion": 3 }
```

启停不隐式改名称、释义或别名。停用使该组名称、别名及指向它的纠错建议退出后续辅助，保留纠错映射自身 enabled；不修改文档、授权或既有页面文本。标准名称与别名仍占用现存关系，移除或改名才释放已经不存在的关系。

## 人工错词写入

`POST /api/admin/search-terms/corrections`，目标必须来自已确认的稳定术语身份：

```json
{ "wrongExpression": "报消", "termId": 9 }
```

创建初始 enabled=true、version=0。错误表达键全局唯一，停用映射也占用；不得等于任一现存名称或别名，包括停用组。正确表达取目标当前标准名称，不保存一份正确名称副本，不指向另一错词，不加入反向同义集合。

`PUT /api/admin/search-terms/corrections/{id}` 以完整 wrongExpression、termId 及 expectedVersion 编辑。保留自身 enabled。创建、内容编辑及重新启用都要求目标存在且启用；目标改名后仍引用原 termId。

`PATCH /api/admin/search-terms/corrections/{id}/enabled` 接受与术语启停相同的正文。独立停用不重分配目标，因此目标已停用时仍允许关闭映射。实际建议可用性为“映射自身 enabled && 目标组 enabled”；目标恢复后，已关闭映射不会自动开启。

## 首次回执、匹配重放与未知结果

写服务返回 `TermReceipt`，控制器通过 R 成功包装交付，例如：

```json
{
  "code": 200,
  "message": "ok",
  "data": {
    "kind": "TERM",
    "id": "9007199254740993",
    "version": "0",
    "dictionaryRevision": "1",
    "replayed": false
  }
}
```

- kind 仅为 `TERM` 或 `CORRECTION`。回执记录首次成功的身份、实体版本和词典修订，不是当前实体读值，也不含词条文本、释义、别名、输入摘要或其他操作者事实。
- 同一请求标识绑定操作者、精确操作、目标种类／ID、原 expectedVersion 和完整规范化载荷。匹配重试保留原标识和原意图；别名换序等价，展示变化、换目标、换操作者或换操作不等价。
- 匹配重放仍核对当前资格及锁后操作者，返回首次回执并标记 replayed=true。不读取当前实体来比较已经推进的版本，不再次递增修订、实体版本或记录成功审计。
- `POST /api/admin/search-terms/commands/query` 接受 `{ "requestId": "term-create-001" }`。当前写资格者只核对本人原命令，返回存储的首次回执，replayed=false；这不是重新执行维护。
- 响应丢失时可由用户主动核对或匹配重试，不自动重发。核对 404 只说明此次未取得可回显的本人回执，**不证明原操作失败**；读值与草稿相同也不证明原命令成功。
- 首次成功与随后列表／详情刷新失败必须分别呈现。新身份或撤权后不能沿用旧回执延续资格。

## 固定冲突与故障类别

管理控制器及路由前全局异常入口只交付固定失败。HTTP 状态表达类别，code 恒为 500，data 显式为 null，reasonCode 来自固定类别；客户端映射固定 reasonCode，不展示远程 message 或原始异常。例：

```json
{
  "code": 500,
  "message": "词典已变化，请重新查询",
  "data": null,
  "reasonCode": "DICTIONARY_CHANGED"
}
```

| reasonCode | 状态 | 含义 |
| --- | --- | --- |
| INVALID_INPUT | 400 | 严格输入或规范化关系非法 |
| UNAUTHORIZED | 401 | 会话缺失、失效或已撤销 |
| PAYLOAD_TOO_LARGE | 413 | 实际正文超出 32 KiB |
| FORBIDDEN | 403 | 当前独立资格或身份门禁拒绝 |
| NOT_FOUND | 404 | 授权后目标缺失，或没有可回显的本人回执 |
| NAME_CONFLICT | 409 | 标准名称已由其他稳定组占用 |
| EXPRESSION_CONFLICT | 409 | 错词键重复，或错词与现存名称／别名冲突 |
| TARGET_UNAVAILABLE | 409 | 内容写入／重新启用的目标不存在或停用 |
| VERSION_CONFLICT | 409 | 原前置版本与当前实体不符 |
| REQUEST_MISMATCH | 409 | 同标识绑定不同或绑定事实损坏／不完整 |
| NO_CHANGE | 409 | 规范化后没有实际变化 |
| DICTIONARY_CHANGED | 409 | 受信续页上下文的词典修订已变化 |
| STATE_CONFLICT | 409 | 未细分的安全状态冲突 |
| METHOD_NOT_ALLOWED | 405 | 不支持的方法 |
| UNSUPPORTED_MEDIA_TYPE | 406／415 | 响应协商或正文媒体类型不支持 |
| UNAVAILABLE | 503 | 连接、持久化、修订／实体版本溢出或损坏回执等不可用 |
| INTERNAL_ERROR | 500 | 未知程序错误，不回显异常链 |

认证缺失保持 401，身份或资格门禁拒绝为 403，未知程序错误保持 500。依赖故障不改成成功空词典，版本溢出不回零。no-store 同时保护成功、认证拒绝、输入失败、未知管理路径及错误派发；服务端日志不输出词条、名称查询、回执输入或原始异常链。网关／前置代理的实际链路效果未验证，尚不能据此声明端到端保密。

## 事务、修订与审计

一次首次成功维护依次完成：当前写资格 → 请求标识事务锁 → 既有回执绑定 → 单例修订行锁 → 清理 Mapper 一级缓存并重核资格 → 目标／冲突／版本校验 → 实体与关系 → 修订推进 → 最小回执 → 一条持久成功审计。匹配重放在锁后重核后返回首次回执，不写实体或审计。

词典管理使用 5 秒短事务和明确 JDBC／statement／lock 等待限额，写事务使用 READ COMMITTED；所有维护统一锁修订行，跨表冲突不由并发独立写入口绕过。实体、修订、回执和审计由同一业务事务承载，任一失败直接传播；本次事务代理替身只核对顺序和失败分支，**真实锁、提交原子性与回滚未验证**。

审计使用 SEARCH_TERM_CREATE／UPDATE（search-term）和 SEARCH_CORRECTION_CREATE／UPDATE（search-correction），启停归入 UPDATE。只保存稳定身份、前后目标 ID／enabled／version／dictionaryRevision 和固定 changedFields，不保存正文或建议。审计查询仍要求 audit:read，保留未知但合法操作域／目标类型的精确查询兼容。普通搜索、建议采用和管理读取不进入维护审计；编译后依赖规则限制只有两种人工写服务能够调用词典审计生产者。
