# 需求与场景证据

范围：28 项需求、95 个场景。下表逐需求列出全部场景及关联实现／离线证据。场景名称表示业务契约；列出的 SQL、持久迁移、真实并发与发布事实仅经人工审阅，不能将替身测试视为真实环境证明。

核验发现的离线覆盖缺口已补充，具体测试方法及 W1–W4 的修复证据见 [remediation.md](remediation.md)。前端新增真实权限 store 的 `recheck.test.ts`；api 夹具现在包含合法的来源新增、保留和移除。下表作为需求导航，方法级证据以修复记录为准。

第二轮发现的预演弹窗恢复问题 R1 已关闭，最新逐项复核及执行结果见 [第三轮报告](verification-report-3.md)。`recheck.test.ts` 现有 21 项，新增文档／空间 403／404／405 原草稿恢复、实际详情页、撤销动作、失权／搬迁／身份／离页清理、显式关闭及候选首页缺席用例；正式白名单合计 145 项通过。

后端实现位于 `kb-app/.../auth/{controller,service,mapper,model}/diagnostics`，配置位于 `config/Diagnostic*`；测试在 `auth/service/diagnostics`。表中简称 Access、Permission、Target、Input、Evaluation、Boundary、Service 对应 `Diagnostic*Test`。前端证据为 `kb-web/src/authorization/{access,api,state,panel,layer}.test.ts`；既有页面回归为 `admin/space-pages.test.ts`，包含批量与单条写入、资格恢复、删除互斥和搬迁流程。

## authorization-diagnostics（14 项／49 场景）

| 需求 | 全部场景 | 实现与证据 |
| --- | --- | --- |
| 先操作者再目标 | 目标身份不代替操作者；无权管理与资源不存在；文档搬迁后再次诊断 | DiagnosticAccess／Service；Access、Permission、Service |
| 最小用户选择 | 没有身份目录资格；现有授权中的停用用户；候选之后停权；不存在用户及游标混用 | 资源 POST 搜索、QueryCursor、TargetLoader；Target、Service、panel；游标资源绑定人工审阅 |
| 单用户明确范围 | 空间诊断不扫描文档；指定同空间一份文档；文档已搬离空间 | Access 路径过滤、固定 Request；Access、Input、Service；管理列表调用人工审阅 |
| 全部命中来源 | 直接与部门同时命中；多个角色同一权限；文档例外只扩权；全局管理不隐含阅读 | sources 与共享 AuthorizationRules；Target、Evaluation、Boundary、Permission；SQL 来源并集人工审阅 |
| 授权与门槛分层 | 密级阻断但已命中；归档后诊断；审核不代表审批；下载与阅读独立 | Evaluator／DiagnosticLayer；Evaluation、layer、Access、DocumentReviewDecisionTest |
| 受控身份密级投影 | 不同身份阻断相同形状；读取失败；操作者另有身份管理权限 | TargetLoader／Views；Target、Boundary、api；模型字段人工审阅 |
| 当前发布范围 | V1 与待审 V2；无当前版本；指针损坏；权限通过但原件故障 | DiagnosticMapper.current 共用发布证据、不查询原件；Evaluation、Boundary、VersionAccessRulesTest、VersionAccessPurposeTest；真实指针 SQL 人工审阅 |
| 单种精确意图 | 完整批量草稿；显式撤销；缺 manage 资格；超限或混合 | Input 共用原写纯校验；Input、Access、api、spaceApi；50／51、300／301、文档四权限回归 |
| 并集与条目变化 | 撤个人仍有部门；撤例外仍有空间；新增 view 仍受密级；无关主体；无变化操作 | Overlay／Evaluator；Evaluation、Service；不展开成员、无角色撤销引用检查 |
| 同次一致读取 | 计算期间变化；下一请求撤权；时间不是凭据 | 独立 RR 事务、captured 行集；Boundary、Service 受控事实交错、Permission；真实隔离未验证 |
| 只读与正式写独立 | 预演无副作用；未预演直接提交；预演不可用后提交 | Database／Overlay 无写调用，原写 API；Boundary、Service、panel、space-pages；只读 SQL 人工审阅 |
| 固定最小投影 | 管理但不可读；只展示命中关系；撤销来源仅在 before | Views、sources 上限、Overlay；Boundary、Evaluation、panel、api |
| 有界与故障 | 来源上限；查询超时；身份注入 | Limits、Input、StatementBudget；Input、Target、Service、Boundary；实际网络与数据库 timeout 未验证 |
| 私有通信与解码 | 目标不一致；旧后端不支持；不进入遥测 | no-store 过滤、固定错误、api 解码；Boundary、api、panel、SearchRouteTest、SearchTermGatewayProtectionTest；HttpObservationConfiguration 固定模板人工审阅 |

