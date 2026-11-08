# 独立核验报告：batch-document-import

日期：2026-11-08。Schema：`spec-driven`。本报告对应 `/openspec-verify-change`，与实施记录 `verification.md` 分开保存。

## 第二轮独立核验结论（当前）

**全部适用核验通过：0 项 CRITICAL、0 项 WARNING、0 项 SUGGESTION。W1、W2 关闭，可进入归档流程。**

| 维度 | 当前结果 |
| --- | --- |
| Completeness | 任务 38/38 完成；30/30 条 ADDED/MODIFIED 需求找到实现；无 REMOVED/RENAMED 需求，无不可读取的跟踪文件。 |
| Correctness | 30/30 条需求、80 个场景完成代码与离线证据映射；原 U9/U11 偏差已消除；本轮额外 2 项实际 Session/API 配合内存传输的竞争实验通过。 |
| Coherence | 10 组设计决策与项目结构一致；资格代次现已覆盖任务读取、单项读取、发送前核对、回执及摘要结果，符合设计第 8 节。 |

本轮重读 status/apply、proposal、design、tasks、三份 delta 及既有证据，复核下方全部需求/场景映射；重点重新审阅队列、页面、API、Session 生命周期以及后端归属/受理/恢复、V36、联合保护 SQL、配置与网关边界。没有材料缺失导致的 Not verified 项；按设计明确排除的真实数据库、外部存储/网络、部署验收及说明文档断言不计作跳过检查。

### W1/W2 关闭依据

| 问题 | 当前实现 | 持久回归证据 |
| --- | --- | --- |
| W1：旧成功读取恢复撤权清单 | `kb-web/src/imports/queue.ts:32-35,64-73` 捕获/推进资格代次；`ImportTask.vue:49-69` 完整核对成功且代次有效才恢复目标和描述。 | `pages.test.ts:85-118` 同会话拒绝后释放旧摘要/清单，仍不显示；新核对恢复后需重新选择并主动继续。 |
| W2：最小投影漏清其他页或被 revision 丢弃 | `queue.ts:37-46,84-93,108-116` 在状态排序前整体撤权；`ImportTask.vue:55-63` 任一请求观察到撤权立即生效。 | `queue.test.ts:61-91` 覆盖页/单项/发送前、旧/同 revision、ACCEPTED 保留及跨项文件清理；`pages.test.ts:121-152` 覆盖分页和双请求乱序。 |
| 恢复后的旧回调隔离 | `queue.ts:75-94,104-125` 使用捕获代次投影；`ImportTask.vue:31-44,73-83` 将代次传入文件交接。 | `queue.test.ts:94-124`、`pages.test.ts:155-178` 覆盖迟到单项/发送前读取/回执和摘要，不恢复描述、选中文件或自动发送。 |

原始映射中的 D4、U9、U11 对 W1/W2 的引用现按上表关闭。其余 27 条需求及相应场景映射仍成立；前端行号因修复顺移，当前主要入口为：队列调度 `queue.ts:56-61,96-135`、状态核对 `:75-94`、清理 `:137-141`、任务读取 `ImportTask.vue:49-71`、文件匹配 `:73-84`、汇总/回执展示 `:98-116`。

### 本轮新增实验与执行证据

为验证此前 API 替身未覆盖的协作边界，使用临时 `kb-web/src/imports/verification-recheck.tmp.test.ts` 挂载生产 `ImportTask`，保留实际 `Session`、`importApi`、JSON 编解码和状态核心，仅以可控 Promise/内存 Response 替换 fetch 传输。

执行目录 `kb-web`：

```powershell
npm exec -- vitest run src/imports/verification-recheck.tmp.test.ts --reporter=verbose
```

1. 实际 Session 收到单项 403 后重读 `/api/auth/me`，账号仍有效且 epoch 不变；随后释放旧成功摘要/清单，名称和目标仍不可见；新的任务核对恢复描述且无文件上传：**通过**。
2. 下一页返回省略 `file` 的真实协议响应，摘要暂未结束；已加载页和目标立即清理，之后旧有资格摘要也不能恢复目标，同时保留固定受理回执：**通过**。

结果 **2/2 通过**，退出码 0；实验文件已移除。这两项只证明进程内 Session/API/组件协作，没有 HTTP 监听服务、真实浏览器或外部组件。

