# 实现与离线验证记录

日期：2026-11-08。变更：`batch-document-import`，schema：`spec-driven`。

## 已交付实现

- app：固定清单、本人创建幂等与 resolve、分级历史读取、逐项 multipart、实际内容核验、独立尝试键、原子文档/版本/解析任务/回执、确定失败失效、未知提交保留、同步容量准入及到期恢复。
- 持久化：V36 三张账本表、唯一与状态约束、不可逆回执/尝试触发器；旧文档无需回填。未运行数据库迁移，已人工审阅迁移及 MyBatis SQL。
- 对账：版本与 ACTIVE 联合保护，过期未失效仍保护，缺失抽样只查存活版本；宽限期内和未知修改时间的对象不计为孤儿。
- web：`/imports`、`/imports/:importId`，多选/拖拽与固定清单，单并发可终止摘要 Worker、最多双并发队列、同页创建交接、待确认有界读取、主动匹配恢复、最小投影、固定版本独立处理状态、身份及页面生命周期清理。
- gateway：dev 与默认 prod 配置均有独立导入根/子路径路由，150 秒响应等待、认证及 no-store；app 模板与 multipart 限额校验、前端 180 秒单项等待。
- 文档：API、导入运维、对账运维、根与前端 README；人工核对升级暂停清理、app → gateway → web、回退保留账本及旧 app 禁止删除。

## 范围

只执行进程内 Mock、内存仓储/事务、SDK/流替身、受控时钟/屏障、MockMvc、内存 WebFlux 交换及模拟 DOM 测试。未启动应用、HTTP 监听服务、前端开发服务器或真实浏览器，未连接数据库、Redis、MinIO/S3、解析器或模型。

没有编写或执行针对 README、Markdown、注释或说明文档示例内容/存在性的断言。下列 Mapper/配置测试读取的是生产 SQL/YAML 并验证其实际绑定行为。打包检查针对运行产物，不将文档存在视作实现证据。

## 后端白名单

在仓库根目录执行（`-o` 使用本机已有依赖）：

```powershell
mvn -o -pl kb-app,kb-gateway -am test '-Dtest=ImportInputTest,ImportMapperTest,ImportPropertiesTest,ImportConfigurationTest,ImportCreateServiceTest,ImportAccessTest,ImportReadServiceTest,ImportControllerTest,ImportFileChecksTest,ImportAttemptServiceTest,ImportObjectWriterTest,ImportAcceptanceServiceTest,ImportFailureServiceTest,ImportUploadServiceTest,ImportFileControllerTest,ImportAdmissionTest,ImportRecoveryServiceTest,StorageImportMapperTest,StorageReconciliationServiceTest,StorageReconciliationPropertiesTest,S3ObjectStorageTest,DocumentUploadServiceTest,DocumentPublicationUploadTest,DocumentManagementServiceTest,PermissionServiceTest,EpochSessionServiceTest,ModuleBoundaryTest,ImportRouteTest,SaTokenGatewayConfigTest,SearchRouteTest,ConversationRouteTest,InboundHeaderSanitizeFilterTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
```

结果：app **164**、gateway **14**，合计 **178** 项全部通过，0 失败、0 跳过。命令实际输出经 PowerShell `Select-String` 过滤构建和测试摘要，不影响执行范围。

最终人工复核后补充的定向验证：

```powershell
mvn -o -pl kb-app -am test '-Dtest=ImportAdmissionTest,ImportControllerTest,ImportFileControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -pl kb-app,kb-gateway -am package '-Dtest=StorageReconciliationServiceTest,StorageImportMapperTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
```

分别 **12** 项和 **24** 项通过。新增覆盖了编码/矩阵路径不能绕过准入、宽限期边界与未知修改时间不计为孤儿；后者同时完成最终 app/gateway 打包。上述分组有重叠，不叠加为独立用例总数。

## 前端白名单

工作目录 `kb-web`：

```powershell
npm exec vitest run src/imports/api.test.ts src/imports/files.test.ts src/imports/ImportDraft.test.ts src/imports/queue.test.ts src/imports/creation.test.ts src/imports/ImportCreatePanel.test.ts src/imports/pages.test.ts src/imports/verifier.test.ts src/imports/lifecycle.test.ts src/routing.test.ts src/navigation.test.ts src/routing/lazy-routes.test.ts src/documents/recovery.test.ts src/documents.test.ts src/session.test.ts
npm run typecheck
npm run bundle:test
npm run build
npm run bundle:report -- --check-isolation --out .bundle-analysis/current-report.json
```

