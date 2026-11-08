# 批量文档导入 API

本文描述 `batch-document-import` 的固定契约，接口交付进度以变更任务清单为准。导入新建文档，每批统一空间和密级，不按文件名或内容摘要更新已有文档。

## 固定清单

创建清单使用 UTF-8 JSON：

```json
{
  "spaceId": 9,
  "securityLevel": 0,
  "items": [
    {
      "itemKey": "file-1",
      "fileName": "说明.txt",
      "title": "说明",
      "sizeBytes": 5,
      "sha256": "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    }
  ]
}
```

- `spaceId` 为精确正整数，最大 Long.MAX_VALUE；密级使用当前字典，不固定为 0–3。
- `itemKey` 为 1–128 个 ASCII 字符，匹配 `[A-Za-z0-9][A-Za-z0-9._:-]*`，批次内唯一。
- 文件名去除首尾空白，禁止路径分隔符、控制字符及 `.`/`..`；最多 255 个 UTF-16 单元，沿用现有上传文件名限制。
- `title` 可省略或为 null，空白时采用文件名；非空标题最多 500 个 Unicode code point，与现有文档数据库约束一致。
- 大小为正整数，摘要为 64 位十六进制，规范化为小写。服务器仍按实际文件字节独立校验。
- 所有身份和 revision 精确保留整数，禁止浮点或字符串替代 JSON 数字。未知字段、重复键、非法 UTF-8、NUL、孤立代理项及尾随 JSON 拒绝。
- 清单成功创建后冻结目标、密级、顺序、项键、文件描述和标题；改换文件或目标需要新的明确意图。

## 身份与重复语义

创建和项上传使用 `X-Request-Id`，去除首尾空白后为 1–256 个 Unicode code point，不接受控制字符。客户端建议生成 UUID。

同一创建者、同一创建标识和同一规范化清单返回同一批次；标识复用但内容不同返回 409。同一项只允许创建一份文档，响应丢失后的核对使用原项身份。已失效尝试的标识不重新启动上传；用户主动继续需新的尝试标识、最新 revision 与匹配文件。

不同项即使同名同摘要仍是独立意图。新批次可以再次导入相同内容。摘要不能用于秒传、下载、查找其他人的文档或自动覆盖已有版本。

## 接口目录

以下接口采用现有会话认证、R 包装和 `Cache-Control: no-store`：

| 方法与路径 | 契约 |
| --- | --- |
| GET `/api/document-imports/capabilities` | 当前公开容量、格式及协议版本 |
| POST `/api/document-imports` | 固定清单创建，返回 `importId`、`replayed` |
| POST `/api/document-imports/resolve` | JSON `requestId` 只读核对，返回 `found` 和可空 `importId` |
| GET `/api/document-imports` | 本人批次游标分页 |
| GET `/api/document-imports/{importId}` | 任务摘要及当前继续资格 |
| GET `/api/document-imports/{importId}/items` | 固定顺序项分页 |
| GET `/api/document-imports/{importId}/items/{itemId}` | 当前项状态与固定受理三元组 |
| POST `/api/document-imports/{importId}/items/{itemId}/file` | 一个 multipart `file`、`expectedRevision` 及请求标识 |

`resolve` 没有查得记录只代表当前读取事实，不能证明原在途请求已取消。收到合法批次回执前不得发送文件到猜测的任务。

## 状态与回执

项状态为 `PENDING`、`PROCESSING`、`ACCEPTED`、`FAILED`。`revision` 在状态改变时递增，受理后固定保存 `documentId`、`versionId`、`parseTaskId`。受理不表示解析完成、审核通过或已发布，文档后续删除也不能使原项重新创建文档。

批次计数为 `pending`、`processing`、`accepted`、`failed`，同次读取相加等于项数。存在待处理项为 `OPEN`，全受理为 `COMPLETED`，已终结且混合成功/失败为 `PARTIAL_FAILED`，全失败为 `FAILED`。客户端“待确认”另行表示，不冒充服务端失败。

持久失败代码为 `INVALID_FILE`、`PAYLOAD_TOO_LARGE`、`STORAGE_UNAVAILABLE`、`EXPIRED`、`ACCESS_DENIED`。错误对外只使用固定类别，不回显原始存储异常。明确失败和网络结果未知须通过持久项状态区分。

## 配置与公开能力

配置前缀为 `kb.document-import`，非法整数、越界或容量/预算矛盾阻止启动。以下为当前初值：

| 键 | 默认 | 范围 |
| --- | ---: | --- |
| `max-items` | 100 | 1–500 |
| `max-total-bytes` | 1073741824 | 1–107374182400 bytes |
| `max-concurrent` | 8 | 1–64，每实例 |
| `max-concurrent-per-user` | 2 | 1–实例并发上限 |
| `attempt-timeout-seconds` | 120 | 1–120 秒 |
| `storage-timeout-seconds` | 60 | 1–尝试期限秒数 |
| `recovery-interval-ms` | 30000 | 1000–3600000 ms |
| `recovery-batch-size` | 100 | 1–1000 |

