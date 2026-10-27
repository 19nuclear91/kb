# 个人文档收藏

用户可收藏当前有 view 权限且满足密级规则的文档，收藏属于本人。管理员身份不允许读取或修改他人的收藏。查看、下载和编辑仍由各自的业务接口鉴权。

## 用户入口

- 文档列表、文档详情和搜索结果提供“收藏文档”/“取消收藏”。同页同文档共享状态，提交期间互斥；搜索中的多个分块只按文档身份收藏。
- 主导航“我的收藏”对应 `/favorites`，支持直接访问、刷新及登录后的站内返回；身份确认并完成必要改密后加载，不依赖 AI 会话开关。
- 收藏页显示标题、当前版本、密级和收藏时间，点击“更多收藏”沿服务端游标继续加载，“刷新收藏”重新读取首页。页内取消成功后移除条目，成功空列表显示“暂无可查看的收藏”；读取失败可主动重试。
- 点击标题进入 `/documents/{documentId}` 读取当前文档。未发布时显示“尚未发布”；收藏搜索结果不会改写原搜索或问答引用的版本。
- 收藏状态读取失败时，文档或搜索内容仍可展示，但收藏按钮禁用并提供“核对收藏状态”。成功状态批次省略目标时，页面清除对应旧标题、片段和入口，并关闭相关引用或预览。
- 收藏列表和状态仅在当前页面内存中保留。退出、切换用户、强制改密或离开页面会中止活动请求；后台恢复时先遮蔽旧内容、重新核对身份，再读取当前资源。

## 数据与升级

`V20__document_favorites.sql` 新增 `kb_document_favorite`，保存关系 ID、用户 ID、文档 ID 和创建时间；`user_id + document_id` 唯一，按用户和关系 ID 倒序分页。收藏不保存标题、正文、版本或空间快照，也不改变文档更新时间。

- 用户或文档最终硬删除时，通过外键级联删除对应关系。
- 文档软删除、空间归档及撤权时保留最小关系，但在读取时隐藏。权限恢复后，未取消的收藏重新可见且保持原创建位置。
- 取消本人收藏无需当前文档 view，关系不存在、文档不存在或已不可见均返回相同取消成功结果。
- 新版本发布不复制收藏；进入收藏文档时由文档详情选择当前版本。文档尚无当前版本时允许收藏，页面显示尚未发布。

升级时由 kb-app 单点执行迁移，先发布支持收藏的 kb-app 和包含收藏 `no-store` 策略的 kb-gateway，再发布前端。四个收藏端点均使用现有 `/api/documents/**` 网关路由及 PUT/DELETE CORS 配置；静态托管为 `/favorites` 提供 SPA 页面回退，API 保留真实响应。旧前端继续使用原有接口；收藏界面不依赖 AI 会话开关。

回退时先回退前端，再按需回退 kb-app/kb-gateway，保留收藏表、本人关系和 Flyway 历史。旧后端与较新迁移版本的兼容问题不得通过自动 repair 或删除迁移历史解决。同源托管与缓存约定见 [用户端运维说明](user-web-operations.md)。

## 写入与结果核对

PUT 表示“设为已收藏”，DELETE 表示“设为未收藏”，不会反转当前值。同用户重复收藏保持原创建时间；取消后再主动收藏产生新创建位置。两端同时提交相反指令时，以服务端实际执行后的当前状态为准。

写请求结果不确定时，界面禁用该文档的进一步写入，并提供主动只读核对。核对只说明现在是否收藏，不能证明原请求是否成功；它不会自动重新发送原写请求。取得当前状态后，可由用户再次明确选择操作。

## 列表与批量状态

收藏列表只返回当前可查看的关系，条目包含 `documentId`、`title`、`spaceId`、`securityLevel`、`currentVersionId`、`currentVersionNo`、`favoritedAt`。版本字段可空，不提供总收藏数或隐藏条数。

`limit` 默认 20，上限 100；后续请求携带 `nextCursor`。游标绑定本人和查询种类，按关系创建位置倒序，权限过滤先于截取。分页不是冻结快照：新增收藏需刷新首页，取消、撤权、恢复或搬迁均按当次事实处理。文档版本更新不改变收藏顺序。

状态批次接收 1–100 个不同文档 ID，按输入顺序返回可见目标的 `documentId`、`favorited`。可见未收藏返回 false；不可见或不存在均省略。界面清理被省略目标的旧内容，读取故障则显示状态待确认，不默认转换成 false。

## 接口

成功均为 HTTP 200，使用 `R` 封装。PUT/DELETE 的 data 是 `{documentId, favorited}`；批量状态的 data 是 `{items}`；列表的 data 是 `{items, hasMore, nextCursor}`。响应和网关提前拒绝响应均禁止缓存。

| 方法与路径 | 输入 |
|---|---|
| PUT `/api/documents/{documentId}/favorite` | 仅路径 ID |
| DELETE `/api/documents/{documentId}/favorite` | 仅路径 ID |
| POST `/api/documents/favorites/status` | JSON `documentIds` 数组 |
| GET `/api/documents/favorites` | 可选 limit、cursor |

ID 为正 long，批量数组需要不同的 JSON 整数，不能用字符串或小数替代。未知/重复参数或字段、空数组及超限输入返回 400；PUT/DELETE 不接受正文或查询参数。无会话为 401，主体停用或强制改密为 403，不可查看的 PUT 目标为 404；依赖故障为 503，不伪装为空收藏。批量接口需要 JSON 媒体类型，否则返回 415。

以下为调用说明，不属于本次运行验证：

