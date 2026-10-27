# Proposal

## Why

当前上传直接创建 `published` 文档，Worker 在解析成功后切换当前版本，无法表达业务审核；指定版本读取也没有待审隔离。需要在不影响旧发布版本、既有授权与 Embedding 一致性的前提下，建立可逐空间启用的版本级单级审核。

## What Changes

- 空间默认自动发布，管理员可启用审核；每个版本创建时固定策略，切换开关不追溯已有版本。
- 需审版本解析成功后由编辑者手动送审；空间审核人池中的任一合格人员可通过或驳回，禁止自审，通过与发布原子完成。
- 审核人须同时具备当前 `view`、空间 `review` 和足够密级；提交者身份、空间管理或审核身份均不自动授予正文、下载权限。
- 待审等未发布版本仅向有查看资格的上传者／提交者与合格审核人开放；普通读者继续读取原发布版本。覆盖列表、文件、引用、收藏及历史回答，私有审核材料不进入检索或 RAG。
- 保存真实上传者、审核轮次、私有审核意见、发布证据、幂等回执及不含正文的追加事件，为后续通知和报表提供事实源。
- **BREAKING**：新增需审版本的读取与发布语义改变，旧 app/Worker 不能混部写入；旧版本保留显式兼容标记，不把索引完成伪装为历史发布事实。
- 不引入工作流引擎、多级审批、会签、强制管理员代审或通知渠道；站内通知由后续独立变更消费已提交事件。

## Capabilities

### New Capabilities

- `document-review`：空间策略、版本级单级审核、未发布版本访问、受控发布与业务事件。
- `user-web-document-review`：空间审核设置、本人版本工作台、审核待办、私有意见及发布状态交互。

### Modified Capabilities

- `space-authorization`：启用空间 `review` 授权，继续拒绝尚未实现的 `comment` 和文档级 `review`。
- `document-parsing`：分离索引完成与业务发布，保留租约、profile、空间和密级一致性。
- `citation-provenance`：固定版本引用增加发布／候选版本访问门禁，不扩大 RAG 来源。
- `conversation-history`：历史回答与模型上下文的来源检查不能借审核资格引入未发布材料。
- `document-favorites`：收藏不成为首版待审文档的元数据或版本旁路。
- `user-web-documents`：区分解析、审核、发布状态，按选中版本控制文件与编辑操作。

## Impact

- 主要涉及 `kb-app` 的 document、space、auth、search、conversation 服务与 Mapper，`kb-worker/parse/ChunkPublisher`、候选索引刷新，以及 `kb-web` 文档与空间管理页面。
- 在 `kb-common` 增加纯 JDK 发布与协作事件契约；app/Worker 保持部署边界，不让 AI 直连数据库。
- 后续新增 Flyway 迁移；本次只规划，不执行迁移、不改主规格。迁移编号实施时按最新库历史分配，不改 V1–V22。
- 本变更先实施并同步规格，再实施 `in-app-notifications`、`versioned-document-comments`，最后实施 `knowledge-operations-reports`；共享规格不得无序覆盖。
