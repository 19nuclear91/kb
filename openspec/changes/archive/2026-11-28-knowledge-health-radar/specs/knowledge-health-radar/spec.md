# Spec Delta

## Purpose

为具有独立资格的空间管理员提供单空间文档维护诊断，将责任人与复审事实、当前可读发布版本的适用期汇集为六类明确提示和去重清单，帮助用户定位需要处理的资料，并准确表达统计范围、业务时间和访问限制。

## ADDED Requirements

### Requirement: 雷达请求必须同时核对当前功能与空间管理资格

`GET /api/admin/spaces/{spaceId}/health-radar` SHALL 先核验有效身份及 `knowledge:health:read`，再核验目标空间当前 manage 或 `space:manage-all`。未认证为 401，身份失效、强制改密或缺功能资格为 403；空间不存在或不可管理统一为 404。每次请求及翻页均重新鉴权，客户端声明、旧响应和游标不构成授权。

#### Scenario: 仅有空间管理资格
- **WHEN** 用户可以管理目标空间但未持有雷达读取权限
- **THEN** 返回 403，不返回统计或文档清单

#### Scenario: 仅有雷达读取资格
- **WHEN** 用户持有雷达读取权限但不可管理目标空间
- **THEN** 返回与空间不存在相同的 404

#### Scenario: 下一页前撤权
- **WHEN** 用户取得游标后失去雷达权限或目标空间管理资格
- **THEN** 下一次读取分别按 403 或 404 拒绝，游标不延续资格

### Requirement: 首期诊断范围必须为一个未归档空间的活动文档

每次诊断 SHALL 只包含路径指定空间内未删除、未归档的文档，包含尚无当前发布版本的文档。文档是否具有适用期信号另行判定。目标空间已归档时返回 409 SPACE_ARCHIVED，不能返回零问题或继续诊断；不得接受跨空间、全局或包含归档文档的参数。

#### Scenario: 未发布首版与归档文档
- **WHEN** 空间有一份无当前发布版本的活动文档和一份已归档文档
- **THEN** 前者可按责任人与复审规则进入诊断，后者不进入任何数量或清单

#### Scenario: 空间在打开页面后归档
- **WHEN** 用户再次读取时目标空间已归档
- **THEN** 返回 409 SPACE_ARCHIVED，不将归档解释为全部问题已解决

#### Scenario: 文档搬迁或删除
- **WHEN** 文档已搬出目标空间或被删除，之后发起新的诊断请求
- **THEN** 本次结果排除该文档及其全部信号

### Requirement: 责任人与复审信号必须沿用现有状态优先级

雷达 SHALL 将既有复审状态 UNASSIGNED、NEEDS_REASSIGN、OVERDUE、DUE_SOON 分别映射为 STEWARD_UNASSIGNED、STEWARD_NEEDS_REASSIGN、REVIEW_OVERDUE、REVIEW_DUE_SOON；OK 不产生这四类信号。同一文档至多命中其中一类，不另按旧到期日重复推导。责任人资格按当前启用、主部门、改密、密级及 view/edit 规则判断，不暴露不合格原因，也不把逾期解释为内容失效。

#### Scenario: 取消责任人后原到期日已过
- **WHEN** 文档无当前责任人但保留的到期日早于今天
- **THEN** 只产生 STEWARD_UNASSIGNED，不同时产生 REVIEW_OVERDUE

#### Scenario: 原责任人失去维护资格
- **WHEN** 当前责任人不再满足维护资格，且文档已到复审日期
- **THEN** 只产生 STEWARD_NEEDS_REASSIGN，保留最小责任人信息而不显示失效原因

#### Scenario: 责任人恢复合格
- **WHEN** 原责任人重新满足资格且仍为当前责任人
- **THEN** 新请求按既有到期日计算复审状态，不沿用旧的需改派结论

### Requirement: 适用期信号只能使用操作者可读的当前发布版本

适用期诊断 SHALL 同时核对当前版本属于该文档、满足既有发布及版本读取规则、操作者当前 view 和密级资格，并只使用该版本自己的日期声明。EXPIRED、EXPIRING_SOON 分别映射为 APPLICABILITY_EXPIRED、APPLICABILITY_EXPIRING_SOON。其他状态、无当前发布版本或不可读版本不产生适用期信号，亦不披露其身份、日期、状态、跳过原因或隐藏数量。

#### Scenario: 可管理但不能读取
- **WHEN** 管理员可以诊断一份文档但不能读取其当前版本
- **THEN** 只按可见的责任人与复审信号计算，不在适用期数量或响应中暴露该版本的信息

#### Scenario: 历史版本已届满但新版未声明
- **WHEN** 当前发布版本未声明适用期，而历史版本已经届满
- **THEN** 不产生适用期信号，不回退历史版本或自动复制旧日期

