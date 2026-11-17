# 归档记录：版本差异与变更说明

2026-11-17 经用户明确授权，同步主规格后归档 `version-diff-and-change-notes`，schema 为 `spec-driven`。规划产物全部完成，实施任务 **36/36** 完成；[核验报告](verify-report.md)无未解决的 CRITICAL、WARNING 或 SUGGESTION。

## 主规格同步

全部为新增要求，没有移除或重命名。新建 3 份、扩展 4 份主规格，共新增 **36 项要求、99 个场景**；已有规格标题、Purpose 及原有要求保持不变，新规格保留增量中的 Purpose。

| 主规格 | 操作 | 新增要求 | 新增场景 |
| --- | --- | ---: | ---: |
| [audit-query](../../../specs/audit-query/spec.md) | 扩展 | 2 | 4 |
| [document-change-notes](../../../specs/document-change-notes/spec.md) | 新建 | 10 | 31 |
| [document-diff](../../../specs/document-diff/spec.md) | 新建 | 10 | 28 |
| [document-parsing](../../../specs/document-parsing/spec.md) | 扩展 | 1 | 5 |
| [user-web-document-diff](../../../specs/user-web-document-diff/spec.md) | 新建 | 6 | 16 |
| [user-web-document-review](../../../specs/user-web-document-review/spec.md) | 扩展 | 2 | 5 |
| [user-web-documents](../../../specs/user-web-documents/spec.md) | 扩展 | 5 | 10 |

同步后逐项比对增量要求与主规格，确认没有剩余内容待同步，也没有增量操作标题残留。`openspec validate --specs --json`：**69 份主规格全部通过，0 问题**。随后将整个变更目录移入当前归档路径，保留 `.openspec.yaml`；`openspec list --json` 确认活动变更为空。

## 文档与验证

更新了根 README、前端 README、[开发路线图](../../../../docs/development-roadmap.md)和[功能说明](../../../../docs/version-diff-and-change-notes.md)的归档状态、正式规格及证据入口，修正了归档后的相对链接。解析与审计接入文档随实现一并交付。

实施与修复阶段的后端 25 类/94 项离线测试、前端 127 文件/1420 项测试、类型检查、生产构建和包体隔离均通过。再次核验的详情页 26 项测试也通过，其中包含 4 项临时生命周期验证；临时用例已删除，不计入正式 1420 项。具体命令和边界见[验证记录](verification.md)、[场景映射](traceability.md)及[核验报告](verify-report.md)。归档阶段只同步规格和更新文档，没有改动业务代码或重复全量测试。

功能**未部署**。没有执行 V40 或真实数据库、解析服务、浏览器、前后端联调及部署验收。发布顺序仍为全部 app（含 V40）、Worker、web；回退反向进行并保留新表。归档不代表真实环境验收通过。
