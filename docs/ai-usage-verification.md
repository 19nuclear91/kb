# OpenSpec 验证报告：ai-usage-and-audit-query

日期：2026-10-24。Schema：spec-driven。验证依据为 proposal、design、tasks 和四份 delta，范围为本地实现及约定的离线证据。首次验证发现 C1、W1–W4；用户随后授权修复，本报告已更新修复后的复核结果，并保留修复前的复现证据。四份规格已同步到主规格，变更已于同日[归档](../openspec/changes/archive/2026-10-24-ai-usage-and-audit-query/proposal.md)，34/34项任务完成。

## 结论与评分

首次发现的 **1 项 CRITICAL、4 项 WARNING 均已修复**，本次范围内没有遗留问题。后端明确白名单149项、前端明确白名单190项全部通过，TypeScript、静态构建及严格 OpenSpec 结构校验通过。原29项任务及新增5项修复任务均已落实；任务勾选和结构校验不替代下述回归证据。

| 维度 | 检查结果 |
| --- | --- |
| Completeness | 原29项及新增5项验证修复均落实；27/27个新增需求定位到实现；C1 已同步并纳入白名单。 |
| Correctness | 61个规格场景沿用已有证据；C1、W1–W4 均补充离线回归，后端149项和前端190项通过。 |
| Coherence | V22 清理界限、独立心跳事务、领取前主体复核和终态续期已与 design 第10节一致；模块分层与 AI 无数据库架构检查通过。 |

全部适用的任务、需求映射、场景、设计和代码模式检查均已执行，没有因证据缺失跳过的上述检查。四份 delta 全为 ADDED：共27个需求、61个场景，无 REMOVED/MODIFIED/RENAMED 项需要额外基线恢复。

真实迁移、数据库SQL执行、真实锁/提交顺序/回滚/多实例竞态、Redis、模型、HTTP服务和浏览器运行效果按本变更的明确约定属于范围外，不是新增归档前置条件。本报告没有将内存替身结果宣称为真实环境验证。

## 修复结果与复核证据

| 问题 | 修复行为 | 离线回归 |
| --- | --- | --- |
| C1 | 权限目录预期改为23项，明确检查两项 AI 权限，保留唯一 code 与用途约束。 | PermissionCatalogTest 1项、RoleManagementControllerTest 9项通过，均纳入最终白名单。 |
| W1 | V22 保存单调历史清理界限，和删除同一事务提交；用量与审计查询共同判定，延长保留期不能抹掉缺口。已有采集区间的升级库保守标记到迁移时刻。 | AiUsageReadTest 的短保留期后延长、双查询及边界用例；AiCollectionServiceTest 的删除/界限失败用例；AiUsageMappingTest 的 Instant 映射；AiObservationTransactionTest 的模拟原子提交/回滚。 |
| W2 | 在领取订阅时再次解析当前主体，与登记用户/部门精确比较；明确拒绝不领取、不调度，保留 NOT_INVOKED / NOT_REQUIRED。 | ChatQuotaAuditServiceTest 的用户变化、部门变化、停用、空主体和不变主体用例；AiObservedChatTest 的拒绝不调度。 |
| W3 | FINALIZED 确认后停止观测续期；在途拒绝等待确认结果；配额续期单独运行并保持原释放规则。 | AiObservedChatTest 的慢释放、慢 SETTLED、慢 FINALIZED、在途续期、确认失败和生成中真实续期失败；成功返回 done，错误仍失败关闭，模型和释放不重放。 |
| W4 | 独立服务以 REQUIRES_NEW 先提交心跳，区间同步及清理在互斥锁后复核配置。 | AiObservationTransactionTest 使用真实 Spring 拦截器及内存提交队列，验证两个不同策略实例持续冲突至121秒仍可见、外层回滚不撤心跳，两方清理保持拒绝。 |

最终后端32类、149项：common 3、app 90、gateway 1、AI 55，0失败、0错误、0跳过。前端13文件190项通过，typecheck、build通过；OpenSpec严格校验 valid=true、issues=[]。实际命令和排除范围见 [验证记录](ai-usage-validation.md)。后续仅更新任务勾选和说明文档，不变更已验证代码。

V22 与新增 SQL 仅静态审阅和无连接映射验证，没有运行迁移；事务传播测试使用内存替身，不证明真实数据库的锁、隔离、回滚和多实例并发。下列 C1、W1–W4 章节保留的是首次验证的修复前状态和诊断，不能作为当前仍有问题的结论。

