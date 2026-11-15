# Spec Delta

## Purpose

为整份文档指定一名维护责任人并设置复审周期，由当前合格的责任人定期确认资料仍然准确，并通过本人与空间管理员清单提示需要处理的资料，使复审职责可追踪，且不形成新的访问授权。

## ADDED Requirements

### Requirement: 每份文档至多一名当前责任人且责任人不构成授权

系统 SHALL 为每份文档保存至多一名当前责任人及其复审设置，初始状态为未指定。责任人 MUST NOT 从文档所有者、上传者、角色名称或任何既有字段推导。责任人身份 MUST NOT 授予文档阅读、编辑、下载或审核资格，也不影响检索、发布与审核流程。

#### Scenario: 上线后的存量文档
- **WHEN** 升级后读取任一既有文档的复审信息
- **THEN** 状态为 UNASSIGNED，没有责任人、周期或到期日，不从 owner 字段回填

#### Scenario: 责任人身份不扩大访问
- **WHEN** 被指定为责任人的用户随后失去文档 view
- **THEN** 该用户不能因责任人身份读取文档，复审状态按资格规则变为需重新指定

### Requirement: 责任人资格必须每次按当前事实判定

合格责任人 SHALL 当前启用、主部门启用、无需强制改密，密级不低于文档密级，并对该文档同时具有 view 与 edit（来自空间授权或文档例外授权）。每次读取、清单和确认 MUST 重新判定。当前责任人不合格时状态为 NEEDS_REASSIGN，系统不向任何人说明不合格原因，不自动改派，也不清除原关系。

#### Scenario: 责任人离职或停用
- **WHEN** 当前责任人被停用或被离职流程限制访问
- **THEN** 该文档状态变为 NEEDS_REASSIGN，从其本人清单中消失，其确认请求被拒绝
- **AND** 管理端只显示需重新指定，不显示停用、离职、撤权或降密等原因

#### Scenario: 资格恢复
- **WHEN** 曾不合格的责任人重新满足全部资格，且仍是该文档的当前责任人
- **THEN** 状态按到期日重新计算，文档重新出现在其本人清单中

### Requirement: 指定、改派和取消必须由当前空间管理员提交

`PUT /api/admin/documents/{documentId}/stewardship` SHALL 要求文档当前所属空间的 manage 或 `space:manage-all`，不要求文档 view。请求为 responsibleUserId、cycleMonths、expectedStewardshipVersion：指定或改派时 responsibleUserId 为用户 ID 且 cycleMonths 必填；取消时两者均为 null。提交时 MUST 重新核验目标用户资格，不合格返回 409 且不改变原关系。目标用户合格时，管理员可以指定本人。

#### Scenario: 候选在提交前失去资格
- **WHEN** 管理员选择的用户在提交前被停用或失去 edit
- **THEN** 返回 409 STEWARD_INELIGIBLE，原责任人、周期和到期日均不变

#### Scenario: 只有 edit 没有 manage
- **WHEN** 对文档有 view 与 edit、但没有空间 manage 或 `space:manage-all` 的用户提交指定
- **THEN** 返回 404，不透露文档的复审状态

#### Scenario: 管理员指定本人
- **WHEN** 合格的空间管理员把本人指定为责任人
- **THEN** 指定成功，此后该管理员按责任人身份确认，不因管理资格获得额外确认权

#### Scenario: 提交内容与当前一致
- **WHEN** 提交的责任人和周期与当前完全相同
- **THEN** 返回 409 NO_CHANGE，不产生成功变更或审计

### Requirement: 复审管理读取必须使用最小投影

`GET /api/admin/documents/{documentId}/stewardship` SHALL 对同等管理资格返回 documentId、spaceId、state、responsible（userId 与显示名，或 null）、cycleMonths、firstAssignedOn、lastConfirmedOn、dueDate、stewardshipVersion 和 asOfDate。响应 MUST NOT 包含文档标题、正文或责任人不合格的原因。不存在、已删除或不可管理统一返回 404。

#### Scenario: 需重新指定的文档
- **WHEN** 管理员读取当前责任人已不合格的文档
- **THEN** state 为 NEEDS_REASSIGN，响应保留原责任人的显示名，不含任何原因字段

### Requirement: 责任人候选必须最小投影且不借用身份目录

`POST /api/admin/documents/{documentId}/stewardship/candidates` SHALL 要求同等管理资格，只接受 nameQuery、limit、cursor。nameQuery 去除首尾空白后最多 64 个 Unicode code point，按显示名字面包含匹配；limit 默认 20、范围 1–100。响应只含当时合格用户的 userId 与显示名，按 userId 升序。接口 MUST NOT 要求或授予 `identity:user:read`，不返回用户名、部门、角色、密级或生命周期信息；游标绑定操作者、文档和查询条件。

#### Scenario: 无身份目录读取资格
- **WHEN** 空间管理员没有 `identity:user:read` 时检索候选
- **THEN** 可以取得合格候选的 userId 与显示名，不需要额外权限

