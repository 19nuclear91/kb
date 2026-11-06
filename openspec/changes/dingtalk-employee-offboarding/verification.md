# 核验报告：dingtalk-employee-offboarding

## 2026-11-06 修复后复核

前次核验的 **W1–W4 均已修复**。本轮核对新增实现、关联证据计算、迁移与受控协议，并运行相关回归；修复范围内没有遗留的 CRITICAL/WARNING。下方首次核验记录保留作为问题来源，不代表当前仍存在这些缺口。

| 原问题 | 修复结果与主要位置 | 验证证据 |
|---|---|---|
| W1 输入异常不可见 | DingTalkInputRejected 固定类别、DingTalkEventParser/StreamProtocol/StreamConnection 分片与输入拒绝观测；LifecycleObservations 初始化低基数计数；KbLifecycleInputRejected | 协议和分片拒绝测试验证分类、无成功持久化及无原文日志；超限合成时序告警 |
| W2 完成事件冲突不告警 | Monitoring.Rules.psm1 增加 KbLifecycleEventConflict，读取 CONFLICT 计数，独立于积压；固定目录和通知模板同步 | completed-conflict 合成时序同时验证冲突触发、积压及依赖失败不触发；固定通知隐私测试 |
| W3 缺少接收与停止关联 | V35、OutputStopEvidenceMapper/Service/Controller、OutputStopWindow、OutputStopReporter；按事件、被撤销原代次及执行 UUID 保存关联，主库 checkedAt 配合 AI 单调时差计算停止/耗时区间 | 主库记录映射、签名操作/正文/nonce 隔离、幂等冲突、回滚不观察提交、原代次绑定、取消先于报告、缺失锚点、队列满、区间质量、采样有界和告警测试 |
| W4 摘要缺少来源 | UserLifecyclePanel.tsx:30 展示钉钉来源与连接标识；API 拒绝受管记录缺失连接标识 | jsdom 仅读权限测试：仅请求账号摘要，无写请求，无员工标识展示 |

证据口径：接收事务的主库提交前/后界限与停止区间形成可核验的耗时上下界，不比较 AI 与 app 的墙上时钟。关联下界超过 30 秒触发 KbLifecycleRevocationSlow；只有上界越界或证据不足触发不可确认。后补映射、未关联记录和缺失提交观测不计作首次已映射员工达标。提交后观测失败不推翻已提交停权；后台停止报告故障不延迟输出取消。有关协议和部署说明已更新，没有放宽 30 秒行为要求。

| 本轮检查 | 结果 |
|---|---|
| 后端回归白名单 | 57 个测试类、285 项通过，0 失败、0 错误、0 跳过；首次发现的请求体测试替身时序问题修正后，AI/通知模块已复跑通过 |
| 前端相关组件/API | 4 个文件、87 项通过 |
| 类型检查和生产构建 | 通过 |
| 监控离线验证 | 163 项配置行为检查；74 条规则语法检查；75 组主时序及 1 组可选 AI 场景通过 |
| OpenSpec strict validate / diff 检查 | 通过 |

后端最终执行证据按测试类取最近一次结果，来自临时目录 `kb-offboarding-fix-final.log` 与 `kb-offboarding-fix-ai-final.log`，没有将失败的旧运行计为通过。监控产物位于 `kb-offboarding-fix-final-monitoring-8c711507acb34893b9c31edc17941a92`。

新增 V35 迁移尚未在真实数据库执行；app/AI 的 checkedAt 与停止证据协议须协调发布，保持旧节点排空。遵循既定范围，本轮没有启动外部组件、服务或运行文档内容断言，没有执行真实跨实例验收。没有提交代码或归档。

## 首次核验记录（修复前，保留依据）

核验日期：2026-11-06。核验对象为当前工作区中的实现及本变更的 proposal、design、tasks 和 7 份增量规格，使用 `spec-driven` schema。本轮仅核验并保存报告，没有修改实现、任务勾选或执行归档。

## 结论

