# Tasks

验证仅限进程内单元测试与静态检查，依赖使用 Mock/Stub，不组装跨模块业务链路，不启动应用或外部服务。文档仅交付并人工核对，不编写或执行 README、Markdown、OpenAPI/Javadoc 内容及示例测试。测试按明确的单元测试类/方法白名单运行，打包跳过测试。

## 1. 角色 code 解析查询

- [x] 1.1 在 `PermissionMapper` 增加按 userId 查角色 code 的查询（`sys_user_role JOIN sys_role`），并过滤
  `u.enabled = true`（见 design D3）。验证：单测断言注解内 SQL 同时包含 `sys_role` 连接与 enabled 过滤，照
  `PermissionMapperArchiveFilterTest` 断言 SQL 形状的做法

## 2. 功能角色接线与防回归

- [x] 2.1 在 kb-app 实现 `StpInterface` Bean：`getRoleList` 走 1.1 的查询返回角色 code，`getPermissionList` 返回空列表（见
  design D1、D2、D4）。验证：单测以 stub mapper 覆盖"持有 ROLE_ADMIN / 撤销关联 / 用户已停用"三态下 `getRoleList` 的返回。该测试针对
  `StpInterface` 实现类本身，因此无法通过 mock `IdentityManagementAuthorizer` 满足（见 design D5）
- [x] 2.2 增加装配断言，确认存在一个非测试类实现 `StpInterface` 且带 Spring 组件注解，照 `ModuleBoundaryTest` 的 ArchUnit
  手法或 `NoDatabaseAccessTest` 的反射手法实现。验证：移除该实现类或其组件注解后测试失败——这是"框架默认实现恒返回空角色"
  这一缺陷的唯一装配级防线
- [x] 2.3 增加守护测试，断言生产代码中不存在 Sa-Token 的 permission API 调用（`checkPermission` / `hasPermission`
  的业务侧使用）。验证：在任意生产类中临时加入一次 `StpUtil.checkPermission` 调用后测试失败

## 3. 用户密级调整

- [x] 3.1 在 `AuditScope` 增加 `USER_CLEARANCE_CHANGE`，`targetType` 为 `user`（见 design D6）。验证：`AuditScopeTest`
  覆盖该枚举项符合 `<实体>_<操作>` 前缀约定与 targetType 对应关系
- [x] 3.2 增加带 `version` 条件的密级更新语句与密级字典查询。验证：单测断言更新语句的 WHERE 含 version 条件、字典查询语句形状正确，断言方式与
  1.1 一致（只检查 SQL 文本，不执行）
- [x] 3.3 在 `IdentityService` 实现密级调整：字典校验（D8）、拒绝以自身为目标（D7）、version 冲突返回并发冲突、同一 `requestId`
  重放返回首次结果、审计记录前后密级。验证：单测以 stub mapper 与 stub 字典逐条覆盖上述五种结果
- [x] 3.4 增加 `PATCH /api/admin/identity/users/{userId}/clearance`，要求 `requireManager()`、`X-Request-Id`（或请求体
  `requestId`）与 `version`，并补 OpenAPI 注解。验证：控制器测试覆盖缺请求标识返回 400、无全局管理资格返回 403、以自身为目标返回
  403、成功返回新密级与新版本，沿用 `IdentityManagementControllerTest` 的 mock 方式
- [x] 3.5 拒绝通过其他身份写操作夹带密级变更。验证：测试断言在用户归属转移或启停请求中携带密级字段时返回 400，且密级与归属均未改变

## 4. 密级取值校验统一到字典

- [x] 4.1 把 `DocumentUploadService` 中硬编码的 `0..3` 区间校验替换为 3.2 的字典查询（见 design D8）。验证：单测以 stub
  字典覆盖字典内取值通过、字典外取值返回 400，并断言校验逻辑中不再出现写死的级别上下界

## 5. 文档同步

- [x] 5.1 更新 README 能力表中"角色与密级管理"一行：从"❌ 未实现"改为准确描述——功能角色解析与用户密级调整已实现但**未连真实
  PG 实测**，角色 CRUD、角色分配与文档密级调整仍缺失；交付说明并人工核对实现与验证边界
- [x] 5.2 在 README 第六节上生产硬性检查项中补一条：生产移除 V3 种子后将没有任何 ROLE_ADMIN
  且无法制造第一个，上线前必须先落地引导机制；交付说明并人工核对引导前置条件
- [x] 5.3 更新 README 5.7 / 5.8 的前置说明，指出 `$ADMIN_TOKEN` 需要持有 ROLE_ADMIN 的账户，并在第八节"下一步"第 5
  项中把已完成部分剔除，只保留角色 CRUD、角色分配与文档密级调整；交付说明并人工核对接口资格与剩余工作
