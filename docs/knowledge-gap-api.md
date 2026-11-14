# 知识问题单 API

## 交付范围

问题读写、最小候选、固定资料投影、双来源通知、页面、网关及配置已接入，离线验证已完成。示例仅说明合同，不执行文档内容断言。验证仅为离线业务/事务代理、仓储/transport 替身、模拟 DOM、代码/配置检查及构建，不代表迁移、数据库锁/回滚或部署已验证；发布与兼容回退见 [协调发布清单](knowledge-gap-rollout.md)。

2026-11-14 已同步七份主规格并完成[变更归档](../openspec/changes/archive/2026-11-14-knowledge-gap-feedback-loop/)。行为合同以[知识问题主规格](../openspec/specs/knowledge-gaps/spec.md)及相关权限、页面、通知主规格为准；实际命令与离线结果保留在[归档验证记录](../openspec/changes/archive/2026-11-14-knowledge-gap-feedback-loop/verification.md)，归档不代表上线验收。

## 身份、分享与请求绑定

- 操作者和提问者归属只取当前有效会话；停用、主部门无效、强制改密时拒绝。只分享用户明确确认的描述，不接受原答案、会话/轮次/搜索 ID、引用正文或客户端归属。
- 当前问题可读范围为原提问者、仍有 `knowledge:gap:handle` 的当前负责人及独立 `knowledge:gap:read` 受理人员。分派和关闭另需 `knowledge:gap:assign` / `knowledge:gap:close`，写资格不授予全局读取。
- 修改请求必须有一个 `X-Request-Id`，长度 1–128 个非控制 Unicode code point。绑定当前人、动作、目标、预期版本及规范化载荷。非创建请求使用非负整数 `expectedVersion`；方案确认/拒绝额外绑定当前 `solutionId`。
- 除后续列表和历史合同明确允许的参数外，不接受 URL 查询参数。正文至多 64 KiB，拒绝重复/未知字段、尾随 JSON、非整数身份/版本、损坏 Unicode 和 NUL。文本去首尾空白后计 code point，不截断。
- 修改在 5 秒短事务内完成，Mapper 语句 3 秒、锁等待 1 秒；统一命令绑定 → 创建额度锁（仅 create）→ 问题锁。状态、共享 entry、无正文 event、固定收件人意图、回执一起提交。源事务不等待模型、上传或通知派生。

## 写入口

下表均为 POST，正文类型 `application/json`，成功使用 `R<T>`。所有响应与提前拒绝均应为 `Cache-Control: no-store`。

| 路径 | 正文字段 | 当前资格/状态 |
| --- | --- | --- |
| `/api/knowledge-gaps` | category、description、source、shareAcknowledged | 有效用户明确分享；本人非终态问题少于 100 |
| `/api/admin/knowledge-gaps/{gapId}/assign` | expectedVersion、assigneeUserId | assign + 当前访问；任一非终态；重新核验合格负责人 |
| `/api/knowledge-gaps/{gapId}/notes` | expectedVersion、text | 提问者、当前合格负责人或 assign + 当前访问；非终态 |
| `/api/knowledge-gaps/{gapId}/return` | expectedVersion、reason | 当前合格负责人；处理中/待确认 |
| `/api/knowledge-gaps/{gapId}/solutions` | expectedVersion、text、可选 documentId/versionId | 当前合格负责人；处理中 |
| `/api/knowledge-gaps/{gapId}/confirm` | expectedVersion、solutionId | 仅原提问者；当前待确认方案 |
| `/api/knowledge-gaps/{gapId}/reject-solution` | expectedVersion、solutionId、reason | 仅原提问者；当前待确认方案 |
| `/api/knowledge-gaps/{gapId}/withdraw` | expectedVersion、reason | 仅原提问者；任一非终态 |
| `/api/admin/knowledge-gaps/{gapId}/close` | expectedVersion、reason | close + 当前访问；任一非终态 |

category 为 `MISSING_KNOWLEDGE`、`INACCURATE`、`OUTDATED` 或 `OTHER`；source 为 `MANUAL`、`SEARCH` 或 `CONVERSATION`，只代表提交入口，不证明故障原因。description 和方案 text 长度 1–4000；补充 text 和所有 reason 长度 1–2000。`shareAcknowledged` 必须为 true，服务端保存同意版本 `GAP_SHARE_V1`。

方案可纯说明；资料型必须同时提供正整数 documentId/versionId，固定已发布版本属于该文档，提交时处理人与有效提问者当前均可读。确认再次核验提问者对原版本的访问，不替换最新版，不自动改为说明型；资料失效仍可明确拒绝或撤回。

## 状态与保留

`SUBMITTED` → 人工分派 `IN_PROGRESS` → 提交方案 `AWAITING_CONFIRMATION` → 本人明确确认 `RESOLVED`。本人拒绝回到 `IN_PROGRESS`；负责人退回受理为 `SUBMITTED` 并清空负责人/方案。改派更新分派代次并使旧方案失效；补充说明只递增版本，不改变当前工作身份。

本人撤回为 `WITHDRAWN`；合格受理人员带原因关闭为 `CLOSED`，即使提问者已停用也不伪造解决确认。三类终态不重开，不接受新说明、改派或方案。已分享描述/说明/方案/原因保留，**撤回停止处理，不等于删除**。不存在正文编辑或删除入口。

## 回执与未知结果

创建首次 HTTP 201，其余新动作 HTTP 200；匹配重放 HTTP 200。`data` 固定为最小回执：

