# audit-query Specification

## Purpose

提供独立授权、可跨目标检索且能够稳定分页的管理与登录审计查询，使运维人员可以追溯已有操作和无目标身份的登录失败，同时明确数据源覆盖边界并防止审计载荷成为敏感内容读取通道。

## Requirements

### Requirement: 全局审计查询必须使用独立资格

系统 SHALL 提供 `GET /api/admin/audits` 及 `GET /api/admin/audits/{eventId}`，仅向当前持有 `audit:read` 的启用用户开放。身份审计读取、空间管理、文档密级、外部身份管理、Embedding 管理或 ROLE_ADMIN 名称 MUST NOT 隐含该资格。无会话返回 401，无资格、主体停用或强制改密返回 403，且 MUST 先鉴权再查询事件是否存在。

#### Scenario: 持有旧审计读取资格
- **WHEN** 操作者只有 `identity:audit:read`，没有 `audit:read`
- **THEN** 新全局接口返回 403，原按目标审计接口仍按原规则工作

#### Scenario: 无资格用户猜测事件 ID
- **WHEN** 无资格用户分别查询存在和不存在的合法事件 UUID
- **THEN** 两次请求均返回 403，不区分事件是否存在

#### Scenario: 合格用户查询不存在的事件
- **WHEN** 合格操作者查询合法但不存在的事件 UUID
- **THEN** 返回 404；非法 UUID 返回 400

### Requirement: 查询必须明确覆盖已有持久审计而不伪造完整性

首期查询 SHALL 覆盖现有 `sys_identity_audit` 中的全部已有管理及登录事件，包括成功、失败和 targetId 为空的记录。查询 MUST 保持 append-only，不写回、修补或删除审计。接口说明 MUST 明示不包含 Redis AI 审计、独立 Embedding 操作、尚未被生产者记录的请求及节流期间未记录的尝试。

#### Scenario: 未知用户名登录失败
- **WHEN** 登录失败审计没有解析出目标用户，targetId 为 null
- **THEN** 事件仍可按时间、操作域和登录用户名检索，响应保留 null，不补造用户 ID

#### Scenario: 业务操作没有留下审计
- **WHEN** 查询范围内某个请求未由原生产者写入审计
- **THEN** 查询不生成推测事件，文档不将“未查到记录”解释成“操作从未发生”

### Requirement: 审计必须支持组合条件和明确的时间窗口

列表 SHALL 支持 actorUserId、operationScope、operation、targetType、targetId、status、requestId、loginUsername、from、to，所有已提供条件按 AND 组合。targetId 仅与 targetType 同时提供；单独 targetType 合法。from/to MUST 为带时区时间，采用 `[from,to)`；缺省 to 为首次请求时间、from 为 to 前 7 天，跨度最多 31 天。ID、枚举、长度、空字符串、未知参数或时间范围非法时返回 400。

#### Scenario: 不限定具体目标进行排查
- **WHEN** 合格用户仅指定操作者、FAILED 结果和时间范围
- **THEN** 返回满足条件的跨目标事件，不要求 targetType 或 targetId

#### Scenario: 按登录用户名查询
- **WHEN** 请求提供 loginUsername
- **THEN** 仅在登录成功与失败事件中按已记录用户名精确匹配，不作模糊、全文或正文搜索
- **AND** 与不兼容的 operationScope 组合时返回空页，不忽略过滤条件

#### Scenario: 非法或超长窗口
- **WHEN** from 不早于 to、窗口超过 31 天、时间无时区或 targetId 缺少 targetType
- **THEN** 返回 400，不执行无界扫描或静默放宽条件

### Requirement: 审计分页必须稳定且不得因关联缺失丢失事件

列表 SHALL 按 `(createdAt DESC, id DESC)` 排序，返回 items、hasMore、nextCursor 及实际 from/to；limit 默认 20、范围 1–100。游标 MUST 绑定有效时间范围、过滤条件和最后排序位置，继续请求缺省时间沿用游标范围，不能重新取当前时间。查询 MUST 不依赖用户、角色、部门或文档仍存在的内连接，也不返回无界总数。

