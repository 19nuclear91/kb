# Spec Delta

## ADDED Requirements

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