同时采用当前修复版本已执行的导入模块 **43/43 通过**、类型检查、生产构建与包体隔离证据（见 `verification.md:99-121`），以及此前后端离线白名单和完整场景审阅证据；本轮未重复执行这些已通过的检查，也不将不同批次/重叠测试相加。`openspec validate batch-document-import --strict` 本轮再次通过；写入报告前 `openspec list --json` 确认 root 为 `D:\WorkProject\kb`。

### 最终评估

在约定的代码审阅与进程内离线范围内，完整性、正确性和一致性均通过，没有未解决问题或跳过的适用检查。**可以归档。**真实数据库事务、跨连接竞争、网络退出及生产部署的验证边界沿用设计和实施记录。

---

## 修复阶段记录

2026-11-08：**W1、W2 已修复并通过定向回归**。`ImportQueue` 统一管理上传资格代次，旧任务读取、单项读取、发送前核对、上传回执及摘要结果均不能跨撤权代次恢复描述或文件。最小投影在状态 revision/ACCEPTED 筛选之前清理整个任务；批次与项读取任一先观察到撤权即立即清理，恢复则须同代次的完整核对。

已补充 15 项竞争及恢复回归，导入模块 9 个测试文件 **43/43 通过**；类型检查、生产构建和包体隔离通过。任务重新完成至 38/38。具体命令与证据见 [实现记录的核验后修复章节](verification.md#核验后修复w1w2)。该修复阶段记录只描述定向验证；当前独立核验结论见上节。下面保留修复前的发现和复现记录。

## 原始核验结论（修复前）

**0 项 CRITICAL、2 项 WARNING、0 项 SUGGESTION。任务与实现入口完整，但前端撤权清理存在已复现的响应竞争问题。**

| 维度 | 核验结果 |
| --- | --- |
| Completeness | 38/38 任务完成；30/30 条 ADDED/MODIFIED 需求找到实现。没有 REMOVED/RENAMED 需求，也没有不可读取的跟踪文件。 |
| Correctness | 30 条需求、80 个场景均完成实现/离线测试证据映射；28 条需求未发现偏差，前端最小披露与请求代次隔离两条存在 W1/W2。新增 4 个反例断言失败，相关既有 12 项通过。 |
| Coherence | 后端事务、尝试隔离、对账保护、限额、路由及离线验证范围符合设计；设计第 8 节的本地资格/请求代次隔离不完整，见 W1/W2。目录与项目模式无独立建议项。 |

以上检查均在约定的代码审阅与进程内离线范围内完成，没有因材料缺失而跳过的检查。真实数据库迁移、跨连接锁/提交、网络退出、S3 和部署验收属于明确排除范围，不能由本报告证明，也不新增为归档前置条件。

按本次核验规则，具备**带警告归档**的条件；建议先修复以下两项权限竞争问题。本结果不等于全部检查通过。

## 原始 WARNING（已关闭）

### W1：较早发出的成功读取会恢复已撤权的清单

- 位置：`kb-web/src/imports/ImportTask.vue:48-58`，尤其第 55 行；`kb-web/src/imports/queue.ts:56-60`。
- 关联规格：`specs/user-web-documents/spec.md:125-135`（按当前资格暂停并最小披露）、`:153-163`（身份及请求代次隔离）；设计第 8 节。
- `load()` 与单项核对/上传并行。它只检查组件是否卸载，收到 `canContinue=true` 就调用 `queue.authorize()`，没有检查读取开始后是否发生过撤权。`revoke()` 没有推进可供 `load()` 比较的资格代次。
- 已复现顺序：页面先显示合法清单 → 发起单项核对 → 发起任务刷新并挂起其成功响应 → 单项核对返回 403，页面清理成功 → 释放较早生成的摘要和清单响应 → 原文件名、标题、空间及密级重新显示，恢复文件选择入口。
- 这是同一有效 Session 内的 edit 撤销竞争。`Session.handle403()` 在身份仍有效且不要求改密时不会推进 epoch（`kb-web/src/session.ts:222-236`），因此 Session epoch 检查不能替代上传资格代次。
- **修正建议：**将资格/读取代次交给统一协调器管理；撤权时推进代次并失效此前所有清单/摘要读取。应用响应前比较捕获代次，只有撤权之后发起且完成一致资格核对的读取才能恢复描述。补充上述可控响应乱序组件测试，断言旧成功响应不能恢复字段或选择入口，新核对成功仍需主动选择/继续。

### W2：最小投影清理仅作用于部分项，且可能被状态排序跳过

- 位置：`kb-web/src/imports/queue.ts:31-41`、`:70-76`、`:92-100`；批次读取合并见 `kb-web/src/imports/ImportTask.vue:52-56`。
- 关联规格：`specs/user-web-documents/spec.md:125-135`；后端最小投影语义见 `ImportReadService.java:89-93`。
- `setItems()` 遇到省略 `file` 的项只删除该项本地文件并暂停，未整体撤权；其他已加载页的描述、选中文件以及批次空间/密级可继续保留。摘要与项是两个独立请求，摘要在撤权前得到 `canContinue=true`、项在撤权后得到最小投影，是合法的响应组合。
- `setItems()`、`check()` 和发送前读取均先检查 revision/ACCEPTED 单调性，再处理 `file` 省略。项状态 revision 不代表权限版本：迟到的低 revision 最小投影可能被直接丢弃或转换为 503，从而漏掉清理。
- 已复现三个反例：①加载第 1 页后，第 2 页返回最小投影但摘要仍为 true，第 1 页名称和目标仍显示；②低 revision 最小页投影保留所有旧描述及选中文件；③单项核对收到低 revision 最小投影后抛错，队列仍未撤权。
- **修正建议：**将清单披露资格与项状态合并拆开；在 revision/终态筛选之前消费省略字段的撤权信号，对整个任务清理描述、文件引用和批次目标，同时保留较新的最小状态及固定回执。将该信号接入 W1 的资格代次，防止后续旧响应重新填充。为分页混合资格、低 revision、已有 ACCEPTED 及发送前核对分别增加进程内竞争回归。

## 第一轮实验（修复前）

使用临时 `kb-web/src/imports/verification-review.tmp.test.ts`，直接挂载生产 `ImportTask` 或调用生产 `ImportQueue`；API 使用可控 Promise，Session 替身固定为有效同一 epoch。测试结束后已移除临时文件。

执行目录 `kb-web`：

```powershell
npm exec -- vitest run src/imports/verification-review.tmp.test.ts src/imports/queue.test.ts src/imports/pages.test.ts --reporter=verbose
```

| 核验 | 结果 |
| --- | --- |
| 旧摘要/清单响应不能恢复撤权描述 | 失败；DOM 重新包含 `private-1.txt`、私有标题、固定空间与密级 |
| 下一页最小投影应清理全部已加载页 | 失败；第 1 页名称与固定目标仍在 DOM |
| 旧 revision 最小页投影应清理资格和文件 | 失败；旧描述和选中文件仍保留 |
| 旧 revision 最小单项投影应触发撤权 | 失败；`denied` 仍为 false |
| 既有 `queue.test.ts` | 6/6 通过 |
| 既有 `pages.test.ts` | 6/6 通过 |

合计 **12 通过、4 失败**，命令退出码 1。失败断言针对所要求的行为，不是构建或测试环境错误。四个反例归为上述两个根因，不重复计为四条问题。

此前后端 178 项、前端 125 项、后续定向检查、构建和包体隔离结果使用实施记录 `verification.md` 作为既有执行证据；本轮审阅了相关实现及测试，不把这些数目宣称为本轮重新执行或与重叠分组相加。

本轮 `openspec validate batch-document-import --strict` 通过；`git diff --check` 退出码 0（仅现有 LF/CRLF 策略提示，且该命令不覆盖未跟踪文件）。报告写入前已通过 `openspec list --json` 确认 root 为 `D:\WorkProject\kb`。

## 原始需求与场景映射（按第二轮关闭依据更新判定）

以下按 delta 中需求顺序编号。每行列出该需求全部场景与对应证据；“已映射”表示代码控制流和关联离线测试已审阅，不表示每个场景都有单独测试方法，也不表示真实外部环境已经验证。

路径缩写：

- `A` = `kb-app/src/main/java/com/zhishiku/kb/app/document/imports/`
- `AT` = `kb-app/src/test/java/com/zhishiku/kb/app/document/imports/`
- `W` = `kb-web/src/imports/`
- `ST` = `kb-app/src/test/java/com/zhishiku/kb/app/storage/`

### document-imports：16 条需求，38 个场景

| 编号与需求 | 全部场景 | 实现位置 | 关联离线证据与判断 |
| --- | --- | --- | --- |
| D1 冻结完整上传意图 | 创建合法批次；清单包含非法项；创建后更换目标或内容 | `A/support/ImportInput.java`；`A/service/ImportCreateService.java:33-67`；`A/web/ImportController.java:124-141` | `AT/ImportInputTest.java:22-93`、`ImportFileControllerTest`、`ImportUploadServiceTest`；已映射 |
| D2 本人创建幂等及只读核对 | 批次响应丢失后重试；相同标识并发或改动清单；核对不触发写入 | `A/service/ImportCreateService.java:33-75`；`A/mapper/ImportMapper.java:39-58` | `AT/ImportCreateServiceTest.java:79-146`；仓储竞争/回滚替身；已映射 |
| D3 项身份独立于文件名和内容 | 同批同名同内容；再次主动导入 | `A/service/ImportAttemptService.java:61-86`；`ImportCreateService.java:43-60` | `ImportInputTest`、`ImportAttemptServiceTest.java:68-77`、`ImportCreateServiceTest.java:122-127`；已映射 |
| D4 本人分级读取 | 读取他人任务；上传资格撤销后核对历史；身份或资格依赖不可用 | `A/service/ImportReadService.java:28-94`；`ImportAccess.java` | `AT/ImportReadServiceTest.java:86-112`、`ImportAccessTest`、`ImportControllerTest`；后端投影已映射，消费端问题见 W1/W2 |
| D5 每项提交及重放重核资格 | 仅有空间编辑资格；原件传输期间停权或归档；无资格时重放写请求 | `A/service/ImportAttemptService.java:42-68`；`ImportAcceptanceService.java:34-55` | `AT/ImportAccessTest.java:42-87`、`ImportAcceptanceServiceTest.java:122-139`、`ImportCreateServiceTest`；已映射 |
| D6 实际文件核验 | 同名同大小的文件已改变；伪造文件类型 | `A/service/ImportFileChecks.java:20-76`；`ImportUploadService.java:34-62` | `AT/ImportFileChecksTest.java:21-72`、`ImportUploadServiceTest.java:95`；真实内存字节，已映射 |
| D7 同项至多一份文档 | 相同项同时上传；项成功后响应丢失；文档后来被删除 | `A/service/ImportAttemptService.java:61-86`；V36 不可逆回执；重放不依赖文档当前状态 | `AT/ImportAttemptServiceTest.java:79-117`、`ImportUploadServiceTest.java:66-77`；删除后仍固定回执由控制流与不可逆约束支持，已映射 |
| D8 新主动尝试隔离旧执行 | 旧失败请求迟到；旧标识重新发送；修正为另一份文件 | `A/service/ImportAttemptService.java:62-86`；`ImportFailureService.java`；`ImportAcceptanceService.java:52-70` | `AT/ImportAttemptServiceTest.java:92-107`、`ImportFailureServiceTest.java:46`、`ImportRecoveryServiceTest.java:113-122`；已映射 |
| D9 原子受理回执 | 受理完成；保存回执时故障 | `A/service/ImportAcceptanceService.java:28-63`；既有 `DocumentWriteService.create` | `AT/ImportAcceptanceServiceTest.java:99-118`；真实 Spring 事务代理加内存事务替身，已映射 |
| D10 未知提交不误删/重建 | 数据库已提交但连接断开；原件存在但未创建文档 | `A/service/ImportUploadService.java:49-65`；`ImportRecoveryService.java:50-63` | `AT/ImportUploadServiceTest.java:66-93`、`ImportRecoveryServiceTest`；无异常直接删除分支，已映射 |
| D11 过期持久失效 | 应用崩溃后恢复；提交与恢复竞争 | `A/service/ImportRecoveryService.java:32-63`；`ImportMapper.java:84-88,114-142` | `AT/ImportRecoveryServiceTest.java:113-159,224-255`；受控时钟/项锁替身，已映射 |
| D12 部分成功及准确汇总 | 部分文件失败；失败项主动重试 | `A/model/ImportModels.java`；`ImportReadService.java:79-86`；`ImportMapper.java:69-76` | `AT/ImportInputTest.java:95`、`ImportReadServiceTest.java:47-59`；`W/queue.test.ts:17-24,43-49`；已映射 |
| D13 有界持久查询及历史保留 | 重新登录查看；非法或跨任务游标 | `A/service/ImportReadService.java:28-68`；`A/support/ImportCursor.java`；V36 表/索引 | `AT/ImportReadServiceTest.java:72-83,110-112`、`ImportMapperTest.java:32-42`；没有账本清理入口，已映射 |
| D14 区分受理、处理和阅读 | 未配置解析器；无后续阅读资格 | `A/service/ImportAcceptanceService.java:57-62`；`W/ImportProgress.vue:14-27` | 既有文档写入/发布测试；`W/lifecycle.test.ts:14-28`；已映射 |
| D15 容量及实际执行占用 | 达到容量上限；超时但底层仍在退出 | `A/ImportProperties.java:23-74`；`A/web/ImportAdmissionFilter.java:45-63`；`ImportObjectWriter.java:48-87` | `AT/ImportPropertiesTest`、`ImportConfigurationTest`、`ImportAdmissionTest.java:40-107`、`ImportObjectWriterTest`；同步退出屏障，已映射 |
| D16 路由及诊断披露 | 经网关访问导入接口；存储错误夹带敏感内容 | gateway dev/base 独立路由及 `ImportResponsePolicy`；`A/web/ImportExceptionHandler.java`；`ImportObjectWriter.java:60-61` | `ImportRouteTest.java:30-87`、`SaTokenGatewayConfigTest`、`AT/ImportObjectWriterTest.java:81`、控制器测试；内存路由/交换，已映射 |

### storage-reconciliation：3 条需求，13 个场景

| 编号与需求 | 全部场景 | 实现位置 | 关联离线证据与判断 |
| --- | --- | --- | --- |
| S1 桶起点识别孤儿 | 上传成功但写库失败且补偿删除失败；软删除文档的版本对象删除失败；宽限期保护进行中的上传；存活引用不受文档状态影响；导入尝试时间已过但尚未失效 | `kb-app/src/main/resources/mapper/StorageReconciliationMapper.xml:8-29`；`StorageReconciliationService.java:133-153,216-244` | `ST/StorageReconciliationServiceTest.java:99-197,436-444,457-468`；`StorageImportMapperTest.java:18-32`；已映射 |
| S2 缺失检测只读且区分失败 | 引用存在但对象缺失；检查请求本身失败；检测关闭或上限为零；已登记导入但尚未写入原件 | `StorageReconciliationMapper.xml:32-39`；`StorageReconciliationService.java:186-204` | `ST/StorageReconciliationServiceTest.java:317-367,446-455`；`StorageImportMapperTest.java:34`；已映射 |
| S3 保护与受理/失效一致 | 清理与受理并发；删除前旧执行已失效；已受理文档删除后留有导入历史；导入引用查询失败 | 联合保护 SQL；`ImportAcceptanceService.java:57-61`；`ImportRecoveryService.java:50-63`；`StorageReconciliationService.java:167-176` | `AT/ImportRecoveryServiceTest.java:162-198`；`ST/StorageReconciliationServiceTest.java:235-264,305-314,457`；已提交快照/屏障替身，已映射 |

### user-web-documents：11 条需求，29 个场景

| 编号与需求 | 全部场景 | 实现位置 | 关联离线证据与判断 |
| --- | --- | --- | --- |
| U1 上传提交与解析发布分离 | 上传成功后尚未完成解析；上传成功但没有后续查看权；上传响应丢失；批量项响应丢失 | 既有上传组件；`W/ImportProgress.vue:14-27`；`queue.ts:89-115` | `W/lifecycle.test.ts:14-28`、`queue.test.ts:31-49`；`kb-web/src/documents.test.ts`、`documents/recovery.test.ts`；已映射 |
| U2 本地多文件入口 | 只有编辑权的空间；编辑清单；不支持的导入来源 | `W/ImportWorkspace.vue:23-35`；`ImportDraft.vue:19-59`；`files.ts:3-20` | `W/ImportDraft.test.ts`、`files.test.ts:6`；上传空间使用既有专用候选接口，已映射 |
| U3 服务端限额与摘要 | 文件超限；摘要计算时离页 | `W/ImportDraft.vue:15-38`；`files.ts:23-46`；`hash.worker.ts` | `W/files.test.ts:6-23`、`api.test.ts`；生产 Worker 构建证据，已映射 |
| U4 创建未知保留原绑定 | 创建响应丢失；主动匹配重试 | `W/creation.ts`；`ImportCreatePanel.vue`；`api.ts:93-102` | `W/creation.test.ts:8-25`、`ImportCreatePanel.test.ts`；已映射 |
| U5 有界队列与部分成功 | 单项失败而其他项成功；连续点击开始；暂停后续上传 | `W/queue.ts:49-54,81-118` | `W/queue.test.ts:17-30`；已映射 |
| U6 待确认先核对后恢复 | 服务已受理而页面未收到回执；状态仍不确定；过期执行已恢复 | `W/queue.ts:62-79,109-115`；`verifier.ts:26-51` | `W/queue.test.ts:31-49`、`verifier.test.ts:15-31`；已映射 |
| U7 跨刷新/登录持久记录 | 刷新后查看已受理项；新页面连接旧服务 | 路由和 `ImportDetailPage.vue`；`W/ImportWorkspace.vue:15-21`；`ImportTask.vue:27-58` | `kb-web/src/routing.test.ts:23-33`、`W/pages.test.ts:19-44`、`lifecycle.test.ts:30-52`；已映射 |
| U8 重选文件精确匹配 | 同名文件内容变化；多项内容完全一致 | `W/files.ts:48-50`；`ImportTask.vue:60-70`；`queue.ts:89-105` | `W/files.test.ts:25`、`pages.test.ts:36-44,55-70`、`api.test.ts:18`；已映射 |
| U9 当前资格暂停及最小披露 | 上传中撤销 edit；当前仍能阅读文档；上传容量拒绝 | `W/queue.ts:31-41,56-79,109-115`；`ImportTask.vue:21-26,48-58`；独立 `ImportProgress` | 常规路径有 `W/pages.test.ts:46-53,72-81`、`queue.test.ts:31-36,51-57`；**竞争路径 W1/W2 已复现偏差** |
| U10 汇总与处理状态独立 | 全部受理但尚未解析；处理详情被拒 | `W/ImportTask.vue:84-97`；`ImportProgress.vue:14-27` | `W/pages.test.ts:36-44`、`lifecycle.test.ts:14-28`；已映射 |
| U11 身份及请求代次隔离 | 换号后旧响应到达；页面隐藏后重新进入；大整数与恶意文件名 | Session epoch、`useReviewScope`、`W/api.ts:33-35,104-112`、`queue.ts:22-28,120-123` | `W/lifecycle.test.ts:30-52`、`queue.test.ts:38-42`、`api.test.ts:11`、`pages.test.ts:36-44`；Session/页面隔离有证据，**同 Session 资格代次缺口见 W1/W2** |

## 第一轮设计与模式一致性记录

1. 独立清单 JSON/逐项 multipart，复用 `DocumentWriteService` 的 REQUIRED 事务写入，未走既有上传补偿删除分支：符合设计 1、4、5。
2. V36 提供三表、唯一项/请求/代次/对象键、ACTIVE 唯一索引及不可逆触发器；回执与新尝试、失败恢复的身份边界明确：符合设计 2、3、5。迁移仅人工审阅，Mapper 测试不是数据库执行。
3. 最终受理 REQUIRES_NEW/READ_COMMITTED 短事务，锁项和空间、清缓存后重核身份，SQL 再检查截止；对象 SDK 调用位于事务外：符合设计 5。
4. ACTIVE 保护不按期限过滤，受理同事务切换为版本引用，失效与释放保护共提交；缺失抽样仅版本：符合设计 6。
5. 后端本人最小投影与文档独立阅读权限分离：符合设计 7。前端消费端的撤权时序存在 W1/W2。
6. Vue 桥接纯 TS 队列、Worker 单并发、懒路由、Session/隐藏生命周期沿用项目结构。设计 8 明确的本地 generation 未完整落实；修正可参考 `kb-web/src/documents/deletion.ts:50-86` 的代次捕获/失效模式，以及 `kb-web/src/documents/recovery.test.ts:203` 的旧能力响应晚于拒绝测试。
7. 默认 100 项/1 GiB、浏览器 2、实例 8/用户 2、120/60/150/180 秒预算、分页与账本保留符合设计 9；配置绑定测试使用实际 YAML。API/运维文档已人工对照实现审阅。
8. 仓储/事务/SDK/流替身、MockMvc、内存 WebFlux 和模拟 DOM 符合设计 10；本轮未执行说明文档内容或存在性断言。

## 第一轮后续验证建议（现已落实）

修正 W1/W2 后保留上述四类离线反例为回归，并补充“最新核对恢复资格后仍需用户主动选择/继续”和“清理描述时保留较新 ACCEPTED 回执”的正向断言。随后执行受影响的队列、页面、生命周期测试及前端类型检查，再更新核验结论。