#### Scenario: 候选版本可被管理员本人审核
- **WHEN** 文档有可供本人审核的候选版本，但它不是当前发布版本
- **THEN** 不将候选日期用于适用期信号，即使本人可访问该候选

#### Scenario: 未来才生效或明确长期有效
- **WHEN** 当前版本按既有规则为 NOT_YET_EFFECTIVE、UNDECLARED 或 EFFECTIVE
- **THEN** 不因日期缺失、长期有效或未来截止日而额外产生适用期问题

### Requirement: 日期信号必须共享同一业务日期并保留现有边界

同次诊断 SHALL 使用一次数据库当前时间及既有部署固定业务时区计算业务日期 T，返回 asOf、asOfDate 和 dayTimeZone；默认 Asia/Shanghai。复审到期日或适用期截止日为 T 时归入即将到期，早于 T 为逾期或届满；即将到期包含 T+30，不包含 T+31。适用期仍先判断是否尚未生效。不得使用浏览器日期、应用主机日期或后台定时状态替代。

#### Scenario: 当天、次日及三十天边界
- **WHEN** 合格责任人的到期日分别为 T、T-1、T+30、T+31
- **THEN** 分别产生 REVIEW_DUE_SOON、REVIEW_OVERDUE、REVIEW_DUE_SOON、无复审信号，适用期截止日适用对应边界

#### Scenario: 北京时间跨日
- **WHEN** 数据库时间为 2026-12-31T16:30:00Z，部署业务时区为 Asia/Shanghai
- **THEN** 同次数量、筛选、清单和 asOfDate 均按 2027-01-01 判断

### Requirement: 六类数量与受影响文档数必须精确且去重

响应 SHALL 使用 definitionVersion=KNOWLEDGE_HEALTH_V1，返回六个固定信号各自的文档数量及 affectedDocumentCount。总数为命中至少一个可见信号的文档并集，不是六类数量之和；同文档的多版本、多分块和多条授权不能放大数量。全部数量为规范非负十进制字符串。六类数量及并集始终表示当前完整诊断范围，不受 signal、limit 或 cursor 改变。

#### Scenario: 一份资料命中两类
- **WHEN** 唯一活动文档同时复审逾期且当前可读发布版本适用期已届满
- **THEN** 两个对应数量各为 "1"，affectedDocumentCount 为 "1"，全部清单中只有一项

#### Scenario: 按单类查看第二页
- **WHEN** 用户筛选某类并请求后续页
- **THEN** 统计仍覆盖本次空间诊断范围，清单只包含所选类，数量不被当前页条数替代

#### Scenario: 大整数及真实零
- **WHEN** 某数量超过 JavaScript 安全整数范围，或成功诊断范围确无任何可见信号
- **THEN** 前者原样返回精确十进制字符串，后者六类及并集均为 "0"

### Requirement: 清单必须按文档合并并保持最小管理投影

每个清单项 SHALL 只包含 documentId、非空 signals、stewardship（状态、责任人 ID/显示名或 null、到期日或 null）及 applicability。applicability 仅在命中可见适用期信号时返回当前固定版本 ID、适用状态、生效日及截止日，否则为 null。signals 按固定六类顺序返回该文档全部可见原因，不因单类筛选截断；清单不含标题、正文、账号详情、失效原因或其他版本信息。

#### Scenario: 单类筛选中的多原因文档
- **WHEN** 用户筛选 REVIEW_OVERDUE，返回文档也命中可见的 APPLICABILITY_EXPIRED
- **THEN** 该项同时显示两项原因和获准的日期，仍只占一个清单位置

#### Scenario: 只有适用期信号
- **WHEN** 文档复审状态为 OK，但其可读当前发布版本已届满
- **THEN** 清单显示 OK 的最小复审信息和适用期信号，不制造复审问题

#### Scenario: 管理信息不能补全标题
- **WHEN** 用户有全部雷达准入资格但无某文档阅读资格
- **THEN** 允许的管理项使用文档 ID 表示，不返回或补查标题和正文

### Requirement: 筛选与分页必须有界且先按全部资格取集合

请求 SHALL 只接受 signal（默认 ALL 或六类代码之一）、limit（默认 20，1–100）、cursor。清单先应用范围、当前授权和信号规则，再按 documentId 升序分页，以 limit+1 判断 hasMore。游标绑定调用者、空间、signal、limit、definitionVersion 和业务日期；响应包含 signal、limit、items、hasMore、nextCursor。不得先截取文档再删除无资格项，不允许任意排序、导出、搜索正文或客户端指定日期。

