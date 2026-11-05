# 修复与验证记录

## 实现范围

本次实现 `fix-conversation-routing-and-document-recovery`，对应待办第 2、8、9 项：

- 开发网关补齐 `/api/conversations/**`，实际绑定 dev/prod 配置验证业务与 AI 流式路由、内部接口隔离、SSE metadata、CORS 和 no-store。
- 下载拒绝后重读文档、独立能力和版本资格；固定本次下载版本，保留仍可读的历史版本，不自动切换新版或重发文件请求。
- 详情增加主动资格重核。新版本上传 403/404 后使旧资格失效、暂停提交，核对成功后仅允许手动再次提交；读取故障和明确不可访问分别处理。
- 文件请求按尝试创建取消控制器，旧请求、文档/版本/身份切换、隐藏页面和 StrictMode 重建不会恢复过期资格。
- 保持后端权限、会话开关、上传网络结果不确定语义、文档删除协调以及普通新文档上传协议不变。

新增 `kb-web/src/documents/recovery.test.tsx`，现有 27 个用例，其中追加 2 个同文档旧能力 true 晚于上传拒绝的受控交错用例，分别覆盖新核对仍加载和已返回 false。原上传单测补入明确资格参数；收藏联动单测补齐真实版本身份与预览信息契约，并等待异步预览。

针对用户要求修复的全量回归超时，仅在 `kb-web/src/admin/AclPanel.tsx` 将单条撤销按钮提取为 memo 组件，避免批量选择时反复渲染全部 Ant Design 按钮。未改变选择、权限或提交语义；`space-pages.test.tsx` 本身未改动，保留 301 次实际点击、全部原断言、数据和 30000ms 超时限制。

## 最终验证结果

| 检查 | 结果 | 边界 |
| --- | --- | --- |
| 网关四个配置测试类 | 7 项通过，0 失败、0 错误、0 跳过 | 加载配置并执行实际谓词，无监听服务 |
| 恢复测试文件 | 27 项通过 | 含同文档新旧能力交错、403/404、生命周期和删除协调 |
| `npm run typecheck` | 通过 | TypeScript 静态检查 |
| `npm run build` | 通过 | 有压缩后 JS chunk 大于 1200 kB 的体积告警，未调整阈值 |
| 完整 `npm test -- --run` 最后一次运行 | 84 个文件、1038 项全部通过，0 失败、0 跳过 | 包括空间页面 90 项、删除/审核/评论/文件生命周期等回归 |
| 原超时用例单进程独立复跑 | 优化后通过，约 6.3 秒；全量运行约 6.8 秒 | 保留原 30000ms 限制；独立筛选时其他 89 项未运行，不计通过；最终全量未筛选 |
| OpenSpec strict | 通过，issues 为空 | 仅校验本变更规格，不代替功能测试 |
| `git diff --check` | 通过 | Git 另有工作区 LF/CRLF 归一化提示，未修改仓库换行配置 |

## 原完整回归阻塞及解除

此前 `kb-web/src/admin/space-pages.test.tsx` 的“空间批量撤销 / 301 条禁用提交且不拆批，减少到 300 条后可确认”在完整回归及单独复跑中均报：

```text
Error: Test timed out in 30000ms.
```

此前全量结果为 1035 通过、1 超时，任务 4.1 当时保持未勾选，未将失败认定为既有缺陷。继续排查时，未优化版本单独复跑曾通过但仍耗时约 18.7 秒；说明耗时波动，不能仅凭一次通过宣布解除。

选择循环会重复渲染所有撤销按钮。将与选择无关的按钮 memo 化后，未改动的原用例独立耗时降至约 6.3 秒，全量中约 6.8 秒。最后完整回归 1038 项全部通过，现已满足任务 4.1；未跳过测试、缩减点击、放宽断言或增加超时阈值。该优化证明移除了显著的无关渲染开销，不将单次计时当作真实浏览器性能验收。尝试的 CPU 采样命令未生成可读 profile，不以其作为结论依据。

## 实际执行命令

所有终端使用 PowerShell，日志写入会话 scratch，构建产物为项目所需输出。

### 网关

从仓库根目录执行：

```powershell
# 配置修复前：dev 失败、prod 通过，成功复现路由遗漏
mvn -o -pl kb-gateway -am test '-Dtest=ConversationRouteTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'

# 修复后：7 项全部通过
mvn -o -pl kb-gateway -am test '-Dtest=ConversationRouteTest,SearchRouteTest,ReviewRouteTest,NotificationRouteTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
```

### 前端

在 `kb-web` 目录执行：

```powershell
# 初次实现的七文件回归：64 项通过（后续核验含通用 lifecycle 的八文件回归为 67 项通过）
npm test -- --run src/documents/recovery.test.tsx src/documents.test.tsx src/favorites/documents.test.tsx src/reviews/file-lifecycle.test.tsx src/documents/controls.test.tsx src/documents/operations.test.tsx src/documents/write-coordination.test.tsx
npm run typecheck
npm run build

# 本轮最后一次全量：84 文件、1038 项全部通过，无筛选或跳过
npm test -- --run

# 独立排查同一原用例：历史曾超时；本轮未优化约 18.7 秒，优化后约 6.3 秒
npm test -- --run src/admin/space-pages.test.tsx -t '301 条禁用提交且不拆批' --maxWorkers=1

# 本轮新增交错回归：2 项通过；完整恢复测试：27 项通过
npm test -- --run src/documents/recovery.test.tsx -t '同文档旧能力'
npm test -- --run src/documents/recovery.test.tsx
```

从仓库根目录执行：

```powershell
openspec validate 'fix-conversation-routing-and-document-recovery' --type change --strict --json
git diff --check
```

## 复现与复核经过

- 原实现下，首批 5 个前端用例全部失败，分别复现下载拒绝后 V1 消失、缺少主动资格重核、上传拒绝后不重读能力。
- 定向回归中修正测试对 Ant Design 加载图标可访问名称的假设，并仅在新测试的 ConfigProvider 关闭动画；不改变产品动画或放宽权限断言。
- 进行一次只读复核，发现默认版本下载被拒后遇到新版本发布会自动改选。新增两个用例先复现失败，再在重核前固定原 versionId，最终通过。
- 首轮完整回归发现收藏测试缺少 `documentId` 和合法 `preview-info`，补齐实际协议、等待预览就绪后，保留并通过原清理断言。
- 本轮补齐两个同文档旧能力交错回归：在上传在途时发起旧资格核对，再由上传 403 触发新核对，释放已取消的旧 true。新核对加载或已返回 false 时，上传入口、表单与下载仍禁用；直接表单提交不增加 POST。
- 本轮局部优化 ACL 撤销按钮后，完整前端 1038 项、相邻网关 7 项、类型检查、构建、OpenSpec strict 与 diff 检查通过。构建仍有 1200 kB 以上 JS chunk 告警，未调整阈值；测试仍输出既有 React key spread 告警，未屏蔽日志。

本轮日志保存在会话 scratch：`fix-space-timeout-before.log`、`fix-space-timeout-after.log`、`fix-old-capabilities-test.log`、`fix-recovery-targeted.log`、`fix-verification-full-tests.log`、`fix-verification-build.log`、`fix-verification-gateway.log`。历史失败日志仍保留，不用新结果抹去此前阻塞记录。

## 未执行

未启动业务服务、网关监听、浏览器或真实数据库、Redis、对象存储、模型与 Office 服务；未验证实际网络 SSE、浏览器下载及部署行为。未同步主规格、归档变更或创建提交。
