# Design

## Context

动机见 `proposal.md`。当前实现的关键接入点如下：

- `DocumentController` / `DocumentManagementService` / `DocumentMapper.xml` 提供文档详情和按空间的 offset 分页；标题保存在 `kb_document`，列表按 `updated_at DESC, id DESC` 排序。当前版本文件类型来自关联的 `kb_document_version`。
- `DocumentMapper.xml` 已有父文档权限、空间归档及 `parentVersionVisibility` 条件，首版待审文档不能仅凭空间 view 进入普通列表。新查询必须复用这一事实来源。
- `DocumentCapabilitiesService` 先确认 view，再分别解析 download/edit，可增加 `canEditMetadata`。
- `SpaceAuthorizer` 的管理兜底是精确 `space:manage-all`。原 `document-relocation` 规格仍有“全局身份管理员”的旧表述，本变更将其对齐当前功能权限模型，不增加身份角色隐含授权。
- `DocumentRelocationService` 目前先锁文档，随后清例外、取消活动审核、切换空间、同步分块并写审计；旧回执未完整绑定输入。新增目录锁要求调整此写路径的锁顺序，并实现新协议的严格绑定。
- `IdentityAuditService.success` 的旧幂等分支不适合作为新命令回执；`DocumentSecurityLevelService` 的请求锁、锁后重新解析和严格审计，以及 `CommandReceiptMapper` 的事务回执可供模式参考。
- `Documents.tsx` 已有文件版本、审核、评论、收藏及删除协调；`DocumentOperations` 持有跨子组件的在途状态。新资料编辑需要参与该协调。
- 网关 prod/dev 已覆盖 `/api/documents/**` 与 `/api/admin/**` 以及 PUT/PATCH；新端点仍需验证鉴权前错误的 no-store 策略。

本次开发与验收仅采用纯内存单元/组件测试、编译、类型检查和静态构建。数据库相关实现及迁移作为代码产物交付；本文的运行时事务、约束和迁移设计描述目标行为，不构成执行外部组件、集成验证或部署的任务。

## Goals / Non-Goals

**Goals:**

- 在既有文档身份、版本审核、ACL 和权限目录之上增加可维护的元数据组织体系。
- 使分类归属在数据库约束和并发协议中一致，文档筛选的列表与总数使用相同授权快照。
- 使每个新写操作可凭固定请求意图核对，并让管理者与普通编辑者各自在其资格下完成操作。

**Non-Goals:**

- 不扩展 `/api/search` 的混合检索筛选、模型材料或分块内容，不触发解析、Embedding 或 RAG 重建。
- 不引入分类级 ACL、跨空间分类、多分类归属、自由建标签、批量搬迁、标签合并或回收站。
- 不把元数据审核、全文摘要生成、上传幂等改造或通用管理框架重构纳入本变更。

## Decisions

### D1. 文档资料与文件版本分别持有状态

已确认的业务规则以规格为准：文档标题、人工摘要、单分类和多标签即时生效；分类按空间、标签全局；全局标签统一维护；标签筛选为 AND；搬迁显式选择目标分类或未分类。

为 `kb_document` 增加 `summary`、`category_id`、`metadata_version`。仅实际资料修改和搬迁增加 `metadata_version`；版本发布继续更新自己的发布状态，分类/标签改名只改变目录，读取时解析当前名称。普通元数据更新语句只更新资料字段及 `updated_at`，不写回预读的 `space_id`、`security_level`、`status` 或 `current_version_id`。

替代方案：将资料复制到每个文件版本并参与审批会改变已经确认的即时编辑语义，也扩大现有审核发布协议，因此采用文档级资料。历史文件和引用仍绑定原版本，新读取标题来自当前父文档；既有已经封存的聊天记录不批量改写。

### D2. 分类使用邻接树、独立树状态和复合外键

拟新增：

| 存储 | 核心字段与约束 |
|---|---|
| `kb_category_tree_state` | `space_id` 主键、`tree_version >= 0`；一空间一行，随空间创建/删除 |
| `kb_document_category` | `id`、`space_id`、`parent_id`、`name`、`normalized_name`、`sort_order`、时间；`UNIQUE(space_id,id)` |
| `kb_document` 增量 | `summary` 可空、`category_id` 可空、`metadata_version BIGINT NOT NULL DEFAULT 0` |
| 分类父引用 | `(space_id,parent_id)` 引用同表 `(space_id,id)`，阻止跨空间父节点 |
| 文档分类引用 | `(space_id,category_id)` 引用分类 `(space_id,id)`，null 分类采用普通空引用语义 |

