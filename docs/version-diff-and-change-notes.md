# 版本对比与变更说明

首期已实现，2026-11-17 完成主规格同步并[归档](../openspec/changes/archive/2026-11-17-version-diff-and-change-notes/)，未部署。36/36 项任务完成，核验问题已关闭；[验证记录](../openspec/changes/archive/2026-11-17-version-diff-and-change-notes/verification.md)与[核验报告](../openspec/changes/archive/2026-11-17-version-diff-and-change-notes/verify-report.md)仅覆盖约定的离线范围。行为契约见[版本对比主规格](../openspec/specs/document-diff/spec.md)、[变更说明主规格](../openspec/specs/document-change-notes/spec.md)及[对比页主规格](../openspec/specs/user-web-document-diff/spec.md)。

## 版本对比

`GET /api/documents/{documentId}/versions/{versionId}/diff?baseVersionId={id}`，路径版本是目标，查询版本是基准；即使基准更新，也保持请求方向。只允许一个 `baseVersionId`，三个 ID 为正整数且两版本不同。每次请求分别检查两侧的 INTERACTIVE 访问资格，任一不存在、归属不符或不可读统一 404。只需 view，返回解析文本；原件下载仍按 download 授权。

成功响应沿用 `R` 包装。`data` 包含 `documentId`、`base` 与 `target`（`versionId`、`versionNo`、`parserName`、`parserVersion`、`truncated`）、`status`、`missingSides`、`warnings`、`stats`、`operations`、`valueChanges`。操作为 EQUAL、ADDED、REMOVED、MODIFIED、MOVED，包含结构类型、标题路径、两侧块序号、页码、全文；非表格修改提供 EQUAL/INSERT/DELETE 逐字片段，过长则 `segmentsOmitted=true`。表格使用 CELLS 行列差异或 RESTRUCTURED 两侧完整内容。关键数值仅从修改块及修改单元格提取日期、金额、百分比、条款编号，不代表全部变化。

| status | 含义 |
|---|---|
| AVAILABLE | 返回操作、数量与数值变化，内容相同也使用此状态 |
| SNAPSHOT_MISSING | 指定侧没有与当前索引周期一致的快照 |
| TOO_LARGE | 任一侧块数或字符数超限，尚未读取块正文 |
| TIMEOUT | 计算达到截止时间，不返回部分结果 |
| LOW_SIMILARITY | 可对齐内容占比低于阈值 |

这些状态均为 HTTP 200，优先级按表中无法对比状态的顺序判定；非 AVAILABLE 的 stats 为 null，操作和数值列表为空。`PARSER_DIFFERS` 表示解析器名称或版本不同，`TRUNCATED` 标明截断侧，两者不会单独阻止对比。满额立即返回 429 `DIFF_CAPACITY`，无等待队列；依赖、读取超时及计算异常返回 503 `UNAVAILABLE`，不伪装为空结果。成功和错误响应均为 no-store，不缓存、不写读取审计、不记录正文或数值。

配置前缀 `kb.document-diff`，非法值阻止启动；环境变量以 `KB_DOCUMENT_DIFF_` 开头，后接配置键的大写下划线形式。

| 配置键 | 默认值 |
|---|---:|
| max-chars-per-version | 500000 code point |
| max-blocks-per-version | 10000 |
| timeout-ms | 5000（只计计算） |
| max-concurrent | 4（每实例） |
| inline-diff-max-chars | 20000 |
| pairing-max-cells | 250000 |
| min-similarity | 0.3 |

快照与分块由 Worker 原子提交。读取在只读 REPEATABLE READ 事务中完成，Mapper 语句超时 3 秒。计算超时返回后，仍在退出的计算继续占用名额，直到实际退出。

排查 SNAPSHOT_MISSING：先核对 app 已完成 V40、Worker 已升级并通过启动表检查；再核对版本 `index_status` 是否为 indexed；随后核对快照 `task_id` 和 `execution_generation` 是否与该版本当前解析任务一致。旧 Worker 发布、索引刷新尚未完成、回退期间重新处理均可能缺少可用快照。不会从检索分块拼接，也不提供存量回补；需按既有资格与发布限制决定是否重新处理候选版本。

发布顺序为全部 app（含 V40）、Worker、web；回退反向进行并保留新表，不反向迁移。离线测试不证明真实数据库约束、事务锁或触发器已生效，真实对齐效果还需 E-01 解析服务样本验证。

