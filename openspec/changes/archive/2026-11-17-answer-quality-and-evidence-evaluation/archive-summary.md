# 归档记录：回答质量与引用证据评测

2026-11-17 经用户明确授权，同步主规格后归档 `answer-quality-and-evidence-evaluation`，schema 为 `spec-driven`。规划产物全部完成，实施任务 **48/48** 完成；修复后再次核验无未解决的 CRITICAL、WARNING 或 SUGGESTION。

## 主规格同步

全部为新增要求，没有移除或重命名。新建 3 份、扩展 2 份主规格，共新增 **36 项要求、86 个场景**；已有规格标题、Purpose 及原有要求保持不变，新规格保留增量中的 Purpose。

| 主规格 | 操作 | 新增要求 | 新增场景 |
| --- | --- | ---: | ---: |
| [answer-evaluation-collection](../../../specs/answer-evaluation-collection/spec.md) | 新建 | 12 | 28 |
| [answer-evaluation-review](../../../specs/answer-evaluation-review/spec.md) | 新建 | 9 | 22 |
| [answer-quality-evaluation](../../../specs/answer-quality-evaluation/spec.md) | 新建 | 10 | 23 |
| [chat-model-adapter](../../../specs/chat-model-adapter/spec.md) | 扩展 | 3 | 7 |
| [multi-turn-chat](../../../specs/multi-turn-chat/spec.md) | 扩展 | 2 | 6 |

同步后逐项比对增量要求与主规格，确认没有剩余内容待同步，也没有增量操作标题残留。`openspec validate --specs --strict --json`：**72 份主规格全部通过，0 问题**。随后将完整变更目录移入当前归档路径，保留 `.openspec.yaml`；`openspec list --json` 确认活动变更为空。

## 文档与验证

更新了根 README、[开发路线图](../../../../docs/development-roadmap.md)、[操作手册](../../../../docs/answer-evaluation.md)及[验证记录](../../../../docs/answer-evaluation-verification.md)的归档状态与证据入口，修正了提案归档后的相对链接。数据、采集、裁判、复核与报告说明随实现一并交付。

实施及修复阶段的 184 项离线测试通过：独立工具 66 项、kb-ai 白名单 118 项。全模块 `test-compile`、工具打包及 JAR 静态检查通过；修复后再次核验复跑的 45 项测试通过，不重复计入上述总数。具体范围、命令和场景映射见[验证记录](../../../../docs/answer-evaluation-verification.md)。归档阶段同步规格、更新文档并固定评测夹具换行，没有修改业务代码或重复运行完整测试。

提交前补充 `examples/.gitattributes`，避免 Windows 自动换行转换破坏数据集摘要。以 `core.autocrlf=true` 重新检出暂存示例，18 个文件字节一致，两个数据清单的 6 项文件摘要全部匹配；检查只涉及本机 Git 与文件字节。

功能**未部署**。没有启动前端、完整业务应用、独立 CLI JAR 或 HTTP/SSE 测试服务器，也没有连接数据库、Redis、对象存储、解析服务、被测模型或裁判。没有执行说明文档的指定内容测试或命令回放。真实联调、供应商协议、模型效果、费用、人工业务审定与业务质量阈值仍未验证；归档不代表真实环境验收通过。