未发现 CRITICAL 问题，发现 **4 项 WARNING**，没有额外 SUGGESTION。核心停权、永久撤销旧会话、人工复职、应急资格保护与 AI 输出许可均有实现和进程内测试证据。49 项任务勾选完成不等同于规格全部满足；异常观测、时限证据和摘要展示仍有下述差异。

| 维度 | 核验结果 |
|---|---|
| 完整性 | 任务 49/49；39 项 ADDED/MODIFIED 要求均找到实现入口；无 REMOVED/RENAMED 要求；没有不可读或缺失的规划工件 |
| 正确性 | 已对照 39 项要求、104 个场景的代码与测试证据；W1、W2、W3、W4 所列行为未完整满足，不能计为全部通过 |
| 一致性 | 持久状态、锁顺序、认证代次、短期证明、精确权限和输出许可符合主要设计；D4/D10 的异常告警及耗时证据存在差异；未发现需要单独报告的代码模式偏离 |

约定范围内的任务完成、规格覆盖、实现映射、场景覆盖、设计遵循及代码模式检查均已执行，没有因工件缺失跳过的检查。按 verify 工作流，可带以下改进项进入归档评估；建议先修复 W1/W2，并处理 W3 的设计口径差异。此结论不是自动归档或真实环境验收通过。

## WARNING

### W1：范围外、损坏和超限事件未记录固定异常类别

- 规格依据：`specs/enterprise-account-lifecycle/spec.md:17` 的“接收到范围外事件”、`:65` 的“接收输入超过固定上限”；`design.md:76` 要求超限拒绝并告警。
- 实现位置：`kb-app/src/main/java/com/zhishiku/kb/app/auth/lifecycle/DingTalkStreamProtocol.java:43`、`:55`、`:104`；`DingTalkEventParser.java:22`、`:36`。
- 触发方式：企业/应用不匹配、非法必要字段、员工数量超限或消息超限。解析器抛出的异常在协议层直接转换成 LATER/断开；这些路径不会进入 `LifecycleReception.receive`，因而也不会增加其 FAILED 计数。协议层没有固定错误类别的日志、计数或其他异常记录。
- 影响：账号资格没有被错误修改，但接入可以持续拒绝事件而没有对应的异常分类证据；连接类告警也不能替代该记录。
- 建议：在解析/协议边界记录有限枚举的拒绝类别，并为超限接入既有受控告警流程；禁止记录原始员工、企业、载荷和凭据。扩展 `DingTalkStreamProtocolTest`，在原有拒绝断言之外验证分类计数；使用离线规则测试验证超限告警，无需启动平台或服务。

### W2：同键不同内容的冲突没有可靠的告警路径

- 规格依据：`specs/enterprise-account-lifecycle/spec.md:77` 明确要求“隔离该冲突并告警”。
- 实现位置：`kb-app/src/main/java/com/zhishiku/kb/app/auth/lifecycle/LifecycleObservations.java:34`；`kb-app/src/main/java/com/zhishiku/kb/app/auth/mapper/LifecycleInboxMapper.java:61`；`kb-app/src/main/java/com/zhishiku/kb/app/auth/lifecycle/LifecycleMetricsReader.java:29`；`ops/monitoring/scripts/Monitoring.Rules.psm1:90`、`:93`。
- 触发方式：首份事件所有员工均已 OFFBOARDED，清理完成后，再收到相同 eventId、不同内容。注册器保留首份依据并设置 conflicted，接收计数增加 CONFLICT；隔离 SQL 只将 UNMAPPED/FAILED 项改成 REVIEW，所以此例不会产生 REVIEW 积压。
- 影响：现有规则不读取 CONFLICT，依赖失败规则仅匹配 FAILED；该冲突既不触发积压告警，也不触发依赖失败告警。隔离与原限制有效，但“告警”部分未满足。
- 建议：增加或扩展固定冲突告警，直接覆盖冲突计数或持久冲突状态，不依赖逐员工项恰好处于待处理状态。同步固定通知目录/模板，并加入“原员工项全部完成且无积压仍告警”的合成时序用例。现有 `LifecycleReceptionTest.restartReplayAndSameKeyConflictPreserveFirstEvidenceAndDoNotRevokeAgain` 只证明幂等和依据保留。