#### Scenario: 多条事件具有相同时间
- **WHEN** 页面边界上有多条相同 createdAt 的事件
- **THEN** 使用内部 ID 作为确定性的第二排序键，后续页不跳过或重复边界事件

#### Scenario: 目标实体后来被删除
- **WHEN** 审计目标的角色、空间或其他业务实体已经不存在
- **THEN** 原事件仍按存储的标识和时间返回，不因关联对象缺失被过滤

#### Scenario: 查询条件与游标不一致
- **WHEN** 调用者更换过滤条件、提供损坏游标或在其他查询类型使用游标
- **THEN** 返回 400，不把原游标当作有效查询或授权快照

### Requirement: 审计响应必须使用白名单安全投影

列表和详情 SHALL 返回事件标识、操作域、操作者 ID、目标类型/ID、操作、结果、请求标识和时间；详情另提供按操作域白名单构造的结构化变更摘要，登录事件可返回受长度限制的 attemptedUsername。MUST NOT 原样返回 beforeState、afterState、自由文本 reason，或输出密码、哈希、会话令牌、外部 subject、文档标题/正文、存储键及远程错误体。

#### Scenario: 原事件含额外敏感 JSON 字段
- **WHEN** 事件摘要中存在 subject、password、token、title、content 或未知嵌套字段
- **THEN** 列表与详情均不输出这些字段，不能仅依赖底层实体序列化注解

#### Scenario: 已知操作的前后值
- **WHEN** 查询用户部门/密级、空间归档、角色权限或文档搬迁等已定义操作详情
- **THEN** 仅返回该操作已批准的 ID、布尔值、密级数值、权限 code 及变更方向
- **AND** 不通过关联加载当前名称、正文或其他身份详情

#### Scenario: 历史载荷损坏或操作域未识别
- **WHEN** 原摘要不是预期对象、字段类型错误或没有已注册的摘要规则
- **THEN** 仍返回合法的事件元数据，并标记摘要不可用，不回显原字符串或抛出解析细节

### Requirement: 审计读取必须保持实时撤权和可辨认故障

每次列表与详情请求 SHALL 重新判定当前资格，并返回 `Cache-Control: no-store`。游标、上一页结果与会话中旧角色 MUST NOT 延续已撤销权限。数据库或授权依赖故障 SHALL 返回安全的 503；错误和日志 MUST 不记录查询用户名、请求标识原值或原始审计载荷。审计查询本身 MUST 不递归创建可被同一查询不断追加的新审计事件。

#### Scenario: 翻页前撤销全局审计权限
- **WHEN** 第一次查询后权限被撤销，操作者继续使用原游标或事件 ID
- **THEN** 新请求返回 403，不继续输出历史权限下的记录

#### Scenario: 数据库访问失败
- **WHEN** 审计查询无法可靠完成
- **THEN** 返回 503，不把故障伪装为成功空页，也不输出 SQL、连接信息或原始异常

### Requirement: 企业登录与本人绑定必须进入既有全局审计范围

系统 SHALL 把企业登录和本人绑定的终结事件分别记录为 sys_identity_audit 中的 SSO_LOGIN 和 SSO_BIND，支持现有全局列表及详情查询。事件使用服务端生成的关联身份，保留成功、失败及未解析内部用户的 null 目标；同一授权事务不得重复记录终结事件。读取仍要求 audit:read，不新增普通用户审计读取资格。

#### Scenario: 已绑定用户完成外部登录
- **WHEN** 企业认证、内部身份检查和会话建立均成功
- **THEN** 现有审计接口可查到 SSO_LOGIN 成功事件，actor 与 target 指向内部用户
- **AND** 事件不包含内部令牌、平台令牌或外部 subject

#### Scenario: 未绑定或非企业成员
- **WHEN** 授权事务因未绑定、成员门禁或供应商故障终止，且审计存储可用
- **THEN** 记录失败终结事件，未知内部用户保持 null，不猜测用户名或用户 ID

