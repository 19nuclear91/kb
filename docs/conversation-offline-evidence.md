# 多轮会话离线验证证据

变更：multi-turn-conversation-history。2026-10-14 在 Windows/pwsh、JDK 21、Maven 离线模式实施。新功能默认关闭，无具体生产模型适配器时新入口返回
503。

完整白名单回归为 164 项（common 6、app 40、gateway 7、AI 111），全部通过；之后对严格 JSON、会话过期、来源确认重放和固定结算内容绑定执行补充检查
40 项，全部通过。新增用例包含在补充检查中，重复执行数不作为独立覆盖数。

## 实际验证范围

只使用纯 JDK、Mockito、Reactor StepVerifier/虚拟时间、mock ExchangeFunction、standalone MockMvc、mock JDBC 结果集、空配置上下文的
Binder/CORS 处理器、ArchUnit 和既有 JDK 语法树规范检查。不启动完整业务应用、监听 HTTP 端口、发送真实 HTTP、连接
DB/H2/Redis/MinIO、运行 Docker 或调用真实模型。没有新增文档内容/文件文字断言测试。

生产 XML 由真实 MyBatis 映射构建器加载，结果处理器消费 mock ResultSet，未开连接、执行 SQL 或模拟数据库来替代真实数据库证明。迁移与
Lua 仅人工审查；Lua 使用固定意图摘要及 INFLIGHT/DONE 标记，中途执行无法确认时阻止自动重放，不把未确认执行报为成功。固定摘要绑定轮次和固定时间；时间按
PostgreSQL 微秒精度规范化。

## 需求与场景对应

| 规格需求及主要场景                                                         | 离线证据                                                                                                                              |
|-------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| 可信个人归属：本人、重新登录、他人/管理员、停用、强制改密                                     | ConversationServiceTest、ConversationSessionTest、ConversationIdentityAndPrivacyTest；权限均来自服务端 gate                                  |
| 幂等创建：默认/显式标题、Unicode、匹配重放、不同内容、删除后、未知字段                           | ConversationServiceTest、ConversationControllerTest；真实序列化与 standalone MVC                                                          |
| 稳定且有界分页：固定创建游标、20/100、非法游标、追加后的不可变序号                              | ConversationServiceTest；生产 mapper 人工检查创建排序与正序 seq，未验证 SQL 执行                                                                      |
| 当前权限整体屏蔽：A/B 来源撤权、权限恢复、缺行、损坏身份、MODEL 空来源、权限故障                     | ConversationServiceTest、DocumentVersionAccessServiceTest；BLOCKED 整体投影及 503                                                        |
| 实际输入传递依赖：父轮并集、仅新引用、未选入材料、确认重放与冲突                                  | TurnExecutionServiceTest、ConversationBudgetTest；保存身份与最终 Plan 对应                                                                   |
| 版本与分块边界：历史版本、文档例外、当前权限变化、分块缺失不回退版本                                | DocumentVersionAccessServiceTest、DocumentCitationAuthorizationTest；真实授权 SQL 未执行                                                   |
| 未完成展示与后续排除：RUNNING/FAILED/CANCELLED/INTERRUPTED、保存前缀、COMPLETED 可见 | ConversationServiceTest、MultiTurnChatServiceTest                                                                                  |
| 原始输入与派生文本分别处理：问题不改写、屏蔽回答仍保留本人原问题、消息角色不提升                          | ConversationServiceTest、ConversationBudgetTest                                                                                    |
| 删除与迟到栅栏：本人重复 204、清空调用、活动取消、旧保存/完成/重放 404、保留账本                     | TurnExecutionServiceTest、ConversationControllerTest                                                                               |
| 敏感输出控制：公共投影、未知字段、固定错误、日志无正文/票据、no-store                           | ConversationControllerTest、ConversationIdentityAndPrivacyTest、MultiTurnControllerTest、SafeChatLoggingTest                         |
| 可信多轮入口：服务端历史、拒绝自带 messages/history/role/source/身份、预提交门禁           | MultiTurnControllerTest、InternalConversationSecurityTest                                                                          |
| 轮次幂等：相同请求不再生成/召回/领额、内容冲突、旧预期序号仍可匹配重放                              | TurnExecutionServiceTest、MultiTurnChatServiceTest、MultiTurnControllerTest                                                         |
| 同会话串行：活动轮次与旧 seq 拒绝、锁后插入编排、UUID 票据                                | TurnExecutionServiceTest；数据库单飞约束仅人工审查                                                                                             |
| 完整当前可用历史：所有非 COMPLETED 或 BLOCKED 排除、不把部分回答压缩成摘要                   | ConversationServiceTest、ConversationBudgetTest                                                                                    |
| 追问主题：任务租约→它过期后怎么办、无历史、查询上界、无材料不调用模型                               | ConversationBudgetTest、MultiTurnChatServiceTest、RetrievalClientTest                                                               |
| 上下文/输出预算：原问题不截断、整对移除、来源上限、Unicode、必需输入超限                          | ConversationBudgetTest、MultiTurnChatServiceTest；估算为确定性测试适配器，不证明真实供应商                                                              |
| 显式多轮契约：完整 role/materials/output 输入、无适配器/不支持估算 503、旧单轮保持           | ConversationBudgetTest、MultiTurnChatServiceTest、ChatServiceAdapterTest                                                            |
| 持久终态：最终保存后结算再 done、完成/取消顺序、结算故障保留 COMPLETED                       | TurnExecutionServiceTest、MultiTurnChatServiceTest；真实 PG 回滚/CAS 未执行                                                                |
| 批次顺序：同 seq 同摘要重放、不同正文/跳号、保存失败、Unicode 保存位置                        | TurnExecutionServiceTest、ConversationMappingTest、MultiTurnChatServiceTest                                                         |
| 断连：取消上游、有界前缀保存、CANCELLED、失败关闭不复活终态                                | MultiTurnChatServiceTest、TurnExecutionServiceTest                                                                                 |
| 持久过期：INTERRUPTED、释放 active、旧资格拒绝、不自动重启模型                          | TurnExecutionServiceTest、ConversationMaintenanceTest；后台组件未实际启动                                                                    |
| 当前部门及诚实用量：部门变化、未确认关联不调度、UNKNOWN/NOT_INVOKED、最后累计值、超预留             | MultiTurnControllerTest、MultiTurnChatServiceTest、UsageSettlementTest、ChatQuotaAuditServiceTest                                    |
| 固定费用重试：相同 lease/event/摘要、审计去重、异常、超过安全期 REVIEW、删除后可清理              | ConversationSettlementTest、ConversationMaintenanceTest；Redis/Lua 未执行                                                              |
| 独立内部授权：用户令牌不能代替签名、body/path/token 篡改、nonce 重放、另一轮票据、过期用户清理        | RequestSignatureTest、InternalConversationSecurityTest、ConversationSettlementTest、ConversationSessionTest、TurnExecutionServiceTest |
| 新旧流兼容：首次 SSE/重放 JSON、四事件、身份头、CORS、内部路由排除                          | MultiTurnControllerTest、MultiTurnChatServiceTest、ConversationRouteTest、旧 ChatControllerTest/ChatServiceLifecycleTest              |

