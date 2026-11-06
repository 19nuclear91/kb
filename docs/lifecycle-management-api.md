# 生命周期管理 API 与复职规则

基础路径为 `/api/admin/identity`，使用当前登录令牌。所有响应包含 `Cache-Control: no-store`，成功沿用 `R<T>`。四项权限独立判断，角色名称、普通用户管理资格和 SSO 绑定均不能替代下列 code。

| 接口 | 精确权限 | 用途 |
| --- | --- | --- |
| `GET /lifecycle/status` | `identity:lifecycle:read` | 配置、连接、应急资格及真实全局积压摘要 |
| `GET /users/{id}/lifecycle` | `identity:lifecycle:read` | 人工状态、最终状态、限制和版本 |
| `GET /lifecycle/events` | `identity:lifecycle:read` | 事件游标分页 |
| `GET /lifecycle/events/{id}/items` | `identity:lifecycle:read` | 原事件逐员工处置分页 |
| `GET /lifecycle/emergency` | `identity:lifecycle:read` | 登记保障和当前资格分页 |
| `POST /users/{id}/lifecycle/verification` | `identity:lifecycle:manage` | 按准确员工标识核验 |
| `PUT /users/{id}/lifecycle/mapping` | `identity:lifecycle:manage` | 明确纳管或变更映射 |
| `DELETE /users/{id}/lifecycle/mapping` | `identity:lifecycle:manage` | 无限制、无未解决事件及在途复职时退出 |
| `POST /lifecycle/events/{id}/retry` | `identity:lifecycle:manage` | 重试服务端原始事件 |
| `POST /users/{id}/lifecycle/restoration-check` | `identity:lifecycle:restore` | 核对当前事实并取得复职证明 |
| `POST /users/{id}/lifecycle/restore` | `identity:lifecycle:restore` | 再次核验后确认复职 |
| `POST /lifecycle/emergency/verification`、`POST /lifecycle/emergency` | `identity:emergency:manage` | 候选本人本地验证及登记/轮换 |

每次请求先检查当前会话、强制改密及精确权限，然后才查目标、回执或供应商。事务提交前再次检查操作者。撤权后，即使持有成功请求的原参数，也不能读取其回执。

## 请求、分页和反馈

核验映射请求含 `source: "dingtalk"`、配置中的 `connectionId`、准确 `employeeId`、用户 `version` 和 `mappingVersion`（尚无生命周期行时为 `-1`）。响应只返回显示名称、连接别名、版本以及五分钟有效的 `proof`/`expiresAt`。纳管请求含原 `verification` 对象、`proof`、`reason`、`requestId`；退出请求含两个版本、原因和请求标识。不会按姓名、手机号推断目标，也不会写 SSO 的 `sys_user_identity`。

重试事件请求只允许 `reason`、`requestId`，额外的载荷字段返回 400。路径中的事件 ID 是内部记录编号。读接口不返回钉钉员工 ID、企业标识、原始业务事件 ID、载荷、摘要、密码或确认凭据。事件列表可用固定 `status` 筛选；列表 `limit` 为 1–100，后续页传服务端 `nextCursor`，游标绑定查询及目标。`hasMore` 只表示下一页存在，不代表全局总数。

写请求 `requestId` 最长 128 字符、原因最长 500 字符。回执绑定操作者、操作、目标和规范化后的完整请求内容。同标识但内容不同返回 409；完全一致的重试返回原结果，不能借此重复推进代次或执行轮换。响应未知时保留原请求，管理员明确选择后才能重发，不自动生成新请求标识重试。页面隐藏、身份或目标切换、撤权时应清除本地敏感状态；此时通过读取最新事实核对结果。

| HTTP 状态 | 语义 |
| --- | --- |
| 400 | 参数、游标、范围或载荷字段无效 |
| 401 | 会话缺失/旧代次/失效，或本人本地凭据验证失败 |
| 403 | 缺少精确权限、当前身份不允许访问 |
| 404 | 鉴权后确认目标不存在或不可管理 |
| 409 | 版本、凭据、幂等绑定、当前状态或应急保障发生冲突 |
| 503 | 数据或平台依赖无法确认，不能作为未纳管、已恢复或成功停权 |

写接口失败使用安全固定消息和 `reasonCode`，不会回显底层异常。生命周期冲突细分 `LIFECYCLE_RESTRICTED`、`LIFECYCLE_CONFIRMATION_STALE`、`EMERGENCY_MANAGER_REQUIRED`；其他未知错误按依赖不可用处理。客户端不能依据原始异常文本执行恢复。

## 两步复职

1. `restoration-check` 请求 `{ "version": 1, "mappingVersion": 1 }`。服务端读取受管关系、当前限制、主部门、密级、角色与权限事实及事件边界，并在事务外查询钉钉当前成员。事务内重新核对相同事实，发放五分钟、一次性、绑定操作者与目标的证明。
2. 展示返回的 `employeeName`、`departmentId`/`departmentName`、`roles`、`clearanceLevel`、`manualEnabled`、`restriction`，管理员填写原因并明确确认。`restore` 请求含原 `version`、`mappingVersion`、`proof`、`reason`、`requestId`。
3. 服务端在事务外再次查询当前成员；随后取得与离职接收相同的管理锁、映射锁、用户锁，复核版本、全部事实和事件边界，消费凭据、解除限制、推进认证代次，原子写入审计和回执。已有匹配回执仍需重新鉴权，但无需再次访问供应商。

角色事实修订号来自该用户角色分配/解除及当前角色更名/权限变更的持久审计序号，并同时绑定角色和权限快照。这样即使角色改动后又恢复原值，旧证明也会失效。部门版本、用户版本、映射版本、强制改密、人工状态、密级及认证代次同样参与绑定。所有正常管理变更和复职共用管理锁。

事件边界包含已保存事件最大内部编号、数量、冲突数量以及未完成/待核对计数。新事件、原事件新增冲突或尚待处理的 UNMAPPED/FAILED 项都不能被一次旧确认覆盖。成员不可见、无效、查询超时、部门无效和密级不合法均拒绝；没有强制恢复入口，也不自动重试写入。

成功时仅解除生命周期限制，不改变人工状态、部门、角色、密级或强制改密要求：

- `RESTORED_RELOGIN_REQUIRED`：人工状态允许使用，必须重新登录。所有旧代次令牌永久无效。
- `RESTORED_MANUALLY_DISABLED`：离职限制已解除，账号仍被人工停用。后续人工启用是独立授权操作。

普通启用在存在限制时返回 `LIFECYCLE_RESTRICTED`，不能替代复职。成功复职保留原事件身份和最后事件引用，以第二次成员核验时间记录恢复边界；已经完成的 REVIEW 项记录解决时间，其他尚未消费的复职证明失效。

## 迟到与再次离职

同一已处理业务事件重复投递或人工重试只返回原处置，不再次推进代次。复职后的新有效离职事件再次停权；此前未见过、发生时间不晚于恢复核对时间的事件进入 REVIEW 并限制访问，等待人工再次核对。不能只依据较大时间戳解除限制。边界和幂等依据本期长期保留，关闭订阅也不会删除它们。

应急本人核验的字段与轮换规则见 [应急保障说明](lifecycle-emergency.md)，接收与可靠提交语义见 [事件处置](lifecycle-event-processing.md)。这些 API 和进程内测试验证领域规则，不替代生产跨实例的 30 秒停权证据。