### W3：接收至 AI 本地停止的证据尚未形成，当前指标是另一种耗时

- 规格依据：`specs/enterprise-account-lifecycle/spec.md:89` 要求保留可核验的接收、阻断与本地停止证据；`design.md:142` 要求接收至 AI 本地停止耗时。
- 实现位置：`kb-ai/src/main/java/com/zhishiku/kb/ai/auth/OutputPermitMetrics.java:80`；`kb-app/src/main/java/com/zhishiku/kb/app/auth/lifecycle/LifecycleObservations.java:31`；`ops/monitoring/scripts/Monitoring.Rules.psm1:88`。
- 当前行为：AI 记录从最近一次成功身份检查的请求开始至本地停止的保守上界，另有许可逾期指标；app 记录接收提交后的独立观测。两者没有关联具体撤销事实与执行停止的证据。该实现没有伪造精确耗时，`docs/lifecycle-operations.md:62`、`:68` 也诚实说明了这一限制，但设计仍要求不同的测量口径。
- 影响：当前指标可以发现许可逾期、提示 30 秒目标不可确认，不能给出指定离职接收至对应 AI 停止的可核验耗时。此项不表示短期许可已经失效，也不是要求补做被明确排除的集成测试。
- 建议：补齐受控的内部撤销/执行证据关联，并在不把用户或事件标识放入指标标签的前提下生成约定耗时；用可控时钟和跨服务调用替身核对因果关系。若决定首期仅交付保守上界，应明确修订设计和相关规格的证据口径，保留 30 秒行为要求，不能将上界当成精确实测。

### W4：账号生命周期摘要未展示受管来源

- 规格依据：`specs/admin-web-users/spec.md:27` 要求摘要区分受管来源。
- 实现位置：`kb-web/src/admin/lifecycle/UserLifecyclePanel.tsx:28`。
- 当前行为：Descriptions 展示管理类型、人工状态、生命周期限制、最终状态及更新时间/复职时间，未展示来源；读取模型已有 connectionId，只有映射对话框显示相关来源信息。只有读取权限的管理员无法通过摘要确认来源。
- 建议：在受管账号摘要展示固定的“钉钉”来源及允许展示的连接标识，不请求额外写权限接口或暴露原始员工标识；补充 jsdom 摘要展示用例。

## 要求与实现映射

以下表格用于定位，标题为规格要求的简称；“有实现”不代表没有上述差异。场景覆盖依据包括代码控制流、仓储/事务替身、可控时钟、协议测试及组件测试，不把测试数量当作场景数量。

### enterprise-account-lifecycle：11 项要求、28 个场景

| 要求 | 主要实现 | 测试/核验依据 |
|---|---|---|
| 独立且受限的接入边界 | DingTalkLifecycleSettings、DingTalkEventParser、DingTalkStreamConnection、LifecycleHttp | Settings/StreamProtocol/StreamConnection/Http 测试；异常记录缺口见 W1 |
| 独立映射和明确确认 | LifecycleMappingService.verify/map、LifecycleMappingPolicy、LifecycleProofService | MappingService/MappingPolicy/ProofService 测试 |
| 映射历史与禁止绕过 | LifecycleMappingService.requireMutable/exit、LifecycleMapper | 事件先到后补映射、受限退出、在途复职测试 |
| 可恢复的持久接收 | LifecycleReception.receive、LifecycleStateService.persist、V31 | Reception 的提交/回滚/重投/多员工测试；超限异常缺口见 W1 |
| 稳定事件身份与有界处置 | LifecycleEventRegistry、LifecycleRetryService、LifecycleInboxMapper | Registry/Retry/Reception 测试；冲突告警见 W2 |
| 三十秒访问撤销 | EpochSessionService、各入口身份检查、OutputPermitService | 会话/Office/入口/输出许可测试；关联证据见 W3 |
| 人工复职与授权独立 | LifecycleRestorationService.check/restore | RestorationService 的双重成员核验、事实变动、手工禁用、审计回滚测试 |
| 复职和迟到事件顺序 | LifecycleReception.process、恢复边界和一次性证明 | 旧事件、新事件、迟到 REVIEW、复职竞争测试 |
| 启停与应急保障 | LifecycleReadiness、ManagementInvariant、独立清理/恢复调度 | Readiness/ManagementInvariant/Retry/Cleanup 测试 |
| 回执与审计 | LifecycleAuthorizer、LifecycleReceipts、IdentityAuditService | 精确权限、撤权后重放、绑定冲突、事务失败测试 |
| 未知状态与成功区分 | LifecycleMetrics/Reader/Observations、OutputPermitMetrics | 指标采样、固定通知、合成时序测试；W1/W2/W3 补足观测差异 |

