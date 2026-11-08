# AI 用量与调用审计页面

`/admin/ai/usage` 使用 `ai:usage:read`，提供独立服务端总览和 UTC 日、部门 ID、用户 ID 分组。`/admin/ai/audits` 使用 `ai:audit:read`，提供事件列表和阶段快照。旧 `/admin/audits` 继续处理管理与登录审计。两项新资格互不蕴含，导航和页面均按精确 code 判断，不要求用户、部门或空间目录可用。

页面共用用户 ID、部门 ID、SINGLE/MULTI、生成状态、真实用量状态、结算状态筛选。审计页另有调用 UUID 和事件类型。编辑的是草稿，只有点击“查询”才生效；刷新使用已提交条件，继续读取使用原窗口和游标，重置恢复最近7天。时间以设备本地时区输入，提交后转换为带时区时刻，最长31天，左闭右开。分组方式和条数也在显式提交后生效。

总览不通过相加已加载分组计算。各页分组保留自己的 asOf，活跃调用可能在读取期间改变；UTC 日期按 UTC 零点划分，与本地自然日可能不同。部门和用户显示调用当时的稳定 ID，不补查名称。所有 token、额度、计数、ID 均按原十进制字符串展示，不转换成 JavaScript Number。

KNOWN 表示可靠累计输入/输出统计，两个零也是合法已知样本。UNKNOWN 的实际 token 为 null，列表显示“未知”，详情显示“未知（null）”；NOT_INVOKED 表示没有模型调度意图，实际 token 为零。总览没有已知样本时显示“无已知样本”。计划扣除额度、确认结算额度和状态分别展示，单轮与多轮仍沿用原策略，不表示货币费用。

每次读取展示窗口、asOf、当前保留天数、首次覆盖与最多100个采集区间。未启用采集、采集缺口、早于覆盖起点及保留期外窗口分别由提示解释；只剩 REVIEW 例外记录不能证明普通历史完整。依赖故障提供明确重试，不用静态零值替代失败响应。

审计按事件身份去重，同一个 callId 可以出现多个阶段。事件类型为 REGISTERED、ACQUIRE_INTENDED、RESERVED、DISPATCHED、FINALIZED、SETTLED、REVIEW、QUOTA_REJECTED。状态筛选针对事件提交时快照，详情不会自动变成当前调用状态。身份、调用、内部序号、发生时间或类型不匹配的详情视为依赖故障；404 提示不存在或已清理，保持现有列表。快照不可用时只显示安全事件元数据。

前端对安全 DTO 的未知枚举保留有界原始文本，兼容服务端新增的安全代码；筛选输入仍只接受当前列出的约定值。所有文本均通过 Vue 插值渲染，禁止 `v-html`，即使包含 HTML 标记也不会执行。

页面退出、切换身份、强制改密、权限撤销、后台和离页时取消请求并清空资料及草稿。后台恢复后由现有会话机制重新核对身份、精确资格，再读取默认窗口。403 先清理，同一会话代次与权限上下文最多自动核对一次，不通过重新挂载循环请求；显式恢复重新使用默认条件。列表、汇总、详情均检查请求代次和 AbortSignal，旧响应不能恢复资料。

资料只保存在页面内存，不写 localStorage/sessionStorage、URL、控制台或遥测。页面地址始终是固定路由，安全登录返回地址只接受精确路径。详情使用 Vue 文本插值显示，不执行 HTML，不补查正文和名称，也不提供导出、删除或改账操作。

托管时将这两条固定前端路由回退到 SPA 入口，保持 `/api/admin/ai/**` 代理到现有网关管理 API 路由。网关和 app 在鉴权前设置 no-store，反向代理不得缓存这些 API 的成功或拒绝响应；静态资源仍采用现有发布策略。不得对查询值、调用 ID、事件 ID 或响应体启用访问分析和客户端回放采集。内部 `/internal/ai-observability/**` 不配置公网路由。

验证只使用模拟 DOM、内存请求、受控 Promise 和虚拟时间，不启动浏览器、HTTP 服务、数据库或 Redis。类型检查与静态构建单独执行。历史执行记录见 [离线验证记录](ai-usage-validation.md)，接口口径见 [协议说明](ai-usage-api.md)。当前 Vue 版本在 `kb-web` 目录执行：

```powershell
npm test -- --run src/admin/aiUsageApi.test.ts src/admin/aiUsageQuery.test.ts src/admin/aiUsagePages.test.ts src/admin/aiUsageAccess.test.ts src/admin/aiUsageRoutes.test.ts
npm run typecheck
npm run build
```
