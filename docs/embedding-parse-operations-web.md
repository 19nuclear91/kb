# 向量重建与解析死信管理页面

管理区新增 `/admin/embedding`（向量重建）和 `/admin/parse/dead-letters`（解析死信），继续使用现有登录、首次改密和退出流程。两条固定路径可作为登录后返回地址，默认登录落点仍为文档中心。

## 入口与独立资格

向量页面读取要求 `embedding:read`，发起和恢复额外要求 `embedding:rebuild`；死信页面读取要求 `parse:deadletter:read`，单条重放额外要求 `parse:deadletter:replay`。只持写权限不开放页面，但不改变服务端独立写权限。角色名称、空间管理或文档编辑资格不代替这四项功能权限；空间目录读取故障不影响已确认的运维资格。

权限未确认、强制改密、退出或身份切换会停止请求并清理资料和表单。筛选、摘要、操作原因、待核对请求只保存在当前内存，不写入地址栏或持久存储。浏览器刷新后不会恢复原未确认请求；关闭等待也不会撤销服务端已经受理的操作。

## 向量重建

页面分别展示全局模式和当前响应所在应用实例的就绪状态。模型清单的联合就绪表示存在新鲜的查询与文档侧探测，不等于全体实例健康。页面只能选择已登记且就绪的 profile，不编辑模型定义、服务地址或密钥。

- UNINITIALIZED：需要显式选择目标并发起初始化，空库也需经过后台校验。
- ACTIVE：展示当前 profile，可用同一 profile 发起修复性重建。
- QUIESCING、REBUILDING、VERIFYING：展示真实阶段、分块计数和下次尝试时间；计数达到总量不表示完成，VERIFYING 仍在自动校验。
- BLOCKED：展示安全错误原因，依赖恢复并确认目标就绪后可恢复原作业，保留成功进度。

维护期间授权 BM25 和上传仍可用，向量召回关闭，普通解析等待。开始确认固定当前控制代次，恢复固定当前作业版本；原因去除首尾空白后为 1–500 个 Unicode code point。202 仅表示受理。页面不提供取消、暂停、手工校验、强制激活或即时回滚。

进行中作业在每轮读取结束后至少 5 秒再刷新，同一时刻只有一轮读取；稳定状态、页面隐藏、读取故障、卸载或身份失效时停止。后台恢复先重新确认身份与资格；故障后显式重新读取。ACTIVE 且 runId 为空时，整页刷新只展示当前系统状态，不补造历史作业。

## 解析死信

列表默认 OPEN、第一页、每页 20 条，可切换 50/100 条，支持状态、失败分类和本地时间输入。提交时间转换为带时区时间，起点包含、终点不包含；两端存在时起点须早于终点。“全部”省略对应筛选参数。总数和身份保留大整数精度，翻页或筛选会隔离旧响应。

列表按进入、提交筛选、翻页、手动刷新和确认重放事件读取，不定时轮询；每次实际查询使用新的请求标识并保留查询审计。摘要抽屉只展示已有列表投影，不联查正文或身份目录，也不根据版本标识推测文档路径。没有处置时间时显示未处置，不补造服务端未提供的处置人和原因。

仅 OPEN 死信可单条重放，确认时核对死信、任务与版本身份，原因去除首尾空白后为 1–2000 个 Unicode code point。成功表示原死信已重放、新周期重新入队，仍受 Embedding 维护和普通领取规则约束；不表示故障已修复或文档已可检索。首版无批量重放、手工解决或历史重建列表。

## 结果不确定与兼容性

网络错误、5xx 或无法核对成功回执时显示结果待确认，冻结原目标、原因和版本。可按原内容与原请求标识重试，或重新读取事实；记录从筛选结果中消失并不能单独确认本次成功。放弃核对只清理本地绑定，新提交使用新标识。404/409 重新读取后需要重新确认，不自动补发；403 重新确认当前权限。

死信标识上限为 256、原因上限为 2000，超长或含 NUL 返回 400，取消旧版静默截断；未知筛选及无效时间同样返回 400。不同操作者、目标或原因复用标识返回 409。旧记录只按保存的事实核对，被旧实现截断的原始文本不能还原。服务端锁与升级兼容说明见 [解析任务恢复](parse-task-recovery-operations.md)。

## 验证范围

本次仅使用 Mock/Stub 的进程内单元与组件测试、受控时钟、内存路由、类型检查和静态构建。没有启动应用、数据库、Redis、对象存储、真实模型或浏览器；不包含集成测试、端到端测试或文档内容测试。Mock 的编排结果不证明实际数据库互斥、事务回滚或生产运行效果。

## 进程内验证记录（2026-10-23）

后端在项目根目录执行如下白名单，kb-app 27 项、kb-worker 14 项通过，0 失败、0 跳过；kb-common 在构建链中编译，不运行白名单以外的用例。

```powershell
mvn -o -pl kb-app,kb-worker -am test '-Dtest=ParseDeadLetterServiceTest,ParseDeadLetterValidationTest,ParseDeadLetterControllerTest,OperationsErrorPolicyTest,IdentityManagementAuthorizerTest,EmbeddingCommandTest,GlobalExceptionHandlerTest,ParseMaintenanceServiceTest,ParseRecoveryIdentityTest,ParsePublishIdentityTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

前端在 `kb-web` 目录执行下列隔离用例清单，16 个文件、225 项通过。随后增加两个迟到响应隔离用例并收紧可选响应字段校验，对变动的两个文件定向重跑 40 项全部通过；这 40 项与前轮部分重叠，不累计为独立用例数。

```powershell
npm test -- --run src/admin/operationsApi.test.ts src/admin/operations-pages.test.tsx src/admin/operations-routing.test.tsx src/admin/hooks.test.tsx src/admin/api.test.ts src/admin/access.test.tsx src/admin/access-spaces.test.tsx src/admin/spaceApi.test.ts src/admin/space-pages.test.tsx src/admin/users.test.tsx src/admin/departments.test.tsx src/admin/roles.test.tsx src/navigation.test.tsx src/routing.test.tsx src/session.test.ts src/api.test.ts
npm test -- --run src/admin/operations-pages.test.tsx src/admin/operationsApi.test.ts
npm run build
```

`npm run build` 包含 TypeScript 类型检查和 Vite 静态构建。上述命令不会运行真实数据库烟测、样本文档解析、应用监听或浏览器验收，也未执行说明文档内容断言。
