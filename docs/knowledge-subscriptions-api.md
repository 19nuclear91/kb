# 知识订阅 API

全部接口属于当前已登录、启用且已完成强制改密的本人，身份来自服务端会话，禁止传入userId指定他人。成功响应使用 `R<T>`（code=200、message、data，以及框架序列化的ok字段），所有成功及错误响应均 `Cache-Control: no-store`。

## 输入与分页

数据库ID、并发版本和计数使用精确十进制字符串，UUID使用标准小写格式。JSON正文最多16KiB，拒绝重复键、未知字段、尾随值和非法Unicode。命令必须携带唯一一个 `X-Request-Id`，1至128个非控制Unicode字符；同一标识只绑定同一原意图。

分页默认20、最多100，响应为 `items / hasMore / nextCursor / asOf`。游标绑定本人、用途和筛选条件，不授予访问资格。周日期为真实周一的YYYY-MM-DD；列表先按当前权限过滤再分页，无隐藏项目总数。

| 方法及地址 | 参数/正文 | 成功data |
| --- | --- | --- |
| GET `/api/knowledge-subscriptions` | 可选kind、state、limit、cursor | 本人关系列表；不可用目标隐藏targetId、label、enabled，保留本人取消身份 |
| POST `/api/knowledge-subscriptions/candidates` | kind必填；query最多128字符、limit、cursor可选 | 当前可发现的目标、本人关系版本与代次；名称按字面包含匹配 |
| POST `/api/knowledge-subscriptions/target-state` | kind、targetId | 一个当前可订阅目标及本人关系，首次version为"0" |
| POST `/api/knowledge-subscriptions` | kind、targetId、expectedVersion | 新订阅代次回执，已有效关系拒绝 |
| PUT `/api/knowledge-subscriptions/{id}/cancel` | generationId、expectedVersion | 原代次取消回执 |
| GET `/api/knowledge-subscription-preferences` | 无 | mode、version、effectiveAt、timeZone、weeklySchedule；默认WEEKLY/"0"不写库 |
| PUT `/api/knowledge-subscription-preferences` | mode、expectedVersion | 新频率生效回执 |
| POST `/api/knowledge-subscriptions/receipts/query` | requestId | CONFIRMED及最小回执，或UNKNOWN及null；不放在URL中 |
| GET `/api/knowledge-updates` | 可选weekStart、limit、cursor | 本人两种频率的当前可读发布 |
| GET `/api/knowledge-updates/{id}` | 无 | update、当前人工说明、补写/更正标记、受保护基准及comparisonStatus |
| GET `/api/knowledge-digests` | limit、cursor | 已生成且存在当前可读成员的本人周报 |
| GET `/api/knowledge-digests/{id}` | limit、cursor | digest及按文档归并的groups分页 |
| GET `/api/knowledge-digests/{id}/documents/{documentId}` | limit、cursor | 该组当前可读的固定发布分页 |

kind为DOCUMENT、SPACE、TAG；state为ACTIVE、CANCELLED；mode为PER_PUBLICATION、WEEKLY。候选中的停用标签不可新建订阅；已有列表可展示当前可发现的停用标签并允许取消。有效关系上限固定200条。

## 命令示例

新建文档订阅，使用新的请求标识，正文：

```json
{"kind":"DOCUMENT","targetId":"9007199254740993","expectedVersion":"0"}
```

取消时地址使用原回执subscriptionId，正文保持原代次：

```json
{"generationId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","expectedVersion":"1"}
```

改为逐次提醒：

```json
{"mode":"PER_PUBLICATION","expectedVersion":"0"}
```

最小命令回执包含requestId、operation、subscriptionId、generationId、version、state、mode、effectiveAt和replayed。频率回执中的关系身份和state为空；订阅/取消回执中的mode为空。不包含目标ID、名称和正文，目标撤权后仍可本人核对。匹配重放先于预期版本判断，旧取消重试不能取消重新订阅的新代次。

## 读取与错误

个人更新保留原发布版本和原频率；周报只读取WEEKLY、固定周以及dispatchPosition确定的原成员。分组计数只包含当前可读发布，周报全部不可读时详情返回404。打开目标不改变通知已读状态。

详情 `comparisonStatus` 为BASE_AVAILABLE、NO_BASE或BASE_UNAVAILABLE。hasNote=false明确表示未填写；读取说明失败返回503，不用缺说明代替错误。说明正文为当前值，原基准不会被最新版替换。版本对比沿用既有受保护对比接口与快照缺失语义。

400表示非法输入；401表示认证失效；403表示主体不可用或强制改密；404统一表示不存在/他人/当前不可读；409表示版本、代次、原意图冲突、无变化或达到上限；413表示正文超限；503表示依赖或完整结果不可确认。`X-Kb-Subscription-Error` 返回固定类别，不回显私有输入和底层异常。UNKNOWN不证明原命令未执行，当前状态不替代原回执。

通知读取能力头使用 `X-Kb-Notification-Contract: KNOWLEDGE_SUBSCRIPTIONS_V1`，详见[通知](in-app-notifications.md)。API不提供内部匹配、重发、删除通知或代他人订阅入口。
