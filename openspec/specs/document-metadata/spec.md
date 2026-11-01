# document-metadata Specification

## Purpose

为文档级标题、人工摘要、分类和标签提供可持续整理的读取与编辑能力，并以当前权限执行组合筛选，使资料修改、并发回执、分类归属和用户看到的文档结果保持可核对的一致关系。

## Requirements

### Requirement: 元数据必须属于整份文档并在保存后立即生效

系统 SHALL 将标题、人工摘要、分类和标签作为文档级资料，成功保存后在下一次授权读取中生效，即使空间采用 REVIEW_REQUIRED。元数据具有独立 metadataVersion，编辑不创建文件版本或审核轮次。版本原件、发布证据和固定引用身份 MUST 保持其既有含义；摘要与分类标签不自动成为模型材料。

#### Scenario: 需审空间修改资料
- **WHEN** 合格编辑者修改已发布文档的标题和摘要并保存成功
- **THEN** 下一次文档元数据读取返回新资料，metadataVersion 增加一次，文件版本发布指针和审核轮次没有因此推进

#### Scenario: 查看历史版本
- **WHEN** 文档改名后用户打开一个仍可访问的历史版本
- **THEN** 文档级标题使用当前资料，文件名和引用版本仍对应所选历史版本，不把当前摘要解释为该版本已审核正文

### Requirement: 元数据读取必须先通过当前文档可见性

系统 SHALL 提供 GET /api/documents/{documentId}/metadata，返回 documentId、spaceId、title、summary、category、tags、metadataVersion、updatedAt。category 为 null 或当前分类的 ID、名称和路径，tags 为稳定 ID 顺序的 ID、名称、enabled。读取 MUST 使用既有父文档 view、密级、空间和可见版本规则；不可访问与不存在统一 404，无会话 401，主体停用或强制改密 403。

#### Scenario: 首版候选对普通读者不可见
- **WHEN** 用户有空间 view，但文档首版仍待审且该用户无候选访问资格
- **THEN** 元数据读取返回 404，不返回标题、摘要、分类、标签或版本号

#### Scenario: 仅有管理或编辑资格
- **WHEN** 用户有 manage 或 edit，但不满足文档当前查看规则
- **THEN** 元数据读取返回统一 404，不用管理或编辑资格替代阅读资格

### Requirement: 元数据修改必须具有当前查看与编辑资格

系统 SHALL 提供 PUT /api/documents/{documentId}/metadata，要求目标文档当前可查看且具有独立 edit，包括合法文档例外 edit。写入等待并发操作后 MUST 重新核对当前主体、空间、密级及 view/edit；不可查看或不存在为 404，可查看但无 edit 为 403。目标已删除或归档时不得修改。

#### Scenario: 通过文档例外编辑
- **WHEN** 用户通过文档例外同时满足 view 和 edit，且当前密级足够
- **THEN** 可以修改该文档资料，不要求其拥有空间 manage 或身份目录权限

#### Scenario: 等待期间失去资格
- **WHEN** 编辑请求等待其他写入时文档被搬迁、升密、删除，或主体权限被撤销
- **THEN** 按取得写入资格后的当前事实重新判断，旧页面能力不能使不再合格的请求成功

### Requirement: 编辑输入必须完整明确且受限

PUT SHALL 接收 title、summary、categoryId、tagIds、expectedMetadataVersion 五个必需字段；summary/categoryId 可显式 null，tagIds 可为空数组。标题去首尾空白后为 1–512 个 Unicode code point，摘要最多 2000，空白摘要归为 null；标签最多 20 个不重复正整数。版本为非负整数。未知或重复字段、非法 Unicode、NUL、缺字段与超限值为 400，正文超过 64 KiB 为 413，禁止静默截断。

#### Scenario: 明确清空资料
- **WHEN** 合格用户提交 summary=null、categoryId=null、tagIds=[] 和合法标题及版本
- **THEN** 摘要、分类和标签被明确清空，文档成为未分类，而不是把 null 当作忽略该字段

#### Scenario: 夹带权限或重复标签
- **WHEN** 请求夹带 spaceId、securityLevel、ownerId、版本内容，或 tagIds 存在重复身份
- **THEN** 返回 400，不通过元数据入口修改这些字段，也不执行部分更新

