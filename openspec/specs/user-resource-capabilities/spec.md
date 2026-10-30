# user-resource-capabilities Specification

## Purpose

为普通用户的上传目标选择和文档操作界面提供最小、实时的只读资源事实，覆盖只有空间编辑资格的上传者，并分别表达文档下载与编辑能力，使客户端能够控制交互而不通过管理目录、角色名称或旧能力标记扩大实际资源授权。

## Requirements

### Requirement: 资源能力查询必须绑定当前可信主体

两个新增查询 SHALL 仅依据服务器已验证会话及当前启用用户、有效主部门执行，并受既有强制改密门禁限制。缺失或无效会话返回 401，主体停用或必须改密返回 403；不得因客户端声明身份、角色、密级或权限而改变结果。

#### Scenario: 普通启用用户访问
- **WHEN** 合法用户没有任何管理功能权限并访问上传候选或文档能力查询
- **THEN** 系统按其实际资源资格判定，不额外要求身份目录读取、角色读取或空间管理权限

#### Scenario: 会话存在但主体已失效
- **WHEN** 会话对应的用户或主部门被停用，或用户被要求改密
- **THEN** 新查询返回 403，不退化为匿名结果或复用旧能力

### Requirement: 上传空间候选必须只表达实际空间编辑资格

系统 SHALL 提供 `GET /api/auth/upload-spaces`，仅返回当前主体持有空间 edit 的未归档空间，每项只含 spaceId、name。空间 view、manage、space:manage-all、角色名称或文档例外 edit MUST NOT 单独产生上传候选。该查询不得改变既有管理空间清单的资格和字段。

#### Scenario: 只有空间 edit
- **WHEN** 用户仅持有空间 A 的 edit，没有 A 的 view 或 manage
- **THEN** 上传候选包含 A 的最小记录，不要求 A 出现在既有 view/manage 空间清单中
- **AND** 该结果不会授予用户查看 A 中文档的资格

#### Scenario: 管理、查看和文档例外均不蕴含上传
- **WHEN** 用户只拥有空间 manage、space:manage-all、空间 view，或仅某文档的 edit 例外
- **THEN** 不因为这些资格返回该空间作为新文档上传候选

#### Scenario: 空间归档或没有候选
- **WHEN** 有 edit 的空间已经归档，或用户没有可上传空间
- **THEN** 前者不进入结果，后者返回成功空页，不泄露其他空间名称或总数

### Requirement: 上传候选必须支持有界且重新鉴权的分页

上传候选 SHALL 仅接受 limit、cursor，按 spaceId 升序返回 R 封装的 items、hasMore、nextCursor；limit 默认 20、范围 1–100，不返回全局总数。游标 SHALL 绑定该查询种类，继续请求重新过滤当前 edit 资格。未知或重复参数、非法 limit、损坏或其他查询种类的游标 MUST 返回 400。

#### Scenario: 继续读取下一页
- **WHEN** 用户使用返回的合法 nextCursor 读取下一页
- **THEN** 结果按上一页末尾 spaceId 向后读取，不重复已遍历位置，且只包含当次请求仍有资格的空间

#### Scenario: 使用旧游标继续访问已撤权空间
- **WHEN** 第一页后某空间的 edit 被撤销，下一次请求继续使用旧游标
- **THEN** 该空间不再出现在候选中，游标不会保留已撤销的资格

#### Scenario: 参数或游标越界
- **WHEN** 请求携带 userId、q 等未知参数，重复 limit、非法数量或来自其他查询的游标
- **THEN** 返回安全 400，不静默忽略参数或执行扩大范围的查询

### Requirement: 文档能力必须先确认当前查看资格再返回最小投影

系统 SHALL 提供 `GET /api/documents/{documentId}/capabilities`，不接受查询参数，路径为正整数。只有按既有文档 view、当前密级、当前空间和未删除规则可查看的父文档，才返回 R 封装的 documentId、canDownload、canUploadVersion、canRetry、canDelete。不存在、不可见、已归档或已删除统一 404；不得回显其他资源信息。

#### Scenario: 可查看的文档尚无当前发布版本
- **WHEN** 文档记录存在且用户可查看，但首个版本尚未发布
- **THEN** 可返回其操作权限事实，不将没有当前版本等同于没有文档
- **AND** 能力结果不承诺已经有文件可预览或下载，canDelete 不因没有当前发布版本而被强制置为 false