## 首次验证 CRITICAL（已修复）

### C1：新增权限后，既有权限目录单元测试确定失败

位置：[PermissionCatalogTest.java:11](/D:/tmp/kb/kb-app/src/test/java/com/zhishiku/kb/app/auth/PermissionCatalogTest.java:11)，新增目录项见 [Permission.java:23](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/model/Permission.java:23)。

`Permission.codes()` 已包含23项，原测试仍断言 `hasSize(21)`。本轮按明确名单单独运行，得到 `Expected size: 21 but was: 23`，该 Maven 检查退出码为1。同次执行的 RoleManagementControllerTest 共9项通过，说明目录接口可以返回新权限，失败点是未同步的既有回归约束。

建议：更新目录测试的预期集合/数量及方法名称，明确包含 `ai:usage:read`、`ai:audit:read`，继续检查唯一、精确 code、用途和拒绝通配；将该纯代码单测纳入相应离线白名单后重跑。不要通过移除新权限或删除有意义断言消除失败。此测试直接读取 Permission 枚举，不属于文档内容测试。

关联任务：1.4、8.1。当前任务记录仍为已勾选，本轮未重写其状态。

## 首次验证 WARNING（已修复）

### W1：保留期延长后，已经清理的历史可能被误报为完整

位置：[AiUsageReadService.java:103](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/aiusage/service/AiUsageReadService.java:103)，尤其124行；删除路径见 [AiCollectionService.java:56](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/aiusage/service/AiCollectionService.java:56)，区间 SQL 见 [AiUsageMapper.xml:125](/D:/tmp/kb/kb-app/src/main/resources/mapper/AiUsageMapper.xml:125)。

复现条件：先按1天保留期完成清理，之后将保留期延长至180天，查询最近7天。两个采集区间连续，旧区间的 retentionDays 为1，新区间为180。当前判定仅使用区间连续性和当前180天，忽略历史清理边界，也没有持久化清理覆盖水位。

进程内直接调用真实查询服务、使用 Mapper 返回值替身，实际得到 `historyMayBeIncomplete=false`。已经删除的第2–7天数据不能因延长配置恢复，页面却会隐藏历史不完整提示。

建议：在清理事务中持久化最小覆盖/清理界限，查询时与采集区间和当前保留期共同判定；延长期限不能消除既有缺口。为“缩短并清理，再延长保留期”的汇总、审计覆盖元数据增加离线回归用例。

依据：ai-usage-audit 的“查询必须公开覆盖范围与历史缺口”，admin-web-ai-usage 的“页面必须明确采集覆盖与保留范围”，design 第7/9节。当前已有旧窗口和停采测试，缺少历史保留策略变更后的此分支。

### W2：单轮领取配额前未复核登记后的主体变化

位置：[AiObservedChat.java:65](/D:/tmp/kb/kb-ai/src/main/java/com/zhishiku/kb/ai/observation/AiObservedChat.java:65)、[ChatQuotaAuditService.java:161](/D:/tmp/kb/kb-ai/src/main/java/com/zhishiku/kb/ai/chat/ChatQuotaAuditService.java:161)。

首次登记会复核主体，但 `acquireConfirmed` 后续只检查传入ID为正，直接使用登记返回的主体进入领取流程，没有重新解析当前 token 并比较绑定。登记与领取间还包含 ACQUIRE_INTENDED 网络提交及重试，存在主体被停用、会话失效或移部门的时间窗口。

内存诊断将登记主体设为用户1/部门2，将当前主体解析替身设为用户1/部门3；实际 `resolveCalls=0`，返回租约仍归部门2。此诊断没有连接或执行真实 Redis。

建议：在实际领取前复核当前启用主体，与登记的 userId/departmentId 精确比较；变化时停止领取和模型调度，不切换部门继续执行，保留安全的未调用事实。补充移部门、停用及会话失效的单轮离线测试。

依据：design 第3节明确要求“领取前主体变化导致绑定不能核对时停止，不换用户或部门继续生成”，第4节要求贯穿同一可信主体。多轮 reserved 已有部门不匹配拒绝测试，单轮没有等价保护。

### W3：生成终态提交后仍续期，可把成功收尾变成 SSE error

