# Spec Delta

## ADDED Requirements

### Requirement: 适用期与复审事件必须进入既有审计范围

适用期修改、责任人变更与复审确认的成功写入 SHALL 写入既有持久审计来源，分别使用独立操作域 DOCUMENT_APPLICABILITY_UPDATE（目标类型 document-version）、DOCUMENT_STEWARDSHIP_UPDATE（目标类型 document，操作为 assign、reassign、unassign 或 cycle）和 DOCUMENT_STEWARDSHIP_CONFIRM（目标类型 document）。列表和详情支持这些操作域与目标类型，继续要求 audit:read、固定窗口和当前权限。匹配重试只对应一条成功事件，读取与被拒写入不产生成功事件。

#### Scenario: 按操作域查询复审确认
- **WHEN** 合格审计用户按 DOCUMENT_STEWARDSHIP_CONFIRM 查询
- **THEN** 返回窗口内的确认事件及操作者 ID，不因审计用户缺少文档阅读或空间管理资格而受限

#### Scenario: 重试不产生重复事件
- **WHEN** 同一请求标识的适用期修改被匹配重试
- **THEN** 审计中只有一条对应的成功事件

### Requirement: 适用期与复审审计必须只投影固定结构

DOCUMENT_APPLICABILITY_UPDATE 摘要 SHALL 只投影 documentId、versionId、修改前后的生效日、截止类型、截止日与适用期版本，以及修改时版本是否已对读者可见、是否提供了原因；复审事件只投影前后责任人 ID、周期、最近确认日、到期日和复审版本。摘要 MUST NOT 输出原因文本、显示名、文档标题或正文；字段错误时保留事件元数据并标记摘要不可用。

#### Scenario: 已发布版本更正
- **WHEN** 合格审计用户查看一次附原因的已发布版本更正
- **THEN** 摘要显示前后日期与“已提供原因”，不显示原因文本

#### Scenario: 载荷混入名称
- **WHEN** 审计载荷除合法字段外还包含显示名、标题或未知嵌套字段
- **THEN** 这些内容不进入安全投影，查询不能成为人员目录或文档内容的读取入口
