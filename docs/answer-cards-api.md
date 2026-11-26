# 标准答案卡 API

所有路径以 `/api/answer-cards` 开头。身份从当前已验证会话与认证代次取得，必须完成要求的改密。所有成功与失败响应均 `Cache-Control: no-store`。成功由实际app业务端点声明 `X-Answer-Card-Version: 1`，网关不补此头；客户端缺头或结构损坏应判为不可用。

成功为 `R<T>`：`code:200`、`message:"ok"`、具名 `data`，以及统一包装可能序列化的 `ok:true`。失败使用HTTP状态和固定安全消息，不回显正文、查询、来源或底层异常。字段精确定义见 `AnswerCardRequests` 与 `AnswerCardViews` 的OpenAPI注解。

## 通用输入

- ID是规范正整数十进制字符串，版本是规范非负十进制字符串；都不超过Long.MAX_VALUE。禁止JSON数字ID、指数、小数、正号、前导零和空白。
- 拒绝未知字段、重复字段、尾随JSON、NUL、孤立代理项和超限输入。仅支持JSON正文，不把搜索或候选名称放进URL。
- 搜索正文上限16KiB，其他正文128KiB。所有写入要求单个 `X-Request-Id`，1至128个非控制Unicode码点；服务端按收到的标识精确绑定，不去除首尾空白，回执与审计使用同一标识。同原意图重试必须原样保留，客户端使用UUID。
- 日期使用YYYY-MM-DD；时间使用ISO8601带时区。所有日期声明必传 `effectiveFrom`、`untilKind`、`effectiveUntil`：前者可null；类型为UNDECLARED、OPEN_ENDED或DATE，只有DATE可且必须提供截止日。
- 下表写入字段均必传，只有明确标注可null的字段接受null。可选分页limit省略默认20（1–100）；搜索limit省略默认10（1–20），JSON整数，禁止null。游标省略或null为首页，生成的游标绑定当前用户、用途、条件和位置，不提供持续资格。

## 读取及候选

| 方法和相对路径 | 条件 | 返回data |
| --- | --- | --- |
| GET 空路径 | 可选spaceId、limit、cursor | Page<Item>，当前可展示摘要，卡片ID倒序 |
| POST /search | query必填1–256码点；spaceId省略/null为不限；limit可选 | Search：items、asOfDate、evaluatedAt、appliedQuery、appliedSpaceId |
| GET /{id}/revisions/{revisionId} | 不接受查询参数 | Detail，所选修订必须仍为当前可展示版 |
| GET /workspace | view默认editable；可选spaceId、limit、cursor | Page<Workspace>，view还支持reviewable、mine、attention |
| GET /{id}/workspace | 不接受查询参数 | Workspace，发布版与草稿分别授权 |
| GET /{id}/history | view默认revisions，或reviews；可选revisionId、limit、cursor | Page<History>；reviews可限定修订，逐轮次有界读取 |
| GET /management | spaceId必填；limit、cursor可选 | Page<Management>，仅最小管理信息 |
| GET /{id}/stewardship | 不接受查询参数 | Management |
| POST /source-options | spaceId、name必填；limit、cursor可选 | Page<SourceOption>，name字面匹配标题，0–128码点 |
| POST /{id}/stewardship/candidates | name必填；limit、cursor可选 | Page<Person>，name字面匹配显示名，0–64码点 |

Page包含items、hasMore、nextCursor和asOfDate，不返回隐藏total。来源候选仅为本人可读、具可信发布证据的当前版本，含日期修订与声明。责任人候选仅含用户ID和显示名；不要求身份目录资格。

普通详情统一404表示不存在、非当前发布版或当前不可展示。工作区缺任一来源访问时不返回该修订；最小管理响应不包含问题、答案、来源身份或驳回原因。历史reviews模式可取得同一修订的多次审核事实，不将最近轮次冒充全部历史。

修订availability为AVAILABLE、SOURCE_CHANGED、NOT_YET_EFFECTIVE、EXPIRED、DRAFT、HISTORICAL或UNAVAILABLE。HISTORICAL表示已被新版取代且原审核通过的修订；已批准修订因来源或日期暂停时保留原审核事实，以相应暂停状态表达当前展示条件。

