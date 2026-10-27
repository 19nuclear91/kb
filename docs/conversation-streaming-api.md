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
状态、可空真实用量及 chargedTokens）、error（通用消息）。注释帧用于连接保活。done 必须在完整回答与结算意图持久化、固定账本结算及
app 确认后出现。

每轮只订阅模型一次。增量按 Unicode 安全切块，默认 1024 code point 或 1 秒保存；顺序写入与有限请求量限制待保存缓冲，回答上限
64000 code point。执行与配额续期失败会取消上游。客户端断开即取消生成，有限时间保存已接收前缀并关闭 CANCELLED，不后台继续生成。未确认关闭由
app 租约恢复为 INTERRUPTED。完整保存后断开不会把 COMPLETED 改为 CANCELLED。

旧 POST /api/ai/chat 保留原请求、事件、占位兼容与用量字段，不自动写入历史。新接口不提供 SSE
重放、编辑分支、共享或前端。引用核对与文件预览/下载继续使用既有固定版本接口及各自当前权限。

