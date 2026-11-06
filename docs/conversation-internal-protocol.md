# 内部协议

公开网关只转发 /api/conversations/** 与 /api/ai/**，不转发 /internal/**。app 的 POST /internal/conversations/{operation}
接受 check、begin、prepare、seal、append、renew、reserve、dispatch、finish、ack；AI 仅提供 POST
/internal/conversations/settle。内部控制器不属于公开调用契约。

双方使用外部注入的共享密钥。X-Service-Caller 为 kb-ai 或 kb-app；其余头为 X-Service-Timestamp（UTC
秒）、X-Service-Nonce、X-Service-Signature。签名是 HMAC-SHA256 的十六进制值；规范串逐行连接调用方、实际大写方法、实际路径、时间、nonce、原始请求体
SHA-256、satoken SHA-256（缺省为空字符串摘要）。按原始字节签名，禁止对签名后正文再次序列化。比较采用常量时间；验签后 Redis SET
NX EX 去重，故障拒绝。重试保留业务标识并重新生成 nonce。

内部 HTTP 调用通过 W3C `traceparent`/`tracestate` 关联追踪；这些头不参与签名，不作为身份、授权或幂等依据。`X-Request-Id` 与 trace 标识语义独立，匹配重放复用 requestId，但每次入口请求生成新的 trace。baggage 不进行网络传播，供应商请求不得携带内部追踪上下文。

check/begin/prepare 需要有效的当前用户会话；seal 还须当前用户与执行票据归属相同。它们检查当前启用、强制改密与个人归属。append/renew/reserve/dispatch/finish
使用服务身份和 app 铸造的 execution：conversationId、turnId、ownerUserId、executionEpoch、authEpoch、ticket。票据仅首次 begin
返回，数据库保存摘要。authEpoch 来自已验证的用户会话，并在创建事务内复核，不能把一次旧会话请求重新绑定到复职后的新代次。
reserve、dispatch、seal、append、renew 和成功 finish 均先锁定身份、复核认证代次及有效状态，再锁会话和轮次；不能凭未过期的执行租约绕过账号撤销。
现有来源权限检查继续独立执行。服务端恢复的旧轮次若缺少 authEpoch，不推断其身份，只允许维护关闭及固定结算。

reserve 绑定 departmentId、leaseId、eventId、reservedTokens；dispatch 在模型调用之前记录意图。seal 接受 kind、实际选中的父轮
UUID 与本轮新引用，app 重新检查父轮完整来源和所有当前 view，并计算传递并集。MODEL 要有有效版本的新材料；NO_RESULTS
不接受父轮或引用。append 接受固定 sequence/text；重复相同批次不追加，不同正文或跳号拒绝。

finish 接受 status、lastBatchSeq、finalUsage、promptTokens、completionTokens，以及可选 cancellationReason，保存终态与结算意图。
已确认撤销使用 status=CANCELLED、cancellationReason=ACCOUNT_REVOKED。该原因只能用于取消，不能给成功完成附加原因后绕过检查。
取消/失败仅需服务签名与原票据的用户、轮次、执行栅栏、认证代次绑定，不要求当前用户会话，也不授予新材料或追加资格。
撤销收尾允许客户端确认位置落后于服务端已保存位置，以处理 append 已提交但响应未知；保留的前缀以服务端为准，不能提交超前位置或新正文。
同一终态按原固定用量和原因重放，不能更换结算意图。ack 仅接受
turnId/intentDigest：AI 完成固定账本结算后确认 SETTLED，不读取正文，不需要用户会话。纯 settle 仅接受 kb-app
签名及固定账本字段；用户令牌不能授权，不可调用生成或历史入口。

输入拒绝未知字段，并限制大小、数量、长度、深度及数值类型。错误只含固定消息；日志不记录问题、回答、令牌、票据、完整
prompt、材料或原始异常。所有敏感成功与错误响应 no-store。数据库不保存密钥、用户令牌、明文票据或检索材料快照。

独立认证核对使用另一套签名和密钥，见 [认证代次协议](authentication-epoch.md)。轮次服务签名、输出许可、资源权限和账本摘要各有不同职责，不互相替代。