#### Scenario: 适用期可见集合稀疏
- **WHEN** 很多较小 ID 文档对操作者不可读，后面仍有满足适用期筛选的文档
- **THEN** 从真正合格集合取得当前页，正确计算下一页，不返回虚假末页或不可见占位

#### Scenario: 游标复用于其他范围
- **WHEN** 把游标用于另一用户、空间、信号或 limit
- **THEN** 返回 400，不返回其他范围的统计或文档

#### Scenario: 日期已经变化
- **WHEN** 当前业务日期与游标日期不同且操作者仍满足准入资格
- **THEN** 返回 409 RADAR_DATE_CHANGED，要求从首页重新查询，不拼接不同业务日期的页

### Requirement: 同次汇总与清单必须来自一致事实并明确跨请求变化

同一成功响应中的空间状态、授权、责任人资格、当前版本、日期声明、六类数量、并集及清单 SHALL 使用一致的已提交读取快照和同一时间锚点。下一次请求重新计算，不保留跨请求授权快照或历史诊断；游标不是冻结快照。每页返回当次汇总，新结果可因确认、发布、改派、搬迁或权限变化而改变，不承诺跨页历史完备性。

#### Scenario: 查询期间复审确认提交
- **WHEN** 另一事务在诊断读取期间提交确认
- **THEN** 同次数量和清单同时反映确认前或确认后的同一快照，不出现统计无命中而清单仍为命中的内部矛盾

#### Scenario: 两次请求间改变当前版本
- **WHEN** 第一页后发布了新版本，用户继续读取
- **THEN** 按新请求的当前版本与权限重新计算，不使用第一页固定的旧发布版本统计适用期

### Requirement: 雷达必须为只读诊断且处理动作重新授权

诊断 SHALL 只读取现有业务事实，不写入复审状态、适用期、处理任务或通知，不自动指定、改派、确认、延长日期、归档或修改文档。处理入口必须进入既有管理或文档详情页面，由目标页面重新核验实际动作资格；固定版本核对使用既有预览流程并重新授权。诊断信号及数量不构成文档失效、内容正确性、知识缺失或整改完成的判定，不提供健康评分。

#### Scenario: 管理员查看逾期项
- **WHEN** 非当前责任人的空间管理员打开逾期项
- **THEN** 可以进入既有管理流程，但不能以雷达资格代替责任人确认

#### Scenario: 处理后再次查询
- **WHEN** 责任人确认成功而同文档的当前发布版本仍已届满
- **THEN** 新查询移除复审原因，保留适用期原因和该文档，不把一次处理视为全部问题解决

### Requirement: 输入容量错误与读取故障必须明确且不缓存

入口 SHALL 拒绝非法空间 ID、未知/重复参数、未知信号、越界 limit、非法或超过 2048 字符的游标，返回固定安全 400。诊断完整读取预算为 6 秒，依赖或预算失败返回安全 503，不能返回部分统计、旧结果或假零。所有成功及失败响应包括网关拒绝均 no-store；日志及遥测不记录诊断项、身份、空间/文档/版本标识、责任人或原始异常，诊断不产生成功写审计。

#### Scenario: 任一必要查询失败
- **WHEN** 空间、身份、责任人、版本读取资格、统计或清单查询无法可靠完成
- **THEN** 整次返回 503，不将不可确认的适用期资格解释为正常无信号

#### Scenario: 客户端传入全局或归档参数
- **WHEN** 请求夹带 allSpaces、includeArchived、sort 或 export 参数
- **THEN** 返回 400，不扩大范围或静默忽略参数

#### Scenario: 网关提前拒绝
- **WHEN** 网关在请求到达诊断服务前拒绝身份或路径访问
- **THEN** 错误响应同样声明 no-store，不缓存历史诊断内容

### Requirement: 雷达响应必须具有完整固定结构

成功响应 SHALL 使用既有 code/message/data 包装，data 包含 definitionVersion、spaceId、asOf、asOfDate、dayTimeZone、signal、limit、counts、affectedDocumentCount、items、hasMore、nextCursor。counts 的固定字段为 stewardUnassigned、stewardNeedsReassign、reviewOverdue、reviewDueSoon、applicabilityExpired、applicabilityExpiringSoon。身份 ID 均为规范正十进制字符串，日期为 ISO 日历日期、asOf 为带偏移 ISO 时间；无下一页时 nextCursor 为 null，否则为非空游标。

#### Scenario: 响应结构与身份精度
- **WHEN** 合法诊断包含大于 JavaScript 安全整数的文档、版本或责任人 ID
- **THEN** 完整包装响应原样保留其十进制字符串，客户端后续链接不舍入或改写 ID

#### Scenario: 最后一页
- **WHEN** 本次范围内在当前页之后没有其他合格文档
- **THEN** hasMore 为 false 且 nextCursor 为 null，不生成无意义的下一页入口