位置：[AiObservedChat.java:85](/D:/tmp/kb/kb-ai/src/main/java/com/zhishiku/kb/ai/observation/AiObservedChat.java:85)、[AiObservedChat.java:118](/D:/tmp/kb/kb-ai/src/main/java/com/zhishiku/kb/ai/observation/AiObservedChat.java:118)、[AiUsageWriter.java:206](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/aiusage/service/AiUsageWriter.java:206)。

FINALIZED 确认后，服务端调用已经终结、leaseUntil 被清空，renew 必须拒绝。但生成端的观测续期流仍与业务流合并，一直运行到 done/error；done 又要等待原额度释放和 SETTLED，因此慢收尾期间会主动触发对终态续期。

虚拟时间诊断使用合法的1秒续期、1.5秒成功额度释放：FINALIZED 和 SETTLED 都成功，实际输出 `finalized=true, settled=true, SSE=[error]`，预期应为 `[done]`。复现使用真实 AiObservedChat、服务替身和虚拟时间，没有模型/网络/Redis。

建议：在确认 FINALIZED 后停止观测租约续期，或明确区分本地已知终态的预期拒绝与真正失联；有需要的原配额续期应单独处理，避免扩大 Redis 重试。新增慢释放、慢确认和终态续期交错的受控流测试。

依据：design 第4节正常终止和有界收尾、任务3.3/3.4及原单轮 SSE 兼容要求。当前测试覆盖生成中续期和取消，没有覆盖终态已保存但收尾尚未结束的交错。

### W4：配置冲突会回滚本实例心跳，过期后可能错误恢复清理

位置：[AiCollectionService.java:38](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/aiusage/service/AiCollectionService.java:38)、[AiUsageMaintenance.java:56](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/aiusage/service/AiUsageMaintenance.java:56)，心跳有效性查询见 [AiUsageMapper.xml:151](/D:/tmp/kb/kb-app/src/main/resources/mapper/AiUsageMapper.xml:151)。

心跳更新和一致性判定处在同一事务；发现冲突后抛出的 ApiException 会回滚刚写入的心跳。定时任务仅捕获异常，不停止该实例承接HTTP请求。两个不同策略的活实例若已经登记，持续冲突会使旧心跳最终过期，先刷新成功的一方随后看不到仍存活的另一策略。

内存事务替身给定两个已登记的活实例、分别保留1天和180天，模拟第30/60秒冲突回滚与第91秒重试，实际得到 `shortPolicyConsistent=true, longPolicyHeartbeatExpired=true`。这表明清理保护可以重新放行短策略，而长策略实例仍在模型中存活。

两个不同策略初始并存的可能性来自静态顺序：一致性检查在采集区间互斥锁之前，两个并发启动事务可能在对方提交前各自检查成功。这一实际数据库并发时序未运行，本条运行证据仅为明确初始条件下的内存事务分支复现。

建议：让活实例策略声明通过独立、先提交的心跳事务保持可见，并在取得策略互斥锁后复核；配置冲突期间应持续阻止清理，不能以维护事务回滚使冲突实例从视图中消失。增加两实例心跳回滚/租约到期的内存事务替身用例，无须连接真实数据库。

依据：design 第7/9节及任务6.2“配置不一致拒绝清理”。当前单测仅令 inconsistentInstances 返回1，未模拟冲突心跳的回滚与到期。

## 需求与场景证据矩阵

以下按规格原顺序覆盖27个新增需求及其61个具名场景。“有证据”表示已检查代码及对应单元测试或共用分支，并不把多个独立替身测试当作端到端证明。W1–W4 是进一步检查设计边界发现的问题；不能因基础场景有证据而忽略它们。

路径约定：后端测试简称指向各模块 `src/test/java/com/zhishiku/kb/{app|ai|common}` 对应包；前端测试均位于 `kb-web/src/admin`，不代表额外执行未列入白名单的测试。

### ai-usage-audit：15个需求、31个场景