```powershell
$base = 'https://kb.example.com'
$headers = @{ satoken = '<当前会话令牌>' }
Invoke-RestMethod -Method Put -Uri "$base/api/documents/11/favorite" -Headers $headers
Invoke-RestMethod -Method Post -Uri "$base/api/documents/favorites/status" -Headers $headers -ContentType 'application/json' -Body '{"documentIds":[11,12]}'
Invoke-RestMethod -Method Get -Uri "$base/api/documents/favorites?limit=20" -Headers $headers
Invoke-RestMethod -Method Delete -Uri "$base/api/documents/11/favorite" -Headers $headers
```

## 验证范围

验证仅采用内存单元测试、代码静态审查、编译、类型检查和静态构建。测试不启动应用、HTTP 服务、浏览器或任何数据库及外部组件，也不以本说明或示例作为测试对象。数据库迁移、事务并发及真实应用链路未执行。

## 本地检查记录（2026-10-24）

在 Windows PowerShell 中执行以下明确白名单。

项目根目录：

```powershell
mvn -o -pl kb-app,kb-gateway -am test '-Dtest=DocumentFavoriteViewsTest,DocumentFavoriteServiceTest,DocumentFavoriteQueryTest,DocumentFavoriteControllerTest,DocumentFavoriteResponseTest,FavoriteResponsePolicyTest,AdminQueryAccessTest,QueryParametersTest,QueryCursorTest,DocumentCapabilitiesServiceTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
```

结果：11 个测试类、44 项测试通过，其中 kb-app 43 项、kb-gateway 1 项，失败/错误/跳过均为 0。新增收藏测试共 26 项；相关回归覆盖当前身份门禁、参数与游标、独立文档操作能力和模块依赖方向。服务与控制器使用 mock/stub，响应策略使用内存请求/响应，架构检查读取编译后的类；不建立 Spring 应用上下文或数据连接。

`kb-web` 目录：

```powershell
npm test -- --run src/favorites/api.test.ts src/favorites/store.test.ts src/favorites/button.test.tsx src/favorites/documents.test.tsx src/favorites/page.test.tsx src/favorites/lifecycle.test.tsx src/api.test.ts src/documents.test.tsx src/navigation.test.tsx src/routing.test.tsx src/session.test.ts src/lifecycle.test.tsx
```

结果：12 个文件、90 项测试通过。新增收藏测试共 31 项，覆盖大整数、批量去重、同文档互斥、主动核对、旧响应隔离、文档/搜索接入、分页与安全展示；其余为 API、原文档页面、导航、路由及 Session 生命周期回归。全部使用 mock transport、受控 Promise、内存路由与 jsdom。

| 工作目录 | 实际命令 | 结果 |
|---|---|---|
| 项目根目录 | `mvn -o package '-DskipTests' '-Dstyle.color=never'` | 整个 Maven reactor 编译打包成功；所有模块跳过测试执行 |
| `kb-web` | `npm run typecheck` | 通过 |
| `kb-web` | `npm run build` | 通过，生成 `dist` 静态产物 |

代码静态审查确认 V20 为新增迁移，用户/文档外键级联清理、唯一关系与分页索引齐备；PUT 从授权文档集合写入且保留原创建位置，DELETE 仅按本人键删除，列表与批量状态复用文档授权片段，过滤位于分页截取之前。四条控制器路径、网关路由与 CORS 兼容，应用和网关响应策略覆盖收藏路径；前端路由位于身份保护布局中，搜索引用继续使用原版本三元组。

以上结果不包含迁移执行、真实 SQL 权限与事务并发验证、集成/端到端测试、说明文档及示例测试；未启动开发服务器、应用、真实浏览器、数据库、Redis、MinIO、Docker 或模型服务。

### 验证反馈修复记录（2026-10-24）

- 非空表单正文校验：收藏响应过滤器在表单解析之前为 PUT/DELETE 建立最多 1 字节的读取缓存；控制器同时检查已消费字节和剩余输入，避免 `&&` 等非空正文被误判为空。新增内存单测覆盖两种方法、已知/未知正文长度、较长正文和真正空正文，拒绝时不调用收藏服务。
- 搜索撤权清理：补充状态批次省略目标及 PUT 返回 404 两个模拟 DOM 场景；确认同文档所有旧标题、片段与收藏按钮被清理、已打开引用关闭，其他可见文档仍保留。
- 收藏续页重试：补充续页先失败再成功的模拟 DOM 场景；确认保留已有条目、沿同一游标重试、禁止并发重复加载，并在成功末页后移除继续加载入口。

项目根目录实际执行：

```powershell
mvn -o -pl kb-app,kb-gateway -am test '-Dtest=DocumentFavoriteControllerTest,DocumentFavoriteResponseTest,FavoriteResponsePolicyTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -pl kb-app,kb-gateway -am package '-DskipTests' '-Dstyle.color=never'
```

结果：4 个白名单测试类、14 项测试通过，失败/错误/跳过均为 0；kb-common、kb-app、kb-gateway 及父项目打包成功，打包阶段跳过测试执行。过滤器和控制器直接使用内存请求/响应及 mock 服务，不启动 Spring 应用上下文或 HTTP 服务。

`kb-web` 目录实际执行：

```powershell
npm test -- --run src/favorites/documents.test.tsx src/favorites/page.test.tsx
npm run typecheck
npm run build
```

结果：2 个白名单文件、16 项测试通过；类型检查与静态构建通过。上述三项验证反馈均已处理，任务记录同步至 `openspec/changes/document-favorites/tasks.md`。