#### Scenario: 不合格用户
- **WHEN** 某用户对文档只有 view、没有 edit，或密级低于文档密级
- **THEN** 该用户不出现在候选结果中

#### Scenario: 游标用于其他文档
- **WHEN** 客户端把一份文档的候选游标用于另一份文档或另一个名称条件
- **THEN** 返回 400，不返回候选

### Requirement: 复审周期与到期日必须确定计算

cycleMonths SHALL 只接受 3、6、12、24。到期日等于基准日加 cycleMonths 个月，目标月没有对应日期时取该月最后一天；基准日为最近确认日，从未确认时为首次指定日。首次指定日在文档第一次被指定责任人时记录，此后不因改派、取消或再次指定而改变。修改周期时按同一基准日重算；确认后以确认日为新的基准日。

#### Scenario: 月末日期
- **WHEN** 首次指定日为 2026-11-30，周期为 3 个月，且从未确认
- **THEN** 到期日为 2027-02-28

#### Scenario: 改派不重新计时
- **WHEN** 管理员把责任人从 A 改为 B，周期不变
- **THEN** 到期日不变

#### Scenario: 取消后再次指定
- **WHEN** 文档曾被指定、后被取消，之后再次指定责任人且周期不变
- **THEN** 首次指定日和到期日保持原值，不能借取消再指定推迟到期

#### Scenario: 修改周期
- **WHEN** 最近确认日为 2026-03-10，周期由 12 个月改为 6 个月
- **THEN** 到期日由 2027-03-10 变为 2026-09-10

### Requirement: 复审状态必须按业务日期在读取时计算

系统 SHALL 使用与适用期相同的 Asia/Shanghai 业务日期 T 计算复审状态，依次判定：没有当前责任人为 UNASSIGNED；当前责任人不合格为 NEEDS_REASSIGN；T 晚于到期日为 OVERDUE；到期日减 T 不超过 30 天为 DUE_SOON；其余为 OK。状态 MUST NOT 持久保存或由定时任务改写；未指定的文档如曾设置周期，仍返回其到期日。

#### Scenario: 到期日当天与次日
- **WHEN** 到期日为 2027-03-10，且 T 为 2027-03-10
- **THEN** 状态为 DUE_SOON
- **AND** T 为 2027-03-11 时状态为 OVERDUE

#### Scenario: 未指定优先于逾期
- **WHEN** 文档已被取消责任人，且其到期日早于 T
- **THEN** 状态为 UNASSIGNED，响应仍返回原到期日

### Requirement: 只有当前合格责任人可以确认资料仍然准确

`POST /api/documents/{documentId}/stewardship/confirm` SHALL 只接受 expectedStewardshipVersion，并要求 X-Request-Id。调用者 MUST 是该文档当前合格的责任人，且文档当前可读、未删除、未归档、所属空间未归档。成功后最近确认日为 T、确认人为调用者，并按确认日重算到期日。其他人员包括空间管理员和编辑者 MUST 被拒绝；发布新版本、修改资料或适用期都不视为确认。

#### Scenario: 编辑者代为确认
- **WHEN** 对文档有 view 与 edit、但不是当前责任人的用户提交确认
- **THEN** 返回 403，最近确认日和到期日不变

#### Scenario: 提前确认
- **WHEN** 状态为 OK 的文档由当前合格责任人确认
- **THEN** 确认成功，到期日按确认日重算

#### Scenario: 改派后旧页面确认
- **WHEN** A 已被改派为 B，A 仍用旧页面提交确认
- **THEN** 请求被拒绝，不产生确认记录

#### Scenario: 发布新版本
- **WHEN** 文档发布了新版本，但责任人尚未确认
- **THEN** 最近确认日和到期日均不变

### Requirement: 本人清单必须只列出本人当前负责且可读的文档

`GET /api/documents/stewardship` SHALL 接受 view（默认 attention，或 all）、limit（默认 20、范围 1–100）、cursor，只返回调用者为当前合格责任人且当前可读的文档。attention 只包含复审为 DUE_SOON 或 OVERDUE，或当前发布版本适用期为 EXPIRING_SOON 或 EXPIRED 的文档。每项返回 documentId、已授权标题、复审状态、dueDate、lastConfirmedOn、cycleMonths、提醒原因及当前发布版本的标识；按到期日、文档 ID 升序，先过滤再分页，不返回总数。

#### Scenario: 只因适用期届满而列出
- **WHEN** 文档复审状态为 OK，但当前发布版本的截止日在 20 天后
- **THEN** 该文档出现在 attention 视图中，提醒原因为当前版本适用期即将届满

#### Scenario: 没有当前发布版本
- **WHEN** 本人负责的文档尚无当前发布版本
- **THEN** 当前版本标识为 null，只按复审状态决定是否进入 attention 视图

#### Scenario: 改派后
- **WHEN** 文档从 A 改派给 B
- **THEN** 下一次读取时该文档从 A 的清单中消失，出现在合格的 B 的清单中

