# Design

## Context

动机见 `proposal.md`，外部行为见 `specs/storage-reconciliation/spec.md`。当前实现对本设计的约束：

- 上传路径 `DocumentUploadService` 先生成 `documents/<uuid><ext>` 对象键写入桶，再提交数据库事务；事务失败时尽力补偿删除，补偿也失败即产生无库痕迹的孤儿对象。删除路径
  `DocumentManagementService.delete` 只做软删除（`status='deleted'`），对象删除在事务提交后尽力执行，失败仅记录 WARN
  并注释说明需要由对账任务补偿。
- kb-app 的 `ObjectStorage` 只有 put/delete/get；`S3ObjectStorage` 基于 AWS SDK v2 `S3Client`，没有 `ListObjectsV2` 与
  HEAD 能力。kb-worker 的存储接口为只读 get，且 `kb-worker/application.yml` 中已有 `storage-reconciliation` 配置块（
  `interval-ms`、`orphan-grace-seconds`），但没有任何代码读取，是死配置。
- `kb_document_version.object_key` 有唯一约束 `uq_document_version_object_key`（V5），对象键到版本的映射是 1:
  1。存活文档语义在既有代码中已确立：`SpaceMapper.countLiveDocuments` 用 `status <> 'deleted'` 判定活文档，软删除残留不再被视为引用。
- 单飞先例：身份同步用事务级 `pg_advisory_xact_lock(741306011)`，密级变更用
  `pg_advisory_xact_lock(hashtextextended(key,0))`；解析任务与 Embedding 重建用租约行（`lease_until`/`lease_owner`
  ）。当前没有会话级 advisory lock 先例，常量 `741306012` 未被占用。
- 调度先例：kb-app 已通过 `EmbeddingReadinessScheduler` 启用 `@EnableScheduling` 并以 `@Scheduled(fixedDelayString)`
  轮询，对账调度复用同一模式；Spring 默认调度线程池只有一条线程，两个长任务需要分池。
- 开发种子 `db/dev/V3__dev_seed.sql` 的版本对象键为 `dev/1.md`…`dev/5.md`，不在 `documents/` 前缀下且桶中不存在；缺失检测在
  dev 环境会持续报告这些引用，属预期噪声。
- 已知关联缺陷（不在本变更范围）：`kb_document.space_id` 外键没有 ON DELETE 规则，软删除残留文档会让 `DELETE FROM kb_space`
  失败。

## Goals / Non-Goals

**Goals:**

- 周期性发现"桶中有、库中无引用"的孤儿对象，覆盖上传侧（写库失败/崩溃）与删除侧（软删除后删除失败）两类成因；默认只报告，显式开启后按护栏清理。
- 只读发现"库中有引用、桶中缺失"的反方向异常，为运维提供信号，不自动修复。
- 多实例部署下至多一个实例执行对账；任何对账失败不影响上传、下载、检索等主业务。
- 配置与凭据保持最小范围：对账只使用 kb-app 的存储凭据与既有连接配置，kb-worker 保持只读。

**Non-Goals:**

- 不修复空间删除外键缺陷、不引入内容哈希秒传去重、不做多桶、跨区域或桶生命周期规则。
- 不提供 HTTP 管理接口、指标端点或新权限码；不新增数据库表、不要求 Flyway 迁移。
- 不改变上传、删除、下载路径的既有行为（除把重复的对象键前缀字面量替换为共享常量）。
- 不把对账视为数据一致的证明：缺失检测是抽样，孤儿判定有宽限期窗口，真实桶上的删除效果不在本次验证范围。

## Decisions

### D1. 对账落在 kb-app，配置从 kb-worker 迁入，凭据最小化

对账需要"列出对象 + 删除对象"，而这两项能力都只属于原件生命周期所有者 kb-app：上传、删除、下载都在这里发生，kb-worker
的存储接口保持只读（get）不扩大。因此新增能力全部放在 kb-app 的 `app.storage` 包，复用 `kb.storage` 连接配置，仅要求 kb-app 的
S3 凭据增加前缀内的 `s3:ListBucket`（删除模式复用既有 `s3:DeleteObject`）。

`kb-worker/application.yml` 中的死配置块删除；配置迁入 kb-app：

