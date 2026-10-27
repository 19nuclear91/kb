# Embedding 离线验证记录

## 2026-10-07 W3-W5 四场景补测回归

本轮仅修改测试、白名单和验证/任务记录，不修改生产代码、依赖或迁移。新增 15 个参数化用例：维护期既有文档上传 4 例、不同请求/目标竞争
START 2 例、部分重建后拒绝旧模型 3 例、RESUME 三种恢复阶段的锁前/内预检失败 6 例。

定向命令与日志：

```powershell
mvn -o -pl kb-app,kb-worker -am test '-Dtest=DocumentMaintenanceContractTest,ParseTaskClaimServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' *> 'target\embedding-upload-focused-20261007-155036.log'
mvn -o -pl kb-app -am test '-Dtest=EmbeddingCommandScenarioTest,EmbeddingCommandTest,EmbeddingCommandControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' *> 'target\embedding-command-scenarios-focused-20261007-155356.log'
```

上传/领取 12/12 通过（app 6、Worker 6，15:50:49 完成）；命令 21/21 通过（app 21，15:54:08 完成）。随后增加 ER-07 的 RESUME 修改目标字段
400 断言，最终完整白名单包含此断言。通过数按参数化执行次数计，不按断言数计。

新增 `EmbeddingCommandScenarioTest` 纳入白名单，不移除原测试。完整离线打包命令：

```powershell
$tests = @(Get-Content -LiteralPath 'docs\embedding-offline-tests.txt' | Where-Object { $_.Trim() })
$selector = $tests -join ','
mvn -o package "-Dtest=$selector" '-Dsurefire.failIfNoSpecifiedTests=false' *> 'target\embedding-four-scenarios-final-20261007-155642.log'
openspec validate 'embedding-profile-consistency-and-rebuild' --strict
```

| 模块         | 执行/通过 | 失败 | 错误 | 跳过 | 打包    |
|------------|-------|----|----|----|-------|
| kb-common  | 23    | 0  | 0  | 0  | 成功    |
| kb-app     | 261   | 0  | 0  | 0  | 成功    |
| kb-gateway | 8     | 0  | 0  | 0  | 成功    |
| kb-worker  | 102   | 0  | 0  | 0  | 成功    |
| kb-ai      | 3     | 0  | 0  | 0  | 成功    |
| 合计         | 397   | 0  | 0  | 0  | 全模块成功 |

2026-10-07 15:57:09（Asia/Shanghai）完成，`BUILD SUCCESS`，OpenSpec strict 通过。首次完整执行
`target/embedding-four-scenarios-offline-20261007-155510.log` 也为 397 项通过；两次完整执行和定向结果不累加。最终数量取最终日志的各模块汇总，包含嵌套测试。白名单
83 个名称对应 88 个顶层 `*Test.java` 测试类；其余 73 个顶层测试类未执行，不计为通过。与前轮 382 项相比增加 15 项，无原测试被移除。

DP-01 使用真实上传/版本服务配合内存原件、版本和任务 Stub，验证旧
current_version/indexed、授权读取及等待状态；组合普通领取门禁和授权查询静态证据，不作为服务间集成测试。ER-06 通过独立
MockMvc、真实命令服务及保存状态的 Mapper Stub 确定性交错两个合法请求，只允许一个 run/成功审计；ER-07 检查原目标、成功进度及维护模式；ER-20
使用真实 readiness 服务与可控时钟触发过期，限制全部 Mapper 写调用，包含禁止 resumeItems 和成功审计。具体方法映射见
`docs/embedding-openspec-verification.md`。

任务 11.1-11.3 完成后为 39/39，W3-W5 专门测试缺口关闭，W1/W2 与 UUID 修复保持通过。未启动或连接
PostgreSQL、Redis、MinIO、Docker、真实模型、监听服务或完整业务应用；未验证真实锁互斥、事务回滚、迁移、多进程或崩溃恢复，不宣称生产验证完成。未同步主规格或归档。以下段落保留各次历史状态，不代表当前仍有测试缺口。

## 2026-10-07 W1/W2 偏差修复回归

激活服务移除本地模型注册表依赖，按独占锁内持久目标 ID/fingerprint 校验集群 query/document readiness。查询实例新增
`EmbeddingInstanceReadiness`，周期探测共用启动 UUID，`GET /api/admin/embedding/status` 返回 `localEmbedding`；缺少目标配置时报告
MISSING_PROFILE 并保持 BM25，控制不可读仍报错。日志仅在固定原因变化时输出，不引入公开端点、外部依赖或迁移。

定向命令：

