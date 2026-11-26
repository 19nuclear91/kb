# reviewed-answer-cards 离线交付核验

核验日期：2026-11-26。范围为本变更六份增量规格的 **49 项需求、110 个场景**。代码包含固定空间答案卡、不可变修订、独立非自审工作流、固定来源与日期修订、责任人周期复审、独立搜索、最小管理页面和会话内未知写入恢复。任务状态见 [tasks.md](tasks.md)。实施核验阶段未同步主规格、归档、提交或部署；后续同步与归档结果见[归档记录](archive-record.md)。

本文件记录实施阶段的验证批次和场景索引。随后按verify技能进行两轮核验及修复，最新结论、159项前端回归和44项后端定向回归见[verify-report.md](verify-report.md)；其中新增测试与修复证据补充下表，本文件的原批次数字及包体字节数保留为实施阶段记录。

## 验证范围与结果

全部执行采用进程内单元测试、Mock/内存仓储与传输替身、MockMvc 内存派发、模拟 DOM、编译和静态构建。未启动业务服务、监听端口或连接数据库、Redis、对象存储、模型；未运行真实浏览器或前后端联调。没有新增文档、迁移或 SQL 文本/存在性断言测试。

| 检查 | 实际结果 |
| --- | --- |
| 后端离线白名单 | 首批184项通过：app115、gateway10、AI6、采集协议53；后续33项定向回归通过，其中新增历史批准状态和Mapper发现两项，去重共186项 |
| 搜索执行器最终定向复测 | 4项通过，覆盖预算、容量、取消释放、依赖故障和响应上限 |
| 全模块测试编译 | 10个Reactor项目成功，未运行全量测试 |
| 前端相关模拟DOM/协议回归 | 最终7文件154项全部通过 |
| 前端类型检查与生产构建 | `vue-tsc --noEmit` 与 Vite 构建成功 |
| 包体报告工具 | 13项全部通过 |
| 最终生产产物隔离 | `--check-isolation`通过，包含6个新增卡片场景 |
| OpenSpec格式 | `openspec validate reviewed-answer-cards --strict`通过 |
| 差异空白检查 | `git -c core.safecrlf=false diff --check`通过 |

后端结果按不同测试用例去重，未将定向复测重复计数。测试数不表示110个规格场景全部具有自动化测试；下表逐项区分执行证据与人工审阅。

真实迁移执行、数据库约束/触发器、锁/死锁/回滚、SQL等价性和执行计划、ParadeDB分词及实际排名、外部组件和部署链路均**未验证**。内存事务测试使用实际Spring事务Advice与内存事务管理器，只证明服务事务边界和替身状态恢复；受控交错不证明数据库真实并发。以上未验证事项不作为本轮离线交付完成的前置条件。

## 实际命令

以下后端命令在仓库根目录执行。白名单排除了全量入口中可能监听端口、启动服务或连接外部组件的用例；`-o`禁止Maven联网取依赖。

```powershell
mvn -o -pl kb-app,kb-gateway,kb-ai,kb-eval-collector -am test '-Dtest=AnswerCardCommandMappingTest,AnswerCardSourceMappingTest,AnswerCardAccessTest,AnswerCardRulesTest,AnswerCardCommandsTest,AnswerCardReadServiceTest,AnswerCardWorkflowTest,AnswerCardSearchExecutorTest,AnswerCardInputTest,AnswerCardControllerTest,PermissionCatalogTest,RolePermissionValidatorTest,AdminBootstrapRunnerTest,AuditSummaryProjectorTest,SpaceWriteServiceTest,EpochSessionServiceTest,EpochAuthenticationInterceptorTest,SearchTextOnlyControllerTest,SearchServiceTest,AnswerCardResponsePolicyTest,KnowledgeGapRouteTest,SaTokenGatewayConfigTest,ChatEventCitationTest,ProtocolTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -pl kb-app -am test '-Dtest=AnswerCardMapperDiscoveryTest,AnswerCardReadServiceTest,AnswerCardControllerTest,AnswerCardWorkflowTest,AnswerCardCommandsTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -pl kb-app -am test '-Dtest=AnswerCardSearchExecutorTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o test-compile '-DskipTests' '-Dstyle.color=never'
```