- 白名单 **15 个文件、125 项**通过；`vue-tsc --noEmit` 通过。
- 补充资格拒绝即时清理批次目标、能力并发应用、初始化匹配互斥后，定向执行 `pages.test.ts files.test.ts queue.test.ts lifecycle.test.ts`，**18 项**通过；此前同页交接等相关定向 **12 项**通过。分组有重叠，不累计用例数。
- 包体工具 **9 项**通过；最终 `npm run build`（含类型检查）通过，产出独立摘要 Worker 和两条导入页面 chunk。
- 最终包体隔离通过：入口/登录累计 gzip **127269 bytes**，文档列表 **233174 bytes**，导入列表/创建页 **177809 bytes**，导入详情 **137711 bytes**。路由场景统计为静态依赖闭包，摘要 Worker 是独立动态资源。没有执行跨变更包体增长比较。
- 入口与文档列表未提前包含 `src/imports/`，导入仍使用现有页面错误边界及身份门禁。

## 构建与结构检查

```powershell
mvn -o -pl kb-app,kb-gateway -am package '-DskipTests' '-Dstyle.color=never'
jar tf 'kb-app/target/kb-app-0.1.0-SNAPSHOT.jar' | Select-String -Pattern 'V36__document_imports.sql|BOOT-INF/classes/application.*yml|ImportRecoveryScheduler.class'
jar tf 'kb-gateway/target/kb-gateway-0.1.0-SNAPSHOT.jar' | Select-String -Pattern 'BOOT-INF/classes/application.*yml|ImportResponsePolicy.class'
openspec validate batch-document-import --strict
git diff --check
```

构建成功，app 产物包含 V36、恢复调度与配置；gateway 产物包含 dev/base 路由配置及响应策略。最后一次代码变动后已再次完成上述定向 `package`。OpenSpec 严格校验通过；diff 检查无空白错误，Git 提示现有 LF/CRLF 转换策略。

## 规格场景映射（人工审阅）

| 规格与场景 | 实现 | 离线证据 |
| --- | --- | --- |
| 固定清单、非法字段/Unicode/大小、同内容独立身份 | `ImportInput`、V36、`ImportCreateService` | `ImportInputTest`、`ImportMapperTest`、`ImportCreateServiceTest` |
| 创建响应丢失、并发唯一竞争、同标识冲突、resolve 无副作用 | 创建/resolve 服务、`ImportCreation` | 创建服务竞争/回滚替身，`creation.test.ts`、`ImportCreatePanel.test.ts` |
| 本人分级读取、他人 404、撤权隐藏、仅 edit、依赖故障与跨登录 | `ImportAccess`、`ImportReadService` | `ImportAccessTest`、`ImportReadServiceTest`、页面与会话测试 |
| 实际内容匹配、伪 MIME/PDF、空/超限与取消 | `ImportFileChecks`、既有 `DocumentFilePolicy` | `ImportFileChecksTest`、旧上传回归、`files.test.ts` |
| 同项互斥、旧标识失效、独立对象键、ACCEPTED 不逆转 | `ImportAttemptService`、V36 约束 | `ImportAttemptServiceTest`、`ImportFailureServiceTest` |
| 最终资格重核、实际空间策略、回执失败整体回滚 | `ImportAcceptanceService` 加入既有文档事务 | Spring 事务代理与内存提交替身、`DocumentPublicationUploadTest` |
| 已提交但响应未知、失败持久失效、迟到旧结果 | `ImportUploadService`、`ImportFailureService` | `ImportUploadServiceTest`、恢复/受理屏障测试；无删除调用入口 |
| 执行到期、崩溃恢复、受理/恢复竞争、恢复失败 | `ImportRecoveryService`、独立调度 | 受控时钟、项锁与已提交快照替身；不访问对象或自动创建文档 |
| 原件保护转换、到期 ACTIVE、失效后迟到残留、删除前复核 | 对账 UNION SQL 与恢复/受理事务 | `StorageImportMapperTest`、`ImportRecoveryServiceTest`、`StorageReconciliationServiceTest` |
| 缺失抽样、已删除版本回收、历史保留 | 仅版本抽样、回执不可逆 | 对账/读取测试，人工审阅无历史删除路径 |
| 资源限额、真实退出占用、身份预检、错误/no-store | 导入配置、准入过滤器、SDK 写入、控制器 | 配置绑定、并发/响应屏障、SDK/流替身、MockMvc |
| 多选/拖拽、标题/移除、无效项保留、单并发摘要 | `ImportDraft`、`FileHasher`、Worker | `ImportDraft.test.ts`、`files.test.ts`；生产 Worker 构建 |
| 部分失败继续、双并发、暂停、429、待确认不重传 | `ImportQueue`、`ImportVerifier` | 可控 Promise、假时钟窗口/失败上限与主动恢复测试 |
| 任务列表/项分页、计数、精确 ID、同名区别、接口不支持 | API 解码、`ImportWorkspace`、`ImportTask` | `api.test.ts`、`pages.test.ts` |
| 重选匹配、新 revision/标识、ACCEPTED 不重传 | `matchFile`、队列发送前读取 | 文件、队列与页面恢复测试 |
| 隐藏/换号/离页清理、迟到隔离、固定版本阅读拒绝 | Session epoch、页面 scope、`ImportProgress` | `lifecycle.test.ts`、队列/创建清理、懒加载路由测试 |
| 完整路由、真实拒绝、独立超时、AI SSE 不受影响 | gateway dev/base route、`ImportResponsePolicy` | `ImportRouteTest`、认证、搜索/会话路由与头净化回归 |

