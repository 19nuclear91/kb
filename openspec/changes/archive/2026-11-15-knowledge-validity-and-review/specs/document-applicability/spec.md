# Spec Delta

## Purpose

为固定文档版本记录正文声明的生效与截止日期，并在阅读、审核、搜索和问答引用中按业务日期给出可核对的适用状态，使用户能分辨资料是否仍然适用，同时不改变发布、召回或引用身份。

## ADDED Requirements

### Requirement: 适用期必须作为固定版本的独立声明

系统 SHALL 为每个文档版本保存独立的适用期：生效日为“未声明”或具体日期，截止日为“未声明”“明确长期有效”或具体日期，两者均为不含时刻的日历日期。适用期 MUST 与文档资料、原件、审核轮次和发布证据分开；设置或修改不创建文件版本或审核轮次，不改变当前版本、发布状态、检索范围或引用身份。

#### Scenario: 升级前已有的版本
- **WHEN** 升级后读取此前已存在的任一版本的适用期
- **THEN** 生效日与截止日均为未声明，适用期版本为 0
- **AND** 系统不从正文、标题、上传时间或发布时间推断日期

#### Scenario: 新版本不继承旧日期
- **WHEN** 文档创建或发布新版本，而上一版本已声明适用期
- **THEN** 新版本的生效日与截止日均为未声明，不复制上一版本的声明

#### Scenario: 修改不影响发布与召回
- **WHEN** 当前发布版本的截止日被修改为早于今天的日期
- **THEN** 该版本仍是当前发布版本，并继续按原权限参与搜索和问答
- **AND** 已有引用的文档、版本和分块身份不变

### Requirement: 适用状态必须按业务日期在读取时计算

系统 SHALL 以数据库当前时间换算到 Asia/Shanghai 的日期 T 计算状态，生效日与截止日均包含当天。判定顺序为：两项均未声明为 UNDECLARED；生效日晚于 T 为 NOT_YET_EFFECTIVE；截止日早于 T 为 EXPIRED；截止日减 T 不超过 30 天为 EXPIRING_SOON；其余为 EFFECTIVE。状态 MUST NOT 持久保存或由定时任务改写，响应同时返回原始声明与 asOfDate=T。

#### Scenario: 截止日当天与次日
- **WHEN** 截止日为 2026-12-31，且 T 为 2026-12-31
- **THEN** 状态为 EXPIRING_SOON
- **AND** T 为 2027-01-01 时状态为 EXPIRED

#### Scenario: 即将届满的 30 天边界
- **WHEN** 截止日为 2026-12-31，且 T 为 2026-12-01
- **THEN** 状态为 EXPIRING_SOON
- **AND** T 为 2026-11-30 时状态为 EFFECTIVE

#### Scenario: 已发布但尚未生效
- **WHEN** 生效日为 2027-01-01，T 为 2026-12-15，无论截止日如何声明
- **THEN** 状态为 NOT_YET_EFFECTIVE

#### Scenario: 只声明其中一项
- **WHEN** 版本只声明截止日，或只声明生效日
- **THEN** 按已声明的一项判定状态，未声明的一项仍原样返回为未声明
- **AND** 未声明的截止日不被解释为明确长期有效

#### Scenario: 明确长期有效
- **WHEN** 截止日为明确长期有效，且生效日未声明或不晚于 T
- **THEN** 状态为 EFFECTIVE，此后不会因日期推移进入 EXPIRING_SOON 或 EXPIRED

#### Scenario: 业务日期跨越 UTC 日界
- **WHEN** 数据库当前时间为 2026-12-31T16:30:00Z
- **THEN** T 为 2027-01-01，状态按该日期计算

### Requirement: 适用期读取必须沿用版本当前访问规则

`GET /api/documents/{documentId}/versions/{versionId}/applicability` SHALL 返回 documentId、versionId、effectiveFrom、untilKind、effectiveUntil、state、corrected、applicabilityVersion、asOfDate，以及当前用户能否修改及审核冻结标记。读取 MUST 校验版本属于该文档，并使用既有版本交互访问规则，未发布版本仅对上传者、送审者和合格审核人开放。不存在、归属不符或不可访问统一返回 404。

#### Scenario: 无候选访问资格
- **WHEN** 只有父文档 view、既非上传者和送审者也非合格审核人的用户读取未发布候选版本的适用期
- **THEN** 返回 404，不透露该候选版本是否存在