以下前端命令在 `kb-web` 执行。测试使用模拟DOM与调用替身，构建与报告只读取本地产物。

```powershell
npm test -- --run src/answerCards src/admin/auditApi.test.ts src/search/page.test.ts src/search/assistance-page.test.ts src/routing/lazy-routes.test.ts
npm run build
npm run bundle:test
npm run bundle:report -- --check-isolation --out .bundle-analysis/answer-cards-report.json
```

最终格式及空白检查在仓库根目录执行：

```powershell
openspec validate reviewed-answer-cards --strict
git -c core.safecrlf=false diff --check
```

包体报告为本地生成产物 `kb-web/.bundle-analysis/answer-cards-report.json`，归档时已清理，不提交构建目录，可用上述命令重新生成。下列字节数为每个场景按文件去重后包含共享依赖的总和，不能跨场景相加，也不代表网络实测。

| 场景 | 文件数 | 原始字节 | gzip字节 | Brotli字节 |
| --- | ---: | ---: | ---: | ---: |
| entry | 14 | 401268 | 135390 | 117867 |
| answerCards | 22 | 428733 | 147069 | 127825 |
| answerCardDetail | 21 | 429144 | 147077 | 127845 |
| answerCardWorkspaces | 23 | 440377 | 152028 | 131956 |
| answerCardWorkspace | 23 | 445604 | 153923 | 133478 |
| answerCardManagement | 21 | 435872 | 149849 | 130130 |
| answerCardSearch | 22 | 429145 | 147351 | 128063 |

## 证据索引

表中的 **T** 表示已执行的进程内测试证据，**S** 表示人工静态审阅。S不等于实际依赖行为已通过。以下缩写链接到实现和测试，表中另说明核对的分支。

| 缩写 | 实现 | 执行证据 |
| --- | --- | --- |
| W | [写入服务][W]、[命令绑定][C] | [WT][WT]（13项工作流）、[CT][CT]（5项绑定与回执） |
| R | [读取服务][R] | [RT][RT]（10项读取与投影） |
| A | [当前资格][A]、[来源SQL][S] | [AT][AT]（6项资格交集）、来源结果映射2项 |
| D | [日期与状态][D]、[内容输入][Content] | [DT][DT]（4项规则与边界） |
| Q | [查询SQL][Q]、[搜索执行器][E] | [RT][RT]、[ET][ET]（4项预算与容量） |
| I | [Controller及严格解码][I]、[请求/响应模型][Models] | [IT][IT]（4项输入）、[HTTP][HTTP]（4项MockMvc） |
| F | [前端卡片模块][F]、[页面壳][Pages] | [FT][FT]（12项组件）、[FC][FC]（3项原意图）、[FA][FA]（12项协议/路由） |
| Audit | [摘要投影][Audit]、[审计读取][AuditRead] | [AuditT][AuditT]（16项），前端[auditApi.test.ts][AuditWebT] |
| M | [V44迁移][M]、[卡片Mapper][Mapper] | 命令/来源真实MyBatis结果映射3项；[Mapper发现][Discovery]1项；没有执行SQL |

映射测试使用真实XML、ResultSet替身，不连接数据库；Mapper发现仅扫描Bean定义，不启动应用上下文。网关的路由/认证/响应限制测试使用Mock请求与内存交换；AI引用与采集协议分别使用纯对象或内存传输替身。

## answer-cards：44个场景

对应 [answer-cards/spec.md](specs/answer-cards/spec.md)。

