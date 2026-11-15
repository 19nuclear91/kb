# 主规格同步与归档记录

日期：2026-11-15；变更：`knowledge-validity-and-review`；Schema：`spec-driven`。

## 归档结果

- 用户明确要求先同步主规格再归档，并在九份规格比对摘要后确认同步。
- CLI 确认规划产物全部 `done`、任务 **37/37** 完成。原 34 项与新增 8.1–8.3 三项整改记录均保留。
- 先完成九份主规格同步及逐项比对，再移动变更至 `openspec/changes/archive/2026-11-15-knowledge-validity-and-review/`。原 17 个文件（含 `.openspec.yaml`、全部 delta、两轮核验及实施记录）完整保留；仅修正归档后的相对入口并补充本记录。
- 归档后 `openspec list --json` 返回无活动变更。

## 同步明细

所有操作均为 ADDED，合计 **42 项要求、110 个场景**，没有修改、删除或重命名要求。

| 主规格 | 操作 | 新增要求 | 场景 |
|---|---|---:|---:|
| [admin-web-documents](../../../specs/admin-web-documents/spec.md) | 追加 | 3 | 7 |
| [admin-web-spaces](../../../specs/admin-web-spaces/spec.md) | 追加 | 1 | 3 |
| [audit-query](../../../specs/audit-query/spec.md) | 追加 | 2 | 4 |
| [document-applicability](../../../specs/document-applicability/spec.md) | 新建 | 11 | 34 |
| [document-stewardship](../../../specs/document-stewardship/spec.md) | 新建 | 14 | 36 |
| [user-web-conversations](../../../specs/user-web-conversations/spec.md) | 追加 | 2 | 5 |
| [user-web-document-review](../../../specs/user-web-document-review/spec.md) | 追加 | 1 | 3 |
| [user-web-document-stewardship](../../../specs/user-web-document-stewardship/spec.md) | 新建 | 4 | 8 |
| [user-web-documents](../../../specs/user-web-documents/spec.md) | 追加 | 4 | 10 |

既有六份规格的标题、Purpose 及未涉及内容保持不变；三份新规格使用标准标题，并原样保留 delta 的 Purpose。所有要求均置于唯一 `## Requirements` 下，没有残留 delta 操作标题。全九份正文与增量一致。

同步前取得一次 `openspec instructions specs --change 'knowledge-validity-and-review' --json`，响应有效且未配置额外 `rules`。内容比对首次因 PowerShell 原生输出编码误报；统一 UTF-8 后全九份比对通过，没有改写规格规避检查。

同步后 `openspec validate --specs --strict`：**66/66 通过**；归档前变更严格校验通过。README、路线图、前端及相关功能/管理文档已更新归档状态、最新数量和入口。

## 离线证据与保留边界

最新执行为[第二轮独立核验](verify-report-2.md)：后端白名单 **25 类/218 项**、前端全量 **122 文件/1343 项**通过；八模块测试编译、类型检查、生产构建、包体工具 **11 项**及隔离检查通过。已执行核验中 **0 CRITICAL / 0 WARNING / 0 SUGGESTION**，原 W1/W2/S1 关闭。不累计首次实施与整改定向数量；历史见[实施记录](verification.md)、[首次核验](verify-report.md)及[110 场景映射](traceability.md)。

Requirement Implementation Mapping 与 Scenario Coverage 仍部分 **Not verified**。本次同步、归档和文档更新阶段没有重跑业务测试，也没有执行 V39、数据库/Redis/容器、真实浏览器、联调或部署；没有为说明文档或迁移 SQL 文本增加断言测试。归档是工作流收尾，不把离线证据改写为真实约束、触发器、锁、回滚、SQL 等价性、执行计划、完整动态链路或上线验收已通过。

发布与回退由部署流程另行执行，见[知识适用期与复审](../../../../docs/knowledge-validity-review.md#上线与回退)。
