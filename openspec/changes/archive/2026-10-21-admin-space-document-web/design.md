# Design

## Context

动机和范围见 [proposal.md](proposal.md)，行为契约见 `specs/`。本设计基于现有实现：

- `kb-web/src/admin/access.tsx` 的 `AdminAccessProvider` 以会话 epoch 为界只读取 `/api/auth/permissions`；`AdminGuard` 与 `adminNavigation` 只接受单个权限 code。`navigation.ts` 的登录返回地址白名单只含三个身份管理路径。
- `adminWrite`、`adminFailure`、`useAdminWrite`、`useAdminResource`、`useCursorList`、`settle` 与具体路径无关，可直接复用；`adminReasons` 是全局有限映射。
- 空间与文档接口已存在：`SpaceController`（目录、摘要、生命周期、空间授权）、`DocumentAclController`、`DocumentRelocationController`、`DocumentSecurityLevelController`、`DocumentAdminQueryController`（ID 级清单，默认包含归档空间）、`PrincipalQueryController`（空间/文档上下文候选）。所有写入在请求体缺少 `requestId` 时回退 `X-Request-Id`。
- `GET /api/admin/spaces` 无分页，返回 viewable/manageable；`space:manage-all` 使全部空间 manageable。
- `IdentityManagementFeedbackAdvice` 以 `assignableTypes` 加 `WRITES` 精确白名单投影 `IdentityException`。空间/文档服务已抛 `IdentityException`，但 409 均为通用 CONFLICT，并经 `GlobalExceptionHandler` 回显服务端 message（含文档数量、空间 ID）。
- `ModuleBoundaryTest` 只约束 web → service → mapper 方向，不禁止 Web 层之间的类型引用。

## Goals / Non-Goals

**Goals:**

- 不新增后端接口与权限，把资源级管理资格作为第二个事实源并入现有权限上下文。
- 复用身份管理已建立的写入生命周期、错误白名单和 epoch 隔离，不另起一套状态机。
- 以可回退的方式把空间/文档冲突细分进现有错误投影。

**Non-Goals:**

- 修改空间/文档服务的授权顺序、幂等、事务或 404/403 先后（存在性探测另案）。
- 新增“我的可管理空间”、文档标题批量查询等后端读取接口，或为空间目录增加分页。
- 跨 epoch 缓存空间目录，或把 manageable 当作授权凭证。

## Decisions

### 1. 可管理空间并入 AdminAccessProvider，状态独立

`check()` 在同一 sequence、epoch 与 AbortController 下并行读取 `/api/auth/permissions` 与 `/api/admin/spaces`，分别记录 `status` 与 `spaceStatus`（loading/ready/unavailable），并提供按精确 spaceId 索引的 manageable 空间及 `canManageSpace(spaceId)`。两者独立失败：目录失败不使身份管理页不可用，权限失败也不伪装目录为空。

备选：各页面各自读取目录，会让导航、守卫和详情入口得到不一致的事实，403 后也无法统一核对；新增“我的可管理空间”接口与目录重复且超出本次范围。代价是每次权限核对多一次目录请求，文档中心已在读取同一目录，体量可接受。

### 2. 导航与守卫改为谓词

`adminNavigation` 的项由单个 `code` 改为可见性谓词：身份项保持 `has(code)`；新增“知识空间”分组 `/admin/spaces`，谓词为存在 manageable 空间，或持有 `space:create`、`space:manage-all`。`perm` 增加 `spaceCreate`、`spaceManageAll`、`grantManage`、`securityLevelWrite`。

- `/admin/spaces`：新守卫等待两个来源均非 loading，谓词成立则放行；不成立时，目录不可用显示可重试故障，否则显示无访问资格。
- `/admin/spaces/:spaceId`、`/admin/documents/:documentId`：只要求权限来源 ready，不套用导航谓词；资格由页面自身的资源读取决定。
- `navigation.ts` 返回地址白名单精确增补 `admin/spaces`、`admin/spaces/<正整数>`、`admin/documents/<正整数>`，不放宽为任意 `/admin/**`。

备选：为空间区引入伪权限 code，会与“不得以名称或前缀代替资格”冲突，不采用。

### 3. 页面与读取组织

新增 `admin/spaceApi.ts`（路径、WriteIntent 工厂、DTO）、`SpacesPage.tsx`、`SpaceDetailPage.tsx`、`DocumentPanelPage.tsx`，以及空间与文档授权共用的 `PrincipalPicker.tsx`。

| 页面/区块 | 读取 | 前置条件 |
| --- | --- | --- |
| 空间目录 | provider 中的 manageable 空间 | 导航谓词 |
| 空间摘要 | `GET /api/admin/spaces/{id}` | 路径合法 |
| 空间授权、空间文档清单 | `GET .../{id}/acl`；`GET /api/admin/documents?spaceId=` 游标 | 摘要 manageable |
| 删除前核对 | `GET /api/admin/documents?spaceId=&limit=1` | 打开删除确认时 |
| 文档归属 | `GET /api/admin/documents?documentId=` | 路径合法 |
| 例外授权 | `GET /api/admin/documents/{id}/acl` | 归属非空 |
| 密级 | `GET .../security-level`；`/api/auth/security-levels` | `document:security-level:write` |
| 标题 | `GET /api/documents/{id}` | 无；失败只显示 ID |
| 主体候选 | `.../acl/principals?principalType=&q=` | 对应资源可管理 |

