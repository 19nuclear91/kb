# Proposal

## Why

空间授权目前一次只能授予一个主体的一个权限位：为新空间加入十几个部门，或给同一主体同时授予 view 与 download，都要逐条提交并逐条等待回执。前端 `useAdminWrite` 以"一次写入、一个请求标识、一个结果"为模型，循环发送多条请求会让页面同时面对多个待核对结果，没有可靠的交互形态。本变更在服务端提供同一空间上"多主体 × 多权限位"的原子批量授予，并把空间授权面板改为多选，使一次人工确认对应一个请求标识、一条审计和一份回执。

## What Changes

- 新增 `POST /api/admin/spaces/{spaceId}/acl/batch`：请求体携带主体列表与权限位列表，服务端对二者笛卡尔积执行授予；全部校验通过后才在同一事务内写入，任何一条校验失败则整批拒绝且不写入任何授权行。
- 批量授予沿用单条授予的资格规则：权限位含 manage 时整批要求 `space:grant-manage`，否则要求该空间 manage 或 `space:manage-all`；权限位限于 view、download、edit、review、comment 与 manage；角色主体须存在；主体与权限位列表不允许重复，主体数量有上限。
- 写请求必须携带 `X-Request-Id`，重复提交按既有审计幂等规则返回首次结果；新增审计操作域 `SPACE_ACL_BATCH_GRANT`，一批只产生一条审计事件，事件快照保存逐条授予结果。审计查询摘要为该操作域提供计数级投影，不逐条展开主体。
- 回执逐条标明已存在授权，并给出新写入数、已存在数与因密级当前不生效的主体数；密级口径与单条授予一致，授权不修改任何主体密级。
- 空间授权面板的授予改为：候选可多选并在切换主体类型或搜索条件后保留已选项，权限位改为复选，确认前展示主体数、权限位数与总条数，提交后按回执逐条呈现。文档例外授权面板保持单选单条，不受影响。
- 修正 `admin-web-spaces` 中权限位清单与实际不符之处：空间面板当前已提供 review 与 comment。
- 不在本次范围：批量撤销、文档例外批量授予、跨空间批量授予、修改既有单条授予与撤销接口、为 USER/DEPT 主体补充存在性校验、数据库迁移。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `space-authorization`：新增同一空间上多主体 × 多权限位的原子批量授予要求，包括资格判定、整批校验、幂等、回执与单条审计事件约束。
- `admin-web-spaces`：空间授权面板的授予交互改为多主体多权限位，新增确认摘要与逐条回执呈现要求，修正可选权限位清单。

## Impact

- 后端：`SpaceAclService` 新增批量授予；`SpaceController` 新增路由；`AuditScope` 新增操作域并在 `AuditSummaryProjector` 补充投影；新增批量请求与回执记录类型；`SpaceMapper` 视需要补充批量计数与写入。单条授予、撤销、ACL 表结构与权限目录保持不变，不新增迁移。
- 前端：`kb-web/src/admin` 的 `AclPanel.tsx`、`PrincipalPicker.tsx`、`spaceApi.ts` 类型与写意图，以及对应单元测试。文档面板复用的例外授权保持原有单选路径。
- 审计：`/admin/audits` 页面对新操作域显示计数级摘要；`docs/admin-query-api.md` 与 `docs/admin-space-document-web.md` 需同步说明。
- 部署顺序为后端先行：新前端只通过批量接口授予，遇到不支持该路由的旧服务时授予功能显示不可用，撤销不受影响；回退前端即恢复原单条授予交互。
