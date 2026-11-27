# Verify：knowledge-subscriptions-and-digests

日期：2026-11-27。使用 `openspec-verify-change`，按用户要求执行核验、修复 CRITICAL/WARNING、重新核验。当前为第二轮结论。

## 核验结论

| 维度 | 结果 |
| --- | --- |
| 完整性 | 47/47任务完成；8份增量规格的45项ADDED/MODIFIED需求均有实现对应；没有REMOVED/RENAMED项 |
| 正确性 | 已核对45项需求的实现及113个场景的代码分支、相关离线测试和继承回归证据；本轮补齐4项前端行为测试 |
| 一致性 | 实现遵循统一事务顺序、发布时上下文、原代次取消、固定业务周、动态授权与说明、三档通知契约及既有技术分层 |
| 当前问题 | CRITICAL 0；WARNING 0；SUGGESTION 0 |

结论适用于本次约定的代码审阅与独立离线验证范围。数据库锁、触发器、真实事务提交、权限SQL执行计划、跨实例行为、浏览器及前后端联调仍未验证；这些活动已明确排除，不作为新增验收任务。没有把替身测试计为数据库验证，也没有把113个场景宣称为113项端到端测试。

## 第一轮问题及关闭证据

| 编号 | 级别 | 发现 | 修复与复核 |
| --- | --- | --- | --- |
| W1 | WARNING | 订阅API等待响应头和响应体没有超时；请求一直不结束会使写入停在sending，无法核对回执或重试。对应用户端原意图恢复要求及任务8.3 | `kb-web/src/subscriptions/api.ts:47`增加15秒整体期限和取消竞争，覆盖响应头及响应体。写入超时进入结果未知，读取超时安全失败；保持原ID和载荷，取消底层请求，隔离迟到响应。`pages.test.ts:48`和`protocol.test.ts:49`修复前均失败，修复后通过 |
| W2 | WARNING | 原订阅专项未直接覆盖“取消结果未知→显式放弃→重读→新代次订阅”及“换号后迟到命令回执”两个场景 | `kb-web/src/subscriptions/pages.test.ts:117`验证重读完成前不能重新订阅、新请求使用最新版本且不修改旧取消载荷；`:146`使用不同用户会话验证旧请求被取消、私有目标及回执被清理、新身份无自动写入。两项均通过 |

第一轮未发现CRITICAL。第二轮重新检查上述问题、关联页面生命周期及全部增量规格，未发现剩余CRITICAL/WARNING，按用户要求停止循环。

## 需求与场景证据映射

下表的需求数和场景数来自本变更增量规格；测试名称可在仓库中定位。服务实现根目录为 `kb-app/src/main/java/com/zhishiku/kb/app/`，Mapper XML根目录为 `kb-app/src/main/resources/mapper/`。

