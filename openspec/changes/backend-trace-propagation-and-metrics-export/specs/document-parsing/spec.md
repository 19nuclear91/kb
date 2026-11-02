# Spec Delta

## MODIFIED Requirements

### Requirement: 解析任务指标必须支持健康观测

系统 SHALL 暴露低基数的任务处理、队列、租约、死信和告警指标，并使其可经 Worker 的指标导出端点被监控系统抓取；任务处理耗时 MUST 使用显式分桶。指标标签 MUST 不包含任务 ID、版本 ID、对象路径或用户输入。

#### Scenario: 队列积压观测

- **WHEN** 监控系统读取 Worker 指标
- **THEN** 可以获得待处理数量、死信数量和最老待处理任务年龄
- **AND** 指标能区分任务状态而不产生每任务一个时间序列

#### Scenario: 处理结果和耗时观测

- **WHEN** 任务完成、重试、死信或租约失守
- **THEN** 系统更新对应结果计数和处理耗时分布
- **AND** 可以按结果类型和失败分类观察趋势

#### Scenario: 外部阈值告警

- **WHEN** 外部监控规则根据积压年龄、死信深度或租约失守率超过阈值
- **THEN** 运维系统可以触发通知
- **AND** Worker 不要求绑定某个具体通知平台才能提供这些观测数据

#### Scenario: 导出端点抓取

- **WHEN** 监控系统按 Prometheus 协议抓取 Worker 指标端点
- **THEN** 取得上述全部指标，名称与标签与导出前一致，任务耗时直方图含显式分桶

## ADDED Requirements

### Requirement: 任务执行必须具有独立追踪且不向第三方传播

Worker 每次任务执行 SHALL 开启独立的追踪上下文，并写入该次执行的全部日志，包括在独立工作线程中完成的解析、分块、向量化与发布。对第三方解析服务与 embedding 供应商的调用 MUST NOT 携带追踪传播头或 baggage。追踪关闭时 MUST 不影响任务领取、执行与发布。

#### Scenario: 执行日志关联

- **WHEN** 一次解析任务在工作线程中执行并发布
- **THEN** 领取、解析、向量化与发布的日志共享同一 trace 标识，并保留原任务与版本标识

#### Scenario: 第三方解析请求头

- **WHEN** Worker 调用第三方解析服务或 embedding 供应商
- **THEN** 请求不含 `traceparent`、`tracestate` 或 baggage

#### Scenario: 追踪关闭

- **WHEN** Worker 未启用追踪
- **THEN** 任务领取、执行、重试与发布行为与启用前一致，日志不含追踪标识但保留任务与版本标识
