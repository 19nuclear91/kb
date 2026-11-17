# Spec Delta

## ADDED Requirements

### Requirement: 变更说明写入必须进入既有审计范围

变更说明的成功写入 SHALL 写入既有持久审计来源，使用独立操作域 DOCUMENT_CHANGE_NOTE_UPDATE，目标类型为 document-version。列表和详情支持该操作域与目标类型，继续要求 audit:read、固定窗口和当前权限。匹配重试只对应一条成功事件；读取、对比和被拒写入不产生成功事件。

#### Scenario: 按操作域查询说明写入
- **WHEN** 合格审计用户按 DOCUMENT_CHANGE_NOTE_UPDATE 查询
- **THEN** 返回窗口内的说明写入事件及操作者 ID，不因审计用户缺少文档阅读资格而受限

#### Scenario: 读取对比不产生审计
- **WHEN** 用户多次读取版本对比和变更说明
- **THEN** 审计中不出现对应事件

### Requirement: 变更说明审计必须只投影固定结构

DOCUMENT_CHANGE_NOTE_UPDATE 摘要 SHALL 只投影 documentId、versionId、写入前后的基准版本 ID、写入前后是否有说明、写入前后的说明版本、写入时版本是否已对读者可见，以及是否提供了原因。摘要 MUST NOT 输出说明文本、原因文本、文档标题或正文；字段错误时保留事件元数据并标记摘要不可用。

#### Scenario: 附原因更正说明
- **WHEN** 合格审计用户查看一次附原因的已发布说明更正
- **THEN** 摘要显示前后说明版本、对读者可见和“已提供原因”，不显示说明或原因文本

#### Scenario: 载荷混入文本
- **WHEN** 审计载荷除合法字段外还包含说明文本、标题或未知嵌套字段
- **THEN** 这些内容不进入安全投影