#### Scenario: 只有管理或下载资格
- **WHEN** 用户只有管理权限或 download，但没有目标文档的 view
- **THEN** 能力查询返回与文档不存在相同的 404，不泄露其他能力、标题或文档状态

#### Scenario: 非法路径或参数
- **WHEN** 已通过身份门禁的请求使用非正整数、超范围路径 ID 或任何查询参数
- **THEN** 返回 400，不猜测文档身份或补默认版本

#### Scenario: 只有编辑资格而无查看资格
- **WHEN** 用户具有目标文档 edit，但没有目标文档 view
- **THEN** 能力查询仍返回统一 404，不为提供删除入口而暴露文档或其 canDelete
- **AND** 本查询不改变既有删除 API 独立执行的 edit 授权规则

### Requirement: 文档下载与编辑能力必须独立计算

canDownload SHALL 按目标文档当前 download 资格判定；canUploadVersion、canRetry 和 canDelete SHALL 按当前 edit 资格判定。各资格均遵守现有文档例外、空间归档及密级规则，不由 view、manage、功能权限、ownerId 或角色名称推导。能力只表示权限，不保证版本存在、格式可预览或任务可重试；canDelete 表示整文档删除资格，不表示单版本删除或恢复能力。

#### Scenario: 查看、下载和编辑的不同组合
- **WHEN** 可查看用户仅拥有 download，或仅额外拥有 edit
- **THEN** 前者只有 canDownload=true，后者 canUploadVersion/canRetry/canDelete=true，未授予的独立资格仍为 false

#### Scenario: 文档例外授予编辑或下载
- **WHEN** 可查看用户通过合法文档例外获得 edit 或 download，而没有相同的空间权限
- **THEN** 相应文档能力按例外生效，不因此把整个空间加入上传候选
- **AND** 文档例外 edit 可以产生 canDelete=true，不要求空间 manage

#### Scenario: 能力允许但版本不可操作
- **WHEN** canRetry=true 而选中版本没有可重试失败任务，或 canDownload=true 而请求文件不存在
- **THEN** 实际操作仍按原有入口的任务和文件事实拒绝，能力查询不会修改版本状态或制造可用文件

#### Scenario: 查看或管理身份不隐含删除
- **WHEN** 可查看用户是上传者、拥有 manage 或某管理功能资格，但没有目标文档 edit
- **THEN** canDelete=false，不依据这些身份或管理资格启用删除

#### Scenario: 处理或审核中的版本不改变整文档删除资格
- **WHEN** 可查看且拥有目标文档 edit 的用户查询能力，而某版本正在排队、解析、维护等待或审核
- **THEN** canDelete=true，不把版本是否完成、是否可读或是否可重试作为额外删除权限条件

#### Scenario: 删除前编辑权限被撤销
- **WHEN** 用户此前得到 canDelete=true，随后 edit 被撤销而 view 仍有效
- **THEN** 下一次能力查询返回 canDelete=false，实际删除仍重新鉴权
- **AND** 旧能力响应不成为可重复使用的删除凭据

### Requirement: 能力查询必须实时、只读并安全表达依赖故障

两个新增查询 SHALL 对每次请求取得当前资格，成功响应使用 Cache-Control: no-store。授权或数据依赖无法可靠读取时 MUST 返回安全 503，不返回虚假的空页、false 能力或旧结果。查询不得写入授权、修改密级、访问文件内容或触发解析/生成；响应及日志不得暴露 ACL、正文、存储键、令牌或底层异常。

#### Scenario: 权限、归属或密级在两次请求间变化
- **WHEN** 用户保持原登录会话，而角色/部门/空间授权、文档归属或密级已变化
- **THEN** 下一次查询按新事实返回候选或能力，失去父文档查看资格时统一 404
- **AND** 不要求退出重登，也不因原能力标记继续放行实际操作

#### Scenario: 数据库或身份读取故障
- **WHEN** 当前主体、权限或最小资源投影无法可靠读取
- **THEN** 查询返回无敏感明细的 503，不把故障伪装为空结果或全部 false

#### Scenario: 查询没有写入和文件副作用
- **WHEN** 合法查询执行成功或失败
- **THEN** 不创建业务成功审计、上传文件、解析任务、会话或授权记录，不调用对象存储和模型
- **AND** 成功响应仅包含规定投影并标记 no-store