清单固定最多 1 MiB、JSON 深度最多 8。单文件上限来自 `kb.storage.max-upload-bytes`，默认 50 MiB。公开能力字段为 `protocolVersion=1`、`maxItems`、`maxTotalBytes`、`maxFileBytes`、`maxManifestBytes`、`uploadConcurrency`、`extensions`；并发建议为 `min(2, 当前每用户上限)`。不提供存储地址、桶凭据或内部配置。

列表及项分页默认 20，范围 1–100。任务和幂等历史首期不自动清理。发布与原件对账策略在导入运维说明中单独描述。

## 本人归属与分级读取

任务仅属于创建者，管理员角色不授予他人任务的查询权。创建、上传及匹配写重放需要当前原空间 edit；查询本人最小历史不依赖 edit。当前认证代次、账号和主部门启用、强制改密门禁始终独立生效。

- 本人仍有原空间上传资格：任务摘要可返回 `spaceId`、`securityLevel`，项的 `file` 返回原提交文件描述。它是原清单，不是文档当前标题或当前所属空间。
- 本人失去 edit 或空间归档：任务摘要省略目标和密级，`canContinue=false`；项省略 `file`、`canUpload=false`，保留项身份、顺序、revision、受理状态、固定失败码及已受理三元组。
- 他人的批次/项与不存在的目标统一 404。依赖故障返回 503，不伪装成空列表、资格撤销或文档已删除。
- 仅有 edit 无 view 可以上传和核对回执，但处理状态、审核意见、正文及文件仍通过已有文档接口独立检查。原文档搬迁或删除不改变首次受理记录，不使该项获得再次创建资格。

## 分页与查询参数

任务列表采用批次 ID 倒序；项列表采用固定 ordinal 正序。仅接受单个 `limit` 和 `cursor` 参数，未知参数、重复参数、非法页大小拒绝。响应包含 `items`、`hasMore`、`nextCursor`，末页游标为 null；游标绑定本人及对应批次，不能作为访问凭据。

详情、能力与单项接口不接受查询串。创建与 resolve 的数据只在 JSON 请求体发送。resolve 的 `found=false` 不自动允许新建替代批次；仍需要保留原创建意图并核对或主动匹配重试。

## 错误处理

| 状态 | 含义与下一步 |
| --- | --- |
| 400 | 输入、标识、分页或 JSON 非法，修正后重新明确意图 |
| 401 | 会话失效或认证代次撤销，停止发送并重新登录 |
| 403 | 强制改密或当前上传资格拒绝，暂停后续上传并重核 |
| 404 | 任务或项不存在/当前不可访问，不推断目标是否属于他人 |
| 409 | 请求绑定、文件匹配、revision 或执行状态冲突，先读取当前项 |
| 413/415 | 文件/请求超限或内容格式被拒；持久项是否已记失败仍需读取确认 |
| 429 | 上传容量繁忙，主动稍后继续，不表示 AI 额度耗尽 |
| 503/响应损坏或丢失 | 结果不可确认，只读核对；不能直接认定未落库并重传 |

读取成功的原受理三元组是历史事实，不保证当前文档仍可访问。日志和错误响应只输出固定类别，不附带文件名、摘要、正文或原始依赖异常。

## 逐项文件与主动重试

项文件入口仅接收一个名为 `file` 的文件和一个十进制 `expectedRevision` 表单字段。禁止额外或重复字段、额外文件、查询串和重复 `X-Request-Id`。标题、空间、密级不能在此入口修改。

先取得本人原清单和上传资格，再匹配实际文件。文件名、真实字节数或摘要不同返回 409，不领取执行；内容匹配后的格式拒绝使本次尝试持久失效。已受理项的匹配重放返回原三元组，即使 revision 已旧，也不会再写原件。

| 核对到的项状态 | 用户动作 |
| --- | --- |
| `PENDING` 且 `canUpload=true` | 选择匹配文件，明确开始上传 |
| `PROCESSING` | 有界核对，等待受理或到期失效，不发送另一次文件 |
| `ACCEPTED` | 保留固定回执，不重传；按固定版本独立查看处理状态 |
| `FAILED` 且 `canUpload=true` | 读取最新 revision，选择原匹配文件，以新请求标识主动继续 |
| `FAILED` 且失败码为内容错误或超限 | 修正文件后建立新清单，原项不可换内容 |
| `canUpload=false` 或资格核对失败 | 暂停发送，恢复资格后重新读取，不用旧快照继续 |
| 未取得可信响应 | 标记待确认，仅核对，不自动重试 |

存储失败可以留下孤儿原件；最终事务返回异常也可能已经提交。批量路径不在这些异常上直接删除对象。详见 [导入运维说明](document-import-operations.md)。