| 键                             | 环境变量                                                    | 默认值          | 含义               |
|-------------------------------|---------------------------------------------------------|--------------|------------------|
| `interval-ms`                 | `KB_STORAGE_RECONCILIATION_INTERVAL_MS`                 | `3600000`    | 轮次间隔（fixedDelay） |
| `initial-delay-ms`            | `KB_STORAGE_RECONCILIATION_INITIAL_DELAY_MS`            | `300000`     | 启动后首轮延迟          |
| `orphan-grace-seconds`        | `KB_STORAGE_ORPHAN_GRACE_SECONDS`                       | `3600`       | 孤儿保护窗口           |
| `prefix`                      | `KB_STORAGE_RECONCILIATION_PREFIX`                      | `documents/` | 扫描前缀             |
| `delete-enabled`              | `KB_STORAGE_RECONCILIATION_DELETE_ENABLED`              | `false`      | 删除开关（默认报告模式）     |
| `max-deletes-per-cycle`       | `KB_STORAGE_RECONCILIATION_MAX_DELETES_PER_CYCLE`       | `500`        | 单轮删除上限           |
| `missing-check-enabled`       | `KB_STORAGE_RECONCILIATION_MISSING_CHECK_ENABLED`       | `true`       | 缺失检测开关           |
| `missing-check-max-per-cycle` | `KB_STORAGE_RECONCILIATION_MISSING_CHECK_MAX_PER_CYCLE` | `100`        | 缺失抽样上限           |

保留既有环境变量名 `KB_STORAGE_RECONCILIATION_INTERVAL_MS` 与 `KB_STORAGE_ORPHAN_GRACE_SECONDS`，使既有部署说明继续有效。

**替代方案：** 把对账放 kb-worker 需要给 worker 增加列举与删除权限，扩大解析侧的攻击面，且 worker
不拥有原件生命周期知识；独立运维脚本模块则引入新的部署单元与配置来源。均不采用。

### D2. 以对象存储为权威方向做单向扫描，覆盖两类孤儿

对账以"桶列举结果减去存活引用"
为孤儿定义，而不是从数据库出发。原因：上传侧孤儿（写库失败、崩溃）在数据库中没有任何痕迹，任何从库出发的对账都发现不了它们；删除侧孤儿（软删除后删除失败）虽然库中有残留版本行，但其文档
`status='deleted'`，用同一存活谓词也能被桶到库方向覆盖。单一方向的扫描同时覆盖两类成因，不需要引入"上传意图表"或 outbox。

**替代方案：** 双向对等对账需要两套谓词与修复动作；上传先写库占位（pending 行）会改变上传事务结构与恢复语义，超出本次范围。不采用。

### D3. 存活引用谓词与分页 IN 比对

存活引用定义为 `kb_document_version v JOIN kb_document d ON d.id = v.doc_id WHERE d.status <> 'deleted'` 的全部
`object_key`。与文档状态（draft/in_review/published/archived）、空间是否归档、版本是否为当前版本都无关：它们仍可被下载、预览与引用，误删即数据丢失。

比对按列举分页进行：每页最多 1000 个键，用一次 `IN` 查询取回其中的存活键，得到该页孤儿候选；不在内存中保存全桶键集合或全库存活键集合，避免大部署下的内存放大。

每轮开始时用一次 `EXISTS` 探测存活引用是否为空（`LIMIT 1`，代价低）。为空而桶中存在候选时，按"疑似连接错误库"
处理：只报告、拒绝删除、记录高优先级告警。

**替代方案：** 全量加载存活键集合（百万文档量级下内存与查询成本不可控）；按前缀逐段查库（UUID 键与前缀无相关性）。采用分页 IN。

### D4. 删除护栏与默认报告模式

删除模式默认关闭。开启后，候选对象必须依次通过：键属于配置扫描范围（列举请求本身带前缀，服务仍对返回键做前缀校验防御）、
`LastModified` 早于宽限期、删除前以同一存活谓词复核不是存活引用。删除在完整列举与复核查询都成功之后才执行：列举或数据库访问失败即中止本轮，不做任何删除；单个对象删除失败只记
WARN 并继续本轮其余删除，失败对象由下一轮自然重试，不新增重试表。

单轮删除数量受 `max-deletes-per-cycle` 约束；列举阶段只为日志保留前 100
个孤儿键、为删除保留至多上限个候选键，计数单独累加，不随候选规模放大内存。每轮输出一条汇总日志（扫描数、孤儿数、删除数、删除失败数、缺失数、检查失败数），孤儿键以
INFO 打印但每轮至多 100 条。