```json
{"gapId":11,"resultVersion":2,"state":"AWAITING_CONFIRMATION","eventId":"ab60bf9a-8bae-4c38-b29b-e2f1a9afc9c5","solutionId":"e4eb15dd-7a1c-4d12-91bf-5a1957d14b51","replayed":false}
```

非方案动作 solutionId 为 null；方案提交/确认/拒绝携带对应不可变方案身份。回执不含问题描述、原因、资料身份或当前详情；整数身份和版本必须精确保留。

1. 响应丢失不等于提交失败。相同身份、动作、目标及未改变的规范化载荷，显式重试复用同一请求标识，返回首次结果且不重复事件/通知意图。
2. 变更内容、目标、版本或方案身份必须使用新的请求标识并重新确认，不用旧标识覆盖业务。
3. 409 后先重新读取当前事实，不能自动覆盖或确认新方案。
4. 匹配重放仍重新鉴权。改派/退回使旧负责人失去访问时，原标识核对也返回 404；清理旧内容，由当前受理人员核对，不扩权恢复旧回执。
5. 刷新、换号和恢复不自动重发；通知已读不代表处理完成。创建匹配重放不再次占额度。

## 错误类别

| HTTP | 含义 |
| --- | --- |
| 400 | 严格输入、参数、游标或请求标识无效 |
| 401 | 会话无效或过期 |
| 403 | 当前主体/功能资格或动作身份拒绝 |
| 404 | 问题不存在或当前不可访问 |
| 409 | 状态、版本、方案、候选、容量或请求绑定冲突 |
| 413 | 正文超过 64 KiB |
| 503 | 当前权限或事实依赖故障、数据库超时 |

错误封装只使用固定类别，`data=null`，不回显正文、人员名称、SQL 或底层异常。未注册的问题路径、编辑/删除/重开方法或错误媒体类型按严格输入拒绝，不回退静态 HTML；其他既有 API 的 405/415 行为不变。

## 读取与分页

| GET 路径 | 查询参数 | 当前资格 |
| --- | --- | --- |
| `/api/knowledge-gaps` | view（mine/assigned，默认 mine）、status、limit、cursor | 有效用户；assigned 另需 handle，且只返回当前分派 |
| `/api/admin/knowledge-gaps` | status、limit、cursor | 独立 read |
| `/api/knowledge-gaps/{gapId}` | 无 | 当前问题可见 |
| `/api/knowledge-gaps/{gapId}/history` | limit、cursor | 当前问题可见 |

status 只能是六种精确状态之一；不提供 OPEN 等聚合别名。limit 默认 20、最大 100。重复/未知参数、非法 gapId、损坏或跨当前人/视图/条件/问题的游标为 400。列表固定按问题创建位置倒序，历史按单内不可变结果版本正序，包含创建位置 0；输入游标不代表访问资格。

分页结构固定 `items`、`hasMore`、`nextCursor`，末页游标为 null；无 total 或隐藏占位。列表项只含 gapId、category、source、state、version、reporter、assignee、needsReassignment、createdAt、updatedAt；参与人仅为 userId/label，标签缺失可为 null，不补查完整身份目录。失效负责人对受理人员只给 needsReassignment，不暴露停用、部门或角色原因。

详情增加 description、consentVersion、capabilities、currentSolution，不嵌完整历史。capabilities 为当次 `assign`、`note`、`returnToReception`、`submitSolution`、`confirm`、`rejectSolution`、`withdraw`、`close`，只作交互提示，写入仍重新核验。currentSolution 是当前方案 entry，entryId 即供确认/拒绝使用的 solutionId；无待确认方案为 null。

历史项含 eventId、kind、resultVersion、actor、assignee、occurredAt、solutionId、entry。无文本的动作 entry 为 null；entry 含 entryId、position、kind、author、text、referenceState、reference、createdAt。历史按当前问题范围分享，没有隐藏内部评论。

## 最小候选 POST

只读候选不要求 X-Request-Id，不创建命令、事实或通知；正文固定 `nameQuery`、`limit`、`cursor`。名称最多 128 个 code point，去首尾空白，空名称不筛选；分页同上。拒绝 URL 查询名称，不回退全局人员/文档目录。

| 路径 | 当前资格 | items 字段 |
| --- | --- | --- |
| `/api/admin/knowledge-gaps/{gapId}/assignees/search` | assign + 当前问题可见 | userId、label |
| `/api/knowledge-gaps/{gapId}/solution-documents` | 当前负责人 + handle | documentId、versionId、title |

处理人候选须启用、主部门有效、完成必需改密且显式有 handle；实际分派再次核验。资料候选只筛当前处理人自身可读的当前已发布版本，不暴露提问者 ACL；提交方案时才分别核验双方。

## 资料投影

当前方案与每个历史 entry 的资料都按当前查看者独立校验，不复制标题/正文快照，不替换为最新版，不自动下载。无关联 `referenceState=NONE`、`reference=null`；可读为 AVAILABLE，reference 只有 documentId/versionId/title。

撤权、归档、删除或不可读时只保留固定不可用状态，例如：

```json
{"entryId":"e4eb15dd-7a1c-4d12-91bf-5a1957d14b51","position":2,"kind":"SOLUTION","author":{"userId":8,"label":"处理人"},"text":"明确分享的人工说明","referenceState":"UNAVAILABLE","reference":null,"createdAt":"2026-11-14T03:00:00Z"}
```

不可用响应不含资料 ID、标题或隐藏数量，人工说明仍按问题范围分享；依赖故障为整体 503，不用旧快照。已解决的本人确认事实不因后续资料变化自动改写。