| 需求 | 实现定位 | 全部具名场景及测试证据 |
| --- | --- | --- |
| 聊天观测必须具有明确采集范围 | [单轮入口](/D:/tmp/kb/kb-ai/src/main/java/com/zhishiku/kb/ai/observation/AiObservedChat.java:36)、[多轮登记](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/conversation/service/TurnExecutionService.java:85) | 多轮请求重放：TurnExecutionServiceTest:117、AiUsageWriterTest:128；配额拒绝和无结果：AiObservedChatTest:105、ChatObservationTest:18、AiMultiObservationTest:80。 |
| 内部采集必须独立验证服务身份 | [签名入口](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/aiusage/web/AiObservationController.java:94)、[主体与固定收尾](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/aiusage/service/AiObservationService.java:29) | 用户伪造调用记录：AiObservationControllerTest:73；会话过期后的固定收尾：AiObservationServiceTest:38、AiObservationControllerTest固定事件用例；会话关闭时独立装配：AiObservationConfigurationTest。另见 W2。 |
| 已启用采集的调用必须可持久核对 | [写服务](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/aiusage/service/AiUsageWriter.java:99)、[客户端固定事件](/D:/tmp/kb/kb-ai/src/main/java/com/zhishiku/kb/ai/observation/AiObservationClient.java:127) | 登记或调度意图响应丢失：AiObservationClientTest:36、ChatObservationTest:29；终结事件重复或冲突：AiUsageWriterTest:66。 |
| 真实用量必须与额度扣除分别表达 | [usage协议](/D:/tmp/kb/kb-common/src/main/java/com/zhishiku/kb/common/aiusage/AiObservation.java:95)、[单轮观测](/D:/tmp/kb/kb-ai/src/main/java/com/zhishiku/kb/ai/observation/AiObservedChat.java:101) | 有最终零用量：AiObservationTest、AiObservedChatTest:52；模型调用后取消：AiUsageWriterTest:93、TurnExecutionServiceTest:204；未调用模型的回答：AiObservedChatTest:68、ChatObservationTest:18。 |
| 观测必须保留既有结算规则 | [原单轮规则调用](/D:/tmp/kb/kb-ai/src/main/java/com/zhishiku/kb/ai/observation/AiObservedChat.java:111)、[多轮事实投影](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/aiusage/service/AiMultiObservation.java:50) | 两种入口都缺少 usage：AiUsageWriterTest:93、AiMultiObservationTest:62、ConversationSettlementTest；结算成功后仅审计确认失败：AiObservationClientTest固定重试、AiObservedChatTest:85的副作用次数约束；慢确认交错另见 W3。 |
| 审计事实必须追加且与业务正文隔离 | [安全视图及追加](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/aiusage/service/AiUsageWriter.java:248)、[删除末尾投影](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/conversation/service/TurnExecutionService.java:394) | 用户转部门或删除会话：AiMultiObservationTest部门绑定、TurnExecutionServiceTest:96/226；终态后收到旧回调：AiUsageWriterTest:93/107/142、TurnExecutionServiceTest:204。 |
| AI 查询必须使用当前独立读取资格 | [读取鉴权](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/aiusage/service/AiUsageReadService.java:56)、[共用当前资格](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/service/AdminQueryAccess.java:48) | 仅获用量统计权限：AdminQueryAccessTest:32；翻页前撤权：共用 AdminQueryAccess 当前权限分支及 AdminQueryAccessTest:50 的同会话撤权用例，未缓存查询资格。 |
| 查询条件与时间窗口必须有界 | [专用查询解析](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/aiusage/service/AiUsageQuery.java:41)、[AND过滤SQL](/D:/tmp/kb/kb-app/src/main/resources/mapper/AiUsageMapper.xml:54) | 组合查询与未知用户：纯ID过滤、不关联当前用户存在性，AiUsageQueryTest、AiUsageReadTest分组替身；无界或非法时间：AiUsageQueryTest:32、aiUsageApi.test.ts:22。 |
| 用量汇总必须按调用去重并公开统计口径 | [单表调用聚合](/D:/tmp/kb/kb-app/src/main/resources/mapper/AiUsageMapper.xml:63)、[无损转换](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/aiusage/service/AiUsageReadService.java:129) | 已知和未知用量共存：AiUsageReadTest:43/92可手算返回值；多个审计事件对应一次调用：AiUsageWriterTest:66及聚合只读调用表的静态证据。没有执行聚合SQL。 |
| 查询分页与汇总必须说明一致性边界 | [首读上界](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/aiusage/service/AiUsageReadService.java:79)、[事件分页SQL](/D:/tmp/kb/kb-app/src/main/resources/mapper/AiUsageMapper.xml:109) | 迟到事件与相同时间：AiUsageReadTest:111、AiUsageQueryTest:18；分组读取期间完成结算：AiUsageReadTest:92、aiUsageQuery.test.tsx“每页独立asOf”。仅核对替身参数及静态SQL。 |
| 查询响应必须采用最小安全投影 | [安全快照投影](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/aiusage/service/AiUsageReadService.java:140) | 元数据载荷夹带敏感字段：AiObservationControllerTest:94、AiUsageReadTest:64；不可访问或已清理的事件：AiUsageReadTest:56、AiUsageControllerTest错误分类、aiUsageQuery详情404。 |
| 故障与未完成采集必须可辨认 | [恢复器](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/aiusage/service/AiUsageWriter.java:216)、[公共错误映射](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/query/web/AdminQueryAdvice.java:35) | 进程在模型调用后退出：AiUsageWriterTest:107/142及原Turn恢复用例；查询数据库不可用：AiUsageControllerTest:44、AdminQueryControllerTest服务异常替身。正常收尾误触发错误见 W3。 |
| 保留期必须可配置且默认一百八十天 | [配置](/D:/tmp/kb/kb-common/src/main/java/com/zhishiku/kb/common/aiusage/AiObservationSettings.java:15)、[anchor](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/aiusage/service/AiUsageWriter.java:195) | 到期的完整调用：AiCollectionServiceTest边界与AiUsageWriterTest晚确认；超过保留期的异常记录：AiCollectionServiceTest受保护状态、AiUsageReadTest:78。 |
| 清理必须有界且不得影响业务账本 | [清理事务](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/aiusage/service/AiCollectionService.java:56) | 清理与结算核查并发：AiCollectionServiceTest重读状态替身，非真实并发；清理后旧事件重试：AiUsageWriterTest:120。配置冲突租约分支见 W4。 |
| 查询必须公开覆盖范围与历史缺口 | [覆盖元数据](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/aiusage/service/AiUsageReadService.java:103) | 启用前或未启用查询、保留期之外存在待核查记录：AiUsageReadTest:43/78、aiUsagePages.test.tsx覆盖提示。历史清理后延长期限的缺口见 W1。 |