## 草稿正文

`content`包含全部六项：question、aliases、answer、applicabilityText、dates、sources。问题1–256码点；问法0–20项，每项1–256；答案0–16000；范围0–2000；来源0–10份不同文档。文本去首尾空白，问法去重另使用NFC和Locale.ROOT小写；保存不执行标记文本。送审要求答案、范围非空且至少一份来源。

每个source只有documentId、versionId、applicabilityVersion三个字符串字段，日期快照不接受客户端提交。保存时核对用户明确选择的三元组，不自动替换旧版本或旧日期修订。

## 写入

| 方法和相对路径 | 正文字段 | 动作 |
| --- | --- | --- |
| POST 空路径 | spaceId、content | 创建卡片与第一草稿，cardVersion从0开始 |
| PUT /{id}/draft | spaceId、expectedCardVersion、branchId、revisionId、content | 有草稿时绑定当前分支和最新修订；无草稿时branchId必须null、revisionId为当前发布版 |
| POST /{id}/reviews | spaceId、expectedCardVersion、branchId、revisionId | 手动冻结精确修订并创建轮次 |
| POST /{id}/reviews/{reviewId}/approve | spaceId、expectedCardVersion、branchId、revisionId、expectedReviewVersion | 非自审批准并发布 |
| POST 同上/reject | 与approve相同，另加reason（1–2000码点） | 非自审驳回 |
| POST 同上/withdraw | 与approve相同，无reason | 由本轮送审者撤回 |
| PUT /{id}/stewardship | spaceId、expectedCardVersion、expectedStewardshipVersion、responsibleUserId、cycleMonths | 管理者指定/改派/改周期，cycleMonths为3/6/12/24 |
| POST /{id}/stewardship/confirm | spaceId、expectedCardVersion、revisionId、expectedStewardshipVersion | 当前责任人确认发布版，无活动草稿且须仍可展示 |

每次新写入推进cardVersion；复审配置及确认同时推进stewardshipVersion。内容修订、轮次、分支都有稳定身份。Receipt只含cardId、cardVersion、branchId、revisionId、reviewId、reviewVersion、stewardshipVersion、state、committedAt、replayed；不适用字段null。状态为DRAFT、IN_REVIEW、APPROVED、REJECTED、WITHDRAWN、ASSIGNED或CONFIRMED。

## 原意图核对

`POST /commands/query` 必传 operation、requestId、cardId、reviewId、payload。operation为八项完整ANSWER_CARD_*枚举；payload必须是原写入端点的精确正文。创建时cardId为null，审核决定时reviewId为原轮次，其他操作reviewId为null。

结果为 `{status:"COMMITTED",receipt:...}` 或 `{status:"NOT_FOUND",receipt:null}`。只查原操作者的精确绑定，继续核验当前动作及原修订访问。已提交重放先匹配回执再检查仅新执行需要的活动状态，因此旧批准可正确核对；不能用后来新草稿替代原分支。核对不写审计或执行写入。

## 错误与搜索容量

400输入无效，401认证失效，403功能资格不足，404目标不可见，409状态/版本/来源/人员冲突，413正文超限，429搜索容量满，503预算或依赖不可用。429带Retry-After秒数。空items是完整成功，不能用空数组掩盖503。

搜索实例并发最多8、每用户最多2，总预算5秒、单语句最多3秒且不超过剩余预算、编码后响应最多256KiB。配置只能调小。无队列等待；请求取消或失败后，真实工作退出才释放占用。搜索在当前只读快照中固定数据库时间、权限和来源事实，返回后不构成持续授权。

## 验证及发布边界

请求解码、服务调用、身份/资源缩减、回执和错误投影以进程内替身验证；真实HTTP链路和数据库未运行。app、gateway、web必须按运维文档顺序发布。新增权限前先排空旧app；旧目录拒绝未知权限的规则保留。迁移/SQL和说明通过人工审阅，不用文档内容断言测试。

本协议已同步主规格并随变更[归档](../openspec/changes/archive/2026-11-26-reviewed-answer-cards/archive-record.md)，最终核验及修复证据见[verify-report.md](../openspec/changes/archive/2026-11-26-reviewed-answer-cards/verify-report.md)。
