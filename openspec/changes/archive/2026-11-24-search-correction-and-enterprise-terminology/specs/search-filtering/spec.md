# Spec Delta

## ADDED Requirements

### Requirement: 增强搜索必须显式扩展输入并兼容原搜索

`POST /api/search/assisted` SHALL 接受既有 query、candidates、topK 及全部组合筛选，另接受 mode、selectionContext、selections。mode 仅为 ASSISTED 或 ORIGINAL，省略为 ASSISTED，显式 null 非法；上下文省略或 null 表示无，selections 省略为空数组但显式 null 非法。既有字段的类型、默认值、交叉校验和权限语义保持一致。`POST /api/search` 继续返回 R 包装数组并拒绝这些新增字段，不根据客户端类型或请求来源偷偷切换行为。

#### Scenario: 新调用仅有合法 query
- **WHEN** 用户向增强入口只提交合法 query
- **THEN** 使用 ASSISTED、candidates=50、topK=50、空筛选和空选择

#### Scenario: 原调用提交增强字段
- **WHEN** 客户端向原 `/api/search` 提交 mode 或 selections
- **THEN** 按未知字段返回 400，不忽略字段并假称增强已生效

#### Scenario: 原 AI 和采集调用
- **WHEN** AI 或检索采集工具使用原接口及原有合法数量和筛选
- **THEN** 保持有序分块数组及固定版本引用，不要求新增模式或词典上下文

### Requirement: 建议选择必须严格解码并验证上下文

selections SHALL 为至多 8 项的数组，每项只含 kind、start、end、termId；kind 仅为 CORRECTION 或 DISAMBIGUATION，位置为原 query 的精确整数 code point 半开区间，termId 为正整数且不超过 Long.MAX_VALUE。区间须合法、不重复且不重叠。非空选择要求非空有效 selectionContext；空选择不得携带上下文。ORIGINAL 不接受选择。未知或重复字段、溢出、小数、非法模式、种类及任一绑定不匹配为 400，已验证上下文的修订变化或到期为 409。

#### Scenario: 选择原文之外的位置
- **WHEN** end 超出 query 的 code point 长度，或两个选择区间重叠
- **THEN** 返回 400，不执行召回或部分采用

#### Scenario: 选择身份精度
- **WHEN** 合法 termId 超出 JavaScript 安全整数范围
- **THEN** 按精确 JSON 整数解码并比较，不经浮点舍入或字符串强制转换

#### Scenario: 修改条件复用建议
- **WHEN** 有效上下文绑定 PDF 与标签 A，但实际采用请求提交 Word 或标签 B
- **THEN** 返回 400，不把旧建议当作不同查询条件的已确认选择

### Requirement: 增强结果必须携带可验证的响应形状

增强入口成功 SHALL 返回 R 包装的对象，含有序 hits 数组和 assistance；不提供 total 或分页。assistance 须符合 search-query-assistance 的模式和字段契约；版本及身份使用精确十进制字符串，原文位置为有界整数。所有成功响应附 `X-Search-Assistance-Version: 1` 与 `X-Search-Filter-Version: 1`，由实际执行服务生成。客户端必须核对两个头及对象结构，缺失、冲突、未知版本或旧数组不得解释为增强成功。

#### Scenario: 原词模式或空命中
- **WHEN** 增强入口以 ORIGINAL 返回结果，或 ASSISTED 返回 hits=[]
- **THEN** 均返回完整对象及两个版本头，空 hits 与 suggestions 各自表达事实

#### Scenario: 请求落到旧服务
- **WHEN** 旧实例返回数组、404 或没有合法增强版本头
- **THEN** 客户端不能将其显示为已应用术语的结果，不自动向其他路径重发

#### Scenario: 有效表达被损坏
- **WHEN** 响应 effectiveQuery、原文位置、mode 或对应必需字段不满足契约
- **THEN** 客户端丢弃该次整体结果并提示协议不可确认，不只隐藏异常说明而展示无法核对的命中

### Requirement: 增强搜索必须在每路截取前保留完整条件

同义扩展、纠错和歧义采用、ORIGINAL 及合法降级 SHALL 继续在每路候选截取前执行相同 ACL、密级、归档、发布版本及全部组合筛选。词条公开范围不扩大文档可见集合，候选按固定分块身份去重。空间、分类、标签、类型或时间交集无命中须返回真实空 hits，不删除条件再次召回；分类、标签目录仍按现有可检索文档事实生成，不混入术语词典。

#### Scenario: 扩展词仅命中无权文档
- **WHEN** 扩展的正式名称存在于无权文档，而当前授权范围无符合结果
- **THEN** 可以显示公开词典关系，hits 为空，不返回隐藏身份、标题、数量或提高权限后的结果

#### Scenario: 文档例外与扩展
- **WHEN** 用户仅通过文档 view 例外可检索某篇已发布资料，搜索未限定空间且术语扩展命中它
- **THEN** 该文档仍可参与召回，不能用空间下拉候选作为额外白名单过滤它

#### Scenario: 采用建议后降级
- **WHEN** 带多标签和时间条件的纠错搜索发生 Embedding 或 rerank 降级
- **THEN** 返回的全部分块仍满足原完整条件，显示的采用关系与该次降级执行一致

#### Scenario: 公开术语没有可见分类
- **WHEN** 词典包含某公开术语但用户没有使用该名称的可检索分类或标签
- **THEN** 不把词条注入分类或标签候选，不借词典显示隐藏目录