根同名与非根同名分别使用唯一索引，避免 null 父节点使唯一约束失效。同级排序使用 `(sort_order,id)`。名称唯一键由服务端按 NFC、去首尾空白和 `Locale.ROOT` 小写规则生成，数据库对该键使用确定性比较；相同规范化规则用于请求摘要。

递归查询计算祖先路径和后代范围，不持久化可漂移的文本路径。最大深度 8，移动检查整个子树的最终深度以及自环/后代父节点。同一树的所有维护共用 `tree_version`，目录修改与引用删除在独占树状态保护下执行。

分类删除要求没有子节点和任何文档引用，包括仍保留的软删除文档；引用冲突不返回文档数量。空空间删除通过外键级联清理分类与树状态，文档存在时遵循既有拒绝规则。

替代方案：物化路径需要在移动时批量更新后代并增加路径一致性维护；闭包表增加成对关系。本次目录深度有限，邻接树及递归查询更适合现有 PostgreSQL 单库结构。

### D3. 全局标签保留稳定身份，停用只限制新增关联

新增 `kb_document_tag(id,name,normalized_name,enabled,version,created_at,updated_at)` 与 `kb_document_tag_relation(document_id,tag_id)`，关系以二元组为主键并有反向 `(tag_id,document_id)` 索引。名称唯一索引覆盖启用和停用词条。

编辑保存比较锁内原关系与提交集合：提交集合中的新增 tagId 必须启用；原来已存在的停用关联可以保留，也可删除。搬迁只保留关系，不执行重新附加；词条改名和启停不批量修改文档时间或版本。首版采用启停生命周期，不提供词条硬删除或合并。

替代方案：字符串标签无法保证改名后的身份一致，JSON 数组也不便于外键和全部匹配，因此采用字典与关系表。

### D4. 读取分为管理目录、编辑候选与筛选候选

| 用途 | 资格 | 返回范围 |
|---|---|---|
| 空间分类管理 | 当前空间 manage 或 `space:manage-all` | 完整分类目录的分层分页、treeVersion，无文档列表和数量 |
| 全局标签管理 | `document:tag:read`；维护另需 `document:tag:write` | 全局词条的分页与启停状态，无关联文档信息 |
| 文档编辑候选 | 目标文档当前 view + edit | 实际所属空间的目录，以及全局启用标签；明确授予的是词条选用信息 |
| 文档中心分类筛选候选 | 当前可查看文档范围及选定空间 | 有可查看文档引用的节点和必要祖先，无隐藏兄弟分支 |
| 文档中心标签筛选候选 | 当前可查看文档范围 | 被可查看文档使用的标签，含停用项，无引用数量 |
| 搬迁上下文 | 文档实际空间管理资格 | 仅 documentId、fromSpaceId、metadataVersion |

元数据详情允许读取该可见文档的当前分类路径及标签名称；有文档例外 edit 的用户可在此文档上下文获取选用目录，不要求身份目录或全局标签管理资格。普通 view 不因某文档可见获得完整空间目录。

全局标签新功能权限加入 `Permission` 固定目录，由现有角色权限管理分配；空间分类继续使用既有空间管理授权。标签管理与文档读写资格分别执行。

替代方案：让所有登录者读取全部目录会把隐藏空间主题暴露给无关用户；让所有选用者先取得管理读取权限又会错误扩大管理面，因此采用上述用途划分。

### D5. API 和输入输出沿用既有协议风格

拟定接口如下，ID 与并发版本均为精确整数：