### Requirement: 文档资料必须有独立并发版本

新建及升级文档 SHALL 使用初始 metadataVersion=0。实际资料变更和空间搬迁 MUST 各推进一次该版本；文件版本上传、发布和分类或标签字典改名不代替文档资料变更。首次修改的 expectedMetadataVersion 过期返回 409；规范化后与当前资料完全相同返回 409 且不生成成功变更。匹配重放先于过期版本判定。

#### Scenario: 两个编辑者同时保存
- **WHEN** 两名编辑者基于相同 metadataVersion 提交不同资料
- **THEN** 至多一个首次修改成功，另一个得到 409，已提交资料不被覆盖

#### Scenario: 搬迁前的表单提交
- **WHEN** 文档搬迁已推进 metadataVersion，用户提交搬迁前打开的旧表单
- **THEN** 返回 409 或当前权限拒绝，不把旧空间分类写回目标空间

### Requirement: 分类与标签关联必须遵循当前目录事实

categoryId SHALL 为文档当前空间的现存分类或 null；不存在、其他空间或并发失效的合法分类 ID 返回统一 409。新增标签关联 MUST 指向现存启用标签；停用标签允许原关联保留或移除，不能重新添加。资料及全部关联必须整体提交，任何失败不得留下部分标题或标签更新。

#### Scenario: 分类被删除或属于其他空间
- **WHEN** 用户提交的分类在保存前已删除，或实际属于另一空间
- **THEN** 返回分类不可选的 409，不猜测同名分类或自动转为未分类

#### Scenario: 保留停用标签并修改标题
- **WHEN** 文档原来关联的标签已停用，编辑者保留该关联并修改标题
- **THEN** 保存可以成功，停用标签仍标记为停用；新增另一条停用标签关联则拒绝整个请求

### Requirement: 元数据写入必须严格绑定请求并可重放

写入 SHALL 要求 X-Request-Id，去首尾空白后为 1–128 个无控制 Unicode code point，绑定操作、操作者、目标、预期版本和规范化完整载荷。匹配重试重新通过当前 view/edit 后返回首次最小回执及 replayed=true；绑定不符返回 409。回执包含 documentId、metadataVersion、updatedAt、changedFields、replayed，不包含旧标题或摘要；并发匹配请求至多生效一次。

#### Scenario: 保存成功但响应丢失
- **WHEN** 用户以原请求标识、原预期版本和原资料重试已经提交的保存
- **THEN** 返回首次回执，不再增加 metadataVersion；客户端另行读取当前资料

#### Scenario: 同标识内容不同
- **WHEN** 同一请求标识被用于另一文档、另一操作者或不同摘要
- **THEN** 返回 409，不返回原资料或复用另一操作的成功结果

### Requirement: 元数据变更审计必须与业务原子一致

成功资料变更 SHALL 同事务保存一次审计与回执，审计包含操作者、文档、请求标识、变更字段及必要 ID/版本，不含标题、摘要或其内容摘要。审计或回执持久化失败 MUST 使本次业务变更整体回滚。读取和被拒写入不创建伪成功事件。

#### Scenario: 审计写入失败
- **WHEN** 标题和标签更新已执行，但成功事件无法持久化
- **THEN** 本次资料、标签关系和 metadataVersion 整体回滚，后续可按原请求重新尝试

### Requirement: 文档组合筛选必须通过请求体执行

系统 SHALL 提供 POST /api/documents/query，接受 titleQuery、spaceId、categoryId、uncategorized、tagIds、fileType、updatedFrom、updatedTo、page、size。所有已给条件按 AND 组合，tagIds 内部为全部匹配；省略条件不限制。成功沿用 R 包装的 page、size、total、items。既有 GET /api/documents 的空间分页继续可用；新入口不接受 URL 查询参数，查询词不进入 URL、日志或浏览器持久存储。

#### Scenario: 同时按主题与空间筛选
- **WHEN** 查询限定空间 A、标签“制度”和“项目 A”及标题关键词
- **THEN** 只返回空间 A 内同时满足两个标签和标题条件的当前可查看文档