```powershell
mvn -o -pl kb-app,kb-worker -am test '-Dtest=EmbeddingProbeTest,EmbeddingActivationTest,EmbeddingInstanceReadinessTest,EmbeddingReadinessSchedulerTest,EmbeddingAdminReadTest,QueryConsistencyTest,SearchServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

2026-10-07 14:54:35（Asia/Shanghai）完成：common 2、app 14、Worker 7，合计 23 项全部通过。日志：
`target/embedding-deviations-focused-20261007-145415.log`。

新增 Worker 激活测试 2 项、app 实例状态测试 5 项，并扩展原查询、调度和管理 API
测试断言。覆盖无本地模型的协调者、重复激活、双侧探测缺失/过期/身份与协议错误，以及实例身份、缺配置不调用旧模型、探测时效与恢复、控制不可读、较旧
epoch 拒绝覆盖、API 字段与日志脱敏。全部使用进程内 Mock/Stub/固定时钟，不连接数据库或模型。

完整白名单增加 `EmbeddingInstanceReadinessTest`，没有移除原测试：

```powershell
$tests = @(Get-Content -LiteralPath 'docs\embedding-offline-tests.txt' | Where-Object { $_.Trim() })
$selector = $tests -join ','
mvn -o package "-Dtest=$selector" '-Dsurefire.failIfNoSpecifiedTests=false' *> 'target\embedding-deviations-offline-20261007-145709.log'
openspec validate 'embedding-profile-consistency-and-rebuild' --strict
```

| 模块         | 执行/通过 | 失败 | 错误 | 跳过 | 打包    |
|------------|-------|----|----|----|-------|
| kb-common  | 23    | 0  | 0  | 0  | 成功    |
| kb-app     | 246   | 0  | 0  | 0  | 成功    |
| kb-gateway | 8     | 0  | 0  | 0  | 成功    |
| kb-worker  | 102   | 0  | 0  | 0  | 成功    |
| kb-ai      | 3     | 0  | 0  | 0  | 成功    |
| 合计         | 382   | 0  | 0  | 0  | 全模块成功 |

2026-10-07 14:57:44（Asia/Shanghai）完成，`BUILD SUCCESS`，OpenSpec strict 校验通过。白名单 82 个名称对应 87 个顶层测试类；73
个顶层测试类仍未执行，不计为通过。统计取本轮日志各模块汇总，包含嵌套测试；定向 23 项及 UUID 9 项已包含在 382 项中，不累加。

新增修复任务 10.1/10.2 完成后清单为 36/36。W1/W2 已关闭，但 W3-W5 的 4
个专门场景测试缺口不在本次请求范围，仍保留；不宣称完整核验通过。未启动或连接外部服务、未执行真实迁移、未验证真实锁/事务/多实例恢复，未同步规格或归档。

## 2026-10-07 UUID 映射修复回归

已修正两侧 `EmbeddingUuidMappingTest` 的扫描范围和异常包装断言；扫描仅包含 UUID TypeHandler，保留真实 MyBatis-Plus 自动配置与
embedding Mapper 扫描，DataSource Mock 禁止获取连接。显式 `(UUID) null` 消除 Mockito 可变参数歧义。

定向命令
`mvn -o -pl kb-app,kb-worker -am test '-Dtest=EmbeddingUuidMappingTest' '-Dsurefire.failIfNoSpecifiedTests=false'`：app 4
项、Worker 5 项，9 项全部通过。日志：`target/embedding-uuid-focused-rerun.log`；首次夹具失败日志
`target/embedding-uuid-focused.log` 保留。

随后按 `docs/embedding-offline-tests.txt` 执行
`mvn -o package "-Dtest=$testSelector" '-Dsurefire.failIfNoSpecifiedTests=false'`，其中 `$testSelector` 为白名单按逗号连接的值。日志：
`target/embedding-offline-build-uuid.log`。

| 模块         | 执行/通过 | 失败 | 错误 | 跳过 | 打包    |
|------------|-------|----|----|----|-------|
| kb-common  | 23    | 0  | 0  | 0  | 成功    |
| kb-app     | 241   | 0  | 0  | 0  | 成功    |
| kb-gateway | 8     | 0  | 0  | 0  | 成功    |
| kb-worker  | 100   | 0  | 0  | 0  | 成功    |
| kb-ai      | 3     | 0  | 0  | 0  | 成功    |
| 合计         | 375   | 0  | 0  | 0  | 全模块成功 |

2026-10-07 11:30:42（Asia/Shanghai）完成，`BUILD SUCCESS`。白名单 81 个名称对应 86 个测试类，仍排除 73 个测试类，不计为通过。9
项定向测试已包含在 375 项中，不重复累加。OpenSpec strict 校验通过，任务保持 34/34，尚未归档。

该回归确认原 UUID Mapper 初始化错误在真实自动配置的离线测试中已消除，并覆盖参数绑定、readiness/control/run 结果构造、空
UUID、CallableStatement 读取及 Worker 租约嵌套参数。未连接真实数据库或模型、未执行迁移，不能据此宣称完整应用启动、真实事务或生产部署已验证。

## 2026-10-07 修复后最终回归

`RolePermissionMigrationTest` 已纳入 V16 的权限定义，保留权限全集精确匹配与去重断言，并补充 V16 仅按 ROLE_ADMIN code
授权和重复插入幂等的静态断言。未修改任何迁移脚本。

先运行权限和迁移定向回归，7 项全部通过：

```powershell
mvn -o -pl kb-app -am test '-Dtest=RolePermissionMigrationTest,EmbeddingMigrationTest,EmbeddingPermissionTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