## 人工变更说明

`GET /api/documents/{documentId}/versions/{versionId}/change-note` 按固定版本的 INTERACTIVE 资格返回说明。字段为 documentId、versionId、hasNote、text、baseVersionId、baseVersionNo、corrected、createdAfterVisible、noteVersion、writeBaseVersionId、writeBaseVersionNo、editable、reviewFrozen、visibleToReaders。没有说明行等同版本 0，基准与文本为空；不会推断、继承或自动生成说明。

`PUT` 同一路径，必需字段只有 text、reason、expectedNoteVersion，前两者可为 null。非空值去除首尾空白后为 1–2000 个 Unicode code point；保留换行和制表符，拒绝其他控制字符、NUL、孤立代理项、重复或未知字段、尾随 JSON。请求体上限 32 KiB。必须携带 X-Request-Id，去除首尾空白后为 1–128 个非控制 code point。

| 版本当前事实 | 写入规则 |
|---|---|
| 从未发布、未进入审核 | 当前 view、edit、候选访问资格；可修改或清除，不要求原因 |
| 待审且 IN_REVIEW | REVIEW_IN_PROGRESS，先撤回后再修改 |
| 已对读者可见、当前无说明 | 首次补写免原因，createdAfterVisible 永久记为 true |
| 已对读者可见、已有说明 | 更正原因必填，立即生效且 corrected 永久记为 true；不可清除 |
| 文档删除或归档、空间归档 | 写入 404 |

基准取同文档中版本号小于目标、具有 PUBLISHED 或 OBSERVED_CURRENT 发布证据的最大版本号。待审每次写入重新确定；已可见且已有说明时沿用原基准。没有基准返回 NO_BASE_VERSION。写入待审 V3 时基准为 V1，即使 V2 后来先发布、V3 再获批，也不重标基准。允许更正已被取代的历史版本，其他版本不变。

成功回执包含 documentId、versionId、noteVersion、corrected、createdAfterVisible、updatedAt、replayed。请求标识绑定操作者、文档、目标版本、预期版本和规范化载荷；当前资格重新确认后，匹配重放返回首次回执，不重复追加历史。写入通过版本行锁与送审、审批串行化，说明、历史、审计、回执在 READ_COMMITTED 单事务中提交。

| HTTP | reasonCode |
|---|---|
| 400 | INVALID_INPUT |
| 401 / 403 | UNAUTHORIZED / FORBIDDEN / PASSWORD_CHANGE_REQUIRED |
| 404 | NOT_FOUND（不存在、归属不符或不可访问） |
| 409 | REVIEW_IN_PROGRESS、REASON_REQUIRED、NOTE_REMOVAL_NOT_ALLOWED、NO_BASE_VERSION、REQUEST_MISMATCH、NOTE_VERSION_CONFLICT、NO_CHANGE |
| 413 | PAYLOAD_TOO_LARGE |
| 503 | UNAVAILABLE |

错误只返回固定原因码，不回显说明、原因或底层异常。成功和错误均 no-store。读取失败表示暂不可确认，不能按没有说明处理。更正缺少原因或预期版本冲突后应重新读取，由用户重新编辑提交；网络中断等结果未确认时保留原请求标识和精确载荷，只能原样重试、读取核对或显式放弃，读到新文本不等同于收到写入回执。

详情页“重新核对操作资格”以及说明重读返回旧值、新值或失败，都保留同一身份与固定版本下的未确认请求和编辑内容；关闭编辑后可继续核对。切换身份、文档或版本、隐藏或离开页面会清理草稿，恢复后不自动保存。对比选择器切换目标时读取该目标的说明，优先选取可见的说明基准；没有说明基准则采用最近的较旧已发布版本。目标说明暂不可确认时提示手选，迟到响应不覆盖新目标或用户手选的基准。

`GET .../change-note/history?limit=20&cursor=...` 与单项读取使用相同访问规则，limit 为 1–100，按时间及 ID 倒序分页。游标绑定身份、文档和版本，不延续授权。历史不可修改或删除，读者可以看到待审草稿及发布后更正的前后文本、基准、是否发布后写入和原因；响应不含操作者。操作者只进入既有审计，操作域 DOCUMENT_CHANGE_NOTE_UPDATE、目标 document-version，摘要仅保留前后基准、是否有说明、说明版本、可见标记和是否提供原因。说明和历史不参与搜索、问答、模型上下文或通知。
