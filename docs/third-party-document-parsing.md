# 第三方文档解析接入

所有格式的原件内容抽取均交给第三方服务，包括 PDF、Office、TXT 和 Markdown。本项目不再内置文件解析引擎，也不提供本地解析回退。上传格式、大小和安全检查继续由 `DocumentFilePolicy` 负责；它不承担知识库正文抽取。

## 尚未接入服务时

第三方服务及 API 契约尚未确定，当前不附带供应商适配器、HTTP 协议或虚构的服务地址。

Worker 可以启动，但缺少 `DocumentParser` Bean 时自动装配不可用实现：启动日志明确提示“第三方解析服务未配置，暂停领取解析任务”；`/actuator/health` 的 `documentParser` 组件为 `OUT_OF_SERVICE`，整体健康检查因此返回非就绪状态。详情仍受现有 Actuator 访问策略约束。

任务领取服务在锁定 Embedding 控制状态、领取租约和增加尝试次数前检查解析接入状态。未就绪时，新上传及待解析版本继续排队，既有已发布版本不因缺少解析器而重新处理。直接调用流水线同样会在下载原件前明确失败，不会生成占位正文、分块或向量。

## 适配器边界

服务确定后，在 Worker 中提供一个实现 `com.zhishiku.kb.worker.parse.DocumentParser` 的 Spring Bean，默认不可用实现会自动退出装配。

- `parse`：把传入的原件流交给第三方服务，返回服务抽取的文本；不得在 Worker 内自行解析原件。
- `parseDocument`：结构化服务应覆盖此方法，将服务响应转换为 `ParsedDocument`，保留结构块、标题路径、表格行列、真实来源坐标、解析器标识和版本、截断与质量问题。纯文本服务可使用接口默认的结果包装；该包装仅转换服务已抽取的文本。
- `isAvailable`：只表达本地接入配置是否就绪，不执行网络请求、不读取原件。缺省为 true，配置不完整的适配器应返回 false。该状态不表示远程服务已通过连通性检查。

适配器自行负责认证、文件上传、服务协议、连接与响应超时、响应大小上限、字符预算、取消及错误映射。不得将服务密钥、原件正文或远程错误体写入日志和失败摘要；不得静默忽略 `maxExtractedChars`。具体配置键待服务协议确定后定义。

Worker 继续负责对象存储读取、任务租约、失败恢复、统一结果分块、Embedding 及原子发布。现有文件与字符限制、`KB_TASK_TIMEOUT_SECONDS` 仍有效；原本地解析堆和解析超时配置已移除，不再使用。

## 验证范围

离线测试覆盖未配置时的装配和健康状态、第三方 Bean 替换默认实现、任务不领取及尝试次数不变、流水线不读取原件或调用 Embedding，以及已有结构分块和索引流程。尚未选择或联调真实第三方服务。

2026-10-24 在 PowerShell 下执行：

- `mvn -o -pl kb-worker -am clean verify '-Dtest=*,!JavaSourceConventionTest' '-Dstyle.color=never'` 通过：kb-common 39 项、kb-worker 112 项测试，失败、错误、跳过均为 0，Worker 完成打包。
- 默认验证先被现有 `JavaSourceConventionTest` 阻断，报错涉及账号管理反馈、资源能力等现有记录类型的文档规范；本次未修改这些规范问题，相关验证明确排除该测试。
- `mvn -o test-compile '-DskipTests' '-Dstyle.color=never'` 全模块通过。
- `openspec validate document-parsing --type spec --strict` 通过。
- 检查新 Worker JAR，未包含已移除的本地解析实现及其解析依赖。