| 接口 | 主要输入/行为 |
|---|---|
| `GET /api/documents/{id}/metadata` | 文档级完整资料及 `metadataVersion` |
| `PUT /api/documents/{id}/metadata` | 必须显式给出 `title,summary,categoryId,tagIds,expectedMetadataVersion`，包括两个可空字段 |
| `POST /api/documents/query` | 规格定义的组合条件和 page/size，返回现有分页结构 |
| `POST /api/documents/query/categories` | `spaceId,parentId,limit,cursor`；授权筛选目录分层分页 |
| `POST /api/documents/query/tags` | `nameQuery,limit,cursor`；授权标签筛选候选 |
| `GET /api/documents/{id}/metadata-options/categories` | `parentId,limit,cursor`；实际文档空间的编辑分类候选 |
| `POST /api/documents/{id}/metadata-options/tags` | `nameQuery,limit,cursor`；全局启用标签编辑候选 |
| `GET /api/admin/spaces/{spaceId}/categories` | `parentId,limit,cursor`；管理目录；省略 parentId 代表根层 |
| `POST /api/admin/spaces/{spaceId}/categories` | `name,parentId,sortOrder,expectedTreeVersion` |
| `PUT /api/admin/spaces/{spaceId}/categories/{categoryId}` | 完整名称、父节点、排序和 `expectedTreeVersion`，用于改名、移动或排序 |
| `DELETE /api/admin/spaces/{spaceId}/categories/{categoryId}` | JSON 体包含 `expectedTreeVersion` |
| `POST /api/admin/document-tags/query` | `nameQuery,enabled,limit,cursor`；管理目录，enabled 省略代表全部 |
| `POST /api/admin/document-tags` | `name`；缺省启用 |
| `PUT /api/admin/document-tags/{tagId}` | `name,enabled,expectedVersion` |
| `GET /api/admin/documents/{id}/relocation-context` | 最小管理上下文 |
| `PATCH /api/admin/documents/{id}/space` | 原源/目标字段，加必需 `targetCategoryId` 与 `expectedMetadataVersion` |

分类路径及下拉选中项由元数据快照提供，无需按猜测 ID 枚举目录。目录页返回 `items,hasMore,nextCursor`，分类另有 `treeVersion`。所有目录分页默认 20、最大 100；分类游标绑定用途、资源、父节点、排序位置和树版本，标签游标绑定用途、上下文、名称和状态条件以及最后 ID。文档候选游标还绑定当前空间，搬迁后不能沿用。筛选目录没有任何可见节点时返回固定空页、treeVersion=0 且无游标，以同一形状覆盖不存在空间和无可见分支；管理及编辑目录仍按各自资格返回真实树版本。

工程默认上限已落到规格：标题 512、摘要 2000、分类名 64、标签名 32、文档标签 20 个，均按 Unicode code point；目录深度 8；查询正文 16 KiB，写入正文 64 KiB。字典名用 NFC 及无控制字符规范化；标题与摘要去首尾空白，摘要空白统一 null，正文拒绝 NUL 与非法代理项，摘要允许正常换行。

写入要求 `X-Request-Id` 为规范化后 1–128 个无控制 code point。搬迁保留既有体内 requestId 兼容来源，但头体同时出现必须一致。新 PUT 的完整载荷与显式 null 避免空字段被误解为部分 PATCH。

严格解码局限于本功能 DTO/端点：拒绝未知/重复 JSON 字段、非整数 ID、负版本、尾随 JSON 及缺少必需键。特别是 `targetCategoryId` 必须区分“键缺失”和“键存在且 null”，不能用普通可空字段直接把二者合并。POST 查询拒绝 URL 参数，不将关键词放入访问 URL。

成功沿用 `R.ok`；错误沿用真实 HTTP 状态、`code=R.FAILURE`、固定 message、`data=null`。新增资料/目录端点使用独立的顶层 `reasonCode` 白名单，包括 `METADATA_VERSION_CONFLICT`、`TREE_VERSION_CONFLICT`、`CATEGORY_UNAVAILABLE`、`TAG_UNAVAILABLE`、`NAME_CONFLICT`、`REFERENCE_CONFLICT`、`REQUEST_MISMATCH` 和 `NO_CHANGE`。既有搬迁入口继续采用 `identity-management-feedback` 的有限分类：元数据版本冲突为 `INVALID_VERSION`，目标分类失效为 `INVALID_PARENT`，请求绑定不符为 `CONFLICT`，原来源/归档等原因码不变。新解码器只在本功能对应接口消费新分类，不扩大原管理反馈投影的路径白名单；未知 reasonCode 走通用错误，不展示原始异常。

替代方案：扩展 GET 并把标题关键词放入 query string 与项目已有保密检索方向不一致；新增 POST 查询并保留无文本条件的旧 GET 更适合兼容现有列表调用。

### D6. 使用独立命令回执，严格绑定规范化输入

