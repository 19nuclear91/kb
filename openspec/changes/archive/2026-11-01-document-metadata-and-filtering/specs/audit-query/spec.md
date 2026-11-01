# Spec Delta

## ADDED Requirements

### Requirement: 文档资料与目录事件必须进入既有审计范围

元数据、分类及标签成功变更 SHALL 写入既有持久审计来源，使用独立操作域 DOCUMENT_METADATA_UPDATE、DOCUMENT_CATEGORY_CREATE/UPDATE/DELETE、DOCUMENT_TAG_CREATE/UPDATE；搬迁沿用 DOCUMENT_RELOCATE。列表和详情支持相应操作域与 document、document-category、document-tag 目标类型，继续要求 audit:read、固定窗口和当前权限。匹配重试只对应一条成功事件。

#### Scenario: 查询全局标签变更
- **WHEN** 合格审计用户按 DOCUMENT_TAG_UPDATE 或 document-tag 目标查询
- **THEN** 可得到该类型已有变更事件，不因标签管理权限而额外扩大或限制审计资格

#### Scenario: 目录删除后读取历史
- **WHEN** 分类已被合法删除，审计用户读取此前创建和删除事件
- **THEN** 原事件仍按稳定 ID 返回，不依赖目录实体继续存在

### Requirement: 新资料审计必须只投影固定结构

元数据审计 SHALL 只投影固定 changedFields、分类/标签 ID 和 metadataVersion 的前后值；分类事件仅允许空间/分类/父节点 ID、排序、treeVersion 和名称已变更布尔值；标签事件仅允许 tagId、enabled、version 和名称已变更布尔值。MUST 不输出标题、摘要、分类路径、词条名称、输入摘要、原始回执或关联文档数量。字段错误时保留事件元数据并标记摘要不可用。

#### Scenario: 标题或摘要修改
- **WHEN** 合格审计用户查看仅修改标题和摘要的事件
- **THEN** 可见哪些固定字段发生变化及前后版本，不返回原文、新文或其内容摘要

#### Scenario: 原载荷混入目录名称
- **WHEN** 审计载荷除合法身份外还包含 name、title、summary 或未知嵌套字段
- **THEN** 这些内容不进入安全投影，查询不能变成文档或词库内容读取入口

### Requirement: 新版搬迁摘要必须安全兼容历史事件

DOCUMENT_RELOCATE 的新摘要 SHALL 在已有安全空间与例外摘要上增加原/目标分类 ID 和前后 metadataVersion，分类允许 null。旧事件没有新字段时保持未知，不伪造曾经未分类或版本为零。分类名称、全局标签名称和文档资料不得通过关联读取补入，原事件保持只读。

#### Scenario: 查看新版搬迁
- **WHEN** 搬迁明确选择未分类且已提交
- **THEN** 审计安全详情可显示目标分类为 null 和新元数据版本，不返回源或目标分类名称

#### Scenario: 查看升级前搬迁
- **WHEN** 历史搬迁记录没有分类和元数据版本字段
- **THEN** 仍展示已有安全摘要，新字段标记未知，不将缺失解释为曾明确选择未分类
