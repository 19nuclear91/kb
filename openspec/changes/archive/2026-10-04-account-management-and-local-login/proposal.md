# Proposal

## Why

登录链路目前只有可关闭的开发登录（凭数字 userId 直接建立会话）。生产上既没有创建用户、分配角色的接口，也没有任何真实凭据校验，更没有制造首个管理员的机制——移除
V3 种子后 `sys_role` 为空，全部管理端点将对所有人返回 403 且无法从产品内部恢复（README
第六节的拦截项）。公司身份源（钉钉）已决定后置集成，因此本期先落地"后台建号 + 本地用户名密码登录"
，把登录链路从开发态升级为可上生产的本地账号体系，同时保证将来绑定钉钉时零返工：外部身份映射表、绑定接口与主体解析均已就绪，后续回调只需复用同一套会话建立逻辑。

## What Changes

- 新增受保护的用户创建接口 `POST /api/admin/identity/users`：唯一用户名、必须落在启用部门、初始密级来自服务端字典，带
  `X-Request-Id` 幂等与审计。
- 新增首个管理员引导（部署配置驱动、幂等）：空库启动时确保 `ROLE_ADMIN` 角色行、首个管理员账号与角色绑定存在，管理端点始终可达；已存在时不做任何修改。
- 补齐角色最小管理：角色列表、用户角色分配与解除（`sys_user_role`），受保护、幂等、可审计；禁止解除系统内最后一个 `ROLE_ADMIN`
  持有者。角色 CRUD 不在本期。
- 新增本地登录 `POST /api/auth/login`：用户名 + 密码，BCrypt 存储（`spring-security-crypto`
  ）；管理员设初始密码、首次登录强制改密、自助改密与管理员重置；登录节流与成败审计；失败响应统一、不区分用户名是否存在。
- 新增当前会话主体查询 `GET /api/auth/me`（含首次改密标记），供前端渲染身份与改密引导。
- 未认证响应语义修正：无会话或无效会话从 500 改为 401（网关、kb-app、kb-ai 一致），与已认证但无资格的 403 区分。
- 网关放行 `POST /api/auth/login`；其余 `/api/**` 仍要求有效会话。
- 数据库：`sys_user` 增加可空的 `password_hash` 等凭据列（新迁移），为将来钉钉-only 账号（无本地密码）留位。
- 明确不在本期：钉钉/OIDC 集成、角色 CRUD、文档密级调整、密码找回（邮件/短信）。

## Capabilities

### New Capabilities

- `user-authentication`: 本地用户名密码凭据、登录会话建立、密码生命周期（初始设置/首次强制修改/自助修改/管理员重置）、登录节流与成败审计、当前会话主体查询、未认证响应语义。

### Modified Capabilities

- `identity-department`: 新增用户创建、角色分配/解除、首个管理员引导三组要求；既有主体解析、审计、并发与角色资格要求不变。

## Impact

- **kb-app**: `auth` 模块新增登录/改密/引导服务与接口；`IdentityService`、`IdentityManagementController`、
  `IdentityDepartmentMapper`、`PermissionMapper`、`AuditScope` 扩展；`pom.xml` 新增 `spring-security-crypto`；新迁移 V12；
  `application.yml` 增加引导与密码策略配置。
- **kb-gateway**: `SaTokenGatewayConfig` 放行 `/api/auth/login`，未认证响应改为 401。
- **kb-ai**: 会话校验失败响应同步为 401；`QuotaPrincipalClient` 对 401/403 的既有分类保持正确（未认证由"配额不可用"归入"
  主体被拒绝"）。
- **测试**: 新增凭据哈希、登录、节流、引导、建号、角色分配的单元/切片测试；修正固定了 500 语义的既有测试。
- **文档**: README 能力表、启动步骤、第六节拦截项与第八节下一步同步更新。
