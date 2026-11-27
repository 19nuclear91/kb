# 知识订阅与周报实施验证

日期：2026-11-27。验证仅使用独立离线测试、模拟DOM、内存仓储/事务边界替身、模拟JDBC结果、实际MyBatis解析/参数绑定、编译和静态构建。未连接数据库、Redis、对象存储、模型或其他外部组件，未启动应用或HTTP测试服务器，未运行真实浏览器、前后端联调、迁移和部署。没有文档存在性、指定文字或章节内容断言。

## 实现及证据范围

| 范围 | 主要实现 | 离线证据 |
| --- | --- | --- |
| 顺序、代次及频率 | V45、PublicationOrder、SubscriptionWriteServiceImpl | SubscriptionWorkflowTest、SubscriptionMappingTest、KnowledgeDeliveryTest |
| 候选及本人接口 | SubscriptionAccess、SubscriptionQueryMapper、两个Controller | SubscriptionAccessTest、SubscriptionReadTest、SubscriptionControllerTest、KnowledgeUpdateControllerTest、共享权限/版本回归 |
| 三个发布入口 | ReviewApprovalService、AutoPublicationTransaction、ChunkPublisher | 审核/自动发布/Worker原回归、KnowledgePublicationServiceTest、KnowledgePublicationSchemaCheckTest |
| 匹配、周历和提醒 | KnowledgeDerivationTransaction及Mapper、KnowledgeCalendar | KnowledgeDerivationTest、SubscriptionWorkflowTest组合场景、KnowledgeMappingTest |
| 更新、周报和说明 | KnowledgeUpdateReadServiceImpl及共享SQL谓词 | KnowledgeReadTest、KnowledgeMappingTest、原说明/对比服务与接口回归 |
| 通知和网关 | 第三来源、最小目标、三档合同、显式业务路由及响应策略 | KnowledgeNotificationTest、原审核/评论/问题通知回归、KnowledgeGapRouteTest、KnowledgeSubscriptionResponsePolicyTest |
| 前端 | `/subscriptions`、`/updates`、`/digests`及固定详情，原意图恢复与新通知目标 | subscriptions、notifications、routing/navigation、versionDiff/changeNotes/reviews/comments白名单 |
| 调度与观测 | 两开关合取、独立阶段预算、低基数快照指标 | KnowledgeSubscriptionSchedulingTest、KnowledgeDerivationTest |
| 结构与模型 | 技术分层、精确请求/响应及Swagger泛型 | JavaSourceConventionTest、RecordOpenApiSchemaTest、ModuleBoundaryTest、全模块test-compile |

内存仓储实现存储合同，用于验证生产服务在原代次、频率、取消、重试和终态返回下的行为。共享SQL片段通过实际MyBatis展开和绑定验证复用关系；测试不执行SQL，不能据此认定真实权限查询、数据库锁、触发器、事务回滚、唯一约束或查询计划已运行通过。真实部署和联调不作为此次任务完成条件。

## 后端命令

从仓库根目录执行：

```powershell
mvn -o -pl kb-app,kb-worker,kb-gateway -am test '-Dtest=SubscriptionAccessTest,KnowledgeSubscriptionSchedulingTest,SubscriptionWorkflowTest,SubscriptionReadTest,SubscriptionControllerTest,KnowledgeUpdateControllerTest,SubscriptionMappingTest,KnowledgeMappingTest,KnowledgeDeliveryTest,KnowledgePublicationServiceTest,KnowledgeDerivationTest,KnowledgeReadTest,KnowledgeNotificationTest,KnowledgePublicationSchemaCheckTest,KnowledgeSubscriptionResponsePolicyTest,KnowledgeGapRouteTest,ReviewApprovalServiceTest,AutoPublicationServiceTest,VersionedChunkPublicationTest,ChunkPublisherTest,ParsePublishIdentityTest,NotificationMappingTest,NotificationModelTest,UserNotificationServiceTest,GapNotificationTargetTest,GapNotificationVisibilityTest,CommentNotificationContractTest,ReviewNotificationVisibilityTest,NotificationControllerTest,JavaSourceConventionTest,RecordOpenApiSchemaTest,ModuleBoundaryTest,PermissionServiceTest,VersionAccessPurposeTest,DocumentVersionAccessServiceTest,ChangeNoteReadServiceTest,ChangeNoteControllerTest,DocumentDiffServiceTest,DocumentDiffControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o test-compile '-DskipTests' '-Dstyle.color=never'
```

最终后端白名单 **167项全部通过，0失败、0错误、0跳过**：common 1项、app 146项、gateway 3项、Worker 17项。包含13项架构边界及4项OpenAPI模型检查。全模块测试编译已通过（10个reactor项目，包括父POM），未执行全量测试；明确排除会启动外部组件、监听HTTP端口或检查文档内容的测试。

## 前端命令

在 `kb-web` 执行：

```powershell
npx vitest run src/subscriptions src/notifications src/routing.test.ts src/navigation.test.ts src/versionDiff src/changeNotes src/reviews src/comments
npm run typecheck
npm run build
npm run bundle:test
npm run bundle:report -- --check-isolation --out .bundle-analysis/knowledge-subscriptions-report.json
```

verify修复后的最终前端白名单 **23文件、265项全部通过**，类型检查与生产构建通过，13项包体工具测试此前已通过。路由及固定提示已经与增量规格对齐，构建未启动应用或浏览器。最新构建的包体隔离通过：入口/登录累计gzip为135,769字节，文档列表242,131字节，knowledgeSubscriptions场景182,459字节（相对入口增量46,690字节）；订阅业务未提前进入入口/登录/文档列表静态依赖，普通与管理页面隔离通过。这些是逐文件压缩求和，不是实际网络耗时。`.bundle-analysis` 和 `dist` 是本地构建产物，不作为源码提交。

## 结构校验及修复记录

```powershell
openspec validate knowledge-subscriptions-and-digests --strict
git -c core.safecrlf=false diff --check
```

最终严格校验和差异空白检查已通过。`openspec instructions apply --change knowledge-subscriptions-and-digests --json` 返回total=47、complete=47、remaining=0、state=all_done。实施中发现并修复：Jackson泛型推断需显式JsonNode；新发布协议要求Worker旧测试夹具补齐order返回；跨Mapper共享SQL片段需要完整命名空间；三来源目标联合需收窄前端类型；Mockito重设已有异常/Answer使用doReturn/doThrow；请求标识保留原文并在规范化前拒绝控制字符；页面路径及固定提示与增量规格对齐。失败执行不计为通过，修复后的最终回归结果如上。

迁移V45已交付但未执行。实施与verify阶段未执行commit、push、部署、外部消息或归档操作；后续按用户授权完成的主规格同步、归档及清理见[归档记录](archive-record.md)。用户、API、运维和发布说明已更新，最终完成状态以tasks为准。

## Verify修复循环

按用户要求使用`openspec-verify-change`执行两轮核验。首轮发现请求缺少整体超时、两个前端恢复场景缺少直接测试，共2项WARNING，未发现CRITICAL。已增加15秒响应头/响应体整体期限，超时保留原命令并隔离迟到响应；新增超时重试、流停滞、取消未知后重订阅及换号迟到回执4项测试。修复前两个超时测试均失败，修复后订阅专项21项及完整白名单265项通过。第二轮CRITICAL=0、WARNING=0、SUGGESTION=0，按要求停止。完整规格映射和问题关闭证据见[Verify报告](verify-report.md)。