#### Scenario: 版本不属于路径中的文档
- **WHEN** 请求的 versionId 属于另一份文档
- **THEN** 返回 404，不返回任一文档的适用期

### Requirement: 候选版本的适用期必须由可访问候选的编辑者设置并在审核中冻结

对从未发布且没有旧兼容访问的候选版本，`PUT` 同一路径 SHALL 要求调用者可按交互访问规则访问该候选，并对文档具有当前 edit（含文档例外 edit），不要求填写原因。该版本存在 IN_REVIEW 审核轮次时 MUST 拒绝修改并返回 409；轮次撤回、驳回或取消后可以再次修改。

#### Scenario: 审核中冻结
- **WHEN** 候选版本处于 IN_REVIEW 时提交适用期修改
- **THEN** 返回 409 REVIEW_IN_PROGRESS，不保存修改，审核人看到的日期保持不变

#### Scenario: 撤回后修改再送审
- **WHEN** 送审者撤回后，合格编辑者修改适用期并重新送审
- **THEN** 修改成功并追加历史，新一轮审核看到修改后的日期

#### Scenario: 有 edit 但无候选访问
- **WHEN** 用户对文档有 edit，但既非上传者、送审者，也非合格审核人
- **THEN** 返回 404，不保存修改

#### Scenario: 提交前版本已经发布
- **WHEN** 编辑者按候选规则提交且 reason 为 null，但版本在提交前已自动发布或获批发布
- **THEN** 返回 409 REASON_REQUIRED，不保存修改，客户端需重新读取并按已发布版本规则补充原因

### Requirement: 已对读者可见的版本必须附原因更正并标记已更正

对具有发布证据或旧兼容访问的版本，修改 SHALL 要求当前 view 与 edit，在 AUTO 与 REVIEW_REQUIRED 空间规则相同，并 MUST 提交去除首尾空白后为 1–2000 个 Unicode code point 的原因。成功后立即生效，corrected 永久为 true；不创建审核轮次，也不改变既有审核结论。文档已删除或归档、所属空间已归档时 MUST 拒绝修改。

#### Scenario: 缺少原因
- **WHEN** 已发布版本的修改请求中 reason 为 null
- **THEN** 返回 409 REASON_REQUIRED，不保存修改

#### Scenario: 原因只有空白或超长
- **WHEN** reason 去除首尾空白后为空，或超过 2000 个 Unicode code point
- **THEN** 返回 400，不保存修改，也不截断原因

#### Scenario: 需审空间中更正
- **WHEN** REVIEW_REQUIRED 空间中具有 view 与 edit 的用户附原因更正已发布版本的截止日
- **THEN** 修改立即生效，不创建审核轮次，读取结果中 corrected 为 true

#### Scenario: 更正历史版本
- **WHEN** 合格编辑者更正一个已被新版本取代的已发布版本
- **THEN** 按同一规则生效，当前版本和其他版本的适用期不变

#### Scenario: 所属空间已归档
- **WHEN** 文档所属空间已归档时提交修改
- **THEN** 返回 404，不保存修改

### Requirement: 适用期修改历史必须不可删除且不暴露操作者

系统 SHALL 为每次成功修改保存修改时间、修改前后声明、修改时版本是否已对读者可见以及原因，记录不可编辑或删除。`GET .../applicability/history` SHALL 使用与适用期读取相同的访问规则，按修改时间倒序以有界游标返回，limit 默认 20、范围 1–100。响应 MUST NOT 包含操作者身份，操作者只进入审计。

#### Scenario: 读者查看更正记录
- **WHEN** 可访问该版本的用户读取修改历史
- **THEN** 可看到每次修改的时间、前后日期和原因，看不到操作者

#### Scenario: 尝试修改或删除历史
- **WHEN** 调用方以任何方式尝试编辑或删除已保存的修改记录
- **THEN** 请求被拒绝，历史保持不变

### Requirement: 适用期写入必须严格校验输入

`PUT` 请求 SHALL 只包含 effectiveFrom、untilKind、effectiveUntil、reason、expectedApplicabilityVersion 五个必需字段，其中 effectiveFrom、effectiveUntil、reason 可为 null。日期为 1900-01-01 至 2999-12-31 之间的 ISO 日历日期；untilKind 只接受 UNDECLARED、OPEN_ENDED、DATE，取 DATE 时 effectiveUntil 必填，否则必须为 null。两项均为日期时截止日不得早于生效日。违反规则、未知或重复字段返回 400。