| 编号与规格场景 | 实现与核验依据 |
| --- | --- |
| A01 创建未完成草稿 | S：W创建及Content允许不完整草稿、送审要求完整；T：DT缺来源草稿与完整性拒绝、HTTP创建回执 |
| A02 多份依据与跨空间来源 | S：A/S逐来源交集；T：AT跨空间两来源及单份失权 |
| A03 文本与来源非法 | S：Content码点/数量界限；T：DT与FA归一问法重复、同文档多版本、坏Unicode；IT拒绝非法输入 |
| A04 输入包含标记与脚本 | T：IT保留字面脚本文本、FT安全渲染；S：SafeText与无富文本解释路径 |
| A05 发布版与新草稿并存 | T：WT发布后另开分支仍保留旧指针；S：Q只关联当前发布修订，页面区分草稿/发布记录 |
| A06 审核中修改或并发建稿 | T：WT审核冻结和锁前后版本交错；S：W卡片锁、M单活动分支约束；真实并发未运行 |
| A07 选择之后来源更新 | T：AT区分可读旧来源与当前来源冲突、FT明确重选；S：W保存/送审核对三元组 |
| A08 同版本日期被更正 | T：WT首次声明阻断批准、DT日期修订不同即SOURCE_CHANGED；S：W禁止自动替换 |
| A09 缺乏可信发布证据 | S：S限定PUBLISHED/OBSERVED_CURRENT且候选只取当前版本；T：来源映射与RT非当前候选拒绝；证据过滤SQL未执行 |
| A10 编辑后委托他人送审 | T：WT全部贡献者及送审者不能批准，驳回后再送审保留轮次；S：W/M同样保护驳回非自审 |
| A11 免审空间中的卡片 | S：W所有空间均必须送审、非自审决定，无AUTO免审分支；T：WT保存/冻结/批准完整流程 |
| A12 两人同时审批 | T：WT第二审核人的旧绑定不能覆盖首个决定；S：W锁与条件更新、M单活动审核；仅受控交错证据 |
| A13 撤回与批准竞争 | T：WT撤回终态后旧批准拒绝；S：W驳回/撤回不修改旧发布指针 |
| A14 批准前依据或人员变化 | T：WT日期首次声明、责任人失格、AT撤销审核资格；S：W锁后资格重核 |
| A15 未来生效 | T：WT未来来源可审核但不能提前确认、DT未来/当前日期边界；S：Q读取时判断日期 |
| A16 截止日边界 | T：DT截止当天AVAILABLE、次日EXPIRED；S：R按数据库时间取上海业务日期，Q使用相同today |
| A17 来源未声明日期 | T：DT未声明仍可用、来源映射保留null日期及修订0；S：CardDates显示未声明 |
| A18 依据发布新版 | T：DT新版导致SOURCE_CHANGED、RT旧/失效详情不回退；S：Q在浏览与搜索截取前过滤 |
| A19 日期更正后又改回 | T：DT日期相同而修订变化仍阻断；S：Q比较修订号，不只比较日期值 |
| A20 仅更改文档标题 | S：D/Q新鲜度只比较版本和日期修订，S按当前授权取标题；无标题变更执行用例 |
| A21 直接访问暂停卡片 | T：RT旧版/到期固定详情404、FT清理旧正文；S：HTTP错误不提供暂停私有原因 |
| A22 展示判断依赖故障 | T：RT依赖失败不变空结果、HTTP安全503；S：R无旧快照缓存回退 |
| A23 用户仅失去一份来源 | T：AT完整集合少一项即拒绝、RT先核来源再取正文；S：Q按本次用户过滤 |
| A24 来源升密或搬迁 | T：AT当前密级降低后拒绝、WT锁后逐文档空间归属变化冲突；S：S读当前ACL/状态/空间 |
| A25 来源仍可读但已被新版取代 | T：RT合格操作者可读SOURCE_CHANGED历史、旧批准修订标HISTORICAL；S：历史不要求来源仍当前 |
| A26 来源已经不可读 | T：RT逐修订失权拒绝、最小管理不取正文或来源；FT历史失权清理；S：管理无正文旁路 |
| A27 候选读取后撤权 | T：AT与RT缺来源拒绝；S：W重新取得来源事实，不接受候选作为授权凭证 |
| A28 缺少身份目录资格 | S：R.sources只要求维护及空间资格，S从本人可读集合取当前发布版；T：RT候选和游标隔离 |
| A29 管理者不具内容资格 | T：RT管理不调用revision/sources/private review，FT仅管理页不请求正文；S：W管理单独鉴权 |
| A30 责任人资格失效 | T：WT失格责任人不能批准、DT标NEEDS_REASSIGN；S：Q.displayable不以责任人合格作为读者可见门禁 |
| A31 候选在提交前失格 | T：WT失格候选拒绝并保留原设置及基准；S：W锁后重核资格，返回固定冲突 |
| A32 改派与月末 | T：DT十一月三十日加三个月为二月二十八日，WT改派/改周期保留首次指定日；S：Q对应日历计算 |
| A33 复审逾期 | T：DT第30/31天及逾期状态、FT逾期仍显示答案；S：Q展示不以dueDate为下线门禁 |
| A34 仅复审逾期后确认 | T：WT确认追加一次事实且不改变有效期；S：W确认只更新复审事实与并发版本 |
| A35 试图解除来源变化暂停 | T：WT来源已变确认拒绝、FT不显示确认入口；S：W确认复用AVAILABLE判断 |
| A36 改派后旧责任人确认 | T：WT改派后原责任人确认拒绝；S：W按当前责任人及复审版本校验 |
| A37 续页期间资格变化 | T：RT历史续页重新取当前集合、跨用户游标拒绝；S：Q所有内容列表在LIMIT前授权，无total |
| A38 管理者接管失格责任人 | T：RT最小管理不取私有事实、FT独立管理入口；S：Q.management不要求来源可读 |
| A39 批准响应丢失 | T：WT旧分支批准重放、当前资格先于回执、无重复成功审计；CT绑定相等返回原回执 |
| A40 回执或审计写入失败 | T：WT实际事务Advice配内存管理器恢复创建/保存和贡献者集合；CT回执落库失败不确认；S：W同事务，未验证数据库回滚 |
| A41 创建结果未知 | T：WT、FC创建尚未知ID仍按原空间核对；HTTP只返回无正文回执 |
| A42 核对前被撤权 | T：WT创建/批准回执核对先鉴权、FC403清私有载荷；S：W不先读回执披露目标 |
| A43 大整数与协议损坏 | T：IT、HTTP、CT及FA保留大整数，拒绝数字ID、重复/未知字段、尾随JSON和损坏日期 |
| A44 敏感正文遇到错误 | T：HTTP固定503不回显异常、FA错误不消费私有失败正文、AuditT白名单；S：卡片Advice/网关不记录请求或底层异常 |

