# AI 用量与审计协议

本能力只采集启用后通过参数和当前主体检查的聊天处理。单轮新订阅产生新的 callId；多轮 callId 等于 turnId，业务重放沿用原记录。查询不回填旧 Redis 摘要或旧轮次，不读取正文和当前名称。

## 公共管理查询

接口通过现有 Sa-Token 会话访问，每次复核启用主体、强制改密与完整权限 code。`ai:usage:read` 只允许 `/api/admin/ai/usage` 和 `/api/admin/ai/usage/groups`；`ai:audit:read` 只允许 `/api/admin/ai/audits` 及 `/api/admin/ai/audits/{eventId}`。读取跨部门元数据，两项互不蕴含，ROLE_ADMIN 名称不参与放行。旧 `/api/admin/audits` 仍只处理管理与登录审计。

所有接口使用 GET，成功采用现有 `R` 包装。错误使用 HTTP 400/401/403/404/503，响应均为 `Cache-Control: no-store`；身份先于参数和目标存在性检查。参数错误不会忽略条件或回退为无筛选读取。

公共筛选：`userId`、`departmentId`（正 Long 十进制字符串）、`mode`（SINGLE/MULTI）、`generationStatus`（RUNNING/COMPLETED/FAILED/CANCELLED/INTERRUPTED/REJECTED）、`usageStatus`（KNOWN/UNKNOWN/NOT_INVOKED）、`settlementStatus`（NOT_REQUIRED/RESERVED/PENDING/SETTLED/REVIEW）、`from`、`to`。条件按 AND 组合。时间必须成对、带时区，窗口 `[from,to)` 默认最近 7 天，最长 31 天。汇总按登记时间，事件按发生时间筛选。

分组另接受 `groupBy=day|department|user`（默认 day）、`limit=1..100`（默认20）和 `cursor`。审计另接受 `callId`、`eventType`、`limit`、`cursor`。未知参数、重复参数、非法 ID、无时区日期和损坏游标返回 400。调用 ID 和事件 ID 为标准 UUID。

汇总的 `metrics` 和各分组行分别提供 `callCount`、`modelDispatchCount`、`knownCount`、`unknownCount`、`notInvokedCount`、`promptTokens`、`completionTokens`、`plannedChargedTokens`、`settledChargedTokens`，以及每个生成状态和结算状态的 `*Count`。所有整数返回精确十进制字符串，计数或总额可超过 Long；无已知样本的真实 token 合计为 null。KNOWN 的零值有实际样本；UNKNOWN 不补零；NOT_INVOKED 表示没有模型调度意图。计划额度不等于已确认额度，更不等于实际 token。

响应携带 `from`、`to`、`asOf`、`dayTimeZone=UTC`、`collectionEnabled`、`collectionStatus=ENABLED|DISABLED`、`retentionDays`、`coverageStartedAt`、`coverageIntervals`、`coverageIntervalsTruncated` 和 `historyMayBeIncomplete`。最多提供100个覆盖区间；过早窗口、停采间隙、保留期外窗口或区间截断均提示不完整。清理事务持久保存只向后推进的历史缺失界限；用量和审计查询的起点不晚于界限时也提示不完整，延长保留期不会撤销该提示。V22 对已有采集区间的升级库保守标记到迁移时刻。即使仅剩 REVIEW 例外记录也不能把历史解释为完整零调用。

分组项的 `key` 是 UTC 日期或稳定部门/用户 ID。续页返回 `hasMore`、`nextCursor`；条件和条数不能在同一游标链中改变。每次分组读取有独立 asOf，活跃调用可变，不承诺跨页冻结。审计按 `(occurredAt,id)` 倒序，首读固定已提交事件上界，后到事件在刷新后可见；游标不延续权限，也不能用于其他查询接口。

事件列表和详情提供 `id`、`eventId`、`callId`、`eventType`、`occurredAt`、`recordedAt`、`snapshotAvailable`、`snapshot`。snapshot 是阶段提交时的安全投影，包含关联 ID、时间、固定状态/结果、实际 token、计划及确认额度、策略和耗时；同一次调用可以有多个不同事件。损坏快照仅保留安全事件元数据，合法但不存在事件返回404。没有正文、名称、租约、摘要、指纹或远程错误体。

## 内部元数据提交

`POST /internal/ai-observability/register|events|renew|status` 仅用于 kb-ai 到 kb-app 的服务调用，不配置公网路由。请求体最多 32 KiB，拒绝重复 JSON 键、未知字段、尾随文档、空根、过深结构和敏感载荷。首次 register 同时透传 satoken，由 kb-app 复核当前用户与部门。后续操作不依赖原用户会话，不提供正文或模型能力。

独立观测密钥通过现有 RequestSignature 计算 HMAC，绑定 kb-ai 调用方、POST、精确内部路径、原始 body、令牌摘要、秒级时间戳与随机 nonce。头名称为 `X-Service-Caller`、`X-Service-Timestamp`、`X-Service-Nonce`、`X-Service-Signature`。nonce 使用独立 Redis 域 `kb:ai:observation:nonce:`；缺省签名窗口60秒、nonce期限180秒。不能复用会话签名密钥。

register 字段：`version=1`、`callId`、`initiatedAt`、`mode=SINGLE`、`turnId=null`。新登记意图缺省只接受300秒以内，已有相同登记按原主体和摘要幂等核对；停采阻止新登记，允许既有事实收尾。返回确认的 callId、epoch、userId、departmentId 和两轴状态。

events 字段由 kb-common 的 AiObservation.Event 定义，阶段为 ACQUIRE_INTENDED、RESERVED、DISPATCHED、FINALIZED、SETTLED、REVIEW、QUOTA_REJECTED，REGISTERED 仅由登记服务生成。固定 eventId 按 callId 与阶段计算；相同身份及摘要重试成功，不同载荷409，不存在调用404。对外接收多轮时仅允许明确的 QUOTA_REJECTED 事实，其余多轮阶段在原 kb-app 业务事务内投影。

事件确认返回原 callId、epoch、acceptedEventId、acceptedDigest，kb-ai 检查身份和摘要后推进。超时或5xx只对同一序列化载荷做有界重试，每次使用新 nonce；不把元数据重试包在模型或 RELEASE 操作之外。renew/status 只接受 callId 和 epoch；renew 仅延长未过期单轮观测租约，status 只返回安全关联和状态。

单轮缺省租约180秒、续期30秒。过期恢复只写 INTERRUPTED 和 REVIEW（没有领取意图时可确认无需结算），不后台生成、不补扣。已有终态不被晚到回调改写，固定结算确认可独立完成。单轮和多轮分别记录 SINGLE_EXISTING_V1 与 MULTI_EXISTING_V1，保留旧计费差异。
