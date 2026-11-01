# Spec Delta

## ADDED Requirements

### Requirement: 资料审计集合必须按字段白名单解码和展示

审计详情 SHALL 支持 tagIds 和 changedFields 的有界集合前后值：tagIds 至多 20 个精确正整数，changedFields 仅接受 title、summary、categoryId、tagIds 的不重复集合。其他字段继续采用既有标量规则，未知数组、嵌套对象、超限或非法元素作为读取故障。集合按安全文本逐项显示，不关联加载文档或词条名称，不把字段代码解释为实际正文。

#### Scenario: 标签集合包含大整数
- **WHEN** 合法资料审计含超过 JavaScript 安全整数范围的 tagId
- **THEN** 页面逐项完整显示原整数，不能经浮点转换导致截断或与其他 ID 合并

#### Scenario: 变更字段说明
- **WHEN** changedFields 为 title 和 summary
- **THEN** 页面只说明这两个字段被修改，不读取或推测修改前后正文

#### Scenario: 响应含任意嵌套对象
- **WHEN** 集合中出现对象、任意名称字符串，或未知字段返回数组
- **THEN** 详情作为读取故障处理，不直接 stringify 后展示原始载荷

### Requirement: 新分类空值必须与历史未知可区分

新版搬迁详情 SHALL 按服务器投影展示显式 null 分类与已知元数据版本；历史没有新字段时显示该信息未记录或省略，不补为未分类和零版本。合法空集合显示为空集合，不能与 null 或摘要不可用混为一谈。原审计身份、当前资格、分页窗口和迟到响应隔离继续执行。

#### Scenario: 明确迁入未分类
- **WHEN** 新版搬迁审计确认目标 categoryId 为 null
- **THEN** 页面可以显示明确未分类，与旧事件缺少分类信息的表现不同

#### Scenario: 旧事件缺少分类
- **WHEN** 旧搬迁事件没有分类和 metadataVersion 变更项
- **THEN** 页面保留已有安全详情，不推造未分类或 metadataVersion=0

#### Scenario: 标签全部移除
- **WHEN** tagIds 的后值是合法空数组
- **THEN** 页面显示标签集合已清空，不把它解释为未记录或摘要解析失败
