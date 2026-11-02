# metrics-export Specification

## Purpose

为四个部署单元提供统一、可被 Prometheus 抓取的指标导出契约：限定暴露面与管理端口、固定直方图分桶、约束标签基数，并把流式响应生命周期纳入可观测范围。

## Requirements

### Requirement: 指标必须以 Prometheus 兼容格式从每个部署单元导出

网关、业务应用、AI 服务与 Worker SHALL 各自提供可被 Prometheus 抓取的指标端点，输出该单元注册的全部指标；端点 MUST 支持 OpenMetrics 协商，以便在追踪启用时为直方图样本附带 exemplar。除下述显式兼容性例外外，既有指标名与标签 MUST 保持不变，导出不得改写或重命名：Worker MUST 保留 `kb.parse.tasks.retried`、`kb.parse.tasks.dead_lettered` 无标签总量，将其 `failure_class` 分类序列分别注册为 `kb.parse.tasks.retried.by_class`、`kb.parse.tasks.dead_lettered.by_class`，标签键和值域保持不变，避免同名指标标签键集合冲突。

#### Scenario: 抓取 Worker 指标

- **WHEN** 监控系统按 Prometheus 协议抓取 Worker 指标端点
- **THEN** 返回既有解析任务、队列、死信与 embedding 指标，除明确拆名的重试/死信分类序列外，名称与标签与导出前一致
- **AND** 原无标签重试/死信总量与 `.by_class` 分类序列同时可被抓取

#### Scenario: 抓取网关指标

- **WHEN** 监控系统抓取网关指标端点
- **THEN** 返回按路由模板、方法与状态聚合的请求指标，流式路由与普通路由可区分

#### Scenario: OpenMetrics 协商

- **WHEN** 抓取请求以 OpenMetrics 格式协商且追踪已启用
- **THEN** 耗时直方图样本可携带当前 trace 标识作为 exemplar
- **AND** 追踪关闭时端点仍正常返回且不带 exemplar

### Requirement: 指标端点必须只在管理端口暴露且生产配置显式

指标与其他管理端点 SHALL 不与业务 API 共用对外路由，网关 MUST NOT 转发任何管理端点。生产环境 MUST 显式配置管理端口与绑定地址，暴露清单 MUST NOT 使用通配；缺失或非法 MUST 在建立业务连接前以只含配置名的固定错误拒绝启动。开发环境可继续在业务端口提供管理端点。

#### Scenario: 生产缺少管理端口

- **WHEN** 生产配置未显式指定管理端口或绑定地址
- **THEN** 单元拒绝启动，错误只包含配置名，不输出其他配置值

#### Scenario: 通配暴露清单

- **WHEN** 管理端点暴露清单包含通配项
- **THEN** 生产配置校验拒绝启动

#### Scenario: 经网关访问管理端点

- **WHEN** 外部客户端通过网关请求任何管理端点路径
- **THEN** 网关不将其路由到任何单元，管理端点只能在管理端口直接访问

#### Scenario: 开发环境沿用业务端口

- **WHEN** 单元以开发配置启动且未指定管理端口
- **THEN** 健康检查与指标端点在业务端口可用，不触发生产校验

### Requirement: 耗时直方图必须按既有预算显式分桶

检索阶段、模型调用与任务处理等耗时指标 SHALL 使用与各自预算对应的显式分桶，使预算边界附近的分位数可被准确观测。分桶 MUST 可配置，非递增或非正的分桶配置 MUST 拒绝启动；默认分桶 MUST 覆盖既有默认预算边界，即检索 2 秒、3 秒、1.5 秒与 8 秒，模型 60 秒。

#### Scenario: 检索预算边界

- **WHEN** 监控系统读取检索阶段耗时直方图
- **THEN** 直方图包含对应阶段预算与 8 秒总预算的边界桶，可判断延迟是否逼近预算

#### Scenario: 非法分桶配置

- **WHEN** 分桶配置包含非正值或未按递增排列
- **THEN** 单元拒绝启动，不静默回落默认分桶

### Requirement: 自动采集的 HTTP 指标标签必须有界

框架自动采集的 HTTP 服务端与客户端指标 SHALL 仅以路由模板、方法、状态与结果类别作为标签。未匹配路径、管理端点自身以及含标识符的原始路径 MUST NOT 形成新的时间序列；任何指标标签 MUST NOT 含用户、请求标识、查询、profile、完整 URL、凭据或资源 ID。

#### Scenario: 扫描不存在的路径

- **WHEN** 客户端请求大量不存在或随机的路径
- **THEN** 这些请求归入固定的未匹配类别，不为每个路径新增时间序列

#### Scenario: 含标识符的路由

- **WHEN** 多个不同文档 ID 请求同一资源路由
- **THEN** 指标按路由模板聚合为同一序列

#### Scenario: 对外供应商调用

- **WHEN** 单元调用模型、embedding、rerank 或第三方解析服务
- **THEN** 客户端指标只按固定目标类别与结果记录，不含完整 URL 或凭据

### Requirement: 流式响应生命周期必须可观测

AI 流式生成 SHALL 记录流开始、正常终结、安全错误终结、客户端取消与超时的低基数计数，以及首个内容事件的延迟。错误终结 MUST 以固定错误码类别计数。全部标签 MUST 为固定枚举，MUST NOT 包含问题、回答、会话或轮次标识。

#### Scenario: 正常完成

- **WHEN** 一次流式生成正常终结
- **THEN** 完成计数增加，并记录首个内容事件延迟与整流耗时

#### Scenario: 客户端断连

- **WHEN** 客户端在生成期间断开连接
- **THEN** 取消类别计数增加，不计为完成或错误

#### Scenario: 检索故障终结

- **WHEN** 流以固定检索不可用错误结束
- **THEN** 错误终结按该固定错误码类别计数，不记录问题文本