## answer-card-search：20个场景

对应 [answer-card-search/spec.md](specs/answer-card-search/spec.md)。

| 编号与规格场景 | 实现与核验依据 |
| --- | --- |
| S01 独立空间限定 | T：FA只发送query/spaceId/limit并验证应用条件；S：Q每组均应用spaceFilter |
| S02 夹带文档筛选 | T：HTTP带fileType拒绝且不进入搜索；S：I固定字段白名单 |
| S03 缺省空间与非法数量 | T：HTTP缺省空间/数量为null/10，IT拒绝字符串、小数、21/null；S：I允许spaceId省略或null |
| S04 问题与正文都出现关键词 | T：RT合成三级顺序保持与去重；S：Q精确问题、问题文本、答案三级合并；真实相关性排名未测 |
| S05 草稿加入关键词 | S：Q每组从published_revision_id关联r，M独立文本索引不决定可见性；T：WT草稿不改变发布指针 |
| S06 无匹配 | T：HTTP/FA合法空结果，RT依赖失败不伪造空结果；S：不调用模型、来源全文或聊天 |
| S07 高分不可见卡片占满全局前列 | S：Q三组各自在ORDER BY/LIMIT前应用displayable及空间条件，再去重截取；未运行真实查询 |
| S08 关联来源部分可读 | T：AT/RT缺一份来源即整体拒绝；S：Q反存在谓词先过滤，不返回隐藏数量 |
| S09 长答案摘要 | T：RT超过400码点的Unicode原文前缀和truncated；S：R不改写答案 |
| S10 复审已逾期 | T：FT逾期仍可读、DT状态边界；S：Q/R只将复审作为结果提示 |
| S11 点击前发布新版卡片 | T：RT旧修订拒绝不回退、FA服务端替换修订拒绝、FT固定旧链接清理 |
| S12 来源在搜索后到期 | T：RT过期详情拒绝、DT次日边界；S：每次详情重新读取日期与当前发布事实 |
| S13 浏览期间来源更新 | S：Q.browse每页复用displayable和当前事务日期；T：RT历史续页当前资格模式、来源变化规则；无真实浏览分页SQL测试 |
| S14 不可见空间 | S：Q.browse使用空间授权与spaceFilter交集，不单独查询目标空间存在性；返回空页；T：FA合法空页消费 |
| S15 搜索超时 | T：ET虚拟时钟预算耗尽、依赖/超限拒绝；S：E及语句预算不返回部分成功 |
| S16 容量已满 | T：ET实例/用户占用拒绝、完成释放、取消后工作退出才释放；S：无界等待队列不存在 |
| S17 搜索失败包含原问题 | T：HTTP固定错误、FA不回显错误正文；S：I/Advice查询仅在POST正文且不打入日志或审计 |
| S18 原AI与评测采集 | T：SearchServiceTest、SearchTextOnlyControllerTest、ChatEventCitationTest、ProtocolTest；S：卡片独立端点无伪文档分块 |
| S19 文档搜索采用纠错 | T：原search/page与assistance-page回归、FT主动预填不自动提交；S：SearchWorkspace只传原问题，不传术语结果 |
| S20 客户端遇到旧后端 | T：FA缺头/未知协议/旧数组拒绝成功；S：F不回退到AI或文档搜索 |

