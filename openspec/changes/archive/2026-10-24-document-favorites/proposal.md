# Proposal

## Why

用户目前只能通过文档中心和搜索重新寻找常用资料，缺少跨登录保留的个人快捷入口。文档、当前版本和实时授权读取已经具备，可在这些能力上增加个人收藏，优先交付一个独立可用的用户功能。

## What Changes

- 提供本人收藏文档、取消收藏、批量查询收藏状态及分页读取收藏列表的接口；收藏关联稳定文档身份，打开时沿用文档当前版本规则。
- 收藏与取消使用明确的目标状态，同一用户与文档最多一条关系，重复收藏保持原收藏顺序。
- 收藏列表和状态查询每次按当前文档 view、密级、空间归档及删除状态过滤；用户可取消本人已经不可见文档的收藏。
- 在文档列表、文档详情和搜索结果增加收藏按钮，按本页不同文档批量读取状态；提供受保护的 `/favorites`“我的收藏”入口。
- 为身份门禁、请求解析、返回状态、分页编排和前端迟到响应补充基于内存数据与替身的单元测试，并完成代码静态审查、编译和静态构建。

## Capabilities

### New Capabilities

- `document-favorites`：个人文档收藏的归属、目标状态写入、实时授权读取、分页及生命周期规则。

### Modified Capabilities

- `user-web-documents`：新增文档与搜索收藏交互、“我的收藏”页面，以及状态核对和当前会话内的交互一致性。

## Impact

- `kb-app`：在现有 document 模块内增加收藏控制器、服务、数据访问和 DTO；新增 Flyway 迁移创建个人收藏关系及索引，复用 `PermissionService` 与现有文档授权谓词。
- API：新增 `PUT /api/documents/{documentId}/favorite`、`DELETE /api/documents/{documentId}/favorite`、`POST /api/documents/favorites/status`、`GET /api/documents/favorites`。新增接口均位于网关现有 `/api/documents/**` 路由范围。
- `kb-web`：增加收藏 API/状态管理和共享按钮、“我的收藏”页面，接入 `Documents.tsx`、`Search.tsx`、`App.tsx`、`navigation.ts`，复用会话生命周期和精确整数编解码。
- 数据库和验证：交付个人收藏关系迁移及查询代码；验证限定为进程内单元测试、代码静态审查、编译、类型检查和静态构建。测试使用 mock/stub，不启动应用、HTTP 服务、真实浏览器或数据库、Redis、MinIO、Docker、模型等外部组件，不创建或运行集成与端到端测试。
- 说明文档继续作为交付物；不编写或执行针对 README、Markdown、注释、文档内容及文档示例的测试。单元测试结论仅覆盖被测代码与替身场景。
- 本变更聚焦文档收藏；分类、标签和答案反馈沿已讨论的后续顺序分别规划。