| 规格 | 需求/场景 | 实现与核验重点 | 相关离线证据 |
| --- | --- | --- | --- |
| knowledge-subscriptions | 10/23 | `controller/subscription/SubscriptionController.java:53`、`SubscriptionInput.java:32`确认本人及严格协议；`service/impl/subscription/SubscriptionWriteServiceImpl.java:32`实现稳定目标、新代次、200条上限、取消和频率；`SubscriptionCommands.java:41`实现原请求锁与回执；`SubscriptionReadServiceImpl.java:36`隐藏不可用目标；`subscription/SubscriptionQueryMapper.xml:4`复用当前view/版本规则；`KnowledgeDerivationMapper.xml:42`按历史区间及原归属匹配 | SubscriptionWorkflowTest、SubscriptionAccessTest、SubscriptionReadTest、SubscriptionControllerTest、SubscriptionMappingTest、PermissionServiceTest、DocumentVersionAccessServiceTest、VersionAccessPurposeTest |
| knowledge-update-digests | 13/29 | `service/impl/subscription/KnowledgeDerivationTransaction.java:45`保存全部原命中；`:94`封口、`:107`等待整周匹配、`:131`按原代次与当前资格终结；`subscription/KnowledgeDerivationMapper.xml:18`反连接发现和有界租约，`:121`检查完整性；`KnowledgeQueryMapper.xml:19`共用授权，`:29`固定周报原成员，`:70`先过滤再分组；`KnowledgeUpdateReadServiceImpl.java:47`动态说明与安全原基准；KnowledgeCalendar、KnowledgeMetrics及调度配置落实时间和恢复 | KnowledgeDerivationTest、SubscriptionWorkflowTest、KnowledgeDeliveryTest、KnowledgeMappingTest、KnowledgeReadTest、KnowledgeUpdateControllerTest、KnowledgeSubscriptionSchedulingTest、DocumentDiffServiceTest、DocumentDiffControllerTest |
| document-review | 2/6 | `document/service/ReviewApprovalService.java:61`、`AutoPublicationTransaction.java:39`在原资源锁后获取顺序并原子保存上下文；`db/migration/V45__knowledge_subscriptions.sql:527`捕获原归属，`:554`拒绝新发布缺少协议。重放不再次获取发布顺序，派生开关不进入发布路径 | ReviewApprovalServiceTest、AutoPublicationServiceTest、KnowledgePublicationServiceTest；迁移约束经源码审阅，未执行数据库 |
| document-parsing | 2/5 | `kb-worker/src/main/java/com/zhishiku/kb/worker/parse/ChunkPublisher.java:191`仅实际AUTO发布保存上下文；`config/KnowledgePublicationSchemaCheck.java:14`拒绝缺失或不兼容协议；原索引、快照及租约门禁保留 | VersionedChunkPublicationTest、ChunkPublisherTest、ParsePublishIdentityTest、KnowledgePublicationSchemaCheckTest |
| document-change-notes | 1/6 | `service/impl/subscription/KnowledgeUpdateReadServiceImpl.java:47`调用固定版本ChangeNoteReadService；说明更正动态读取，基准不可读不投影其身份，通知不复制说明；原说明写入和索引路径未引入订阅发布 | KnowledgeReadTest、ChangeNoteReadServiceTest、ChangeNoteControllerTest及前端changeNotes/versionDiff回归 |
| user-notifications | 6/20 | `notification/service/UserNotificationService.java:57`按契约读取且复核来源；`service/impl/subscription/KnowledgeNotificationTargets.java:21`限制最小本人目标；`subscription/KnowledgeQueryMapper.xml:101`共用当前可见性；`UserNotificationMapper.xml`列表/未读数采用相同过滤；V45保留旧来源唯一键、首次已读并新增第三来源约束；周报按通知行计1 | KnowledgeNotificationTest、NotificationMappingTest、NotificationModelTest、UserNotificationServiceTest、GapNotificationTargetTest、GapNotificationVisibilityTest、CommentNotificationContractTest、ReviewNotificationVisibilityTest、NotificationControllerTest |
| user-web-subscriptions | 8/15 | `kb-web/src/pages/KnowledgePage.vue:12`身份及懒加载路由；`subscriptions/SubscriptionWorkspace.vue:13`候选、频率及本人取消；`hooks.ts:31`保存原意图，`api.ts:47`有界请求；UpdatesWorkspace、DigestsWorkspace、DigestMembers及UpdateWorkspace保留固定发布、周范围和人工说明；复用reviews/lifecycle清理私有内存 | subscriptions/pages.test.ts、protocol.test.ts、routing.test.ts、navigation.test.ts、versionDiff、changeNotes及reviews回归；本轮新增超时、重订阅和跨账号迟到回执证据 |
| user-web-notifications | 3/9 | `kb-web/src/notifications/api.ts`严格两类最小目标与三档契约；跳转固定updateId/digestId，通知导航和列表一致声明新契约；继承显式已读、单在途轮询及身份清理 | notifications/api.test.ts、page.test.ts、unread.test.ts、subscriptions/protocol.test.ts、routing/navigation及原审核、评论相关回归 |

数据库相关场景在本次核验中检查SQL定义、调用顺序、共享查询规则与替身故障处理；没有运行SQL或通过文本包含断言替代数据库行为。场景中涉及原审核、评论、问题或对比规则的部分复用既有实现和对应回归。

## 设计与结构复核

- 订阅、取消、频率、发布、周封口和提醒终结均调用统一order协议；发布三个入口沿用原资源锁，匹配不持有order，通知终结不反向获取资源行锁。
- 匹配使用发布时的空间/标签及历史代次，保存全部重叠依据；取消和新订阅不会改写旧更新。逐次来源须匹配DONE；周报等待覆盖周期完整后才登记收件人。
- 周报成员依原周、WEEKLY和dispatchPosition限定；当前权限过滤发生在分页/分组/计数前。全部不可见为隐藏目标，依赖故障保留503或待重试。
- 人工说明在详情读取时获取；对比保留原基准，基准失效不换为其他版本，首次发布和缺快照沿用既有状态。
- 新Java文件处于controller/service/mapper/domain/enums既有外层分层；Controller、响应泛型及公开模型有OpenAPI注解。已有Java规范、模型和模块边界测试已通过，本轮未改动Java实现。
- 本轮前端修复使用现有Session、AbortController及原意图模型，未增加持久状态、外部请求或业务自动重试。

## 执行证据

本轮执行：

```powershell
# 仓库根目录
openspec status --change knowledge-subscriptions-and-digests --json
openspec instructions apply --change knowledge-subscriptions-and-digests --json
openspec validate knowledge-subscriptions-and-digests --strict
git -c core.safecrlf=false diff --check

# kb-web目录
npx vitest run src/subscriptions
npx vitest run src/subscriptions src/notifications src/routing.test.ts src/navigation.test.ts src/versionDiff src/changeNotes src/reviews src/comments
npm run build
npm run bundle:report -- --check-isolation --out .bundle-analysis/knowledge-subscriptions-report.json
```

修复后的订阅专项21项通过；完整前端白名单23文件、265项通过，生产构建通过（包含`vue-tsc --noEmit`）。最新构建的包体隔离、OpenSpec严格校验和差异空白检查通过。apply再次返回47/47、remaining=0、all_done。后端实现本轮未改动，沿用本会话已完成的167项白名单、全模块测试编译和模型/架构检查证据，完整命令见[实施验证记录](verification.md)。

本报告记录verify阶段结果，当时未执行迁移、部署、提交、规格同步或归档。后续主规格同步与归档另见[归档记录](archive-record.md)，真实环境未验证范围保持。