### admin-web-ai-usage：9个需求、18个场景

| 需求 | 实现定位 | 全部具名场景及测试证据 |
| --- | --- | --- |
| AI 用量与审计页面必须独立授权 | [精确页面门禁](/D:/tmp/kb/kb-web/src/admin/AiUsagePages.tsx:22)、[导航](/D:/tmp/kb/kb-web/src/admin/access.tsx:93) | 仅有用量权限、空间或身份目录故障：aiUsageAccess.test.tsx单项权限及目录失败用例。 |
| 筛选草稿必须与已提交查询分离 | [筛选草稿](/D:/tmp/kb/kb-web/src/admin/AiUsagePages.tsx:41)、[查询代次](/D:/tmp/kb/kb-web/src/admin/aiUsageQuery.ts:37) | 修改草稿后续页：aiUsagePages.test.tsx:22；提交新条件时旧响应晚到：aiUsageQuery.test.tsx新提交中止及迟到用例。 |
| 用量页必须解释真实用量与结算差异 | [总览](/D:/tmp/kb/kb-web/src/admin/AiUsagePages.tsx:76)、[数值类型](/D:/tmp/kb/kb-web/src/admin/aiUsageApi.ts:15) | 只有未知用量、生成已完成但结算未确认：aiUsagePages.test.tsx:35、aiUsageApi.test.ts:11/16，分别保留null、零、计划及确认值。 |
| 分组统计必须保留精度及读取时刻 | [分组表](/D:/tmp/kb/kb-web/src/admin/AiUsagePages.tsx:89) | 超过安全整数范围：aiUsageApi.test.ts:11/38、aiUsagePages.test.tsx:35；分组分页状态变化：aiUsageQuery.test.tsx独立asOf用例。 |
| 调用审计必须展示安全事件与详情 | [列表和抽屉](/D:/tmp/kb/kb-web/src/admin/AiUsagePages.tsx:97)、[详情身份核对](/D:/tmp/kb/kb-web/src/admin/aiUsageQuery.ts:88) | 多事件属于同一调用：aiUsagePages.test.tsx:48；切换详情或目标不可访问：aiUsageQuery.test.tsx详情404/关闭迟到、aiUsagePages.test.tsx:56。 |
| 审计续页必须核对固定窗口与身份 | [列表续页](/D:/tmp/kb/kb-web/src/admin/aiUsageQuery.ts:37) | 重复点击与请求失败：aiUsageQuery.test.tsx单次续页/503；翻页期间出现迟到事件：前端原cursor继续传递，结合AiUsageReadTest:111的上界参数证据。 |
| 页面必须明确采集覆盖与保留范围 | [覆盖提示](/D:/tmp/kb/kb-web/src/admin/AiUsagePages.tsx:28) | 尚未启用采集、保留期之外的异常记录：aiUsagePages.test.tsx:42与AiUsageReadTest:78。前端遵循服务端标志，W1会使其提示缺失。 |
| 页面资料必须服从当前身份与资格生命周期 | [生命周期](/D:/tmp/kb/kb-web/src/admin/aiUsageQuery.ts:71)、[后台会话](/D:/tmp/kb/kb-web/src/SessionProvider.tsx:9) | 读取途中切换账号：aiUsageQuery.test.tsx身份代次清理和取消；服务端拒绝但权限快照仍包含资格：403跨重挂载最多核对一次用例。 |
| AI 查询资料必须限制在页面内存 | [API投影](/D:/tmp/kb/kb-web/src/admin/aiUsageApi.ts:92)、[文本详情](/D:/tmp/kb/kb-web/src/admin/AiUsagePages.tsx:121) | 输入标识并打开详情：aiUsagePages.test.tsx:70；字段含HTML标记：aiUsagePages.test.tsx:48、aiUsageApi.test.ts:48。 |