## user-web-answer-cards：21个场景

对应 [user-web-answer-cards/spec.md](specs/user-web-answer-cards/spec.md)。

| 编号与规格场景 | 实现与核验依据 |
| --- | --- |
| U01 普通读者访问 | T：lazy-routes普通详情不要求维护资格；S：导航及页面使用当前精确权限 |
| U02 直接访问维护链接 | T：lazy-routes无资格不加载私有页面，匿名/改密/故障均不调用加载器 |
| U03 文档区域限定PDF | T：search/page双区域协议、FA独立请求字段；S：SearchPanel显示自己的已提交条件 |
| U04 主动预填 | T：FT仅点击预填原问题，不触发搜索，明确提交后保留应用条件 |
| U05 一个区域故障 | T：search/page卡片/文档调用与原页面回归；S：两个资源独立状态，不互相清空 |
| U06 到期日尚未届满但复审逾期 | T：FT逾期仍读并提示；S：CardDates和stewardLabel区分日期与复审 |
| U07 固定来源访问 | S：CardRevision懒加载VersionPreview，预览路径严格绑定文档与原版本，文件接口继续鉴权；T：FA固定身份协议；未运行真实文件预览 |
| U08 点击前更换发布版 | T：FA不接受替换修订、FT详情404清问题/答案/来源；S：允许主动重查 |
| U09 来源日期变更 | T：FT查候选不改变绑定，明确选择后保存新三元组；S：CardRevision同时显示原声明及可访问当前声明 |
| U10 发布版与草稿不同 | T：FT冻结页和编辑操作、WT指针分离；S：Workspace分区，不用保存执行发布 |
| U11 贡献者兼具审核权限 | T：FT能力为false不显示审批、WT贡献者/送审者拒绝；S：页面有不能自行审批提示 |
| U12 审核结果竞争 | T：FT驳回原因必填、409重读且不自动操作新轮次；WT旧决定冲突 |
| U13 已批准但暂停 | T：FT来源已变的发布展示、RT历史批准不标草稿；S：状态区与发布记录分开呈现 |
| U14 有活动草稿时周期确认 | T：WT草稿存在时确认拒绝、FT审核冻结canConfirm=false；S：按钮只由服务端能力投影开放 |
| U15 仅有空间管理资格 | T：FT仅管理权限使用最小候选，不调用workspace/detail；RT管理投影不读取正文 |
| U16 批准超时但已提交 | T：FC通用原意图恢复、FT关闭工作区后迟到成功保留回执；S：同一状态机用于批准，未单独模拟批准弹窗 |
| U17 核对暂未查到 | T：FC/FT NOT_FOUND保持未知冻结，显式重试复用原标识与载荷 |
| U18 放弃与重新登录 | T：FC放弃后迟到结果作废、换代清空，FT放弃后先重读；S：WeakMap会话内存，不写持久存储 |
| U19 换号后旧响应返回 | T：FA身份代次变化使响应Abort，FT迟到读取不回填，FC旧回执作废；S：所有页面读取共用useCardResource |
| U20 维护资格被撤销 | T：FT清正文/输入、重核中保留意图而确认失权后清载荷；S：useCardCommands按精确动作资格收缩 |
| U21 新页面连接旧后端 | T：FA旧结构/未知版本失败、lazy-routes加载失败后仍可退出；S：不可用提示，不创建占位卡片 |

