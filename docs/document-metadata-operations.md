# 文档资料与目录运维

## 数据模型与迁移脚本

`V27__document_metadata_taxonomy.sql` 承接 V26，增加以下状态：

- `kb_document.summary/category_id/metadata_version`：历史资料为 null/null/0。
- `kb_category_tree_state`：已有空间初始化版本零；新空间创建时同事务建立。
- `kb_document_category`：空间内邻接树，根及非根分别判重；文档和分类父引用均使用同空间复合外键。
- `kb_document_tag`、`kb_document_tag_relation`：全局词条和稳定 ID 关联，初始无关系。
- `kb_document_metadata_command`：只追加的内部请求绑定与最小回执，删除空间或目录不清除历史绑定。

目录父子移动、资料关联及搬迁由树状态锁协调；空间归档、取消归档和删除先锁树状态再锁空间。空间存在任何文档保留引用时拒绝删除，包括软删除文档。无文档的空间删除通过外键同事务清理分类和树状态。

文档软删除保留分类和标签关系。分类存在子节点或任何文档引用时不能删除；标签通过启停维护身份，停用仍保留已有文档关系。

## 发布与回退边界

V28 仅向迁移时已有 ROLE_ADMIN 绑定标签读写权限；空库正式引导新建管理员角色时纳入全集，已有角色撤权不会被启动补回。

发布时应排空旧 app 写入实例，部署理解新权限目录、分类外键和搬迁协议的新 app/common，再同步更新前端。旧客户端缺少 targetCategoryId 或 expectedMetadataVersion 时返回 400。历史 DOCUMENT_RELOCATE 审计只有旧回执、没有新命令绑定的请求标识返回 409，不补造目标分类或重做搬迁。

回退应保留目录、资料、关系、回执和迁移历史，使用理解新权限/外键的兼容后端；不直接回退到只更新 space_id 或不认识新增权限码的旧 app。最小搬迁上下文不依赖文档正文 view，允许空间管理者只凭 ID、来源空间和资料版本确认操作。

## 离线验证

实际白名单与结果见 [离线验证记录](document-metadata-validation.md)，页面操作见 [使用说明](document-metadata-web.md)。

迁移脚本和 SQL 仅交付并静态审查。开发验证使用明确白名单的纯内存单元/组件测试、编译、类型检查与静态构建。未执行数据库迁移、SQL、事务隔离、真实锁竞争或部署演练；内存替身测试不证明这些运行时行为。
