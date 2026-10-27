# Proposal

## Why

当前 `comment` 只是被业务层拒绝的 ACL 预留值，用户不能针对固定版本反馈问题。需要将公开讨论与私有审核意见明确隔离，避免旧版意见漂移到新版或评论成为未发布内容和 RAG 的旁路。

## What Changes

- 对具备可核验发布证据的版本提供纯文本评论、单层回复及本人删除，V1 评论不复制或改指向 V2。
- 阅读遵守当前版本查看资格；发表／回复要求当前 `view + comment` 和密级，管理资格或作者身份不隐含阅读与发表评论权。
- 启用空间及文档例外 `comment` 授权，保持例外只扩权及搬迁清除规则；空间 `review` 继续有效，文档级 `review` 仍拒绝。
- 评论创建、幂等回执、无正文事件原子保存；回复事件交给既有站内通知消费者，不因通知派生失败撤销回复。
- 新增所选版本评论面板；待审交流仅使用审核意见，批准后也不自动公开。评论不进入索引、Embedding、RAG 或会话来源。
- 首期不支持编辑评论、深层回复、附件、富文本、划词、点赞、`@提及` 或管理员代删；治理操作须另行定义资格，不能偷用 manage。

## Capabilities

### New Capabilities

- `document-comments`：固定已发布版本评论、单层回复、本人删除、权限、幂等和协作事件。
- `user-web-comments`：版本评论面板、分页、显式提交与删除及身份隔离。

### Modified Capabilities

- `space-authorization`：在审核变更完成后的授权规则上增加空间及文档例外 `comment`，不改变 view、review、manage 的独立性。

## Impact

- 依赖先实施并同步 `versioned-document-review` 和 `in-app-notifications`；增量规格以审核后的 `space-authorization` 为前置，不可直接覆盖当前主规格。
- 在 `kb-app` 新增 comment 模块、评论与无正文事实迁移，使用 document 的版本访问服务和 notification 的安全目标解析服务；跨模块只经 service，不直调他方 Mapper。
- `kb-web` 在文档选中版本处挂载评论面板，授权页面增加 comment 选项；复用既有文档网关路径。
- 不重写评论历史或给旧索引版本补造发布证据；本次仅规划，不修改实现与主规格。
