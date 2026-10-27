# Tasks

按依赖顺序实施，保留引用正确性和访问安全的 P0 编码任务。每组实现同时补齐单元测试和接口说明，不另起精确高亮、解析改造或快照存储任务。以下全部为待开发任务。

验证仅限进程内单元测试和静态检查，依赖使用 mock/stub；不创建或执行集成测试、端到端测试、部署及接口联调任务，不运行
H2、PostgreSQL/ParadeDB、Redis、MinIO 或真实模型服务。HTTP 响应由内存 stub
提供，不监听端口，不启动完整应用上下文。测试按已核对的单元测试类或方法显式选择，不执行无筛选的全量测试，也不因此删除既有集成测试或修改默认构建配置。

文档、Javadoc 和 OpenAPI/Swagger 注解仅交付并人工核对，不编写或执行文案、字段描述、注解完整性或示例测试；保留业务 DTO 的 JSON 序列化单元测试。

## 1. P0 - 检索版本身份与兼容契约

- [x] 1.1 在 `ChunkHit` 和 `SearchMapper.xml` 中增加 `versionId`、`versionNo`，从同次查询的分块所属版本读取，并保留旧构造器；用生产
  MyBatis `chunkHitMap` 和 mock `ResultSet` 的单元测试验证构造顺序、整数类型、来源空值、正确版本号及 JSON 旧字段兼容，不连接数据库。
- [x] 1.2 为 BM25 单路、向量双路补充 `BoundSql` 及参数映射单元测试，采用 `SearchProfileSqlTest` 的不执行 SQL
  的方式核对发布状态、当前版本、密级、ACL、空间归档过滤；用候选夹具和 mock 重排依赖验证排序后版本及来源字段保留，不执行检索或数据库集成用例。
- [x] 1.3 随检索字段更新 Javadoc、`@Schema`，新建 `docs/citation-provenance.md` 的身份与来源字段说明；运行进程内
  JSON 序列化单元测试验证响应字段，人工核对“versionNo 不定位、未知为 null、来源范围不是精确坐标”的说明，不运行 Swagger 文档测试。

## 2. P0 - 受保护的原版本片段查询

- [x] 2.1 在 `document` 模块增加 `DocumentCitationView`、分块视图及内部查询投影，在 `DocumentMapper` 增加按授权父版本 LEFT
  JOIN 三元组的只读查询；通过生产 XML 的 `BoundSql` 和参数映射检查三元组、连接位置、权限及未删除约束，用 mock `ResultSet`
  验证有无片段投影和来源 null，不执行 SQL。
- [x] 2.2 实现 `DocumentCitationService` 的每请求 view 解析、`AVAILABLE` / `CHUNK_UNAVAILABLE` 转换和不可见目标统一 404；以
  mock `PermissionService`、`DocumentMapper` 的服务单元测试验证授权调用、查询参数、不可见父版本不返回失效详情以及失效不猜测新
  chunkIdx，不接入对象存储、模型或解析任务。
- [x] 2.3 为可查看的原版本构造固定 `previewPath`，独立解析 download 权限并通过既有文件可见性查询决定 `downloadPath`；使用
  mock 权限范围、文件查询结果及异常验证 view-only、view+download、download-only 三种组合和异常传播，序列化结果不含
  `objectKey`、签名直链或凭据。
- [x] 2.4 在 `DocumentController` 增加指定版本 citation GET 路由、正整数参数校验及成功响应 `Cache-Control: no-store`
  ；分别以控制器、参数校验、拦截器和异常处理的独立单元测试核对 200/400/401/403/404 响应映射、JSON 和缓存头，身份及 service 均
  mock，路由注册通过元数据检查，不发送实际 HTTP 请求。
- [x] 2.5 为新查询添加授权边界单元测试，以 mock 的前后权限范围和 Mapper 返回值模拟角色/部门/ACL
  撤权、主体停用、升密、搬迁及空间归档；验证每次重新取 view 范围、拒绝时无数据及管理资格不能替代 view，并通过独立 SQL
  生成断言核对保护条件，不调用真实授权变更或搬迁服务。
- [x] 2.6 用 mock Mapper 返回的生命周期夹具模拟发布 V2 后仍读 V1、同版本重建后旧 ID
  无匹配及向量状态变化；验证服务始终查询原三元组、缺失时明确失效且生成的文件路径固定原版本，只检查返回值，不执行发布、重解析或预览/下载请求。
- [x] 2.7 完善新路由及 DTO 的 Javadoc、`@Schema` 和 `docs/citation-provenance.md` 的请求、两类成功响应、错误码、文件回退与缓存说明；用
  JSON 序列化单元测试、路由元数据及静态模块边界检查核对业务契约，说明及文档注解只人工核对，不运行 Swagger 文档测试，真实文件访问与数据库执行不在本次验证范围内。

## 3. P0 - AI 参考转换与 SSE 来源透传

- [x] 3.1 扩展 `RetrievalClient.Citation` 并逐条解析版本、分块序号和全部来源字段；通过内存 `ExchangeFunction` stub
  提供响应并捕获请求，验证完整字段、历史 null、旧上游缺版本、损坏身份过滤、其他正常条目保留及令牌透传，不启动 HTTP 服务器或请求
  kb-app。
- [x] 3.2 扩展 `ChatEvent.Citation`，保留旧字段类型与兼容构造器，增加来源、`provenanceStatus` 和 `contextTruncated`
  ；用进程内序列化单元测试验证 `VERSIONED` / `UNVERSIONED`、缺版本不补 0、不含正文，通过本地旧 DTO 夹具验证忽略新增字段后仍能读取原字段。
- [x] 3.3 调整 `ChatService.boundContext` 和引用事件映射，使预算裁剪只改正文及裁剪标记；扩展 `ChatServiceAdapterTest`，mock
  召回客户端并使用内存模型 stub，验证完整、部分裁剪、完全排除、空正文和 Unicode 边界，确认引用与传给 stub 的材料身份一致且来源不被重算。
- [x] 3.4 以 `ChatControllerTest` 风格的直接方法调用和 `StepVerifier` 单元测试验证占位应答、错误、超时、取消及
  usage/配额行为；控制器依赖、模型和 Redis 客户端全部 mock/stub，核对事件类型、顺序和终止行为，不建立 SSE 网络连接或运行配额集成测试。
- [x] 3.5 更新 AI record 的 Javadoc、`@Schema` 及 `docs/citation-provenance.md` 的 SSE 示例、旧引用降级、参考材料语义和裁剪边界；通过进程内事件
  JSON 序列化单元测试核对业务字段，文档与注解只人工核对；记录单元测试及静态检查范围，不把未执行的真实环境验证记为通过，不宣称模型逐句使用了
  citation。