## functional-authorization：10个场景

对应 [functional-authorization/spec.md](specs/functional-authorization/spec.md)。

| 编号与规格场景 | 实现与核验依据 |
| --- | --- |
| F01 目录可配置专门人员 | T：PermissionCatalogTest、RolePermissionValidatorTest；S：Permission及roleGroups两项用途标签 |
| F02 仅有原空间审核权 | T：AT只有write不能review；S：A.requireReviewer同时检查精确review功能码 |
| F03 下一次请求撤销 | T：AT下一次请求读取新权限、WT撤权后核对拒绝；EpochAuthenticationInterceptorTest拒绝旧代次 |
| F04 功能权限没有空间范围 | T：AT无目标空间资格404、权限并集不蕴含空间view；S：A要求view/edit |
| F05 文档例外不能授权卡片空间 | T：AT目标空间拒绝；S：S.viewGrant只服务来源，Q.spaceView独立空间ACL |
| F06 管理者无内容访问 | T：AT管理不查询来源、RT/FT最小管理；S：W管理与维护权限分开 |
| F07 撤权后重放批准 | T：WT已提交批准重放/核对先重核审核资格；S：功能门禁位于回执读取前 |
| F08 猜测他人回执 | T：CT操作域/操作者/载荷任一不同均不能复用；S：W先核当前动作与目标范围 |
| F09 存量与空库 | T：AdminBootstrapRunnerTest正式引导完整目录；S：M只一次性绑定已有ROLE_ADMIN，未执行数据库初始化 |
| F10 撤销后重启或旧版回退 | T：AdminBootstrapRunnerTest不补回、RolePermissionValidatorTest拒绝未知目录；S：运维排空旧app与保留数据说明 |

## space-authorization：7个场景

对应 [space-authorization/spec.md](specs/space-authorization/spec.md)。

| 编号与规格场景 | 实现与核验依据 |
| --- | --- |
| P01 删除仍有文档的空间 | T：SpaceWriteServiceTest非空拒绝；S：原文档检查仍保留 |
| P02 删除空空间 | T：SpaceWriteServiceTest空空间及授权删除；S：卡片引用检查在删除前 |
| P03 只有暂停卡片或草稿 | T：SpaceWriteServiceTest卡片引用返回冲突；S：SpaceMapper按所有卡片计数，不按展示状态筛选 |
| P04 建卡与空间删除竞争 | T：WT锁后目标失效拒绝创建；S：W空间共享锁、删除独占协调锁、M空间RESTRICT；未运行数据库竞争 |
| P05 卡片空间归档 | T：AT仅最小管理可读、WT归档目标拒绝；S：Q.spaceView与W阻断内容及责任人修改 |
| P06 来源空间归档 | T：AT/RT任一来源不可访问即拒绝；S：S/Q按来源当前空间archived过滤；真实SQL未执行 |
| P07 取消归档 | S：展示按当前事实动态计算、无归档恢复时重置责任日期的写入；T：DT日期/来源重判，WT改派基准保持 |