### user-authentication：6 项要求、13 个场景

| 要求 | 主要实现 | 测试/核验依据 |
|---|---|---|
| 会话绑定认证代次 | EpochSessionService、IdentityAccessState | 旧代次、缺失代次、停用/复职、重复限制测试 |
| 并发登录与新令牌 | EpochSessionService.issue、LocalAuthService、SsoSession、AuthService | 三种认证方式、候选签发后竞争、只清理候选测试 |
| 全部受保护入口核对当前事实 | EpochAuthenticationInterceptor、gateway/AI CurrentIdentityFilter、OfficeLoginAccess | app/gateway/AI 认证、预览、强制改密、401/403/503 测试 |
| 独立的服务认证 | AuthenticationSignature、InternalAuthenticationService、两侧 AuthenticationClient | caller、时间窗、nonce、令牌绑定、最小返回、失败关闭测试 |
| 清理不是撤销正确性前提 | SessionCleanupService、RedisSessionTokenIndex | 清理失败、租约恢复、旧任务不撤销新令牌测试 |
| 无代次旧会话拒绝 | EpochSessionService.recorded/check、协调升级文档 | 缺失可信元数据拒绝测试；旧节点排空为发布方案，未执行部署演练 |

### identity-department：5 项要求、14 个场景

| 要求 | 主要实现 | 测试/核验依据 |
|---|---|---|
| 人工状态与限制分离 | V29、IdentityAccessState、LifecycleStateService | 状态组合及回滚测试；静态评审迁移保留原 enabled |
| 普通启用不能代替复职 | IdentityService、LifecycleStateService、管理错误反馈 | 普通启用 409、复职保留人工停用测试 |
| 独立本地应急管理员 | ManagementInvariant、EmergencyIdentityFact、生命周期唯一行约束 | 权限跨角色并集、无效凭据/部门、角色名无效、纳管互斥测试 |
| 所有资格收缩路径与并发 | IdentityService、DepartmentService、ManagementInvariant 全局管理锁 | EmergencyMutationGuard、ManagementInvariant 的逐路径/交错测试；锁顺序静态评审 |
| 本人本地验证和轮换 | EmergencyManagementService | LOCAL/SSO/DEV 区分、他人拒绝、凭据变动、原子先建后换和回执测试 |

### enterprise-sso：2 项要求、6 个场景

| 要求 | 主要实现 | 测试/核验依据 |
|---|---|---|
| 保留手工停用和生命周期边界 | SsoSession 经 EpochSessionService 签发；SSO 成员核验与本地认证独立 | SsoSecurityService、EpochSessionService、配置独立性测试及调用路径评审 |
| SSO 绑定和纳管无隐含授权 | 生命周期映射表/服务独立于 SSO 绑定入口 | MappingService/MappingPolicy 测试及两个服务写入范围评审 |

### functional-authorization：3 项要求、9 个场景

| 要求 | 主要实现 | 测试/核验依据 |
|---|---|---|
| 四项精确权限 | Permission、LifecycleAuthorizer、管理控制器、前端 access | Authorizer、读写控制器、QueryService、前端访问矩阵 |
| 鉴权先于目标/外部/回执 | Mapping/Restoration/Emergency/Retry/Query 各入口 | 撤权后重放、无权不存在目标、provider 无调用等测试 |
| 一次性迁移与撤权持久性 | V32、AdminBootstrapRunner | 空库初始化、既有角色不补权、重复启动测试和迁移静态评审 |

### multi-turn-chat：5 项要求、16 个场景