新增本功能专用 `kb_document_metadata_command`：`operation_scope,request_id` 唯一，记录 actor、目标/空间/文档 ID、预期版本、规范化输入摘要及版本、最小 JSON 回执和提交时间。名称、标题、摘要不进入回执或审计；请求摘要仅用于内部精确绑定，不通过查询接口输出。字段与 JSON 载荷有大小约束，首版不在幂等身份仍可能重用时清除绑定。

每个写事务先取得操作域与请求标识的事务级请求锁，再读取既有绑定。无既有绑定时执行真实变更、写回执、追加严格成功审计；任一步失败全部回滚。数据库唯一约束兜底，不用捕获唯一异常后在已经 aborted 的事务里查询来模拟成功。

输入摘要包括操作域、actor、目标、所有前置版本、规范化字段；tagIds 按身份排序。相同标签集合的不同排列具有相同语义；重复 ID 则在规范化前拒绝。无实际变化返回 409，不制造版本更新或成功变更。

匹配重试先核对当前资格，再返回首次最小回执，先于“原预期版本已经过期”的拒绝；变更后的当前状态单独读取。元数据重放要求当前文档 view/edit；分类删除重放可在节点消失后凭原空间当前管理资格返回；标签重放要求当前写资格；搬迁重放要求当前文档管理及原源/目标资格。对于旧 `DOCUMENT_RELOCATE` 审计有记录而新绑定缺失的标识，返回 409，不补造分类绑定。

替代方案：沿用旧 `audit.existing(scope,requestId)` 只凭标识返回会混淆操作者/目标/载荷；将完整资料存进公开审计会增加内容读取路径。独立绑定与安全审计可分别履行两个职责。

### D7. 固定并发锁顺序，等待后重新鉴权

新写路径统一顺序：

```text
request lock
  --> category-tree state locks (space ID ascending)
  --> tag row locks (tag ID ascending, when needed)
  --> document row lock (when needed)
  --> existing review / chunk writes
  --> command receipt + strict audit
```

- 分类维护取得本空间树状态独占锁；元数据修改和搬迁取得相关树状态共享锁。选择分类、删除分类、移动子树不能在引用判定期间相互穿透。
- 元数据写入预读实际空间仅作为加锁提示，取得树共享锁、提交标签的共享行锁及文档行锁后重新读取资料和权限。原空间若已变化，返回冲突；不能持有文档锁再补取新的树锁。
- 搬迁预读实际源空间，按空间 ID 排序取得源/目标树共享锁，再锁文档；锁后核对声明来源、实际归属、metadataVersion、双侧管理与目标可用性。空间与分类在同一 UPDATE 中改变，以满足复合外键。搬迁不需要改标签关联。
- 标签改名/启停只取得对应词条行锁，不获取文档锁；元数据保存持有所提交标签的共享锁直到关联提交，保证“检查启用”与“新增关系”相对于停用有确定顺序。
- `SpaceWriteService` 创建空间时同事务初始化树状态；归档、取消归档及删除先取得树状态独占锁，再按现有方式锁空间行。这样目录写入/元数据关联与空间归档有序，且空空间删除不会与分类外键形成相反锁顺序。
- 元数据保存不访问解析任务或 Embedding 控制锁；Worker 与文件发布仍以现有文档行锁串行。文档删除保留分类/标签关系，不增加“持有文档锁后获取树锁”的路径。
- 新写事务使用 READ_COMMITTED，等待业务锁后清理请求内持久化缓存，重新解析会话主体、当前功能权限和资源权限。不得复用锁前 PermissionScope 当作最终事实。

检查树版本、分类存在性、原停用标签集合及元数据版本均在对应锁内。目录改名或子树移动只改目录，不循环更新文档。审计使用强制事务的严格追加入口，失败直接传播。

替代方案：只加乐观版本可以阻止旧表单，但无法阻止分类被并发删除、标签停用与新增关联交错；只使用文档锁也保护不了目录结构，因此组合状态锁、文档锁及数据库外键。

### D8. 筛选与计数使用同一短读取事务

`POST /api/documents/query` 在只读 REPEATABLE_READ 短事务中解析当前主体、权限并执行 count、分页和该页目录信息批量读取，设置 5 秒数据库事务/语句预算。所有读取共享同一快照，故障为固定 503，不把超时当成空页。

抽出并复用 `DocumentMapper.xml` 的当前父文档可见谓词以及组合条件：

