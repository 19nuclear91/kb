# Spec Delta

## ADDED Requirements

### Requirement: 批量授予必须在同一空间上整批生效

系统 SHALL 提供 `POST /api/admin/spaces/{spaceId}/acl/batch`，接受主体列表与权限位列表，对二者的笛卡尔积在该空间上执行授予。整批 MUST 在同一事务内完成：全部条目校验通过后才写入，任何一条校验失败时整批拒绝且 MUST NOT 写入任何授权行。已存在的授权条目 SHALL 被跳过而不视为失败。批量授予 MUST NOT 跨空间，也 MUST NOT 改变文档例外授权。

#### Scenario: 多主体多权限位整批授予

- **WHEN** 合格操作者对空间 S 提交主体 D1、D2 与权限位 view、download
- **THEN** S 上产生 D1-view、D1-download、D2-view、D2-download 四条授权
- **AND** 四条授权在同一事务中提交

#### Scenario: 部分条目已存在

- **WHEN** 批次中 D1-view 已存在，其余条目不存在
- **THEN** 其余条目被写入，D1-view 保持单条记录
- **AND** 整批返回成功

#### Scenario: 其中一条校验失败

- **WHEN** 批次中某个角色主体不存在，其余条目合法
- **THEN** 整批被拒绝，以与单条授予相同的错误说明原因
- **AND** 该空间的授权集合保持不变

### Requirement: 批量授予资格必须按整批中最严格的权限位判定

批次的权限位列表包含 manage 时，操作者 MUST 持有 manage 授出权限，整批按该资格判定；不包含 manage 时，操作者 MUST 在目标空间持有 manage 或持有跨空间管理权限。资格判定 MUST 基于服务端解析的主体，已停用用户或主部门已停用的用户 MUST 被拒绝。系统 MUST NOT 对同一批次中的条目分别放行。

#### Scenario: 空间管理员提交含 manage 的批次

- **WHEN** 仅持有空间 manage 的操作者提交权限位含 manage 的批次
- **THEN** 系统返回禁止错误
- **AND** 批次中不含 manage 的条目也不写入

#### Scenario: 空间管理员提交不含 manage 的批次

- **WHEN** 仅持有空间 S manage 的操作者对 S 提交权限位为 view、download 的批次
- **THEN** 操作被接受
- **AND** 无需跨空间管理权限或 manage 授出权限

#### Scenario: 已停用操作者

- **WHEN** 会话对应的用户已停用，或其主部门已停用
- **THEN** 系统拒绝该批次
- **AND** 不写入任何授权或审计事件

### Requirement: 批量授予请求必须有界且不含重复项

主体列表 SHALL 包含 1 至 50 个主体，每个主体由 principalType（USER、ROLE、DEPT）与 principalId 组成；权限位列表 SHALL 包含 1 至 6 个权限位，仅限 view、download、edit、review、comment、manage。任一列表为空、超出上限、包含重复条目或包含未被执行的权限位时，系统 MUST 返回参数错误并 MUST NOT 写入任何授权行。

#### Scenario: 主体重复

- **WHEN** 主体列表中同一 principalType 与 principalId 出现两次
- **THEN** 系统返回参数错误
- **AND** 不写入任何授权行

#### Scenario: 超出主体上限

- **WHEN** 主体列表包含 51 个主体
- **THEN** 系统返回参数错误并说明上限
- **AND** 不写入任何授权行

#### Scenario: 包含未被执行的权限位

- **WHEN** 权限位列表包含 share 或其他未定义的权限位
- **THEN** 系统返回参数错误并说明该权限位不被支持
- **AND** 合法的其他权限位也不写入

#### Scenario: 列表为空

- **WHEN** 主体列表或权限位列表为空
- **THEN** 系统返回参数错误
- **AND** 不产生审计事件

### Requirement: 批量授予必须幂等并只产生一条审计事件

批量授予 SHALL 要求请求标识。同一操作者对同一空间以相同请求标识重复提交时，系统 MUST 返回与首次成功一致的回执且 MUST NOT 再次写入授权或审计。重放前 MUST 核对审计记录的操作者、目标空间及快照空间；绑定缺失或不匹配时 MUST 返回 409，且 MUST NOT 返回原快照。每批成功 SHALL 产生一条独立操作域的审计事件，记录操作者、目标空间、每个条目的主体、权限位与是否已存在、时间与请求标识；审计 MUST NOT 包含会话令牌。整批失败时 MUST NOT 写入审计事件。

#### Scenario: 相同请求标识重试

- **WHEN** 同一批次因超时被以相同请求标识重复提交
- **THEN** 系统返回首次成功的逐条回执
- **AND** 授权集合与审计事件数量均不变

#### Scenario: 请求标识属于不同操作者或空间

- **WHEN** 合格操作者提交的请求标识已用于其他操作者或其他空间的批次
- **THEN** 系统返回 409，不返回原批次的主体、权限位或计数
- **AND** 不写入授权或审计；审计绑定缺失或快照空间不一致时同样拒绝重放

#### Scenario: 审计快照记录逐条结果

- **WHEN** 批次成功提交
- **THEN** 审计事件的变更后摘要包含每个条目的主体、权限位与已存在标记
- **AND** 事件可按目标空间与时间检索

#### Scenario: 批次被拒绝

- **WHEN** 批次因校验、资格或持久化失败未提交
- **THEN** 系统不写入成功审计事件
- **AND** 失败信息不泄露操作者无权知悉的主体存在性

### Requirement: 批量授予回执必须逐条标明已存在并汇总密级限制

回执 SHALL 包含每个条目的主体、权限位与 alreadyGranted 标记，以及新写入条目数、已存在条目数和 clearanceBlockedPrincipalCount。clearanceBlockedPrincipalCount SHALL 按主体而非条目计数，仅统计本批至少有一条新授权的主体。口径与单条授予相同：主体有有效成员，且空间内存在密级高于这些成员最高密级的已发布文档时计为受阻；无有效成员不计入。受阻表示至少有一篇文档因密级不可访问，不表示所有文档均不可访问。批量授予 MUST NOT 修改任何主体的密级。

#### Scenario: 部分条目已存在

- **WHEN** 四条条目中一条已存在
- **THEN** 回执中该条目 alreadyGranted=true，其余为 false
- **AND** 新写入条目数为 3、已存在条目数为 1

#### Scenario: 多个主体密级不足

- **WHEN** 批次包含 D1、D2 各两个权限位，且仅 D1 的全部成员密级低于空间内最高文档密级
- **THEN** clearanceBlockedPrincipalCount 为 1
- **AND** 四条授权仍全部写入

#### Scenario: 批次携带密级字段

- **WHEN** 请求体包含密级相关字段
- **THEN** 系统不依据该字段改变任何主体密级
- **AND** 授权结果与不携带该字段时相同
