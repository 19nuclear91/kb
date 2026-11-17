# document-diff Specification

## Purpose

基于解析发布时保存的版本结构快照，对同一文档的两个版本给出块级结构差异和关键数值变化，并在无法可靠对比时明确说明原因，帮助审核人和读者定位版本之间的实际变化，同时不扩大任何版本的访问范围。

## Requirements

### Requirement: 对比必须只使用与当前索引一致的结构快照

系统 SHALL 只使用与版本当前索引执行周期一致、且版本处于已索引状态的结构快照进行对比。快照缺失、与当前执行周期不一致或版本不在已索引状态时，该侧 MUST 视为没有可用快照，不得从检索分块拼接正文、重新调用解析服务或使用旧快照代替。

#### Scenario: 旧 Worker 发布的版本
- **WHEN** 被对比的版本由未保存快照的旧 Worker 发布
- **THEN** 结果状态为 SNAPSHOT_MISSING，并标明缺少快照的一侧，不返回差异明细

#### Scenario: 待审版本刷新索引后尚未完成
- **WHEN** 编辑者对待审版本申请刷新索引，新的索引执行尚未完成
- **THEN** 该版本的对比结果为 SNAPSHOT_MISSING，不使用刷新前的快照

#### Scenario: 快照来自更早的执行周期
- **WHEN** 版本已重新索引，而保存的快照属于此前的执行周期
- **THEN** 该侧视为没有可用快照，结果为 SNAPSHOT_MISSING

### Requirement: 对比接口必须校验版本归属并分别鉴权

`GET /api/documents/{documentId}/versions/{versionId}/diff?baseVersionId={id}` SHALL 以路径版本为目标、baseVersionId 为基准。两个版本 MUST 属于该文档，并各自满足既有版本交互访问规则（未发布版本仅对上传者、送审者和合格审核人开放）。任一版本不存在、归属不符、文档已删除、空间已归档或不可访问时统一返回 404，不说明是哪一侧。

#### Scenario: 普通读者对比待审版本
- **WHEN** 普通读者可以查看已发布 V1，请求对比 V1 与未发布的待审 V2
- **THEN** 返回 404，不透露 V2 是否存在

#### Scenario: 合格审核人对比待审版本
- **WHEN** 合格审核人请求对比当前发布版本 V1 与其可访问的待审版本 V2，两侧均有可用快照
- **THEN** 返回 V1 到 V2 的差异结果

#### Scenario: 基准版本属于另一份文档
- **WHEN** baseVersionId 属于另一份文档，即使调用者可以查看该文档
- **THEN** 返回 404，不返回任一版本的内容

#### Scenario: 撤权后再次请求
- **WHEN** 用户曾成功读取对比结果，随后失去该文档的 view
- **THEN** 再次请求返回 404，不使用先前结果

### Requirement: 对比请求参数必须严格校验

请求 SHALL 只接受一个 baseVersionId 查询参数，取值为正整数且不等于路径中的 versionId。参数缺失、重复、非整数、未知参数或两个版本相同时 MUST 返回 400，且不读取任何版本。接口 SHALL 保留登录、主体状态与强制改密门禁，成功与错误响应均标记 no-store。

#### Scenario: 对比同一个版本
- **WHEN** baseVersionId 等于路径中的 versionId
- **THEN** 返回 400，不读取版本或快照

#### Scenario: 携带未知参数
- **WHEN** 请求除 baseVersionId 外还带有其他查询参数，或 baseVersionId 出现两次
- **THEN** 返回 400

### Requirement: 差异结果必须以结构块为单位表达

可对比时，结果 SHALL 按调用方指定的方向（基准到目标）返回有序操作列表，每项为 EQUAL、ADDED、REMOVED、MODIFIED 或 MOVED 之一，并带结构类型、标题路径以及各侧的块序号和可用页码。修改的段落、列表和代码块 SHALL 给出逐字的 EQUAL、INSERT、DELETE 片段。结果另附各类操作数量。系统 MUST NOT 自动交换两个版本的方向。

#### Scenario: 段落中改了一句话
- **WHEN** 目标版本只修改了某段中的一句话，其余块相同
- **THEN** 该段为 MODIFIED，并给出删除与插入的片段，其余块为 EQUAL

#### Scenario: 段落原样移到别处
- **WHEN** 某段文本未变但出现在目标版本的另一位置
- **THEN** 该段以 MOVED 表达，而不是一条 REMOVED 加一条 ADDED

#### Scenario: 两个版本内容相同
- **WHEN** 两侧快照的全部块归一化后相同
- **THEN** 状态为 AVAILABLE，全部操作为 EQUAL，变化数量均为 0

#### Scenario: 以较新版本作为基准
- **WHEN** 调用方以 V3 为基准、V2 为目标请求对比
- **THEN** 结果按 V3 到 V2 的方向表达，不改为 V2 到 V3

#### Scenario: 修改的块过长
- **WHEN** 一对修改块的长度超过逐字比较的上限
- **THEN** 该项仍为 MODIFIED，并标记未提供逐字片段，同时返回两侧全文