#### Scenario: 截止日早于生效日
- **WHEN** 请求生效日为 2027-01-01、截止日为 2026-12-31
- **THEN** 返回 400，不保存修改

#### Scenario: 截止类型与日期不一致
- **WHEN** untilKind 为 OPEN_ENDED 但同时提供了 effectiveUntil，或 untilKind 为 DATE 但 effectiveUntil 为 null
- **THEN** 返回 400，不保存修改

### Requirement: 适用期写入必须绑定请求并在冲突时拒绝

写入 SHALL 要求 X-Request-Id，去除首尾空白后为 1–128 个非控制 Unicode code point，绑定操作域、操作者、目标版本、预期适用期版本与规范化载荷。匹配重试 MUST 先按当前资格重新鉴权，再返回首次回执并标记 replayed=true，不重复追加历史；绑定不符返回 409。预期版本过期返回 409；修改后与当前声明完全相同返回 409 且不产生成功变更。

#### Scenario: 响应丢失后重试
- **WHEN** 首次修改已提交但响应丢失，客户端以同一标识和载荷重试
- **THEN** 返回首次回执且 replayed=true，历史只有一条记录

#### Scenario: 同一标识用于不同载荷
- **WHEN** 客户端用已绑定的请求标识提交不同的日期或原因
- **THEN** 返回 409 REQUEST_MISMATCH，不返回原载荷内容

#### Scenario: 撤权后重放
- **WHEN** 原操作者失去 edit 后重放此前成功的请求
- **THEN** 按当前资格拒绝，旧回执不延续修改资格

#### Scenario: 并发修改
- **WHEN** 两个请求基于同一预期适用期版本同时提交不同修改
- **THEN** 至多一个成功，另一个返回 409 APPLICABILITY_VERSION_CONFLICT

### Requirement: 适用期变更必须与历史、审计和回执原子提交

每次成功修改 SHALL 在同一事务内更新适用期声明与版本号，并保存修改记录、一条无原因正文的成功审计及请求回执。任一部分保存失败 MUST 整体回滚，不留下只有声明、只有历史或只有回执的部分结果；同一标识的后续重试仍按未成功请求处理。

#### Scenario: 审计写入失败
- **WHEN** 声明已更新但成功审计无法保存
- **THEN** 本次修改整体回滚，读取结果仍为修改前的声明

### Requirement: 按版本批量读取标识必须逐项鉴权且不泄露

`POST /api/documents/applicability/query` SHALL 接收 1–100 个不重复的 documentId 与 versionId 组合，逐项按版本交互访问规则判定，只为可访问项返回 documentId、versionId、state、effectiveFrom、untilKind、effectiveUntil、corrected 和 current（是否为文档当前发布版本），并返回统一的 asOfDate。不可访问、不存在或归属不符的项 MUST 直接省略，不返回占位、原因或数量；该入口不修改数据，也不创建审计。

#### Scenario: 混合可见性
- **WHEN** 请求同时包含可读版本、撤权后不可读的版本和不存在的版本
- **THEN** 只返回可读版本的标识，其余项不出现在响应中

#### Scenario: 已被取代的版本
- **WHEN** 请求的版本已被同一文档的新版本取代
- **THEN** 该项 current 为 false，适用状态仍按该版本自身的声明计算

#### Scenario: 超限或重复
- **WHEN** 请求包含 0 个或超过 100 个组合，或包含重复组合、非正整数 ID
- **THEN** 返回 400，不读取任何版本

### Requirement: 适用期入口必须有界并安全表达失败

所有适用期入口 SHALL 保留登录、主体状态与强制改密门禁，成功与错误响应均标记 no-store。请求体上限 16 KiB，超限返回 413；拒绝未知或重复字段、尾随 JSON、非法 Unicode、NUL 和非整数身份。错误、日志与审计 MUST NOT 回显原因文本或底层异常；依赖无法可靠读取时返回安全 503，不伪装为未声明或空结果。

#### Scenario: 依赖故障
- **WHEN** 适用期或访问规则所需数据暂时无法读取
- **THEN** 返回 503，客户端不把该版本显示为未声明

#### Scenario: 原因包含敏感文本
- **WHEN** 写入因校验或冲突失败，且请求原因中含有内部信息
- **THEN** 错误响应和日志只包含固定原因码，不包含原因文本
