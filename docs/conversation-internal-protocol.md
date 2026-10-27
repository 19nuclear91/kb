# 内部协议

公开网关只转发 /api/conversations/** 与 /api/ai/**，不转发 /internal/**。app 的 POST /internal/conversations/{operation}
接受 check、begin、prepare、seal、append、renew、reserve、dispatch、finish、ack；AI 仅提供 POST
/internal/conversations/settle。内部控制器不属于公开调用契约。

双方使用外部注入的共享密钥。X-Service-Caller 为 kb-ai 或 kb-app；其余头为 X-Service-Timestamp（UTC
秒）、X-Service-Nonce、X-Service-Signature。签名是 HMAC-SHA256 的十六进制值；规范串逐行连接调用方、实际大写方法、实际路径、时间、nonce、原始请求体
SHA-256、satoken SHA-256（缺省为空字符串摘要）。按原始字节签名，禁止对签名后正文再次序列化。比较采用常量时间；验签后 Redis SET
NX EX 去重，故障拒绝。重试保留业务标识并重新生成 nonce。

check/begin/prepare 需要有效的当前用户会话；seal 还须当前用户与执行票据归属相同。它们检查当前启用、强制改密与个人归属。append/renew/reserve/dispatch/finish
使用服务身份和 app 铸造的 execution：conversationId、turnId、ownerUserId、executionEpoch、ticket。票据仅首次 begin
返回，数据库保存摘要；校验活动状态、租约和删除栅栏，因此用户会话自然过期后仅能关闭原有执行，不能读取其他历史。

reserve 绑定 departmentId、leaseId、eventId、reservedTokens；dispatch 在模型调用之前记录意图。seal 接受 kind、实际选中的父轮
UUID 与本轮新引用，app 重新检查父轮完整来源和所有当前 view，并计算传递并集。MODEL 要有有效版本的新材料；NO_RESULTS
不接受父轮或引用。append 接受固定 sequence/text；重复相同批次不追加，不同正文或跳号拒绝。

finish 接受 status、lastBatchSeq、finalUsage、promptTokens、completionTokens，保存终态与结算意图。ack 仅接受
turnId/intentDigest：AI 完成固定账本结算后确认 SETTLED，不读取正文，不需要用户会话。纯 settle 仅接受 kb-app
签名及固定账本字段；用户令牌不能授权，不可调用生成或历史入口。

输入拒绝未知字段，并限制大小、数量、长度、深度及数值类型。错误只含固定消息；日志不记录问题、回答、令牌、票据、完整
prompt、材料或原始异常。所有敏感成功与错误响应 no-store。数据库不保存密钥、用户令牌、明文票据或检索材料快照。