## 修正与未执行项

实施过程中已修正：Mockito 重设有副作用 Answer 引发的替身状态污染、Mapper 测试资源绝对路径、Boot multipart getter 名称、TypeScript Promise 类型/重复窄化检查、测试对象缺失括号。最终定向与白名单均通过，没有放宽超时、删除行为断言或扩大成功条件来掩盖失败。

人工核对修正：单调预算/数据库时钟读取缓存、取消不归为永久内容错误、编码路径准入、撤权即时清理目标与文件、宽限期孤儿计数。宽限期断言从三项无引用全部计数改为仅一项过期对象，并新增精确边界测试，以符合当前及 delta 规格。

按约定排除全部外部集成测试与说明文档断言。没有执行迁移、启动真实服务、测试真实网络取消、数据库跨连接隔离/崩溃提交、S3 迟到 PUT 或生产部署。离线替身仅证明所覆盖的状态转换、调用绑定、事务代理协作和取消/退出控制流，不作为真实数据库原子性或完整生产链路验收结论。变更尚未归档。

## 核验后修复：W1/W2

2026-11-08，根据 `verification-review.md` 的两项 WARNING 重开并完成任务 7.2、7.4，进度恢复为 38/38。

- `kb-web/src/imports/queue.ts`：增加独立于 Session epoch 和项 revision 的资格代次；每次撤权使旧读取及文件匹配失效。摘要/项完整核对只能以原捕获代次恢复资格。单项读取、发送前核对和迟到上传回执统一裁剪旧代次描述，保留可核对的受理事实。
- `kb-web/src/imports/ImportTask.vue`：摘要与项两个请求任一先显示撤权即立即清理，恢复等待两者一致且代次有效；初始化文件交接和重新选文件摘要也携带资格代次，恢复后仍需重选并主动继续。
- 最小投影先于 revision 和 ACCEPTED 单调性检查触发整任务清理，包含其他已加载页、选中文件、空间及密级；保留较新状态和固定回执。
- 将原临时复现并入持久的 `queue.test.ts`、`pages.test.ts`，共新增 15 项：乱序任务读取、分页混合权限、单个读取提前撤权、旧/相同 revision 与终态保护、恢复后的迟到单项/发送前读取/上传回执、旧摘要结果以及主动恢复正向路径。

实际执行（目录 `kb-web`）：

```powershell
npm exec -- vitest run src/imports/queue.test.ts src/imports/pages.test.ts
npm exec -- vitest run src/imports/queue.test.ts src/imports/pages.test.ts src/imports/verifier.test.ts src/imports/lifecycle.test.ts
npm run typecheck
npm exec -- vitest run src/imports
npm run build
npm run bundle:report -- --check-isolation --out .bundle-analysis/current-report.json
```

结果：首次在修复前确认 **12 通过、8 失败**；修复后定向 **25 项通过**；追加迟到回执及摘要等边界回归后，导入模块 **9 个文件、43 项全部通过**。分组有重叠，不累计为独立用例总数。`vue-tsc --noEmit` 与生产构建通过；最终包体隔离通过，入口 gzip 127268、文档列表 233180、导入页 178023、导入详情 137925 bytes。测试继续仅使用进程内替身和模拟 DOM。

仓库根执行 `openspec validate batch-document-import --strict` 通过；apply 再读取确认任务 7.2、7.4 为完成，进度 38/38、`state=all_done`。`git diff --check` 退出码 0（仅现有 LF/CRLF 策略提示，该命令不覆盖未跟踪文件）。