随后保留原白名单，执行完整离线打包；移除前次未生效的 reportsDirectory 参数，使用独立日志保留历史失败记录：

```powershell
$tests = @(Get-Content -LiteralPath 'docs\embedding-offline-tests.txt' | Where-Object { $_.Trim() })
$testSelector = $tests -join ','
mvn -o package "-Dtest=$testSelector" '-Dsurefire.failIfNoSpecifiedTests=false' *> 'target\embedding-offline-build-rerun.log'
openspec validate 'embedding-profile-consistency-and-rebuild' --strict
```

| 模块         | 执行/通过 | 失败 | 错误 | 跳过 | 打包    |
|------------|-------|----|----|----|-------|
| kb-common  | 23    | 0  | 0  | 0  | 成功    |
| kb-app     | 237   | 0  | 0  | 0  | 成功    |
| kb-gateway | 8     | 0  | 0  | 0  | 成功    |
| kb-worker  | 95    | 0  | 0  | 0  | 成功    |
| kb-ai      | 3     | 0  | 0  | 0  | 成功    |
| 合计         | 366   | 0  | 0  | 0  | 全模块成功 |

2026-10-07 08:31:38（Asia/Shanghai）完成，Maven `BUILD SUCCESS`，OpenSpec strict 校验通过。数量来自本次日志的各模块汇总，不与此前定向回归或历史报告相加。白名单仍为
80 个名称、84 个顶层测试类；排除 73 个顶层测试类，其测试方法数量未统计，不属于 366 项执行结果。排除范围及限制见历史记录，测试失败修复后没有缩小白名单。

任务 9.1 已完成；本变更 34/34 项实现任务完成，尚未归档。未启动或连接外部服务，未执行数据库迁移；通过离线回归不代表真实数据库锁、事务回滚、崩溃恢复或生产部署已验证。

## 2026-10-07 首次最终回归（历史失败记录）

只运行无外部服务测试；未启动数据库、Redis、MinIO、Docker、监听 HTTP 服务或真实模型，未执行迁移。测试白名单为
`docs/embedding-offline-tests.txt`，80 个名称匹配 84 个顶层测试类；其余 73 个顶层测试类不在本轮选择中，不记为通过。

明确排除 `SpaceAuthorizationPostgresSmokeTest`、`ParseTaskRecoveryPostgresSmokeTest`、
`QuotaPrincipalClientIntegrationTest`，以及白名单外所有测试；其中 HTTP 集成测试会启动监听服务。类数不能等同于测试方法/参数化用例数。

实际 PowerShell 命令：

```powershell
$tests = @(Get-Content -LiteralPath 'docs\embedding-offline-tests.txt' | Where-Object { $_.Trim() })
$testSelector = $tests -join ','
mvn -o package "-Dtest=$testSelector" '-Dsurefire.failIfNoSpecifiedTests=false' '-Dsurefire.reportsDirectory=target/embedding-offline-reports' *> 'target\embedding-offline-build.log'
```

本环境报告实际仍写入各模块 `target/surefire-reports`；数量以本轮 `target/embedding-offline-build.log`
汇总为准，不能把目录里的历史报告当作本轮全部执行结果。

| 模块                             | 实际结果                                |
|--------------------------------|-------------------------------------|
| kb-common                      | 23 项通过，完成打包                         |
| kb-app                         | 237 项执行，236 通过、1 失败、0 错误、0 跳过，未完成打包 |
| kb-gateway / kb-worker / kb-ai | 因前序模块失败而未执行，不能记为通过                  |

首次尝试合计 260 项执行，259 通过、1 失败，未完成全模块打包。失败为
`RolePermissionMigrationTest.migrationExplicitlyBindsEveryCatalogCodeToAdminLocatedByCode`：测试只合并 V13/V14
的权限，与当前目录比较时缺少 V16 新增的 `embedding:read`、`embedding:rebuild`。当时按 OpenSpec 错误暂停规则保留 9.1
未勾选；后续修复和完整重跑结果见本文件的修复后最终回归记录。

此前本轮分步定向回归均通过，涵盖生成、租约、提交、恢复、实际覆盖扫描、激活、切换、审计指标与升级检查；它们不替代本次失败的最终回归。OpenSpec
strict 校验通过，运维文档 3 段 PowerShell 示例仅作语法解析，未发送请求。

上述 Mock/Stub 和静态检查不证明真实事务回滚、锁互斥、多实例恢复、原子提交或检索效果；生产环境验证不在本次任务范围内。