#### Scenario: 游标属于其他用户或视图
- **WHEN** 客户端使用其他用户或另一视图的游标
- **THEN** 返回 400，不返回结果

### Requirement: 本人提醒数量必须精确且与清单规则一致

`GET /api/documents/stewardship/count` SHALL 无参数，返回与 attention 视图相同规则下的文档数（精确十进制字符串）及 asOfDate。该数量表示当前需要处理的文档数，不是通知数，也不包含调用者无权读取或已不合格负责的文档。

#### Scenario: 确认后数量减少
- **WHEN** 责任人确认了一份 DUE_SOON 的文档，且其当前版本适用期不需要提醒
- **THEN** 下一次读取的数量减少 1

### Requirement: 空间管理员清单必须按状态有界列出 ID 级投影

`GET /api/admin/spaces/{spaceId}/stewardship` SHALL 要求该空间的 manage 或 `space:manage-all`，必需参数 state 为 UNASSIGNED、NEEDS_REASSIGN、DUE_SOON、OVERDUE 之一，limit 默认 20、范围 1–100，cursor 绑定操作者、空间与状态。范围为该空间全部未删除文档，含尚未发布首版的文档。每项只含 documentId、state、dueDate 和 responsible（userId 与显示名，或 null），不含标题；DUE_SOON、OVERDUE 按到期日、文档 ID 升序，其余按文档 ID 升序。

#### Scenario: 从未设置的文档
- **WHEN** 空间中有从未指定过责任人的文档
- **THEN** 这些文档出现在 UNASSIGNED 清单中，dueDate 为 null

#### Scenario: 读取过程中失去管理资格
- **WHEN** 管理员读取第一页后失去该空间 manage
- **THEN** 下一页返回 404，旧游标不延续读取资格

### Requirement: 删除、归档与搬迁必须保留复审事实而不扩大访问

文档删除后，复审记录 SHALL 保留，但不出现在任何读取或清单中，后续确认与修改均返回 404。文档或所属空间归档期间，本人清单不列出该文档，确认及管理端指定、改派、取消均被拒绝，管理读取与空间清单仍可用；取消归档后按原到期日继续计算。搬迁 MUST 保留责任人、周期和日期，资格按新空间重新判定。

#### Scenario: 搬迁后责任人缺少 edit
- **WHEN** 文档搬迁到责任人没有 edit 的空间
- **THEN** 状态变为 NEEDS_REASSIGN，到期日不变

#### Scenario: 取消归档后已逾期
- **WHEN** 空间归档期间到期日已过，随后取消归档
- **THEN** 文档状态为 OVERDUE，重新出现在合格责任人的清单中

#### Scenario: 归档空间中指定责任人
- **WHEN** 管理员在已归档空间中提交指定、改派或取消
- **THEN** 返回 409 SPACE_ARCHIVED，不改变复审设置

### Requirement: 复审写入必须严格绑定请求并原子提交

指定、改派、取消与确认 SHALL 要求 X-Request-Id（去除首尾空白后 1–128 个非控制 Unicode code point），绑定操作域、操作者、目标文档、预期复审版本与规范化载荷。匹配重试 MUST 先按当前资格重新鉴权，再返回首次回执且不重复变更；绑定不符返回 409。版本比较 MUST 在当前资格与目标可访问性检查之后进行：已失去责任人资格或访问权时按现有规则返回 403/404，不返回旧回执或版本信息；仍合格但预期版本过期时返回 409 STEWARDSHIP_VERSION_CONFLICT。复审设置、成功审计与回执 MUST 同事务提交，失败整体回滚。

#### Scenario: 旧责任人重放确认
- **WHEN** 已被改派的原责任人重放此前成功的确认请求
- **THEN** 按当前资格拒绝，不返回原回执

#### Scenario: 确认与改派并发
- **WHEN** 基于同一复审版本的确认和改派同时提交
- **THEN** 至多一个成功，失败方先按锁等待后的当前资格重新鉴权
- **AND** 确认先成功且管理员仍有管理资格时，改派返回 409 STEWARDSHIP_VERSION_CONFLICT
- **AND** 改派先成功时，原责任人的确认返回 403；目标已不可访问时按现有规则返回 404，不泄露版本信息

### Requirement: 复审入口必须有界并安全表达失败

所有复审入口 SHALL 保留登录、主体状态与强制改密门禁，成功与错误响应均标记 no-store。请求体上限 16 KiB，超限返回 413；拒绝未知或重复字段与参数、尾随 JSON、非法 Unicode、NUL、非整数身份和不在档位内的周期。依赖无法可靠读取时返回安全 503，不伪装为空清单、零数量或未指定。错误与日志 MUST NOT 回显显示名查询或底层异常。

#### Scenario: 清单依赖故障
- **WHEN** 资格或复审数据暂时无法读取
- **THEN** 清单和数量返回 503，客户端不显示为没有需要处理的资料

#### Scenario: 周期不在档位内
- **WHEN** 指定请求的 cycleMonths 为 1 或 9
- **THEN** 返回 400，不改变复审设置