## functional-authorization（3 项／12 场景）

| 需求 | 全部场景 | 实现与证据 |
| --- | --- | --- |
| 独立精确资格 | 普通角色独立授予；空间管理无新权限；全局管理无新权限；下次请求撤权 | Permission.AUTHORIZATION_DIAGNOSE、前端 perm；Permission、PermissionCatalogTest、access |
| 真实管理范围 | 只有诊断；可授 manage 但不可管目标；拟自授 manage；自撤最后管理来源 | Access 当前操作者准入与 Overlay 分开；Access、Evaluation；原写资格代码保留 |
| 一次性初始化 | 存量首次迁移；空库引导；撤销后重启；旧实例新权限 | V43、Permission.codes、原 Bootstrap／Validator；TermPermissionTest、Permission；迁移与排空／回退方案人工审阅，真实迁移未执行 |

## admin-web-spaces（6 项／18 场景）

| 需求 | 全部场景 | 实现与证据 |
| --- | --- | --- |
| 双重资格 | 普通空间管理者；资格未确认；已有 USER 行发起 | DiagnosticHost＋AclPanel 父范围；panel、access、space-pages |
| 来源与条件 | 多来源；归档管理；选择不可读文档 | Layer、ID 级同空间管理清单；layer、panel、Evaluation；文档选择过滤人工审阅 |
| 完整草稿绑定 | 修改草稿；换目标用户；不展开批次影响 | fromWrite／binding／generation；api、state、panel；完整 BatchDraft 人工审阅 |
| 前后与保留来源 | 撤销仍可访问；新授权受密级；账号阻断 | Layer／Overlay；Evaluation、layer、panel |
| 与写入独立 | 无预演提交；不支持或故障；未知写入时再次查看 | 原 ResourceWriteDialog、共享写绑定及诊断 blocked；panel、space-pages；失败不调用 submit |
| 私有状态失效 | 只失诊断资格；失资源资格；恢复与持久化 | Host 准入、state、父级 recheck；panel、state、space-pages；只读状态不持久化 |

## admin-web-documents（5 项／16 场景）

| 需求 | 全部场景 | 实现与证据 |
| --- | --- | --- |
| 功能及归属资格 | 管理不可读；只有密级；故障隔离 | DocumentPanel 当前 owner＋DiagnosticHost；panel、access、space-pages；诊断不触发标题请求 |
| 授权与当前版本 | V1／V2；无当前版本；download 缺 view；文档 manage/review | Layer、固定 Current 投影；Evaluation、Input、api、panel、layer；发布指针 SQL 人工审阅 |
| 单项例外 | 撤例外保留空间；授予无法绕过门槛；切草稿后迟到 | fromWrite 单项、Overlay、state；Input、Evaluation、api、panel |
| 父级写入协调 | 未预演授予；删除确认；搬迁 | useDocumentOperations＋共享 write binding＋owner key；panel、space-pages、Access；不结清未知写入 |
| 身份资源生命周期 | 关闭重开；退出后迟到；损坏响应 | Host 清空目标、state generation／epoch、严格解码；panel、state、api |

## 证据边界

独立测试覆盖应用调用、规范化、投影和状态机；SQL 只做 Mapper 装配／参数／结果集替身及人工语义审阅。表中没有真实 SQL 等价、约束／锁／隔离、迁移执行、真实浏览器、部署或联调成功声明。最新命令及计数见 [第三轮报告](verification-report-3.md)，首次失败历史见 [verification.md](verification.md)。