- 标题使用转义后的 ILIKE 字面包含匹配，百分号、下划线和反斜杠不具有输入通配语义。
- 分类使用同空间递归后代集合；元数据筛选不通过 JOIN 多行关系改变文档基数。
- 标签使用集合包含/NOT EXISTS 缺失关联语义，所选 ID 必须全部存在于文档关系。猜测不存在的合法 ID 产生空结果，而非忽略该项。
- count 与分页均使用当前版本 LEFT JOIN；文件类型采用一份共享 CASE。TXT 与 Markdown 在当前实现均可能存为 text/plain，因此结合当前版本已存扩展名区分；其他类型同时核对允许的 MIME/扩展名组合。不一致历史值归 OTHER，无 current_version_id 为 UNPUBLISHED。
- current_version_id 和父文档版本可见条件分开：用户可以看首版候选时仍是 UNPUBLISHED；普通读者不能通过该枚举发现候选存在。
- 排序继续使用更新时间与文档 ID，OFFSET 采用 long 计算。不同页读取不是冻结快照，元数据或权限变化可能改变后续页；前端提供重新查询而不声称跨页稳定快照。

索引包括 `(space_id,category_id)`、文档更新时间/ID、分类父节点排序及标签反向关系。首版不增加新的全文引擎或 trigram 扩展；本次交付查询预算代码和索引定义，并进行静态代码审查，不执行 SQL 或采集查询计划。

替代方案：客户端筛选只处理已加载页且无法给出正确 total；多对多 JOIN 后 LIMIT 会产生重复文档；两次独立快照则可能出现列表与计数矛盾。

### D9. 前端复用现有状态模型，明确资料与目录操作

- `kb-web/src/pages/Documents.tsx` 的文档中心改用 POST 请求体查询，区分筛选草稿与已提交条件；新增分类/标签选择组件、类型选项和带时区时间输入，条件提交回到首页。
- 将元数据 API、DTO、编辑状态及选用组件集中到 `kb-web/src/documents/metadata/`。打开表单读取独立元数据快照与候选，保留当前停用标签；成功回执后重读当前资料，冲突由用户核对后重新编辑。
- 保存以 `DocumentOperations.beginWrite('metadata')` 登记，结果使用现有 confirmed/rejected/uncertain 协调语义。删除确认和元数据提交互斥，卸载表单不能隐藏在途事实。
- 空间详情新增分类管理区块；`/admin/document-tags` 增加标签管理及导航。复用管理写入意图、资源查询和错误消费模式，配置新的精确权限 code。
- `DocumentPanelPage.RelocationBox` 在打开时读取独立上下文，选择目标后按需读取目标分类；选择器区分“未选择”与显式“未分类”。切换目标、会话或页面生命周期清除旧候选，确认按钮等待必要事实齐全。
- 大整数沿用已有 lossless JSON 和 ID/版本转换；分类、标签、摘要全部作为文本。新读取和写入结果仅属于当前身份/资源/查询代次，离页、隐藏和账号变化后旧回调失效。

替代方案：在管理面板直接编辑文档资料会把 manage 与 view/edit 混淆；在超大的 Documents.tsx 内继续堆积所有状态也不利于删除协调，因此保留既有入口并分离功能内状态代码。

### D10. 安全审计扩展为有类型的有限集合投影

新审计操作域加入 `AuditScope`。`AuditSummaryProjector` 当前主要处理标量，需增加有界 ID 数组、固定 changedFields 枚举及可空分类字段的投影，不能把任意 JSON 数组直接透传。`kb-web/src/admin/auditApi.ts` 的 `scalar` 解码同样只接受标量，必须按字段白名单增加 `tagIds`（最多 20 个精确 ID）和 `changedFields`（固定字段集合）的有限数组解码与展示；其他未知数组及嵌套对象仍拒绝。

元数据审计只保存和输出变更字段标识、分类/标签 ID 及前后版本；目录改名以 nameChanged 布尔事实表达。保留已有审计源、窗口、游标和独立权限。旧搬迁缺少新字段时只省略或明确未知；新搬迁的显式 null 与历史缺失分别判断，不能把缺失补成未分类。

新功能端点在 app 和网关提前拒绝时也设 no-store。错误采用固定类别，不打印完整请求、异常 cause 或 ORM 参数；关闭新 Mapper 的正文参数日志。

## Risks / Trade-offs