**替代方案：** 无复核直接删除（无法抵御"报告后引用恢复"
与实现回归）；为删除失败建重试表（引入新状态与迁移，下一轮扫描已天然重试）；提供手动删除管理接口（扩大权限面，本次以日志加开关控制）。均不采用。

### D5. 单飞使用专用连接的会话级 advisory lock

多实例部署下用 PostgreSQL 会话级 `pg_try_advisory_lock(741306012)` 实现单飞：从数据源取一条专用连接，持有整轮，`finally` 中
`pg_advisory_unlock` 后归还；连接中途断开（进程崩溃、网络故障）时 PostgreSQL 自动释放会话锁，不存在租约过期时间窗口。选择会话级锁而非事务级
`pg_advisory_xact_lock`：对账整轮是多次短查询与外部 S3 调用，不能放进一个长事务；选择 advisory lock
而非租约行：单飞是瞬时互斥，不需要可见的持有者状态与续租逻辑，也避免新增表。

常量 `741306012` 与既有 `741306011`（身份）并列登记在锁 SQL
所在处。解锁必须显式执行：连接归还连接池不结束会话，锁会残留；若解锁因连接已损坏而失败，锁随会话终止释放。持有连接整个周期会占用连接池
1 条连接（池上限 10），对账为低频轮询，可接受。

**替代方案：** 租约行（解析任务、Embedding 重建所用模式）适合需要跨轮可见进度与续租的长时间任务，对账轮次短、无进度语义，引入租约表与过期判定是过度设计；不加单飞则多实例重复执行删除与日志。采用会话级
advisory lock。

### D6. 缺失检测为只读抽样，区分"缺失"与"检查失败"

每轮按版本 ID 倒序取最近 `missing-check-max-per-cycle` 条存活引用，对每个对象键执行 HEAD：`NotFound` 计入缺失数并
WARN；其他异常（网络、权限、限流）计入检查失败数，与缺失分开。检测不写库、不重建、不触发重解析，只回答"当前是否能看到这个对象"。

选择近端窗口（新写入优先）而非随机抽样或全量：桶级事故（对象整体丢失）必然命中最近对象，抽样成本固定且查询可走主键索引；旧对象的个例缺失留待后续演进（随机轮转需要持久化游标，全量巡检需要独立任务窗口）。dev
种子的 `dev/*.md` 引用会每轮计入缺失，属预期噪声，在运维文档中说明。

**替代方案：** 全量 HEAD 巡检成本与桶规模线性，不适合放在周期任务；随机抽样不可复现且无额外收益。采用近端窗口。

### D7. 实现落点

kb-app `app.storage` 包新增与扩展：

- `ObjectKeys`：共享常量 `DOCUMENTS_PREFIX = "documents/"`，替换 `DocumentUploadService` 与
  `DocumentManagementService.uploadVersion` 中的两处字面量，默认扫描前缀与上传对象键同源。
- `ObjectStorage` 扩展：`ListObjectsPage list(String prefix, String continuationToken, int maxKeys)`（返回对象键、
  `lastModified` 与下一页游标）与 `boolean objectExists(String objectKey)`（HEAD，`NotFound` 返回 false，其他异常抛出）。
  `S3ObjectStorage` 用 `ListObjectsV2` 与 `headObject` 实现；kb-worker 接口不动。
- `StorageReconciliationProperties`：`@ConfigurationProperties("kb.storage-reconciliation")` record，默认值同 D1 表。
- `StorageReconciliationMapper`（XML 映射）：`listLiveObjectKeys(List<String> keys)`（IN 比对）、`hasLiveReference()`（EXISTS
  探测）、`sampleLiveObjectKeys(int limit)`（近端窗口抽样）。
- `StorageReconciliationService`：轮次编排（取锁、列举、比对、复核、删除、缺失检测、计数与日志），异常一律收敛为本轮失败。专用连接上的
  `pg_try_advisory_lock`/`pg_advisory_unlock` 用原始 JDBC 执行，不经过 MyBatis（映射器每条语句可能取不同池连接，无法保证同一会话）。
- `StorageReconciliationScheduler`：`@Component` 加
  `@Scheduled(fixedDelayString = "${kb.storage-reconciliation.interval-ms:3600000}", initialDelayString = "${kb.storage-reconciliation.initial-delay-ms:300000}")`
  ，捕获所有异常记录日志，不向调度线程传播。
- `application.yml` 增加 `spring.task.scheduling.pool.size: 2`，避免对账长轮次推迟 `EmbeddingReadinessScheduler` 的探测。

