# Proposal

## Why

上传路径先写对象存储再提交数据库事务、删除路径只在事务提交后尽力删除对象，任何一步失败都会留下没有数据库引用的孤儿对象；这些对象在库中没有任何痕迹，当前也没有桶列举能力，
`kb-worker` 里还留着一块无人读取的 `storage-reconciliation` 配置。README
与《架构决策记录》都把对象存储对账列为待办：残留对象既浪费存储，也让已删除文档的原件继续留在桶里。

## What Changes

- 在 `kb-app` 新增对象存储对账能力：按周期列举配置前缀（默认 `documents/`
  ）下的桶对象，与数据库存活引用（文档未软删除的全部版本对象键）比对，识别孤儿对象；默认只报告，开启删除后按安全护栏清理。
- 对账以对象存储为起点做单向扫描，天然覆盖两类孤儿：上传侧（写库失败或进程崩溃，库中无痕迹）与删除侧（软删除后对象删除失败，库中残留版本行）。不新增重试表，删除失败由下一轮自然重试。
- 反向缺失检测（数据库有引用但桶中对象缺失）为只读抽样：每轮按上限抽查存活引用对象是否存在，只计数与告警，不修复、不改库。
- 删除护栏：只处理配置前缀内的对象；仅 `LastModified`
  早于宽限期的对象可删；删除前用同一存活谓词对候选再做一次数据库复核；列举或数据库访问失败即中止本轮且不再删除；单轮删除数有上限；存活引用集合为空时拒绝删除并告警；删除开关默认关闭。
- 单飞执行：以专用连接持有 PostgreSQL 会话级 advisory lock，保证同一时刻只有一个实例在对账，连接断开自动释放；获取不到锁即跳过本轮。
- 配置从 `kb-worker` 迁移到 `kb-app`（`kb.storage-reconciliation`），保留既有环境变量
  `KB_STORAGE_RECONCILIATION_INTERVAL_MS`、`KB_STORAGE_ORPHAN_GRACE_SECONDS`，新增前缀、删除开关、单轮上限与缺失检测开关；删除
  `kb-worker` 死配置块，worker 存储接口保持只读。
- 抽取共享的 `documents/` 前缀常量，替换 `DocumentUploadService` 与 `DocumentManagementService` 中重复的对象键字面量，上传与对账共用同一来源。
- 新增运维文档说明对账语义、配置、日志与人工验证步骤；更新 README 状态表与验证清单。
- 范围不包含：空间删除的外键缺陷修复（软删除残留文档会让 `DELETE FROM kb_space`
  失败，属独立问题，仅作关联记录）、内容哈希秒传去重、多桶与跨区域、桶生命周期规则、HTTP 管理接口、指标端点、新权限码、删除失败重试表、真实
  MinIO/PostgreSQL 联调。

## Capabilities

### New Capabilities

- `storage-reconciliation`: 管理对象存储与数据库引用之间的一致性——孤儿对象识别、删除护栏、缺失对象抽样检测、单飞调度与报告语义。

### Modified Capabilities

无。对账只消费既有文档生命周期事实（`kb_document.status`、`kb_document_version.object_key`），不修改上传、删除、解析、授权或
Embedding 能力的要求。

## Impact

- `kb-app`: 新增 `app.storage` 下的对账配置记录、对账服务、调度器与存活引用查询 mapper；`ObjectStorage` 增加分页列举方法，
  `S3ObjectStorage` 以 `ListObjectsV2` 实现；`application.yml` 增加 `kb.storage-reconciliation` 配置。
- `kb-worker`: 删除无人读取的 `kb.storage-reconciliation` 配置块；不新增列举/删除能力，保持只读存储接口。
- 部署/运维: kb-app 的 S3 凭据需增加前缀内 `s3:ListBucket`（删除模式复用既有 `s3:DeleteObject`）；建议先以报告模式观察一个完整周期，再开启删除。
- 存储: 不新增数据库表、不要求 Flyway 迁移；复核查询命中 `kb_document_version` 既有唯一约束索引。
- 验证: 仅进程内单元测试、mock/stub 与静态检查，覆盖前缀过滤、宽限期、存活谓词、复核、上限、失败中止、单飞与日志计数；不运行
  PostgreSQL/ParadeDB、H2、Redis、MinIO。真实环境效果不作为本次任务完成条件，也不得据单元测试宣称已经验证。
