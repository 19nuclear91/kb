# 多轮适配器与预算

新入口需要 MultiTurnModel，实现有序角色消息、固定系统指令、本轮新材料、maxOutputTokens、contextWindowTokens、inputTokenUpperBound
和带明确 finalUsage 的流事件。仅实现旧 ChatModelClient 不能启用多轮；缺少能力返回 503，不能用占位回答写入正常多轮历史。当前工程不交付具体生产模型供应商。旧
POST /api/ai/chat 保留既有单轮与占位兼容。

app 只提供当前可见且 COMPLETED 的完整历史及完整来源依赖。AI 默认最多 8 对历史，按序保留 user/assistant
角色，不提炼摘要。查询组合最近最多两条合格原用户问题及本轮原问题，以换行分隔，限 8000 code
point；如“任务租约”后问“它过期后怎么办”。组合只用于召回，存储与最后 user 消息仍是原问题。无历史只用当前问题，不再调用模型改写。召回拒绝、故障、无有效版本材料时使用固定
NO_RESULTS，不用旧答案绕过检索。

最终输入加输出上限必须落在模型窗口及本次 12000 token 预留内，默认输出上限 2048，另有 24000 code point 输入保护。生产 token
上界必须由适配器证明，不能用字符数冒充真实 usage。预算优先整对移除最旧历史及其全部依赖，再按 Unicode code point
裁剪新材料；原问题不截断。必需输入不适配时拒绝；来源默认最多 256 个，不能删来源后保留对应历史正文。

source seal 使用最终 Plan 的父轮与新材料，app 再验证并保存实际来源传递并集。SSE citation 只列本轮实际新材料，保留固定
document/version/chunk 及裁剪标记，不把继承来源声明为逐句证据。无 versionId 的旧材料不传模型，也不推断当前版本。

最终累计 usage 只取明确确认的最后值，不求和。模型调用意图先持久化；无可靠最终统计为 UNKNOWN，实际 token 字段
null，chargedTokens 独立为预留。没有调用模型为 NOT_INVOKED、真实及计费均零；KNOWN 保留真实超预留统计，不截断隐藏费用。

