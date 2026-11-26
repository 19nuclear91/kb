# Verification Report: reviewed-answer-cards

核验日期：2026-11-26。使用 `openspec-verify-change` 技能，按用户要求执行“核验→修复CRITICAL/WARNING→重新核验”。当前为第二轮结论，基于未提交的工作区实现。

**最终结果：CRITICAL 0、WARNING 0、SUGGESTION 0。** 第一轮发现的1项CRITICAL和4项WARNING均已修复并完成针对性回归；达到停止条件，verify阶段停止修复循环。随后经用户授权完成主规格同步及归档，见[归档记录](archive-record.md)。

## 核验范围与记分表

本轮读取并对照proposal、design、tasks和全部六份增量规格；`status`及`instructions apply`确认schema为spec-driven，规划产物完整、任务47/47、无不可读取的跟踪文件。`all_done`只用于核对任务状态，未当作实现验证结论。

| 维度 | 最终状态 |
| --- | --- |
| 完整性 | 47/47任务完成；49项ADDED/MODIFIED需求均定位到实现；无REMOVED/RENAMED需求，无缺失产物 |
| 正确性 | 49/49需求完成代码映射；110个场景逐项核对代码、离线执行证据或规格明确允许的人工SQL审阅；本轮新增7项回归用例 |
| 一致性 | D1–D13及技术分层、当前权限、日期、事务、独立搜索、会话原意图、审计及发布边界一致；修复后无未解决的规格偏差 |

上述六个技能检查（Task Completion、Spec Coverage、Requirement Implementation Mapping、Scenario Coverage、Design Adherence、Code Pattern Consistency）均在约定范围内执行。逐场景映射见[实施核验记录](verification.md)，以下修复记录补充并更新其中U18–U20、A39–A43的证据。并非110个场景均运行了自动化测试：数据库过滤、约束和真实并发部分按D13人工审阅。

**未验证边界**：真实数据库迁移、SQL执行/等价性、ParadeDB排名和执行计划、约束/触发器/锁/回滚、外部组件、真实浏览器、前后端联调及部署。它们是用户明确排除的验证活动，本轮没有将其列为缺失测试WARNING或新的完成前置条件，也不把静态/替身结果记为真实环境通过。

## 第一轮发现与修复

| 级别/编号 | 具体发现及关联场景 | 修复与验证 |
| --- | --- | --- |
| CRITICAL C1 | `useCardResource.ts`同步侦听身份变化时立即重新读取，SearchPanel尚未清空旧submitted条件，用户A的查询会以用户B的会话再次发送；违反U19及D10 | [useCardResource.ts:25](../../../../kb-web/src/answerCards/useCardResource.ts)先同步取消/清空，再等页面完成本轮清理后按世代决定是否读取。新增“换号时不会把上一用户已提交的问题重新发给新会话”，修复前实际调用2次、修复后1次 |
| WARNING W1 | 工作区/管理页的`reload`在await之后读取新的key和data，切换目标后可错误确认新卡片回执；拒绝分支亦可能清掉新目标意图；违反U19 | [AnswerCardWorkspacePage.vue:40](../../../../kb-web/src/pages/AnswerCardWorkspacePage.vue)、[AnswerCardManagementPage.vue:40](../../../../kb-web/src/pages/AnswerCardManagementPage.vue)捕获原目标/世代/认证代次；读取返回是否实际应用；await后再次核对。新增旧目标重读及拒绝后重读两项用例，确认新目标committed/unknown状态仍保留 |
| WARNING W2 | 新建未知写入明确放弃后，只发起重读而不等待成功门禁，503后仍可开启新意图；违反U18、D10 | [AnswerCardWorkspacesPage.vue:24](../../../../kb-web/src/pages/AnswerCardWorkspacesPage.vue)要求当前清单成功加载，重读中/失败时禁新建与保存；反馈绑定原键和认证代次。新增“未知创建明确放弃后重读失败，成功核对前不能开启新意图” |
| WARNING W3 | 权限重核使路由组件卸载后，组件级watch停止；随后确认撤权不会清理Session中保留的未知私有载荷；违反U20 | [useCardCommands.ts:7](../../../../kb-web/src/answerCards/useCardCommands.ts)按Session/epoch保留唯一独立权限监听，退出/换号停止并清理；普通离页保留原意图。新增“资格重核导致离页后，确认撤权仍清理会话内未知载荷”，保留既有loading不误清理用例 |
| WARNING W4 | 命令绑定对X-Request-Id执行strip，审计再trim，`r`与` r `会合并；不满足A39/A43的精确原请求标识合同 | [AnswerCardCommands.java:36](../../../../kb-app/src/main/java/com/zhishiku/kb/app/service/impl/answercard/AnswerCardCommands.java)按收到的非控制码点原样绑定；[IdentityAuditService.java:54](../../../../kb-app/src/main/java/com/zhishiku/kb/app/auth/service/IdentityAuditService.java)对卡片域保留同一精确键。新增绑定和审计两项用例，覆盖首尾/全空白、128/129个补充字符、孤立代理项和控制字符；API说明同步 |

