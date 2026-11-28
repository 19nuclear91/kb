# 知识健康雷达 API

`GET /api/admin/spaces/{spaceId}/health-radar` 读取一个未归档空间的活动文档维护诊断。要求当前有效身份、完成必需改密、精确的 `knowledge:health:read` 和目标空间 manage 或 `space:manage-all`。

## 查询

| 参数 | 约定 |
|---|---|
| spaceId | 路径中的正整数，最大 9223372036854775807 |
| signal | 默认 ALL，或下表任一信号代码 |
| limit | 默认 20，范围 1–100 |
| cursor | 服务端返回的下一页游标，最长 2048 字符 |

未知、重复、空值或非法参数拒绝为 400。游标绑定用户、空间、信号、页大小、协议版本和业务日期；不支持跨空间批量、客户端日期、任意排序或导出。

| 信号 | counts 字段 | 含义 |
|---|---|---|
| STEWARD_UNASSIGNED | stewardUnassigned | 未指定责任人 |
| STEWARD_NEEDS_REASSIGN | stewardNeedsReassign | 当前责任人不合格，需要重新指定 |
| REVIEW_OVERDUE | reviewOverdue | 当前合格责任人的复审到期日已过 |
| REVIEW_DUE_SOON | reviewDueSoon | 复审在未来 30 天内到期，含当天 |
| APPLICABILITY_EXPIRED | applicabilityExpired | 当前可读发布版本截止日已过 |
| APPLICABILITY_EXPIRING_SOON | applicabilityExpiringSoon | 当前可读发布版本截止日在 30 天内，含当天 |

前四类沿用既有状态优先级，最多命中一个；后两类最多命中一个。未指定或需改派不能再凭旧日期重复计为复审逾期。未来生效、未声明、长期有效沿用既有适用期规则，不生成额外问题。

## 响应

成功使用 `R<KnowledgeHealthRadarVo>`，即 `{code:200,message:"ok",data:{...}}`。data 固定包含：

- `definitionVersion`：KNOWLEDGE_HEALTH_V1。
- `spaceId`、`asOf`、`asOfDate`、`dayTimeZone`：目标空间、数据库统计时间、对应业务日期及部署固定地区时区。
- `signal`、`limit`：本次清单条件。
- `counts`：上表六个固定字段；`affectedDocumentCount` 为命中任一可见信号的文档并集。
- `items`、`hasMore`、`nextCursor`：当前页、是否还有下一页、下一页游标或 null。

所有身份 ID 和计数都是精确十进制字符串。汇总始终覆盖本次完整诊断范围，不受清单筛选和游标影响；同文档双信号各计一次，但并集只计一份文档。清单按文档 ID 升序，先按当前授权及信号过滤再分页。

每项形状为：

```json
{
  "documentId": "123",
  "signals": ["REVIEW_OVERDUE", "APPLICABILITY_EXPIRED"],
  "stewardship": {
    "state": "OVERDUE",
    "responsible": {"userId": "7", "displayName": "资料负责人"},
    "dueDate": "2026-11-01"
  },
  "applicability": {
    "versionId": "456",
    "state": "EXPIRED",
    "effectiveFrom": null,
    "effectiveUntil": "2026-11-26"
  }
}
```

责任人未指定时 responsible 为 null；dueDate 可空或保留取消前的日期。只有命中可见适用期信号时才返回 applicability；其他情况统一为 null，不区分不可读、无当前版本或没有日期信号。清单不含标题、正文、不合格原因、账号详情或隐藏数量。

## 时间与错误

asOf 是一次数据库时间，状态以同次业务日期计算，默认 Asia/Shanghai；到期当天属于即将到期，次日才逾期。统计和当前页共享只读 REPEATABLE_READ 快照及 6 秒完整读取预算。下一页是新的当前快照，不冻结旧权限和发布版本。

| HTTP | 含义 |
|---|---|
| 400 | 路径、查询或游标不合法 |
| 401 | 登录或认证代次无效 |
| 403 | 身份不可用、改密或功能资格未满足 |
| 404 | 空间不存在或不可管理 |
| 409 | `X-Kb-Health-Error: SPACE_ARCHIVED`：空间归档；`RADAR_DATE_CHANGED`：从首页重新查询 |
| 503 | 依赖、必要授权事实、完整结果或预算不可确认 |

错误使用既有失败包装，详细冲突只通过固定 `X-Kb-Health-Error` 原因头表达。所有成功、拒绝及网关提前响应均 no-store。失败不提供部分汇总、历史缓存或虚假零，也不回显底层异常。

协议内目标 404 带 `X-Kb-Health-Error: NOT_FOUND`，页面据此重新核对资格。旧端点缺失的无该原因头 404、405 在页面显示服务不可用，不自动重试或回退旧接口。页面核对统计时间的原始日历日期和时分秒，拒绝浏览器会自动归一化的非法时间。

迁移、真实 SQL、事务隔离、执行计划、性能与浏览器联调均未验证；本期仅安排独立离线组件验证。发布与回退见[运维说明](knowledge-health-radar-operations.md)。