## audit-query：8个场景

对应 [audit-query/spec.md](specs/audit-query/spec.md)。

| 编号与规格场景 | 实现与核验依据 |
| --- | --- |
| L01 查询卡片审核记录 | S：AuditScope八域及answer-card目标接入原筛选/分页；T：AuditT合法卡片摘要与前端AuditWebT安全投影 |
| L02 仅有卡片维护资格 | S：AuditQueryService列表/详情仍先require(AUDIT_READ)，卡片权限不蕴含；未新增全局审计授权用例 |
| L03 事务失败或匹配重放 | T：WT内存事务失败恢复、匹配批准重放不追加成功审计；S：审计与回执在同一事务 |
| L04 修改答案与来源 | T：AuditT、AuditWebT只接受固定changedFields/版本，拒绝正文或来源列表；S：W只构造安全摘要 |
| L05 驳回原因含业务内容 | T：AuditT及AuditWebT拒绝reason正文；S：W只写reasonProvided标志 |
| L06 未知或损坏字段 | T：AuditT/AuditWebT未知字段、状态或私有文本使摘要不可用，元数据保留 |
| L07 来源更正导致暂停 | S：R/D/Q只计算展示状态，不调用审计或写状态；原来源更正继续使用原审计路径 |
| L08 重复搜索和跨日读取 | S：R、候选、命令query均只读，无通知/确认/维护事件写入；T：WT命令核对与重放无重复成功事件 |

## 人工静态审阅补充

- **数据与分层**：V44为本次空闲迁移编号；九表的中文表/列注释、复合归属外键、不可变触发器、单活动分支/审核约束、单调发布指针及审核批准证据均与实体职责对应。新Java文件落在controller、service/impl、mapper、domain、enums、config等技术层；构造器注入保持一致。正式Mapper扫描的进程内发现测试已通过。
- **事务与来源**：W按请求、升序空间、升序来源文档/版本、卡片/分支/审核/责任设置顺序锁定；锁后逐文档比较空间归属并重核权限与来源。来源无日期行仍锁文件版本，不能绕过首次声明。已提交回执在完成当前授权后、只适用于新执行的活动状态判断前返回。
- **展示与搜索**：Q普通浏览/搜索/详情使用共同displayable谓词，非空来源、可信发布、当前版本、日期修订、全部来源权限与日期都在截取前核对。当前发布指针限制草稿/旧版召回；合成候选测试只证明服务保持SQL约定顺序及去重，不能证明真实分词或数据库排名。
- **历史与最小管理**：历史按修订/审核位置分页并逐修订核来源；管理以ID/工作流/复审信息查询，不先取正文再删字段。历史批准修订采用HISTORICAL，来源或日期暂停保留原批准事实。
- **协议与私密性**：Controller中文OpenAPI保留R包装泛型、精确字符串ID、日期与状态语义；输入拒绝未知/重复字段和超限。独立JSON编码器显式禁用日期时间戳数组。gateway只转发到app，提前限正文、错误no-store、不伪造成功版本头；本次未联调链路。
- **页面与恢复**：所有读取使用身份/目标/请求世代，原意图只存在当前Session内存。关闭局部工作区、NOT_FOUND或资格重核不解冻未知请求；确认失权、退出和换号清理。显式放弃后重读，再开始新意图。权限按钮以服务端能力为准，重试保留原绑定。
- **相邻消费者**：原文档搜索、术语纠错、AI引用/SSE及采集协议没有加入卡片伪分块。搜索区域只接收主动预填的原问题，卡片自身不依赖模型、解析或Embedding。

## 交付说明

已同步[根README](../../../../README.md)、[前端README](../../../../kb-web/README.md)、[路线图F-08](../../../../docs/development-roadmap.md)、[功能说明](../../../../docs/answer-cards.md)、[API](../../../../docs/answer-cards-api.md)和[运维说明](../../../../docs/answer-cards-operations.md)。发布说明要求排空未知新权限的旧app，按app→gateway→web升级，保留历史、回执及新权限识别能力；这些说明不表示已执行迁移或部署。

