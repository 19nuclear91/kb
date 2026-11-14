# 知识问题单协调发布清单

这是发布顺序和止损边界的交付说明，不是已执行的部署记录。本变更仅执行离线单测、代码/配置检查及构建；未执行迁移、数据库约束/锁/回滚、SQL 计划、前后端联调或真实浏览器验收。下表各发布动作均未执行，也不属于本变更的外部验证待办。

2026-11-14 已完成主规格同步与[变更归档](../openspec/changes/archive/2026-11-14-knowledge-gap-feedback-loop/)，不改变下表发布动作的未执行状态。归档是变更流程收尾，不是部署授权、迁移执行或环境验收记录。

## 发布顺序：app → gateway → web

| 阶段 | 操作与检查 | 本轮状态 |
| --- | --- | --- |
| 准备 | 保留数据库备份、V36/Flyway 基线、现有通知身份/顺序/首次已读事实及权限绑定；准备仍认识新增目录和双来源结构的兼容回退版本 | 未执行 |
| 排空旧 app | 停止新流量并排空所有不认识新权限及收件箱结构的旧 app；不能与新版混部，不能绕过未知 code 启动拒绝 | 未执行 |
| 新版 app | 先更新全部 app，由 app 执行 V37/V38；Worker/AI 不执行迁移。迁移失败保持维护窗口，不用 repair、删绑定或禁约束绕过 | 未执行 |
| 新版 gateway | 普通问题根及子路径、admin 入口均到 app，不到 AI；保留 AI/SSE 边界。问题/通知提前拒绝及失败 no-store；列表/计数能力头严格单值处理并透传 | 未执行 |
| 新版 web | 先上传新哈希资产，再切换 HTML，保留旧资产；静态 history fallback 只用于 UI 路由，`/api/**` 错误不得回退 HTML | 未执行 |
| 权限配置 | 通过既有角色管理按职责显式分配 read/assign/handle/close，确保处理人启用、主部门有效且完成改密；问题资格不补文档 ACL | 未执行 |

V37 对当时已有 ROLE_ADMIN 仅一次性绑定四项 code；空库由正式引导使用完整目录，重启/重复引导不恢复已撤销权限。管理员名称不提供运行时豁免。

## 数据约束与兼容合同

- V37 核心问题、entry、event、recipient、command、actor_guard 独立存储。当前方案/队列信号使用单内复合引用及延迟约束；已分享记录保留，entry/event/recipient 不可变，command 绑定只能完成一次最小回执。
- V38 新增独立问题 projection；共享收件箱增加 `gap_event_id`，它与旧 `event_id` 恰有一个非空。保留旧协作收件人复合外键及唯一键，新增问题收件人复合外键及唯一键；类型与来源一致，来源和既有身份/创建位置不可改，已读只保留首次时间。
- 不回填旧通知，不重置 sequence、创建时间或已读；去重按来源命名空间/eventId/recipientUserId，不伪造文档身份，不让通知消费者修改源问题。
- 旧前端缺省契约继续只读旧四类/旧五字段目标/旧计数和旧游标。新列表与计数都发送单值 `X-Kb-Notification-Contract: KNOWLEDGE_GAPS_V1`；游标不跨契约，非法或重复声明为 JSON 400/no-store，不能自动去头降级。
- 问题目标只有 kind/gapId；进入详情重新核验当前参与范围及资料权限。派生、通知已读、资料发布和提问者确认解决是不同事实。

以上数据库约束仅交付 SQL 声明及离线结构证据，不宣称真实数据库已执行或拒绝过非法写入。

## 止损和回退

1. 先撤下新 UI 入口或回退到保留资产的旧静态前端。旧前端仍可使用缺省通知契约，但不展示知识问题；业务记录不会因此删除。
2. 若需停止新问题写入，在受控入口拒绝包括创建在内的九类修改 POST：集合创建、notes、return、solutions、confirm、reject-solution、withdraw、admin assign/close。这是运维入口策略，不是本功能已有配置开关；候选 POST 是只读，是否保留须单独明确。拒绝仍返回 API 错误且 no-store，不伪装成功或静态 HTML。
3. 保留兼容新版 app 对历史问题、当前权限和已有通知的安全读取。`kb.notification.derivation-enabled=false` 可停用两类通知调度以隔离派生故障，但不会停止问题源写入，不会删除提醒意图，也不能宣称已送达。
4. 只可回退到认识新权限、表和双来源收件箱的兼容二进制；否则保持维护窗口。不得反迁移 V37/V38、删表/记录、删除权限绑定、忽略未知 code、重置 projection 或通知已读来迁就旧 app。
5. 恢复时先核对当前角色、主体和源事实；恢复派生按原 lease/cursor/去重接管。未知结果不自动重发，不用原负责人旧回执延续访问。

问题 API、权限矩阵与排障见 [API](knowledge-gap-api.md)、[运维](knowledge-gap-operations.md) 和 [站内通知](in-app-notifications.md)。实际离线命令与结果见 [归档验证记录](../openspec/changes/archive/2026-11-14-knowledge-gap-feedback-loop/verification.md)。
