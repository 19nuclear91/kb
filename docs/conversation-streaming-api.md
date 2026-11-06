# 多轮流接口

POST /api/ai/conversations/{conversationId}/turns 的 JSON 只允许 question、requestId、expectedLastTurnSeq。requestId 也可由
X-Request-Id 提供，两处一致；expectedLastTurnSeq 为非负整数，question 非空、最多 8000 code point。客户端不可提供历史、角色、来源或身份。使用
satoken 身份头；输入放请求体，不能放 URL。

首次成功为 200 text/event-stream，带 X-Conversation-Id、X-Turn-Id、Cache-Control: no-store；CORS 向可信来源暴露两个标识头。匹配重试为
200 application/json，仅 conversationId、turnId、turnSeq、status、replayed=true，不再次订阅、生成、召回或领配额，也不返回正文或执行票据。相同
ID 不同原问题/预期序号 409，删除后 404；先检查重放再检查旧序号。

响应提交前检查当前登录、启用、改密、个人归属、参数、多轮模型能力、begin 和配额关联。非法输入 400，会话失效 401，主体拒绝 403，不可访问
404，冲突 409，配额不足 429，依赖不可用 503。建立流后的故障使用固定 error。

事件为 citation（本轮实际新材料元数据，含固定版本和裁剪标记）、delta（text 增量）、done（会话/轮次身份、COMPLETED、保存位置、usage
状态、可空真实用量及 chargedTokens）、error（固定消息，可选 code）。注释帧用于连接保活。done 必须在完整回答与结算意图持久化、固定账本结算及
app 确认后出现。

每轮只订阅模型一次。增量按 Unicode 安全切块，默认 1024 code point 或 1 秒保存；顺序写入与有限请求量限制待保存缓冲，回答上限
64000 code point。执行与配额续期失败会取消上游。客户端断开即取消生成，有限时间保存已接收前缀并关闭 CANCELLED，不后台继续生成。未确认关闭由
app 租约恢复：持久认证代次或账号资格已撤销时关闭为 CANCELLED/ACCOUNT_REVOKED，其余过期执行为 INTERRUPTED。完整保存后断开不会把 COMPLETED 改为 CANCELLED。

旧 POST /api/ai/chat 保留原请求、事件、占位兼容与用量字段，不自动写入历史。新接口不提供 SSE
重放、编辑分支、共享或前端。引用核对与文件预览/下载继续使用既有固定版本接口及各自当前权限。

检索拒绝、超时、依赖或协议故障使用 `error` 的可选 `code: "SEARCH_UNAVAILABLE"`，固定提示“检索暂不可用，请稍后重试。”；旧单轮接口也支持该字段，缺省保持旧 error 结构。错误不追加为助手正文，不伴随 done，也不声明 NO_RESULTS。真正成功的空列表仍走原无结果流程。

多轮检索错误先尝试既有 FAILED 收尾及未调用模型的零 token 结算。若 app 不可用导致终态无法确认，cleanup 到达上限后仍结束流，由租约恢复机制关闭为 INTERRUPTED；前端需读取持久事实，不能自行伪造 FAILED。匹配旧 requestId 只返回原轮次，包括 FAILED；主动重发需新的 requestId 和当前序号，并重新按当前权限处理。

## 账号撤销与输出许可

单轮模型、占位、用量采集开启/关闭和多轮全部使用独立输出许可。许可绑定令牌对应的用户、认证代次及一次执行，硬上限 10 秒；从本地单调时钟的核对发起时刻起算，网络耗时计入预算。固定每 3 秒刷新一次，单次核对最多 2 秒；无自动网络重试或跨执行正向缓存。刷新失败立即停止，迟到响应不能复活已取消或已过期许可。

引用、正文和成功 done 在最终写出前再次检查；独立定时器不排在模型、正文保存、执行续期或结算队列后。静默上游、慢落库和慢结算不能维持输出。进程暂停后，即使定时器尚未来得及执行，排队内容写出前的单调期限检查仍会拒绝。停止信号先取消上游，再尝试有限收尾；正常完成先合法提交后即使发生撤销，也保留 COMPLETED，但不会向失效流补发 done。

流内固定 error code 为 `ACCOUNT_REVOKED`（身份明确拒绝或代次不匹配）、`AUTHENTICATION_UNAVAILABLE`（依赖、超时或不完整响应）及 `OUTPUT_PERMIT_EXPIRED`（本地许可过期）。后两项不宣称已确认离职。字段兼容原 error 的 message/code；不会把这些消息追加为正文。多轮历史增加可空 `cancellationReason`，已确认撤销值为 `ACCOUNT_REVOKED`，最终状态以服务端读取为准。

服务端只能承诺停止本地输出和取消本地订阅，不能撤回已经发送的数据，也不能证明供应商立即停止计算或计费。进程内替身与虚拟时钟验证规则和取消行为，未进行真实跨实例 30 秒验收。