各区块以目标 ID 为 key 使用 `useAdminResource`/`useCursorList`，切换目标时沿用既有机制丢弃迟到结果。搬迁目标取 provider 中未归档且不同于当前空间的 manageable 空间，`spaceStatus` 不可用时搬迁区块显示故障；搬迁确认需例外授权列表已就绪以显示数量，未就绪不可提交。

### 4. 写入复用 adminWrite，请求标识只走请求头

空间/文档写入全部经 `useAdminWrite` 与 `adminWrite` 发送，请求体不携带 `requestId`，使 intentKey 只绑定业务值；密级接口在两者同时出现时要求一致，只用请求头即无歧义。空间删除不带请求体，授权撤销以请求体携带主体与权限位。ID、版本与密级版本按 `numericId`/`exactVersion` 以 BigInt 精确编码，密级经既有范围校验。

成功后的刷新：空间任一写入、搬迁及账号主部门调整成功后调用 `access.check(true)` 重读两个来源，再按 `settle` 重读当前区块；角色绑定与角色权限写入沿用既有 `check(true)`，现已同时覆盖可管理空间；`useAdminWrite` 收到 403 的既有 `check(true)` 同样核对两个来源。

### 5. 错误投影扩展到空间与文档写入

后端：

- `IdentityConflictReason` 增加 `SPACE_NOT_EMPTY`、`SOURCE_MISMATCH`、`TARGET_ARCHIVED`、`ALREADY_IN_TARGET`。
- 在检查点显式附加：`SpaceWriteService` 编码重复附加 `IDENTIFIER_CONFLICT`、仍有文档附加 `SPACE_NOT_EMPTY`；`DocumentRelocationService` 的已在目标、来源不符、目标归档分别附加对应原因；`DocumentSecurityLevelService` 的版本过期改用 `IdentityErrorCode.INVALID_VERSION`（仍为 409，旧入口 message 不变）。空间插入失败、搬迁并发删除、密级相同、状态变化与请求标识复用保持通用 CONFLICT。
- 扩展既有 `IdentityManagementFeedbackAdvice`：`assignableTypes` 增加四个控制器，`WRITES` 增加 11 条精确路由（空间创建、修改、归档、取消归档、删除，空间授权授予/撤销，文档例外授予/撤销，搬迁，密级调整），`text()` 增加四条固定文案。不新建第二个 advice：两个最高优先级 advice 的先后不确定，且会复制白名单与文案表。

前端：`adminReasons` 增加四条 409 文案；`IDENTIFIER_CONFLICT` 现有文案已涵盖“编码”。页面按原因执行规格中的重读动作，`settle` 对 409 的重读保持不变。

### 6. 前端预检只减少必然失败的提交

删除前的 `limit=1` 核对、搬迁目标过滤、按本人 `clearanceLevel` 与字典过滤密级选项、按 `space:grant-manage` 显示 manage 选项，都只是体验优化。服务端拒绝仍按投影原因处理并重读，前端不以预检结果覆盖服务端判定。本人密级取自会话中的 me，不新增读取。

### 7. 文档详情入口只读 provider

`DocumentDetailPage` 以 `canManageSpace(detail.spaceId)` 或 `has(securityLevelWrite)` 计算入口，不发起额外请求；对应来源未 ready 时视为不满足。

### 8. 验证沿用进程内测试

前端以 vitest 和模拟 DOM 覆盖：provider 两个来源的并行、独立失败与 epoch 隔离；各区块按资格跳过请求；写入生命周期与新原因白名单；返回地址白名单。后端扩展 `IdentityManagementFeedbackTest`（白名单断言由 15 条身份路由改为 26 条，新控制器原因、读取保持原契约）和服务单测（检查点附加正确原因），并运行 `ModuleBoundaryTest`。前端运行 `tsc` 与静态构建。不运行集成、E2E、真实数据库或浏览器。

## Risks / Trade-offs

- [所有登录用户多一次空间目录读取] → 与权限读取并行并共享中止控制；空间规模显著增长时再引入分页或专用接口。
- [manageable 提示过期] → 只作提示，各页面以自身读取为准，403 后重读两个来源。
- [白名单路由的 message 变为固定文案] → 外部调用方不再获得文档数量、空间 ID 等细节；HTTP 状态、code、data 不变，需在接口说明中注明。
- [并发删除、请求标识复用等仍为通用 CONFLICT] → 前端按通用冲突重读，需要时再增加原因。
- [密级读取 403 无法区分超出本人密级与失去权限] → 先核对本人权限，仍持有时给出通用说明。
- [仅密级权限者难以发现不可阅读或归档空间中的文档] → proposal 已列为已知限制，直接访问面板地址仍可用。

## Migration Plan

- 无数据库迁移、新权限或新接口；前后端可独立发布与回退。
- 先发后端：旧前端不读取 reasonCode，状态与字段不变。前端连接旧后端时，空间/文档冲突回退为通用 409 提示。
- 回退前端即移除新页面；回退后端恢复原 message 回显，前端自动回退为通用提示。
- 归档前：`identity-management-feedback` 主规格的 Purpose 只提及账号、部门和角色，delta 无法修改 Purpose，需在归档时扩写为包含空间与文档管理写入。
