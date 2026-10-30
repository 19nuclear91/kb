# Tasks

## 1. 删除能力投影与接口兼容

- [ ] 1.1 在 `DocumentCapabilities` 和 `DocumentCapabilitiesService` 增加 `canDelete`，复用同次 edit 判定并保留 view 前置门禁；更新 DTO 构造点及 `DocumentCapabilitiesServiceTest`，验证权限组合、例外 edit、仅 edit 无 view、未发布文档、撤权及依赖故障均符合增量规格。
- [ ] 1.2 扩展 `kb-web/src/types.ts` 的能力类型和相关夹具，明确仅 `canDelete === true` 可用；增加实际 JSON 字段/前端消费的行为测试，验证字段缺失、false、错误类型及能力查询失败均不启用删除，原有下载/上传/重试字段保持兼容。
- [ ] 1.3 更新 `docs/user-resource-capabilities-api.md` 的响应字段和资格解释；人工核对其与真实 DTO/序列化结果一致，明确 view 为能力查询门禁、edit 为删除资格，不添加文案断言测试。

## 2. 详情页操作协调与确认入口

- [ ] 2.1 建立只挂载于文档详情子树的可选操作协调上下文，以同步门禁维护在途写入、未确认标记和删除独占状态；通过行为测试验证同事件周期互斥、普通写入在途时禁止删除、取消未提交确认后恢复交互，以及无上下文时保持原行为。
- [ ] 2.2 将详情中的上传、解析重试、审核命令、评论提交/删除及收藏写入接入协调上下文，覆盖其显式重试入口；补充使用真实子组件的交互测试，验证在途操作不会因弹窗或子树卸载被当作取消成功，已知未确认写入会提示并要求重新确认，其他页面原有请求绑定与重试语义不变。
- [ ] 2.3 在 `pages/Documents.tsx` 接入“删除文档”按钮和确认组件，打开时重新取得详情及 canDelete，展示整文档影响并与所选版本解耦；组件测试覆盖取消不发请求、重读期间禁用、确认前撤权、无当前发布版本、历史版本选择、隐藏版本不枚举、标题安全展示及大整数 ID。
- [ ] 2.4 更新 `kb-web/README.md` 的文档操作说明，移除“没有文档删除入口”的旧描述并说明详情页入口、edit 资格、全部版本范围和无法恢复；人工对照页面验证，不将说明文案加入单元测试。

## 3. 删除请求、核对与页面生命周期

- [ ] 3.1 实现独立删除状态及 Session 请求适配，调用无请求体的既有 DELETE，不携带 versionId 或虚构幂等参数；补充行为测试验证单次提交、合法成功响应、正文读取中断/畸形响应、网络/超时/408/5xx 未确认，以及 400/401/403/404/409 明确拒绝；有界等待到期不得表示服务端回滚。
- [ ] 3.2 实现用户主动只读核对和重新确认，按新代次读取详情与能力；测试覆盖 404 仅表示不可访问、200 且 canDelete=true 不表示原请求失败、canDelete=false 阻止重删、核对故障继续未确认，以及任何分支均不自动重发 DELETE。
- [ ] 3.3 连接提交阶段的预览关闭、轮询暂停和旧读取失效，以及确认成功后的清理、replace 返回 `/documents`、当前会话一次性提示和列表重读；通过页面测试验证 Blob 释放、旧内容不恢复、列表加载失败不推翻删除成功，反馈不写浏览器持久存储或 URL。
- [ ] 3.4 将删除控制器与既有 Session epoch、文档身份及可见性生命周期对齐；用可控迟到响应测试覆盖换用户、退出、强制改密、切换文档、隐藏/恢复、离开页面，验证旧成功不导航、旧读取不恢复能力、本地 abort 不伪报服务端取消且恢复不重发。
- [ ] 3.5 更新 `docs/user-web-operations.md` 的删除结果核对、发布顺序和回退说明；人工核对区分明确成功与当前不可访问，说明前端回退不会恢复已删除文档，并记录实际执行的验证范围及外部环境未验证项。

## 4. 跨组件回归与交付检查

- [ ] 4.1 在仓库根目录执行 `mvn -pl kb-app -am test '-Dtest=DocumentCapabilitiesServiceTest,DocumentManagementServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false'`，连同本变更新增的接口/序列化测试按实际类名执行；确认测试编译和相关行为测试通过，保留原删除流程的任务、审核和存储清理回归，不用忽略失败标志掩盖失败。
- [ ] 4.2 在 `kb-web` 执行本变更新增删除/协调测试，以及 `npm test -- --run src/documents.test.tsx src/reviews/documents.test.tsx src/reviews/command-lifecycle.test.tsx src/comments/panel.test.tsx src/comments/lifecycle.test.tsx src/favorites/documents.test.tsx src/favorites/lifecycle.test.tsx src/lifecycle.test.tsx src/session.test.ts`；再执行 `npm run typecheck` 与 `npm run build`，确认可选协调机制没有改变其他页面行为，并记录结果。
- [ ] 4.3 执行 `openspec validate user-web-document-deletion --strict` 和 `git diff --check`，逐项核对两份增量规格与实际行为；确认未引入数据库迁移、新权限位、管理后台/批量删除或回收站，明确未执行的真实外部服务验证，不以模拟测试代替上线验收。