### admin-web-access：1个需求、5个场景

需求“AI 管理入口必须分别按用量和审计资格开放”定位于 [App.tsx:64](/D:/tmp/kb/kb-web/src/App.tsx:64)、[navigation.ts:9](/D:/tmp/kb/kb-web/src/navigation.ts:9) 和 [access.tsx:82](/D:/tmp/kb/kb-web/src/admin/access.tsx:82)。

| 具名场景 | 代码与测试证据 |
| --- | --- |
| 登录后返回 AI 用量页 | aiUsageRoutes.test.tsx固定登录返回、safeReturnPath；必要改密沿用AuthPages的安全导航提示，已有sso/login.test.tsx共用分支证据。没有端到端运行。 |
| 分别显示导航 | aiUsageAccess.test.tsx单项精确权限，不挂载另一查询子组件。 |
| 空间目录故障 | AdminAccessProvider功能权限与空间状态分开；aiUsageAccess.test.tsx使用spaceStatus=unavailable仍开放。 |
| 带筛选或外部返回路径 | aiUsageAccess.test.tsx:28、navigation.test.tsx，仅固定站内路径通过。 |
| 后台恢复和撤权 | SessionProvider.suspend、会话epoch与AiContent重挂载；aiUsageQuery.test.tsx身份/资格变化、403及离页受控请求。 |

### functional-authorization：2个需求、7个场景

| 需求 | 实现定位 | 全部具名场景及测试证据 |
| --- | --- | --- |
| AI 用量和调用审计必须具有独立读取资格 | [权限目录](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/model/Permission.java:23)、[当前权限判断](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/service/AdminQueryAccess.java:48) | 权限目录可查询：RoleManagementControllerTest:96动态目录用例通过，另有C1；仅授权统计、旧管理权限与角色名：AdminQueryAccessTest:32；下一次请求撤权：共用当前权限用例AdminQueryAccessTest:50。 |
| AI 新权限初始化必须一次性且遵守升级兼容 | [V21绑定](/D:/tmp/kb/kb-app/src/main/resources/db/migration/V21__ai_usage_audit.sql:82)、[空库引导](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/service/AdminBootstrapRunner.java:158)、[未知目录守卫](/D:/tmp/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/service/RolePermissionValidator.java:26) | 已有管理员首次升级：静态审阅仅ROLE_ADMIN的INSERT及空库引导单测；撤销后重新启动：AdminBootstrapRunnerTest:206/269；旧二进制回退：现有未知code拒绝逻辑、RolePermissionValidatorTest已存在的纯单元用例和升级说明。未运行旧二进制或真实迁移，也不要求新增这类外部验收。 |

## 设计与代码模式核对

