# 权限解释与授权预演归档记录

日期：2026-11-25。变更：`permission-explanation-and-authorization-preview`。Schema：`spec-driven`。

已按用户指示完成主规格同步、归档、临时产物清理及对应文档更新。归档位置：`openspec/changes/archive/2026-11-25-permission-explanation-and-authorization-preview/`，保留原 `.openspec.yaml` 和各轮验证记录。

## 归档依据

- 四类规划产物均为 done，42/42 项任务完成，归档前 apply 状态为 all_done。
- [第三轮核验](verification-report-3.md)为 **0 CRITICAL、0 WARNING、0 SUGGESTION**；原 W1–W4 及预演弹窗恢复 R1 均已关闭。
- 前端最终白名单 9 文件／145 项通过；类型检查、生产静态构建及包体隔离通过。
- 后端第二轮白名单 25 类／129 项通过，包含架构 13 项；沿用对应阶段证据，本次归档没有改动实现或重复执行这些测试。

## 主规格同步

| 主规格 | 操作 | 新增需求／场景 |
| --- | --- | --- |
| `authorization-diagnostics` | 创建主规格，沿用增量规格 Purpose | 14／49 |
| `functional-authorization` | 追加独立诊断资格、真实范围和初始化要求 | 3／12 |
| `admin-web-spaces` | 追加空间诊断、完整草稿预演及私有状态要求 | 6／18 |
| `admin-web-documents` | 追加文档诊断、单项预演及写入协调要求 | 5／16 |
| 合计 | 4 份主规格 | 28／95 |

人工核对四份主规格与增量规格，全部 ADDED 需求及场景已合入，无待应用差异。三份既有规格的标题、Purpose 和原有需求保持不变；新规格使用正式标题及单一 Requirements 节，无增量操作标题或待填写 Purpose。

同步后执行 `openspec validate --specs --strict`：**77 项通过，0 失败**。主规格校验只证明格式有效，不增加运行时验证声明。完成同步核对及目标目录冲突检查后，将完整变更目录移入归档。

## 清理与文档

- 清理本次生成的 `kb-web/dist/`、`.bundle-analysis/authorization-diagnostics.json`、`.bundle-analysis/manifest.json` 和 `.bundle-analysis/modules.json`。临时复现用例已在前轮移除；长期证据保留在各轮报告和场景映射中。
- 更新根 README、前端 README、开发路线图、管理页面及诊断使用说明，修正归档链接、最终测试计数和预演失败恢复说明。
- 当前任务／提案更新为已归档；历史 verify 报告保留当时结论并增加归档补记，不抹除首次失败证据。
- 按用户追加指示，`AGENTS.md` 与 `.agents/skills/openspec/` 修改同本次功能、规格和文档一起提交；使用一条中文提交消息推送至 `origin/main`。

## 验证边界

归档阶段仅执行规格同步、人工审阅、OpenSpec 校验和 Git 操作。没有运行数据库迁移或启动应用、HTTP 服务、真实浏览器、数据库、Redis、对象存储，也未执行文档内容／SQL 文本断言或前后端联调。

V43 仍为已交付、未执行；真实 SQL 等价、约束／锁／隔离、网络与跨实例、浏览器及联调仍未验证。归档及推送均不代表部署或真实环境验收。
