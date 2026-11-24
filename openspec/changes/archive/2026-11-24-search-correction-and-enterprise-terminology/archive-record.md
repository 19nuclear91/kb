# 搜索纠错与企业术语词典归档记录

日期：2026-11-24。变更：`search-correction-and-enterprise-terminology`。Schema：`spec-driven`。范围：`D:/WorkProject/kb`，repo-local。

## 归档结果

- 经用户明确要求，先同步主规格，再归档并更新对应文档；归档路径为本目录 `openspec/changes/archive/2026-11-24-search-correction-and-enterprise-terminology/`。
- proposal、specs、design、tasks 均为 done，实施任务 **51/51** 完成；没有未完成产物或任务警告。
- 已同步全部 10 份增量规格：新增 3 份主规格，更新 7 份现有主规格，共 **51 ADDED／6 MODIFIED／0 REMOVED／0 RENAMED**。现有标题、Purpose 及未涉及内容保留；新能力 Purpose 沿用原增量，不保留增量操作标题。
- 同步后逐份人工对照需求与场景，确认没有剩余待应用内容。`openspec validate --specs --strict`：**76/76 通过，0 失败**；归档前变更严格校验及 `git -c core.safecrlf=false diff --check` 通过。
- 原变更目录整体移入本目录，保留 `.openspec.yaml`、10 份增量规格、设计、任务、实施证据、场景映射及完整核验报告；归档后 `openspec list --json` 返回空活动变更列表。

## 主规格同步清单

| 能力 | 同步结果 |
| --- | --- |
| admin-web-access | 新增 2 项需求：独立读取入口、登录返回与恢复 |
| admin-web-terminology | 新建主规格，新增 7 项需求：表单、目录、回执、资格和生命周期 |
| audit-query | 新增 3 项需求：词典维护事件、安全摘要、同事务及无搜索事件 |
| enterprise-terminology | 新建主规格，新增 10 项需求：人工关系、公开边界、启停、修订和维护 |
| functional-authorization | 新增 3 项需求：精确读写资格、公开辅助边界、初始化兼容 |
| search-filtering | 新增 4 项需求：增强输入、选择解码、双版本响应和完整条件 |
| search-query-assistance | 新建主规格，新增 11 项需求：确定匹配、扩展、明确选择、原词和同源说明 |
| search-query-confidentiality | 修改 3 项、新增 1 项需求：增强正文、保密、no-store、无学习历史 |
| search-resilience | 修改 2 项、新增 5 项需求：共享预算、固定失败、准入、规模及观测 |
| user-web-documents | 修改 1 项、新增 5 项需求：搜索页面对象契约、建议、原词恢复及生命周期 |

主规格目录：`openspec/specs/`；本目录 `specs/` 保留归档增量，不作为需要再次同步的活动变更。

## 带已知警告归档

最新第二轮完整核验为 **0 CRITICAL / 1 WARNING / 0 SUGGESTION**，原 W1～W3 已关闭。用户在获知 W4 后要求归档，本次只执行同步、归档和文档更新，不修复 W4、不缩减规格，也不把任务完成或测试全绿记为无问题通过。

**W4：** 前端 `kb-web/src/search/assistedApi.ts:119-121` 将已明确替换掉的旧 matchedText 仍计为已存在表达；另一处已采纳组合法补充该表达时，完整服务成功被错误拒绝。固定合成例：组 1 标准名 PM／别名项目，组 2 标准名产品／别名 PM；查询 `PM 项目`，第一处选择产品后，实际计划 `产品 项目\nPM` 被前端拒绝。详情、独立纯计划与解码证据及回归建议见 [verify-report.md](verify-report.md)。归档不表示该警告已解决，后续修复须另行授权。

## 证据与未验证范围

第二轮实际复跑后端 **59 类／325 项**、前端 **30 文件／419 项**，0 失败／错误／跳过；全模块测试编译、前端类型检查、生产构建、包体工具 **13 项**及隔离通过。W4 的额外诊断探针只复现当前错误，不计入规格通过数；重叠运行不累计。详见 [实施记录](verification.md)、[完整核验](verify-report.md)及 [57 项需求／168 个场景映射](traceability.md)。归档阶段未再执行业务测试，没有修改生产实现或正式测试。

迁移及文档仅人工审阅，未建立内容断言、快照或存在性测试。未部署或执行 V42，真实数据库约束、锁／回滚、快照、执行计划、网络取消、供应商调用、代理／遥测、多实例、真实浏览器及排序质量仍 **Not verified**；不恢复为隐藏待办或完成／归档条件。

根 README、前端 README、F-05 路线图、搜索／管理／审计与运维说明已改为归档链接及实际状态，明确保留 W4。提交与推送按本次用户授权使用一条中文 commit message 执行，实际 Git 结果以提交历史及远端为准；本记录不宣称发布或真实环境验收完成。