#### Scenario: 旧列表调用
- **WHEN** 既有客户端以 GET /api/documents?page=1&size=20 请求文档
- **THEN** 继续得到原空间分页契约，不因为新增筛选入口而强制使用新协议

### Requirement: 筛选值必须严格校验并具有确定语义

titleQuery SHALL 去首尾空白后最多 128 个 Unicode code point，空白不限制，按不区分大小写的字面包含匹配，SQL 通配字符为普通字符。page 默认 1、范围 1–2147483647，size 默认 20、范围 1–100；tagIds 最多 20 个不重复正整数。categoryId 与 uncategorized=true 互斥；时间使用带时区的含起点不含终点范围，双边存在时起点须早于终点。非法、未知或重复字段为 400，正文超过 16 KiB 为 413。

#### Scenario: 标题含通配符
- **WHEN** 用户搜索标题中的百分号、下划线或反斜杠
- **THEN** 按这些字符的字面值匹配，不扩展为任意标题

#### Scenario: 分类条件冲突或时间反向
- **WHEN** 请求同时提交 categoryId 与 uncategorized=true，或 updatedFrom 不早于 updatedTo
- **THEN** 返回 400，不忽略冲突条件或执行扩大范围的查询

### Requirement: 分类筛选必须包含后代且不增加可见范围

categoryId 筛选 SHALL 包含该分类及其全部后代直接关联的文档；uncategorized=true 只匹配无分类文档。分类本身不授予资源访问权，不存在、不可用于当前可见内容或与 spaceId 不相容的合法分类条件均返回空结果，不暴露其他空间目录。

#### Scenario: 选择父分类
- **WHEN** 用户选择“制度”，且其子分类“报销”中有当前可查看文档
- **THEN** 文档参与结果，每篇只出现一次；其他不可查看文档不贡献 total

### Requirement: 文件类型必须按当前发布版本筛选

fileType SHALL 接受 PDF、TXT、MARKDOWN、WORD、EXCEL、POWERPOINT、OTHER、UNPUBLISHED。类型由当前发布版本已存文件名和 MIME 判定，TXT/Markdown 以其已存扩展名区分；历史未知或不一致类型为 OTHER，没有当前发布版本为 UNPUBLISHED。MUST 不用最高版本号或隐藏候选类型参与匹配。

#### Scenario: 候选版本更换格式
- **WHEN** V1 是已发布 PDF，V2 是待审 Word
- **THEN** 文档匹配 PDF，不匹配 WORD；普通读者不能借类型筛选发现 V2

#### Scenario: 可见但没有发布版本
- **WHEN** 用户有首版候选读取资格且文档没有当前发布版本
- **THEN** 文档只匹配 UNPUBLISHED 类型；其他没有候选资格的用户仍看不到该文档

### Requirement: 分页结果与计数必须使用相同授权筛选快照

查询 SHALL 按 updatedAt DESC、documentId DESC 排序，total 和 items 使用同一请求内一致的数据快照、当前授权与全部筛选条件，先过滤再分页。更新时间表示文档最近变化，包含资料修改、发布及已有更新时间变更。列表提供当前分类、标签及 metadataVersion，人工摘要由元数据详情读取。跨页请求不承诺冻结快照，每次重新鉴权。

#### Scenario: 多标签和多个授权同时命中
- **WHEN** 同一文档有多个标签且同时命中用户、角色及部门授权
- **THEN** items 中该文档仅出现一次，total 不因关联行倍增

#### Scenario: 列表查询期间发生资料修改
- **WHEN** 请求执行计数与读取列表之间，其他事务修改文档的标题、标签或更新时间
- **THEN** 本请求的 total 和 items 仍对应同一快照，下一次请求使用新的事实

### Requirement: 新元数据接口必须有界并安全表达失败

新增接口 SHALL 保留登录、主体状态及强制改密门禁，成功和错误响应标记 no-store；当前依赖无法可靠读取时为安全 503，不伪装成空结果或已保存。错误、日志和审计不得回显标题、摘要、筛选词、令牌或底层异常。分页、候选、请求体和字段上限必须实际执行。

#### Scenario: 数据库暂不可用
- **WHEN** 筛选或保存无法完成依赖访问
- **THEN** 返回固定类别的 503，客户端可区分读取失败与空结果、保存结果未确认与明确成功
