# 知识订阅与周报归档记录

日期：2026-11-27。变更：`knowledge-subscriptions-and-digests`。Schema：`spec-driven`。项目：`D:/WorkProject/kb`，repo-local。

## 归档结果

- 按用户授权先同步8份主规格，再将完整变更目录移至 `openspec/changes/archive/2026-11-27-knowledge-subscriptions-and-digests/`，保留 `.openspec.yaml`、规划、增量规格、任务及两轮核验结论。
- proposal、specs、design、tasks全部完成，任务47/47；最终verify为CRITICAL 0、WARNING 0、SUGGESTION 0，首轮2项WARNING已修复。
- 共38项ADDED、7项MODIFIED，无删除或重命名；增量覆盖45项需求、113个场景。新增规格沿用增量Purpose，既有标题、Purpose和未涉及的需求、场景保留。
- 同步后8份增量的45项需求均与主规格对应，无剩余待应用内容和增量操作标题。`openspec validate --specs --strict`：83/83通过，0失败。
- 归档后`openspec list --json`返回空活动变更列表，工作区差异空白检查通过。

## 主规格同步清单

| 主规格 | 同步结果 |
| --- | --- |
| [knowledge-subscriptions](../../../specs/knowledge-subscriptions/spec.md) | 新建，10项需求：本人订阅、候选授权、原代次、生效顺序、重叠匹配、取消、频率及命令恢复 |
| [knowledge-update-digests](../../../specs/knowledge-update-digests/spec.md) | 新建，13项需求：正式发布、独立派生、固定业务周、完整性、动态授权、人工说明及固定版本对比 |
| [user-web-subscriptions](../../../specs/user-web-subscriptions/spec.md) | 新建，8项需求：受保护路由、候选、频率、原请求恢复、固定发布及身份清理 |
| [document-review](../../../specs/document-review/spec.md) | 新增2项需求：发布上下文原子提交及协议覆盖起点 |
| [document-parsing](../../../specs/document-parsing/spec.md) | 新增2项需求：Worker统一发布协议及启动依赖检查 |
| [document-change-notes](../../../specs/document-change-notes/spec.md) | 修改1项需求：本人更新及周报动态读取固定版本人工声明 |
| [user-notifications](../../../specs/user-notifications/spec.md) | 新增2项、修改4项：第三来源、原本人目标、当前可见性、三档契约及周报计数 |
| [user-web-notifications](../../../specs/user-web-notifications/spec.md) | 新增1项、修改2项：订阅最小目标、固定跳转及新契约 |

## 验证与交付边界

完整记录见[实施验证](verification.md)和[Verify报告](verify-report.md)。后端白名单167项通过；verify修复后的前端23文件/265项通过，订阅专项21项包含在其中，不重复累加。全模块测试编译、前端类型检查、生产构建、13项包体工具测试和最终产物隔离均通过。

本次归档阶段只同步规格、移动目录、清理生成产物和更新文档。V45未执行，真实数据库约束、锁、回滚、权限SQL及执行计划、外部组件、真实浏览器、前后端联调和部署仍未验证，保持原约定的离线范围。

## 清理与文档

已清理9个Maven模块的`target/`、`kb-web/dist/`、本次`knowledge-subscriptions-report.json`及对应`manifest.json`/`modules.json`，以及Vitest/Vite临时缓存。包体数据、测试命令和结果已保存在验证记录，可重新生成；其他变更的报告、探针、旧构建及开发依赖未纳入本次清理。

README、路线图F-10、前端README、用户/运维/发布说明及通知文档已更新为实际归档状态，并修正旧活动变更路径、当前通知三档契约和第三来源说明。实施与verify记录保留当时执行范围，通过本记录说明后续归档动作。

提交与推送按用户授权使用一条中文commit message，提交身份及远端结果以Git历史为准。
