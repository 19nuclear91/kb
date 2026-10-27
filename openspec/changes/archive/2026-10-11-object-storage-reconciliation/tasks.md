# Tasks

按依赖顺序实施。每组实现同时补齐该组所需的单元测试与文档，不另起独立的验证或文档阶段。

验证仅限进程内单元测试和静态检查，依赖使用 mock/stub；不创建或执行集成测试、端到端测试、部署及接口联调任务，不运行
H2、PostgreSQL/ParadeDB、Redis、MinIO。测试按已核对的单元测试类或方法显式选择，不执行无筛选的全量测试，也不因此删除既有测试或修改默认构建配置。真实数据库与桶上的对账、删除效果不属于本次完成条件，也不得据单元测试宣称真实环境已验证。

## 1. 共享前缀常量与配置迁移

- [x] 1.1 新增 `ObjectKeys.DOCUMENTS_PREFIX`（`documents/`），替换 `DocumentUploadService` 与
  `DocumentManagementService.uploadVersion` 中的两处字面量；在 `DocumentUploadServiceTest`、
  `DocumentManagementServiceTest` 中补充对象键以该常量开头的断言，显式运行这两个测试类确认通过，并用 `Select-String` 确认仓库中
  `"documents/"` 字面量仅剩 `ObjectKeys` 与测试断言。
- [x] 1.2 在 kb-app `application.yml` 增加 `kb.storage-reconciliation` 配置块（八个键与环境变量名同 design D1 表）和
  `spring.task.scheduling.pool.size: 2`；删除 kb-worker `application.yml` 中无人读取的 `storage-reconciliation` 配置块；用
  `Select-String` 核对 kb-worker 不再包含该键、kb-app 键名与 D1 表逐一一致。
- [x] 1.3 新增 `StorageReconciliationProperties` record（`@ConfigurationProperties("kb.storage-reconciliation")`，默认值同
  D1 表）；用默认值单元测试核对八个默认值，并断言默认 `prefix` 与 `ObjectKeys.DOCUMENTS_PREFIX` 相等。

## 2. 对象存储列举与存在性检查能力

- [x] 2.1 扩展 kb-app `ObjectStorage` 接口：分页列举（前缀、游标、上限；返回对象键、`lastModified`、下一页游标）与
  `objectExists`（HEAD，`NotFound` 返回 false，其他异常抛出）；`S3ObjectStorage` 以 `ListObjectsV2` 与 `headObject`
  实现；kb-worker 存储接口保持只读不变，用静态检查确认其未新增方法。
- [x] 2.2 新增 `S3ObjectStorageTest`（mock `S3Client`）：验证列举请求的前缀、游标、上限参数与响应映射（键、最后修改时间、末页无游标），验证
  HEAD 的 `NoSuchKey` 映射为 false、其他 `S3Exception` 抛出；显式运行该测试类，不连接真实桶。

## 3. 对账核心：存活引用查询与孤儿处理

- [x] 3.1 新增 `StorageReconciliationMapper` 与 XML 映射：`listLiveObjectKeys(keys)`（IN 比对，谓词
  `d.status <> 'deleted'`）、`hasLiveReference()`（EXISTS LIMIT 1 探测）、`sampleLiveObjectKeys(limit)`（按版本 ID 倒序取前
  N）；用 BoundSql 单元测试核对 IN 参数展开、存活谓词、EXISTS 与抽样排序及上限，不建立数据库连接。
- [x] 3.2 实现 `StorageReconciliationService` 孤儿处理路径：专用连接上以原始 JDBC 执行 `pg_try_advisory_lock(741306012)`
  （未获锁跳过本轮，finally 显式 `pg_advisory_unlock`）、分页列举、每页 IN
  比对、返回键前缀防御校验、宽限期过滤、候选键保留与计数上限、删除前复核、单轮删除上限、列举或数据库失败中止本轮不删除、存活引用为空拒绝删除并告警、报告模式零删除、单个删除失败继续；用
  `StorageReconciliationServiceTest`（mock `ObjectStorage`、mapper、`DataSource`/`Connection`/`Statement`
  ）覆盖以上全部分支并显式运行确认通过。
- [x] 3.3 补齐缺失检测：按上限抽样存活引用执行 HEAD，`NotFound` 计缺失并 WARN、其他异常计检查失败、开关关闭或上限为零不检查；扩展
  `StorageReconciliationServiceTest` 覆盖缺失与失败分别计数及关闭分支。
- [x] 3.4 补齐轮次汇总：返回并记录扫描数、孤儿数、删除数、删除失败数、缺失数与检查失败数，孤儿键以 INFO 打印但每轮至多 100
  条；用轮次结果断言与内存日志捕获验证汇总字段和键打印上限，确认不打印对象内容与凭据。

## 4. 调度接线与运维文档

- [x] 4.1 新增 `StorageReconciliationScheduler`：
  `@Scheduled(fixedDelayString = "${kb.storage-reconciliation.interval-ms:3600000}", initialDelayString = "${kb.storage-reconciliation.initial-delay-ms:300000}")`
  调用服务并捕获所有异常；用调度器单元测试验证异常不外传、服务每轮被调用一次。
- [x] 4.2 新建 `docs/storage-reconciliation-operations.md`：说明两类孤儿与缺失检测语义、配置表（键、环境变量、默认值）、日志字段、报告模式到删除模式的启用步骤、回滚方式、dev
  种子噪声与"未在真实桶/数据库上执行"的边界；人工核对文档中每个键与 kb-app `application.yml` 一致。
- [x] 4.3 更新 `README.md`：§一 状态表补充对象存储对账、§五 验证清单增加对账单元测试条目与真实环境启用步骤（明确标注未在真实环境执行）、§六
  凭据条目补充前缀内 `s3:ListBucket`、§八 下一步同步对账进展；核对各章节描述与 design 的配置表和验证范围一致。

## Workflow follow-up

- 实施并评审通过后，将本变更归档到 `openspec/changes/archive/`。
- 归档后确认 `openspec/specs/storage-reconciliation/spec.md` 已按 delta 正确生成，且主 spec 的 Purpose 来自本 delta。
