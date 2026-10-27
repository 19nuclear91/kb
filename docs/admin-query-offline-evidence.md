# 后端管理查询离线验收记录

变更：`admin-backend-query-capabilities`。执行日期：2026-10-17，Windows PowerShell 7、JDK 21。最终白名单测试于 12:34:16
完成，跳过测试的打包于 12:34:23 完成。

## 实际执行

以下是本次实际采用的明确类名白名单，共 27 个测试类；没有使用通配类名或默认全量测试发现。

```powershell
$tests = 'PermissionCatalogTest,AdminQueryAccessTest,QueryParametersTest,QueryCursorTest,AdminQueryAdviceTest,AdminQueryControllerTest,AdminQueryMappingTest,DocumentAdminQueryServiceTest,IdentityQueryServiceTest,AuthQueryServiceTest,PrincipalQueryServiceTest,AuditQueryServiceTest,AuditSummaryProjectorTest,AuditQuerySourceTest,ModuleBoundaryTest,JavaSourceConventionTest,IdentityManagementAuthorizerTest,PermissionServiceTest,GlobalExceptionHandlerTest,PasswordChangeRequiredInterceptorTest,SpaceAclServiceTest,DocumentAclServiceTest,SpaceAuthorizerTest,SpaceFunctionalPermissionTest,RolePermissionValidatorTest,RolePermissionWriteTest,AdminBootstrapRunnerTest'
mvn -o -pl kb-app,kb-gateway -am test "-Dtest=$tests" '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -o -pl kb-app,kb-gateway -am package '-DskipTests'
openspec validate admin-backend-query-capabilities --strict
```

结果：

- 白名单测试 BUILD SUCCESS。kb-common 执行 1 项，kb-app 执行 118 项，合计 119 项，失败/错误/跳过均为 0；kb-gateway
  没有匹配的白名单测试。
- 离线打包 BUILD SUCCESS：kb-common、kb-app、kb-gateway；打包阶段跳过测试，没有启动产物。
- OpenSpec 严格校验通过：`Change 'admin-backend-query-capabilities' is valid`。
- 测试输出：`target/admin-query-offline-tests.log`；打包输出：`target/admin-query-offline-package.log`
  。原始执行日志为本地构建产物，不作为说明文档测试输入。

## 覆盖与边界

| 任务范围      | 离线证据                                                                                                          |
|-----------|---------------------------------------------------------------------------------------------------------------|
| 公共权限与查询契约 | 权限目录、新旧读取资格独立、角色并集与下一请求撤权、强制改密、参数白名单、重复参数、Unicode、LIKE 转义、limit+1、游标绑定、UTC 微秒和固定窗口测试                          |
| 最小文档清单    | 服务 Mock 管理范围矩阵、全局管理、零范围不查 mapper、三字段投影、顺序 Stub 模拟撤权/归属变化、controller 参数保留与 no-store、JDBC ResultSet Stub 构造 DTO |
| 身份与自助查询   | 用户精确主部门条件、角色持久绑定、外部身份专用投影、资格先于目标查询、权限排序/去重/撤权、实际字典列表与依赖异常、原数组和 me 结构兼容                                        |
| 资源候选      | 当前资源管理上下文、无权/不存在统一拒绝、归档管理、三类型最小候选、资源/类型绑定游标、既有授出 manage 的独立资格、跨领域 service 边界                                  |
| 全局审计      | 只读来源交互、null actor/target 保留、双字段分页位置、AND 条件传递、时区等价、默认窗口续页、31 天边界、操作域摘要规则、角色授权方向、损坏载荷降级、列表/详情故障及日志脱敏            |
| 构建与规划     | ModuleBoundaryTest、JavaSourceConventionTest、离线打包和 OpenSpec 严格校验                                               |

MyBatis 检查只在内存构建映射和参数绑定，并通过 Mock JDBC ResultSet 实例化投影，不建立连接或执行 SQL。controller 使用
standalone MockMvc，全部业务 service 替换为 Mock；服务测试单独执行，不组装 controller-service-mapper 的真实业务链路。门禁仅使用静态会话
Stub、Mock 依赖和本地 Servlet 请求对象。

已人工核对 10 个新增 GET 声明、权限目录、响应字段、查询条件、SQL 未删除及启用谓词、迁移号和索引差异。V19 只初始化当时既有
ROLE_ADMIN 的两个新读取绑定；首次引导由完整权限目录授予新角色，既有不完整角色不自动补授。文档与示例只作人工核对。

本次没有运行集成测试、外部组件烟测、完整应用上下文或文档内容测试；没有启动应用、监听端口、发送真实 HTTP、连接
DB/Redis/MinIO/Docker/模型，也没有使用 H2、嵌入式替代或 Testcontainers。没有执行 Flyway、SQL、建索引、EXPLAIN、迁移、部署、回退或
OpenSpec 归档。

上述结果只证明白名单覆盖的进程内行为、映射构建和产物构建。真实 SQL 语义、迁移效果、事务提交、索引利用/性能、网关会话和跨服务运行链路没有实测证据。审计只覆盖现有
`sys_identity_audit` 的已记录事件，不证明未查到记录的操作从未发生。