**替代方案：** 把列举能力放到 `kb-common` 共享（worker 并不需要，扩大公共面）；在 service 内直接用 `S3Client` 而不经
`ObjectStorage` 抽象（绕过既有接口契约，且难以在单元测试中替换）。均不采用。

### D8. 验证限定为单元测试与静态检查

- 服务单元测试 mock `ObjectStorage`、mapper、`DataSource`/`Connection`/`Statement`，覆盖：宽限期过滤、前缀校验、分页 IN
  比对、复核跳过、删除上限、列举失败中止（已收集候选不删除）、数据库失败中止、空存活集合拒绝删除、删除失败继续、报告模式零删除、缺失与检查失败分别计数、汇总日志计数。
- `S3ObjectStorage` 的列举与 HEAD 用 mock `S3Client` 验证请求参数（前缀、游标、上限）与响应映射（下一页游标、`NoSuchKey` 映射为
  false、其他 `S3Exception` 抛出），不连接真实桶。
- mapper SQL 测试生成 `BoundSql`，核对 IN 列表参数、`d.status <> 'deleted'` 谓词、EXISTS 探测与抽样语句的排序与上限，不建立数据库连接（沿用
  `RolePermissionMapperTest`、`DocumentCitationMapperTest` 的既有方式）。
- 上传与版本上传的既有单元测试补充对象键以 `ObjectKeys.DOCUMENTS_PREFIX` 开头的断言；配置默认值测试核对默认前缀与
  `ObjectKeys.DOCUMENTS_PREFIX` 相等，把"上传键落入扫描范围"在进程内钉死。
- 每组只显式运行已核对的测试类；不运行 H2、PostgreSQL/ParadeDB、Redis、MinIO；不启动完整应用、不发真实 HTTP
  请求。数据库与桶上的实际删除效果不属于本次完成条件，也不得据 mock 结果宣称真实环境已验证。

**替代方案：** 用 MinIO 容器做集成测试能验证真实列举与删除语义，但超出本项目确认的验证范围；以 mock 通过冒充真实环境已验证不可接受。

## Risks / Trade-offs

- [误删存活对象] -> 多重护栏：前缀校验、宽限期、删除前复核、单轮上限、空集拒绝、默认报告模式；启用删除前先观察报告周期。mock
  测试不证明真实桶安全，删除开关由运维显式开启。
- [宽限期内的孤儿被漏过本轮] -> 属预期：对象在超过宽限期后才进入候选，下一轮自然覆盖，不会造成重复清理。
- [列举与删除之间的竞态] -> S3 列举为强一致；新上传对象在事务提交前即便被列为候选，也会被宽限期与删除前复核双重拦截。
- [对账持有连接与锁] -> 整轮持有 1 条池连接；连接断开自动释放锁；解锁失败时锁随会话终止释放，最坏情况下一轮对账被推迟一个周期。
- [大桶列举成本] -> 每页 1000 键的顺序列举，成本与桶规模线性；周期可调；替代演进（S3 Inventory）留给运维层，不在本次。
- [S3 与本地时钟偏差] -> `LastModified` 来自服务端，宽限期 1 小时远大于常见偏差，判定稳定。
- [dev 种子噪声] -> `dev/*.md` 缺失会每轮报告，运维文档说明这是预期；生产环境无此类数据。
- [凭据权限扩大] -> 仅新增前缀内 `s3:ListBucket`，worker 保持只读；删除复用既有 `s3:DeleteObject`，不引入桶级管理权限。
- [单元测试无法覆盖真实执行] -> 在任务与文档中明确"未在真实 PostgreSQL/桶上执行"，不把 mock 结果当验收证据。

## Migration Plan

1. 无 Flyway 迁移、无数据库结构变化、无数据回滚需求。发布 kb-app 新版本即可获得对账能力（默认报告模式）。
2. 部署前给 kb-app 的 S3 凭据增加前缀内 `s3:ListBucket` 权限；缺失该权限时对账每轮失败告警，主业务不受影响。
3. 先以默认配置（报告模式）运行至少一个完整周期，人工核对日志中的孤儿清单与缺失抽样计数。
4. 确认清单符合预期后设置 `KB_STORAGE_RECONCILIATION_DELETE_ENABLED=true`；建议初期保持较小的 `max-deletes-per-cycle`。
5. 回滚：把删除开关恢复为 false 即退回报告模式；完全停止对账可把周期调大或移除凭据权限。均无数据影响。
6. `kb-worker` 死配置块的删除无行为变化（此前无任何读取方）。