- [分类树级写串行与目录版本冲突较多] → 每个空间独立状态，目录操作为短事务；页面重读当前树，不自动重发。
- [文档资料立即生效，文件审核不保存当时的资料快照] → 界面说明文档级资料的含义，人工摘要不自动进入模型材料，审核与文件引用继续使用固定版本。
- [分类引用包含软删除文档，目录可能暂不能删除] → 返回无文档细节的引用冲突；本变更不以清除保留记录的方式绕过完整性。
- [深页 OFFSET 与标题包含匹配成本] → 一致查询谓词、必要 B-tree/关系索引和查询预算；离线验证不提供实际查询性能结论。
- [词库名称更新不会改变文档更新时间] → 更新时间只表示文档自身变化，展示名称每次按当前字典解析。
- [新旧搬迁协议和权限目录不兼容] → 严格旧请求拒绝已获用户确认，按下述发布顺序更新写入端。
- [回执和审计分属不同用途，错误复用可能泄漏输入或误重放] → 独立内部绑定、安全最小回执及严格事务追加；固定用例覆盖响应丢失、冲突及旧审计标识。

## Migration Plan

以下为迁移脚本和部署说明的设计内容，本次仅交付相应代码与说明，不执行迁移、发布、回退或演练。

1. 当前正式迁移到 V26。实施时在最新历史之后增加 `V27__document_metadata_taxonomy.sql` 与 `V28__document_tag_permissions.sql`（若其他变更先占用版本，则在开始实现时顺延），不修改已执行迁移。
2. V27 创建分类/树状态、标签/关系和命令回执；新增资料字段及约束；历史文档 summary/categoryId 为 null、metadataVersion 为 0、标签集合为空。已有空间初始化 treeVersion=0，新空间创建事务初始化状态。历史标题与文件记录按原值保留，不伪造资料修改事件。
3. V28 仅为当时存在的 ROLE_ADMIN 一次性增加标签读写绑定。空库引导自动采用新目录，后续重启不恢复撤销绑定。
4. 发布前排空旧 app 写入实例和旧搬迁操作；新目录写入前确保 app 认识权限 code。部署新 app/common 及相关网关响应策略，再同步发布新前端。旧搬迁请求缺少新字段得到 400，不允许通过旧写实例绕过分类约束。
5. 发布说明覆盖默认空目录、已有文档列表、严格新搬迁、标签权限、元数据编辑、授权筛选和固定版本文件类型的兼容行为，并标明本次为离线验证范围。
6. 功能回退优先隐藏新前端入口、关闭对应写入口并保留已经形成的数据及回执。旧前端对新能力字段可忽略，但旧搬迁不可用。不能直接回退不识别标签权限或只更新 space_id 的旧 app；后端回退须使用理解新目录/外键的兼容版本，或经独立维护迁移处理，禁止自动删绑定、分类、关系或 Flyway 历史。

## Verification Strategy

- 纯内存单元测试：规范化/Unicode、显式 null 与缺失、树环路与深度、版本冲突、标签停用差集、请求绑定、固定错误和有限审计投影。服务依赖使用仓储、权限与事务协作器替身，独立控制器使用服务替身，不启动完整应用或 HTTP 监听。
- 前端单元/组件测试：使用模拟 DOM、Mock 请求和受控 Promise/流，覆盖管理权限拆分、目录分页失效、筛选草稿隔离、停用标签保留、保存与删除协调、搬迁选择、精确 null/大整数及迟到响应。
- 所有测试按明确类/文件白名单执行；Java 静态打包使用 `mvn package '-DskipTests'`，前端执行类型检查和静态构建，不使用全库自动发现测试入口。
- 不编写或运行集成、端到端、真实浏览器、数据库或跨实例测试，不运行或连接 PostgreSQL、Redis、MinIO、Docker、模型及嵌入式数据库/缓存等组件；不执行 SQL、迁移、真实锁/事务验证或查询计划采集，不交付对应测试夹具和运行开关。
- README、Markdown、使用说明、注释和示例仅作交付，不编写或执行这些内容的断言测试，不运行文档示例作为验收，不以源码/DDL 字符串断言代替功能测试。OpenSpec 结构校验单独执行。
- 数据库代码、复合外键、锁顺序与事务边界作静态审查；Mock/Stub 结果只说明已覆盖的业务分支和调用生命周期，不代表真实权限 SQL、数据库约束、快照隔离、回滚或多实例并发已经验证。记录仅列出实际执行的离线检查。
