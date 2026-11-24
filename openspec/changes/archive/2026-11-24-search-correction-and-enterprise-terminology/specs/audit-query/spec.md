# Spec Delta

## ADDED Requirements

### Requirement: 企业词典维护必须进入既有持久审计范围

术语组创建及更新 SHALL 分别使用 SEARCH_TERM_CREATE、SEARCH_TERM_UPDATE 操作域，目标类型 search-term；错词映射创建及更新使用 SEARCH_CORRECTION_CREATE、SEARCH_CORRECTION_UPDATE，目标类型 search-correction。启停属于对应 UPDATE。事件写入既有持久审计来源，列表和详情可按操作域及目标查询，继续要求 audit:read 和既有时间窗口、分页、no-store 规则。词典管理读写资格不自动授予审计读取。

#### Scenario: 按术语查询维护历史
- **WHEN** 合格审计用户按 search-term 及稳定 termId 查询
- **THEN** 返回范围内已记录的创建、编辑和启停事件，不要求其另外持有词典管理权限

#### Scenario: 只有词典管理资格
- **WHEN** 用户拥有 search:term:read 和 search:term:write，但没有 audit:read
- **THEN** 通用审计列表与详情仍拒绝，不因其维护过目标而开放跨操作者历史

#### Scenario: 目标停用后查询
- **WHEN** 审计目标术语或错词已停用
- **THEN** 原事件仍可按存储的稳定身份读取，不因当前启停状态丢失记录

### Requirement: 词典审计摘要必须使用固定结构投影

词典审计 SHALL 仅投影术语或纠错稳定 ID、错词目标 termId、前后 enabled、实体 version、dictionaryRevision 以及固定 changedFields。字段集合只允许 canonicalName、description、aliases、wrongExpression、targetTermId、enabled 中对应实体适用的项。MUST 不输出原始词条文本、释义、别名清单、查询、建议上下文、输入摘要、原始回执或关联文档数量。损坏字段保留事件元数据并标记摘要不可用。

#### Scenario: 修改名称和别名
- **WHEN** 审计用户读取修改标准名称及别名的事件
- **THEN** 可以看到哪些固定字段变化及前后版本，不读取旧名、新名或完整别名集合

#### Scenario: 修改错词目标
- **WHEN** 审计用户读取错词映射改指另一术语组的事件
- **THEN** 可以看到前后目标 termId，不关联补查名称、释义或文档明细

#### Scenario: 载荷混入查询或名称
- **WHEN** 原审计载荷存在额外 query、name、description、selectionContext 或未知嵌套值
- **THEN** 这些内容不进入查询投影，不回显原 JSON 或异常文本

### Requirement: 维护审计必须与回执同事务且不记录搜索事件

词典首次成功写入 SHALL 与实体变化、修订推进和命令回执原子产生一条成功审计；匹配重放不重复产生。校验拒绝、版本冲突、事务失败、管理读取、普通搜索及建议采用不产生成功维护事件。审计读取仍为只读，不通过新审计域持久化用户查询和建议使用历史。

#### Scenario: 匹配重试
- **WHEN** 一个已成功的启停操作使用原标识及载荷重放
- **THEN** 只存在原先一条维护事件，不再次递增词典修订

#### Scenario: 审计提交失败
- **WHEN** 业务写入不能与其审计、回执完整提交
- **THEN** 不保留本次词条变化或成功回执，不对用户报告已维护成功

#### Scenario: 用户连续采用建议
- **WHEN** 普通用户搜索并先后采用纠错和歧义建议
- **THEN** 不生成 SEARCH_TERM_UPDATE 或 SEARCH_CORRECTION_UPDATE，也不保存一份建议采用历史冒充维护审计