四个前端复现用例首次运行全部失败，证明原行为可重现；修复后全部通过，随后为W1拒绝分支增加第二条用例。调整读取调度后，原“换号后迟到响应”测试增加等待首次请求确已发出，再执行换号，使其继续验证真实在途响应条件。没有通过弱化结果断言消除失败。

## 第二轮复核

- **完整性**：重新执行OpenSpec状态与apply instructions，47/47、remaining=0，无新增或遗漏任务；六份规格及设计仍完整。
- **当前访问与固定来源**：核对Access、Write/Read服务、Source/Query Mapper及V44约束；空间与来源权限相交、可信发布证据、来源日期修订0/首次声明/改回、普通当前版及历史逐修订访问继续符合要求。最小管理不读取答案和私有意见。
- **审核与复审**：核对分支贡献者、精确轮次、发布与草稿分离、批准/撤回竞争、旧绑定重放、责任人改派与确认。锁后复读和事务顺序只作静态与内存事务核验，没有宣称真实数据库并发已通过。
- **搜索与协议**：三级SQL各自先过滤再截取，保留固定修订身份及原文摘要；容量/预算、no-store、严格解码和旧服务失败协议保持独立。原文档检索、AI及采集协议的实施回归证据仍适用。
- **前端恢复**：重新追踪主动读取、身份切换、权限loading→离页→ready、关闭重开、NOT_FOUND、放弃后失败、目标A→B与迟到回调；新增用例和原卡片/搜索/路由回归全部通过。会话权限监听在退出或代次改变时停止，不进入浏览器持久存储。
- **原意图与审计**：精确请求标识在命令锁、回执和卡片审计中一致；原有其他审计域保持其既有规则。大整数、Unicode校验、原载荷摘要及先鉴权后回执顺序保持。
- **代码模式**：新增修复沿用现有Vue组合函数、Session epoch、Java构造器注入和技术分层；未创建功能聚合包、未新增外部测试入口或文档内容断言。

## 实际验证结果

| 检查 | 本轮结果 |
| --- | --- |
| 后端定向回归 | 44项通过：命令6、工作流13、身份审计5、安全摘要16、Controller4 |
| 前端相关回归 | 7文件159项通过（原154项+本轮5项） |
| 类型检查 | `npx vue-tsc --noEmit`通过；最终`npm run build`再次包含类型检查 |
| 生产构建 | 成功，2259模块 |
| 最终产物包体隔离 | `--check-isolation`通过，6个卡片场景均单独统计；入口原始401268字节，卡片工作区总计446510字节（含共享依赖） |
| OpenSpec严格格式 | 通过 |
| 差异空白检查 | 通过 |

后端本轮新增2项，另纳入之前白名单未执行的IdentityAuditServiceTest原4项。44项为本轮实际数量，不将其直接与实施阶段186项相加。全模块测试编译和包体工具13项的实施阶段证据见verification.md；本轮后端构建重新编译app及其全部测试。

```powershell
# 仓库根目录：仅进程内白名单
mvn -o -pl kb-app -am test '-Dtest=AnswerCardCommandsTest,AnswerCardWorkflowTest,IdentityAuditServiceTest,AuditSummaryProjectorTest,AnswerCardControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'

# kb-web目录：模拟DOM、类型及静态构建
npm test -- --run src/answerCards src/admin/auditApi.test.ts src/search/page.test.ts src/search/assistance-page.test.ts src/routing/lazy-routes.test.ts
npx vue-tsc --noEmit
npm run build
npm run bundle:report -- --check-isolation --out .bundle-analysis/answer-cards-report.json

# 仓库根目录：规划和差异检查
openspec status --change reviewed-answer-cards --json
openspec instructions apply --change reviewed-answer-cards --json
openspec validate reviewed-answer-cards --strict
git -c core.safecrlf=false diff --check
```

最终未解决问题列表：CRITICAL无，WARNING无，SUGGESTION无。约定离线范围内核验通过，停止修复循环；主规格同步及归档作为后续用户授权操作另记于归档记录。