| 要求 | 主要实现 | 测试/核验依据 |
|---|---|---|
| 新旧流式协议兼容 | ChatController、ChatService、AiObservedChat、ConversationPrivacyFilter | ChatController/ChatObservation/ChatRevocation/PrivacyFilter 测试 |
| 独立短时输出许可 | OutputPermitService | 请求发起时间、2 秒超时、迟到响应、代次/执行绑定测试 |
| 静默/暂停不能绕过期限 | Permit.tick/require/guard、最终 SSE 层 guard | 静默模型/占位、调度暂停、缓慢落库/结算、排队 done 测试 |
| 轮次执行随代次撤销 | V34、ConversationIdentityGate、TurnExecutionService | reserve/dispatch/seal/append/renew/finish、完成竞争和旧票据测试 |
| 受限取消和结算 | TurnExecutionService 的撤销收尾/恢复、MultiTurnChatService | CANCELLED、UNKNOWN/NOT_INVOKED、固定意图、失败恢复与禁止继续生成测试 |

### admin-web-users：7 项要求、18 个场景

| 要求 | 主要实现 | 测试/核验依据 |
|---|---|---|
| 部门和启停独立提交 | UsersPage、useAdminWrite、固定冲突反馈 | users/API 测试及生命周期冲突反馈测试 |
| 独立权限和最小摘要 | UserLifecyclePanel、LifecycleScope、LifecycleQueryService | 无读取资格不请求、失败不当未纳管、身份隔离测试；来源展示见 W4 |
| 明确目标和成员核对 | MappingDialog、employeeInput/verify API | 准确标识、输入变更、核验过期、冲突重新核验测试 |
| 复职核对与最终状态 | RestorationDialog | 部门/角色/密级/人工状态、503、不自动重试、未知结果原绑定测试 |
| 逐员工事件与安全重试 | LifecyclePage、事件/员工项 API | 当前页计数、未匹配、撤权、原请求未知结果测试 |
| 本人应急登记与轮换 | 应急对话框、EmergencyManagementService | 本人密码、先建后换、资格不足、成功重读测试 |
| 敏感状态作用域 | LifecycleScope、useAdminWrite、AbortController | 账号/目标/权限/可见性切换和迟到响应组件测试 |

## 本轮执行结果

| 检查 | 结果 |
|---|---|
| OpenSpec strict validate | 通过 |
| 后端相关单元测试白名单 | 50 个测试类、206 项，0 失败、0 错误、0 跳过 |
| 前端 4 个相关测试文件 | 86 项通过（jsdom/API mock） |
| TypeScript 类型检查 | 通过 |
| 前端生产构建 | 通过 |
| 监控配置行为检查 | 160 项通过 |
| promtool 离线规则检查 | 71 条规则语法通过，66 组主场景和 1 组 AI 未部署场景通过 |
| git diff --check | 通过，仅有已有文件的 CRLF 转换提示 |

后端分两批运行：第一批 37 类/148 项覆盖生命周期、会话、身份签名、AI 撤销及通知；第二批 13 类/58 项覆盖应急不变量、初始化、Office、SSO、入口配置、轮次与协议兼容。没有运行 Maven 全套测试。

测试日志位于本机临时目录：`kb-offboarding-verify-maven.log`、`kb-offboarding-verify-compatibility.log`；离线监控产物位于 `kb-offboarding-verify-ec8f7feb23a342888b694649ad3a8c7f`。这些是本次执行证据，不是需要提交的项目文件。

## 验证边界

遵循 tasks/design 中已经明确的范围：没有启动前端服务、业务服务、数据库、Redis、钉钉、模型、Office 或监控服务，没有运行文档标题/关键词内容断言，也没有执行数据库迁移、真实平台联调、真实并发或部署演练。迁移和锁顺序采用代码评审，行为采用进程内替身及可控时钟验证。

真实跨实例 30 秒达标、数据库并发行为和旧节点排空没有经过实环境验证；这些被明确排除的活动不作为本次任务缺失或新增归档条件。W3 指向的是应交付代码与设计的观测口径差异，独立于是否进行实环境验收。