#### Scenario: 授权事务重放
- **WHEN** 已记录终结事件的回调或结果被重复提交
- **THEN** 请求按防重放规则拒绝，不重复写入该事务的登录或绑定终结事件

### Requirement: 企业认证审计摘要必须使用有限安全投影

新增 SSO_LOGIN 与 SSO_BIND 摘要 SHALL 仅允许平台枚举 dingtalk、wecom、feishu，固定目录中的 resultCode，以及绑定事件的布尔 bound 字段。摘要 MUST 不返回企业 ID、应用 ID、连接作用域键、外部 subject 及其摘要、用户名、远程错误或任何认证凭证。字段非法时沿用摘要不可用的安全降级，不回显原始 JSON。

#### Scenario: 合法平台与失败类别
- **WHEN** 合格审计用户读取一个平台为 feishu、结果为 NOT_MEMBER 的合法失败事件
- **THEN** 仅展示批准的平台和固定结果码及既有元数据，不加载企业或用户当前名称

#### Scenario: 摘要混入额外凭据
- **WHEN** 原事件摘要包含合法平台字段及额外 token、subject、corpId 或用户信息
- **THEN** 额外字段不进入查询结果，原始载荷不被返回

#### Scenario: 伪造平台或结果值
- **WHEN** 原事件的 platform 或 resultCode 不在固定目录中
- **THEN** 保留安全事件元数据，摘要标记不可用，不输出伪造字符串

### Requirement: 新审计域必须保留已有查询协议

新增企业认证事件 MUST 沿用现有状态、时间窗口、稳定游标、当前权限、no-store 和依赖故障语义，不增加新数据源拼接。loginUsername SHALL 继续仅匹配 AUTH_LOGIN_SUCCESS 与 AUTH_LOGIN_FAILURE 的已记录用户名；SSO 事件不补造 username，也不因此扩大旧登录审计摘要。

#### Scenario: 使用旧登录用户名过滤
- **WHEN** 操作者提供 loginUsername，或同时限定 SSO_LOGIN 域与 loginUsername
- **THEN** 前者仍仅匹配旧本地登录域，后者返回空页，不忽略过滤条件

#### Scenario: 查询 SSO 事件前撤权
- **WHEN** 操作者首次读取 SSO 事件后被撤销 audit:read
- **THEN** 后续页和详情均返回 403，旧游标不延续原读取资格

### Requirement: 文档资料与目录事件必须进入既有审计范围

元数据、分类及标签成功变更 SHALL 写入既有持久审计来源，使用独立操作域 DOCUMENT_METADATA_UPDATE、DOCUMENT_CATEGORY_CREATE/UPDATE/DELETE、DOCUMENT_TAG_CREATE/UPDATE；搬迁沿用 DOCUMENT_RELOCATE。列表和详情支持相应操作域与 document、document-category、document-tag 目标类型，继续要求 audit:read、固定窗口和当前权限。匹配重试只对应一条成功事件。

#### Scenario: 查询全局标签变更
- **WHEN** 合格审计用户按 DOCUMENT_TAG_UPDATE 或 document-tag 目标查询
- **THEN** 可得到该类型已有变更事件，不因标签管理权限而额外扩大或限制审计资格

#### Scenario: 目录删除后读取历史
- **WHEN** 分类已被合法删除，审计用户读取此前创建和删除事件
- **THEN** 原事件仍按稳定 ID 返回，不依赖目录实体继续存在

### Requirement: 新资料审计必须只投影固定结构

元数据审计 SHALL 只投影固定 changedFields、分类/标签 ID 和 metadataVersion 的前后值；分类事件仅允许空间/分类/父节点 ID、排序、treeVersion 和名称已变更布尔值；标签事件仅允许 tagId、enabled、version 和名称已变更布尔值。MUST 不输出标题、摘要、分类路径、词条名称、输入摘要、原始回执或关联文档数量。字段错误时保留事件元数据并标记摘要不可用。