| 设计决策 | 结果 |
| --- | --- |
| 1. app独立事件存储和调用视图 | 已实现。没有业务资料级联；写服务原子更新视图/追加事件，固定阶段和发布锁顺序有替身证据。 |
| 2. 用量、结算分轴，保留既有策略 | 已实现，KNOWN/UNKNOWN/NOT_INVOKED与计划/确认额度分别表达；单轮/多轮策略分开。 |
| 3. 独立版本化签名采集和可信主体 | 服务签名、nonce域、严格体解析及固定收尾已实现；W2已补上订阅时的领取前主体复核。caller仍用现有kb-ai身份，密钥和nonce隔离，未扩展会话签名域。 |
| 4. 单轮稳定身份、登记、续期及收尾 | 已实现；W3已分离观测/配额续期，并补齐终态确认和慢收尾交错测试。 |
| 5. 多轮复用既有事务事实 | begin/reserve/dispatch/finish/delete/ack及维护确认均已接入；事务内未新增HTTP/Redis/模型操作。 |
| 6. 精确权限、有界投影、聚合和事件游标 | 已实现；重复读快照、固定上界和SQL条件仅静态/替身验证，不声称真实提交顺序已验证。 |
| 7. 180天、仅解决调用、有界清理 | 已实现；W4已改为独立提交心跳和锁后复核，W1已持久化清理界限。真实数据库行为保持范围外。 |
| 8. 页面独立权限与现有生命周期 | 已实现，原审计入口保持范围，精确整数、草稿和代次、403有界重核、内存清理已有证据。 |
| 9. 配置启停与覆盖运营语义 | 默认关闭、独立密钥及参数校验已实现；W1延长保留期仍显示缺口，W4持续冲突不会因外层回滚丢失心跳。 |

Code Pattern Consistency 已执行：新增目录与现有 web/service/mapper 模式一致，控制器不直接访问Mapper；ModuleBoundaryTest 5项及NoDatabaseAccessTest通过；当前资格不跨请求缓存，旧QueryCursor和管理审计未扩大范围。未提出额外风格建议；既有测试未同步问题C1已修复。

## 首次验证执行证据（修复前）

| 命令/诊断 | 实际结果 |
| --- | --- |
| [前次记录](ai-usage-validation.md)中的后端28类明确白名单，原命令重跑 | 111项通过；不含C1的既有目录测试。 |
| 同一前端13文件明确白名单，原命令重跑 | 190项通过。 |
| `mvn -o -pl kb-app -am test '-Dtest=PermissionCatalogTest,RoleManagementControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'` | 共10项：9通过、1失败、0错误、0跳过；退出码1。失败为C1。 |
| `npm run build`（kb-web目录） | TypeScript检查及Vite静态构建通过。 |
| `openspec validate ai-usage-and-audit-query --strict --json` | valid=true，issues=[]。仅为结构校验。 |
| 明确命名的临时Java进程内诊断 | 4个偏差复现，输出如下；未写入实现或现有测试文件。 |

诊断源码与参数保存在临时目录：[VerifyAiUsage.java](/C:/Users/Burning/AppData/Local/Temp/kb-ai-verify-de62f22db2024c028c5b833b74ae6e42/VerifyAiUsage.java)。它直接调用真实单个服务，依赖使用Mockito/内存数据，续期使用Reactor虚拟时间；心跳回滚使用内存事务管理器，均未启动监听端口或连接外部服务。Java启动器退出0代表诊断执行完成，不代表下列期望行为通过。

```text
COVERAGE: historicalRetentionDays=1, currentRetentionDays=180, historyMayBeIncomplete=false, expected=true after historical cleanup
PRINCIPAL: currentDepartment=3, chargedDepartment=2, resolveCalls=0, expected=reject changed subject before acquire
RENEWAL: finalized=true, settled=true, SSE=[error], expected=[done]
HEARTBEAT: activePolicies=[1,180], shortPolicyConsistent=true, longPolicyHeartbeatExpired=true, expected=cleanup remains blocked
```

首次验证的后端实际共执行121项：120通过、1失败；前端190通过。没有运行默认全量测试发现入口、集成或外部依赖测试，也没有针对README/Markdown/说明文案/注释/示例执行内容断言。当时任务勾选和all_done保持原状，结论为先修复C1并处理W1–W4。当前修复后的复核结果见本报告开头，历史诊断结果不再代表当前实现。