### Requirement: 表格差异必须保留行列语义

两侧对应的表格列数一致时，系统 SHALL 按行对齐，并在修改的行内逐个单元格给出前后值。列数不一致或无法按行对齐时，该表格 MUST 标记为 RESTRUCTURED，并返回两侧表格内容，不编造单元格级的对应关系。

#### Scenario: 表格中改了一个单元格
- **WHEN** 两侧表格列数相同，目标版本只改了某行一个单元格
- **THEN** 该行标记为修改，只有该单元格给出前后值

#### Scenario: 表格新增一列
- **WHEN** 目标版本的对应表格比基准多一列
- **THEN** 该表格标记为 RESTRUCTURED，不给出逐格对应

### Requirement: 关键数值变化必须只从修改内容中提取

系统 SHALL 从 MODIFIED 块和表格中修改的单元格里，识别日期、金额、百分比和条款编号四类数值，比较前后出现情况，并按类型、出现顺序配对为前值与后值；无法配对的列为仅前值或仅后值。比较时忽略全角与半角数字及千分位写法的差别，展示时保留原文写法。ADDED、REMOVED 与 MOVED 块中的数值 MUST NOT 列入。

#### Scenario: 金额被修改
- **WHEN** 某段中的“5,000 元”在目标版本中改为“8,000 元”
- **THEN** 数值变化列出一条金额，前值“5,000 元”，后值“8,000 元”，并关联到该段

#### Scenario: 只改了数字写法
- **WHEN** 某段中的“５０００元”改为“5,000元”，数值相同
- **THEN** 不列为数值变化，该段的逐字差异仍正常给出

#### Scenario: 新增段落中包含日期
- **WHEN** 目标版本新增了一段包含截止日期的文字
- **THEN** 该段以 ADDED 表达，日期不列入数值变化

### Requirement: 解析质量提示必须随结果返回

可对比时，结果 SHALL 附带两侧的解析器名称与版本，两者不同时附 PARSER_DIFFERS 提示；任一侧解析截断时附 TRUNCATED 提示并标明一侧。提示 MUST NOT 阻止返回差异，也不得被省略。

#### Scenario: 两个版本使用了不同的解析器版本
- **WHEN** 基准与目标的解析器版本不同
- **THEN** 状态为 AVAILABLE，并附 PARSER_DIFFERS 提示，说明差异可能来自解析器

#### Scenario: 目标版本被截断
- **WHEN** 目标版本的解析结果被截断
- **THEN** 结果附带标明目标侧的 TRUNCATED 提示

### Requirement: 无法可靠对比时必须返回明确状态而不返回差异明细

以下情况 SHALL 返回 HTTP 200 及对应状态，带两侧版本身份与已知提示，MUST NOT 返回操作列表或数值变化：任一侧没有可用快照为 SNAPSHOT_MISSING；任一侧超过每版本字符数或块数上限（默认 500,000 字符、10,000 块）为 TOO_LARGE；计算超过限时（默认 5 秒）为 TIMEOUT；可对齐内容占比低于下限（默认 0.3）为 LOW_SIMILARITY。

#### Scenario: 版本内容超过上限
- **WHEN** 基准版本快照有 600,000 个字符
- **THEN** 结果为 TOO_LARGE，并在读取块正文之前判定

#### Scenario: 计算超时
- **WHEN** 对比计算超过限时
- **THEN** 结果为 TIMEOUT，不返回部分差异

#### Scenario: 两个版本几乎完全不同
- **WHEN** 可对齐内容占比低于下限
- **THEN** 结果为 LOW_SIMILARITY，页面可据此建议分别预览两个版本

### Requirement: 对比计算必须受实例并发上限约束

每个应用实例 SHALL 限制同时进行的对比计算数量（默认 4）。达到上限时 MUST 立即返回 429 DIFF_CAPACITY，不排队等待。一次计算占用的名额在计算实际退出后才释放，包括超时后仍在退出的计算。

#### Scenario: 并发请求超过上限
- **WHEN** 已有 4 个对比计算在进行，又收到新的对比请求
- **THEN** 新请求立即返回 429，不进入等待队列

#### Scenario: 超时计算尚未退出
- **WHEN** 一次计算已按超时返回 TIMEOUT，但计算线程尚未退出
- **THEN** 该名额在线程退出前不释放

### Requirement: 对比读取必须安全表达故障且不留存结果

对比结果 MUST NOT 跨请求缓存，读取不写审计。版本、快照或访问规则无法可靠读取时 SHALL 返回安全 503，不得表现为空差异或 SNAPSHOT_MISSING。错误、日志与遥测 MUST NOT 包含块正文、差异片段或数值。

#### Scenario: 依赖故障
- **WHEN** 访问规则或快照暂时无法读取
- **THEN** 返回 503，页面不把它显示为“两个版本相同”或“缺少快照”

#### Scenario: 计算异常的日志
- **WHEN** 对比计算因异常失败
- **THEN** 日志只记录版本 ID、状态和固定错误码，不记录正文或差异内容