#### Scenario: 标题或摘要修改
- **WHEN** 合格审计用户查看仅修改标题和摘要的事件
- **THEN** 可见哪些固定字段发生变化及前后版本，不返回原文、新文或其内容摘要

#### Scenario: 原载荷混入目录名称
- **WHEN** 审计载荷除合法身份外还包含 name、title、summary 或未知嵌套字段
- **THEN** 这些内容不进入安全投影，查询不能变成文档或词库内容读取入口

### Requirement: 新版搬迁摘要必须安全兼容历史事件

DOCUMENT_RELOCATE 的新摘要 SHALL 在已有安全空间与例外摘要上增加原/目标分类 ID 和前后 metadataVersion，分类允许 null。旧事件没有新字段时保持未知，不伪造曾经未分类或版本为零。分类名称、全局标签名称和文档资料不得通过关联读取补入，原事件保持只读。

#### Scenario: 查看新版搬迁
- **WHEN** 搬迁明确选择未分类且已提交
- **THEN** 审计安全详情可显示目标分类为 null 和新元数据版本，不返回源或目标分类名称

#### Scenario: 查看升级前搬迁
- **WHEN** 历史搬迁记录没有分类和元数据版本字段
- **THEN** 仍展示已有安全摘要，新字段标记未知，不将缺失解释为曾明确选择未分类

### Requirement: 适用期与复审事件必须进入既有审计范围

适用期修改、责任人变更与复审确认的成功写入 SHALL 写入既有持久审计来源，分别使用独立操作域 DOCUMENT_APPLICABILITY_UPDATE（目标类型 document-version）、DOCUMENT_STEWARDSHIP_UPDATE（目标类型 document，操作为 assign、reassign、unassign 或 cycle）和 DOCUMENT_STEWARDSHIP_CONFIRM（目标类型 document）。列表和详情支持这些操作域与目标类型，继续要求 audit:read、固定窗口和当前权限。匹配重试只对应一条成功事件，读取与被拒写入不产生成功事件。

#### Scenario: 按操作域查询复审确认
- **WHEN** 合格审计用户按 DOCUMENT_STEWARDSHIP_CONFIRM 查询
- **THEN** 返回窗口内的确认事件及操作者 ID，不因审计用户缺少文档阅读或空间管理资格而受限

#### Scenario: 重试不产生重复事件
- **WHEN** 同一请求标识的适用期修改被匹配重试
- **THEN** 审计中只有一条对应的成功事件

### Requirement: 适用期与复审审计必须只投影固定结构

DOCUMENT_APPLICABILITY_UPDATE 摘要 SHALL 只投影 documentId、versionId、修改前后的生效日、截止类型、截止日与适用期版本，以及修改时版本是否已对读者可见、是否提供了原因；复审事件只投影前后责任人 ID、周期、最近确认日、到期日和复审版本。摘要 MUST NOT 输出原因文本、显示名、文档标题或正文；字段错误时保留事件元数据并标记摘要不可用。

#### Scenario: 已发布版本更正
- **WHEN** 合格审计用户查看一次附原因的已发布版本更正
- **THEN** 摘要显示前后日期与“已提供原因”，不显示原因文本

#### Scenario: 载荷混入名称
- **WHEN** 审计载荷除合法字段外还包含显示名、标题或未知嵌套字段
- **THEN** 这些内容不进入安全投影，查询不能成为人员目录或文档内容的读取入口

### Requirement: 变更说明写入必须进入既有审计范围

变更说明的成功写入 SHALL 写入既有持久审计来源，使用独立操作域 DOCUMENT_CHANGE_NOTE_UPDATE，目标类型为 document-version。列表和详情支持该操作域与目标类型，继续要求 audit:read、固定窗口和当前权限。匹配重试只对应一条成功事件；读取、对比和被拒写入不产生成功事件。

#### Scenario: 按操作域查询说明写入
- **WHEN** 合格审计用户按 DOCUMENT_CHANGE_NOTE_UPDATE 查询
- **THEN** 返回窗口内的说明写入事件及操作者 ID，不因审计用户缺少文档阅读资格而受限