## 白名单与构建

完整白名单为：ConversationSettingsTest、RequestSignatureTest、UsageSettlementTest、JavaSourceConventionTest、ConversationEnvironmentTest、ConversationMappingTest、ConversationServiceTest、ConversationControllerTest、ConversationIdentityAndPrivacyTest、InternalConversationSecurityTest、TurnExecutionServiceTest、ConversationMaintenanceTest、DocumentVersionAccessServiceTest、DocumentCitationAuthorizationTest、ModuleBoundaryTest、ConversationBudgetTest、ConversationAppClientTest、ConversationSettlementTest、MultiTurnChatServiceTest、MultiTurnControllerTest、ChatServiceAdapterTest、ChatServiceLifecycleTest、ChatControllerTest、ChatQuotaAuditServiceTest、ChatEventCitationTest、SafeChatLoggingTest、RetrievalClientTest、NoDatabaseAccessTest、ReactiveStackTest、ConversationRouteTest、CorsConfigurationTest、InboundHeaderSanitizeFilterTest、SaTokenGatewayConfigTest。补充检查增加
ConversationSessionTest，并重跑实际改动涉及的用例。未执行任何 IntegrationTest、PostgresSmokeTest 或文档示例。

执行方式为 `mvn -o -pl kb-app,kb-ai,kb-gateway -am test '-Dtest=<上述明确白名单>' '-Dsurefire.failIfNoSpecifiedTests=false'`
；相关模块执行 `mvn -o -pl kb-app,kb-ai,kb-gateway -am package -DskipTests`。OpenSpec 使用
`openspec validate multi-turn-conversation-history --strict`。本文件为人工证据记录，不由测试读取或断言。

## 未实测项

真实迁移、数据库约束/并发锁/事务回滚、Redis 脚本执行与故障原子性、多服务链路、网络/TLS 及生产模型语义未实测，均不属于本轮任务完成门槛。mock
编排成功不证明这些真实运行效果。Redis 灾难性丢失及 INFLIGHT 无法核对的账本需人工 REVIEW，不能换身份重新扣费。

