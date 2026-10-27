# Tasks

验证仅限进程内单元测试与静态检查，依赖使用 Mock/Stub，不组装跨模块业务链路，不启动应用或外部服务。文档仅交付并人工核对，不编写或执行 README、Markdown、OpenAPI/Javadoc 内容及示例测试。测试按明确的单元测试类/方法白名单运行，打包跳过测试。

## 1. 审计操作域与持久化查询

- [x] 1.1 在 `AuditScope` 新增 `DOCUMENT_RELOCATE("document")`，并补一条测试断言其 `name()` 带 `DOCUMENT_` 实体前缀、
  `targetType()` 为 `document`——验证该测试通过，守住 `AuditScope` 类注释里"操作域必须带实体前缀"的要求
- [x] 1.2 新增搬迁所需的写/读 SQL：按 `doc_id` 取当前归属并加行锁（`SELECT space_id ... FOR UPDATE`）、按 `doc_id` 清空
  `kb_document_acl`、按 `doc_id` 同步 `kb_doc_chunk.space_id`、列出目标空间持有 `view` 的主体——验证源文本断言测试覆盖
  `FOR UPDATE` 存在、清空与同步语句均按 `doc_id` 而非按预读的行集过滤
- [x] 1.3 补一条源文本断言测试，锁定 `SearchMapper.xml` 仍按 `c.space_id` 过滤——验证测试通过；这条是本变更的前提，若将来检索改成
  join 文档表，分块同步就不再必要，该测试应当随之失败并提醒删除

## 2. 搬迁服务

- [x] 2.1 新增 `app.space.service.DocumentRelocationService.relocate`，用 `SpaceAuthorizer.requireSpaceManager` 对*
  *实际当前归属空间**与目标空间各判定一次——验证 Mockito 测试覆盖 spec「文档搬迁必须同时具备源空间与目标空间的管理资格」的四个场景，并断言
  `requireSpaceManager` 收到的是实际归属而非请求声明值
- [x] 2.2 实现 `fromSpaceId` 前置条件与基于 `(DOCUMENT_RELOCATE, requestId)`
  的幂等重放——验证测试覆盖「搬迁必须幂等并检测并发冲突」的四个场景，其中重放场景必须断言第二次调用**没有**
  再执行清空例外（否则会清掉新空间管理员刚授出的例外）
- [x] 2.3 实现目标空间校验：不存在/已删除返回 404，已归档返回拒绝，目标等于当前归属返回拒绝且不清空例外，文档不存在或已删除返回
  404，允许从已归档空间迁出——验证测试覆盖「目标空间必须是一个可用且不同于当前归属的空间」的五个场景
- [x] 2.4 实现清空例外并产出回执摘要（`principalKey:permission`
  列表，带上限与截断标记）——验证测试覆盖「搬迁必须清除文档在原空间下获得的全部例外授权」的三个场景，外加一条超过上限时摘要被截断且标记置位的测试
- [x] 2.5 在同一事务内同步 `kb_doc_chunk.space_id`，并按 `DocumentAclService.countClearanceBlocked`
  同口径计算目标空间的密级受阻主体数——以 Mock 单元测试覆盖「搬迁不得改变文档密级」的三个场景、调用顺序及混合密级部门不计入受阻数，人工审阅事务边界
- [x] 2.6 写入 `DOCUMENT_RELOCATE` 审计，`before` 含原空间与被清除例外摘要、`after` 含目标空间与清除条数——验证测试覆盖「搬迁必须产生不可变审计记录」的三个场景

## 3. HTTP 接口

- [x] 3.1 新增 `app.space.web.DocumentRelocationController` 的 `PATCH /api/admin/documents/{documentId}/space` 与请求/响应
  DTO，`requestId` 取请求体或 `X-Request-Id` 回退、缺失返回 400——验证标准 MockMvc 测试（`LocalValidatorFactoryBean` +
  `GlobalExceptionHandler` 作为 controller advice）覆盖 403/404/409/400 各至少一条，确认 `IdentityException` 自带的
  `httpStatus` 真的落到了响应状态上
- [x] 3.2 为接口补 OpenAPI 注解，`description` 必须写明"被清除的例外授权不可恢复"，且 Javadoc 块放在 `@Operation` 等注解*
  *之前**（放在注解之后会被编译器当普通注释，不进生成文档）——交付接口注解与 Javadoc 并人工审阅

## 4. Worker 侧串行化

- [x] 4.1 在 `ChunkPublisher.publish` 的分块 DELETE/INSERT 之前取同一把文档行锁（
  `SELECT ... FROM kb_document WHERE id = ? FOR UPDATE`）——验证单测用 `InOrder`
  断言取锁发生在分块写入之前，覆盖「发布与文档所属空间变更并发」场景所要求的串行化前提
- [x] 4.2 在 `docs/parse-task-recovery-operations.md`
  补一段说明：发布事务现在会持有文档行锁，持有时长随分块数增长，是搬迁延迟与锁等待的主要来源——交付说明并人工核对锁的名称与范围

## 5. 跨变更约束兑付与文档

- [x] 5.1 更新 `DocumentAclService` 类注释里那条「跨变更约束（后续实现文档搬迁时必读）」，从"待实现"
  改为指向本能力已实现的清理行为——交付注释并人工核对实际执行清理的服务
- [x] 5.2 README 能力表新增"文档搬迁"一行并标注真实环境未实测状态，同时删除第八节「下一步」第 6 项（本变更即其兑付）——交付说明并人工核对实现进展，不添加文档内容测试
- [x] 5.3 README 新增一节搬迁验证清单，含 `PATCH /api/admin/documents/{id}/space` 的 curl 示例与要逐条确认的事实：清除条数的含义、受阻数不为
  0 不代表搬迁失败、原空间成员必须同时搜不到也读不到、迁入已归档空间必须被拒——人工核对示例中的字段名与实际 DTO 一致，不执行示例