#### Scenario: 读取对比不产生审计
- **WHEN** 用户多次读取版本对比和变更说明
- **THEN** 审计中不出现对应事件

### Requirement: 变更说明审计必须只投影固定结构

DOCUMENT_CHANGE_NOTE_UPDATE 摘要 SHALL 只投影 documentId、versionId、写入前后的基准版本 ID、写入前后是否有说明、写入前后的说明版本、写入时版本是否已对读者可见，以及是否提供了原因。摘要 MUST NOT 输出说明文本、原因文本、文档标题或正文；字段错误时保留事件元数据并标记摘要不可用。

#### Scenario: 附原因更正说明
- **WHEN** 合格审计用户查看一次附原因的已发布说明更正
- **THEN** 摘要显示前后说明版本、对读者可见和“已提供原因”，不显示说明或原因文本

#### Scenario: 载荷混入文本
- **WHEN** 审计载荷除合法字段外还包含说明文本、标题或未知嵌套字段
- **THEN** 这些内容不进入安全投影

### Requirement: 企业词典维护必须进入既有持久审计范围

术语组创建及更新 SHALL 分别使用 SEARCH_TERM_CREATE、SEARCH_TERM_UPDATE 操作域，目标类型 search-term；错词映射创建及更新使用 SEARCH_CORRECTION_CREATE、SEARCH_CORRECTION_UPDATE，目标类型 search-correction。启停属于对应 UPDATE。事件写入既有持久审计来源，列表和详情可按操作域及目标查询，继续要求 audit:read 和既有时间窗口、分页、no-store 规则。词典管理读写资格不自动授予审计读取。

#### Scenario: 按术语查询维护历史
- **WHEN** 合格审计用户按 search-term 及稳定 termId 查询
- **THEN** 返回范围内已记录的创建、编辑和启停事件，不要求其另外持有词典管理权限

#### Scenario: 只有词典管理资格
- **WHEN** 用户拥有 search:term:read 和 search:term:write，但没有 audit:read
- **THEN** 通用审计列表与详情仍拒绝，不因其维护过目标而开放跨操作者历史

#### Scenario: 目标停用后查询
- **WHEN** 审计目标术语或错词已停用
- **THEN** 原事件仍可按存储的稳定身份读取，不因当前启停状态丢失记录

### Requirement: 词典审计摘要必须使用固定结构投影

词典审计 SHALL 仅投影术语或纠错稳定 ID、错词目标 termId、前后 enabled、实体 version、dictionaryRevision 以及固定 changedFields。字段集合只允许 canonicalName、description、aliases、wrongExpression、targetTermId、enabled 中对应实体适用的项。MUST 不输出原始词条文本、释义、别名清单、查询、建议上下文、输入摘要、原始回执或关联文档数量。损坏字段保留事件元数据并标记摘要不可用。

#### Scenario: 修改名称和别名
- **WHEN** 审计用户读取修改标准名称及别名的事件
- **THEN** 可以看到哪些固定字段变化及前后版本，不读取旧名、新名或完整别名集合

#### Scenario: 修改错词目标
- **WHEN** 审计用户读取错词映射改指另一术语组的事件
- **THEN** 可以看到前后目标 termId，不关联补查名称、释义或文档明细

#### Scenario: 载荷混入查询或名称
- **WHEN** 原审计载荷存在额外 query、name、description、selectionContext 或未知嵌套值
- **THEN** 这些内容不进入查询投影，不回显原 JSON 或异常文本

### Requirement: 维护审计必须与回执同事务且不记录搜索事件

词典首次成功写入 SHALL 与实体变化、修订推进和命令回执原子产生一条成功审计；匹配重放不重复产生。校验拒绝、版本冲突、事务失败、管理读取、普通搜索及建议采用不产生成功维护事件。审计读取仍为只读，不通过新审计域持久化用户查询和建议使用历史。

#### Scenario: 匹配重试
- **WHEN** 一个已成功的启停操作使用原标识及载荷重放
- **THEN** 只存在原先一条维护事件，不再次递增词典修订