[W]: ../../../../kb-app/src/main/java/com/zhishiku/kb/app/service/impl/answercard/AnswerCardWriteServiceImpl.java
[C]: ../../../../kb-app/src/main/java/com/zhishiku/kb/app/service/impl/answercard/AnswerCardCommands.java
[R]: ../../../../kb-app/src/main/java/com/zhishiku/kb/app/service/impl/answercard/AnswerCardReadServiceImpl.java
[A]: ../../../../kb-app/src/main/java/com/zhishiku/kb/app/service/impl/answercard/AnswerCardAccess.java
[D]: ../../../../kb-app/src/main/java/com/zhishiku/kb/app/service/impl/answercard/AnswerCardRules.java
[Content]: ../../../../kb-app/src/main/java/com/zhishiku/kb/app/domain/dto/answercard/AnswerCardContent.java
[S]: ../../../../kb-app/src/main/resources/mapper/answercard/AnswerCardSourceMapper.xml
[Q]: ../../../../kb-app/src/main/resources/mapper/answercard/AnswerCardQueryMapper.xml
[E]: ../../../../kb-app/src/main/java/com/zhishiku/kb/app/service/impl/answercard/AnswerCardSearchExecutor.java
[M]: ../../../../kb-app/src/main/resources/db/migration/V44__reviewed_answer_cards.sql
[Mapper]: ../../../../kb-app/src/main/resources/mapper/answercard/AnswerCardMapper.xml
[I]: ../../../../kb-app/src/main/java/com/zhishiku/kb/app/controller/answercard
[Models]: ../../../../kb-app/src/main/java/com/zhishiku/kb/app/domain/vo/answercard/AnswerCardViews.java
[WT]: ../../../../kb-app/src/test/java/com/zhishiku/kb/app/service/impl/answercard/AnswerCardWorkflowTest.java
[CT]: ../../../../kb-app/src/test/java/com/zhishiku/kb/app/service/impl/answercard/AnswerCardCommandsTest.java
[RT]: ../../../../kb-app/src/test/java/com/zhishiku/kb/app/service/impl/answercard/AnswerCardReadServiceTest.java
[AT]: ../../../../kb-app/src/test/java/com/zhishiku/kb/app/service/impl/answercard/AnswerCardAccessTest.java
[DT]: ../../../../kb-app/src/test/java/com/zhishiku/kb/app/service/impl/answercard/AnswerCardRulesTest.java
[ET]: ../../../../kb-app/src/test/java/com/zhishiku/kb/app/service/impl/answercard/AnswerCardSearchExecutorTest.java
[IT]: ../../../../kb-app/src/test/java/com/zhishiku/kb/app/controller/answercard/AnswerCardInputTest.java
[HTTP]: ../../../../kb-app/src/test/java/com/zhishiku/kb/app/controller/answercard/AnswerCardControllerTest.java
[Discovery]: ../../../../kb-app/src/test/java/com/zhishiku/kb/app/mapper/answercard/AnswerCardMapperDiscoveryTest.java
[F]: ../../../../kb-web/src/answerCards
[Pages]: ../../../../kb-web/src/pages
[FT]: ../../../../kb-web/src/answerCards/pages.test.ts
[FC]: ../../../../kb-web/src/answerCards/commands.test.ts
[FA]: ../../../../kb-web/src/answerCards/api.test.ts
[Audit]: ../../../../kb-app/src/main/java/com/zhishiku/kb/app/audit/service/AuditSummaryProjector.java
[AuditRead]: ../../../../kb-app/src/main/java/com/zhishiku/kb/app/audit/service/AuditQueryService.java
[AuditT]: ../../../../kb-app/src/test/java/com/zhishiku/kb/app/audit/service/AuditSummaryProjectorTest.java
[AuditWebT]: ../../../../kb-web/src/admin/auditApi.test.ts