#### Scenario: 审计提交失败
- **WHEN** 业务写入不能与其审计、回执完整提交
- **THEN** 不保留本次词条变化或成功回执，不对用户报告已维护成功

#### Scenario: 用户连续采用建议
- **WHEN** 普通用户搜索并先后采用纠错和歧义建议
- **THEN** 不生成 SEARCH_TERM_UPDATE 或 SEARCH_CORRECTION_UPDATE，也不保存一份建议采用历史冒充维护审计

### Requirement: 答案卡业务写入必须进入既有持久审计范围

卡片成功写入 SHALL 使用 ANSWER_CARD_CREATE、ANSWER_CARD_SAVE、ANSWER_CARD_SUBMIT、ANSWER_CARD_APPROVE、ANSWER_CARD_REJECT、ANSWER_CARD_WITHDRAW、ANSWER_CARD_STEWARDSHIP_UPDATE、ANSWER_CARD_CONFIRM 操作域，目标类型统一 answer-card。事件与业务及命令回执原子保存，匹配重放不重复。既有全局列表及详情支持新域与类型，继续使用 audit:read、时间窗口及稳定分页。

#### Scenario: 查询卡片审核记录
- **WHEN** 合格审计用户按 ANSWER_CARD_APPROVE 或 answer-card 目标查询
- **THEN** 可以核对已提交的批准事件，不要求或授予卡片正文访问资格

#### Scenario: 仅有卡片维护资格
- **WHEN** 用户具有 answer:card:write 而没有 audit:read
- **THEN** 全局审计仍拒绝，该用户的业务修订历史另按卡片当前访问规则读取

#### Scenario: 事务失败或匹配重放
- **WHEN** 卡片保存事务失败，或已经提交的批准被匹配重试
- **THEN** 前者不产生成功事件，后者仍只有首次成功事件

### Requirement: 卡片审计摘要必须只投影固定安全字段

卡片摘要 SHALL 仅返回卡片/空间/修订/分支/轮次 ID、前后并发版本、固定审核状态、责任人 ID、周期、复审日期、适用日期、是否提供原因及固定 changedFields。changedFields 仅为 question、aliases、answer、applicabilityText、sources、effectiveFrom、untilKind、effectiveUntil、responsible、cycleMonths。不得返回问题、问法、答案、来源身份清单或标题、人员姓名、驳回原因、输入摘要或原始回执。

#### Scenario: 修改答案与来源
- **WHEN** 审计用户读取一次答案正文和来源集合变更
- **THEN** 只显示对应字段发生变化及稳定业务版本，不显示原文、新文、来源清单或内容摘要

#### Scenario: 驳回原因含业务内容
- **WHEN** 审计用户读取包含自由文本驳回原因的业务事件
- **THEN** 仅可见是否提供原因，不通过审计获得私有意见

#### Scenario: 未知或损坏字段
- **WHEN** 原摘要含额外文本字段、未知状态或损坏结构
- **THEN** 按既有白名单策略保留合法元数据并标记摘要不可用，不回显原 JSON

### Requirement: 卡片展示变化与查询不得伪造维护事件

普通浏览、搜索、候选、详情、命令核对及因业务日期或来源事实计算出的展示变化 SHALL 为只读，不追加卡片写入审计。来源更新及日期更正沿用来源自身真实事件，不伪造卡片编辑、批准或准确性确认。需要追溯时根据固定来源绑定及真实历史核对，不保存用户搜索行为冒充运维审计。

#### Scenario: 来源更正导致暂停
- **WHEN** 来源适用期更正后用户读取卡片，卡片因绑定不匹配而停止普通展示
- **THEN** 原来源更正事件保留，不生成无人执行的 ANSWER_CARD_SAVE 或 APPROVE

#### Scenario: 重复搜索和跨日读取
- **WHEN** 用户多次搜索或日期跨过卡片截止日
- **THEN** 不因读取产生卡片维护事件，也不自动形成责任人确认记录
