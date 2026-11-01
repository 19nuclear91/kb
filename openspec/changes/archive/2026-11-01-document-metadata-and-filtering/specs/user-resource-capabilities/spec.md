# Spec Delta

## MODIFIED Requirements

### Requirement: 文档能力必须先确认当前查看资格再返回最小投影

系统 SHALL 提供 GET /api/documents/{documentId}/capabilities，不接受查询参数，路径为正整数。只有按既有文档 view、当前密级、当前空间、可见版本和未删除规则可查看的父文档，才返回 R 封装的 documentId、canDownload、canUploadVersion、canRetry、canDelete、canEditMetadata。不存在、不可见、已归档或已删除统一 404；不得回显其他资源信息。

#### Scenario: 可查看的文档尚无当前发布版本
- **WHEN** 文档记录存在且用户可查看，但首个版本尚未发布
- **THEN** 可返回其操作权限事实，不将没有当前版本等同于没有文档
- **AND** 能力结果不承诺已经有文件可预览或下载，canDelete 和 canEditMetadata 不因没有当前发布版本而被强制置为 false

#### Scenario: 只有管理或下载资格
- **WHEN** 用户只有管理权限或 download，但没有目标文档的 view
- **THEN** 能力查询返回与文档不存在相同的 404，不泄露其他能力、标题或文档状态

#### Scenario: 非法路径或参数
- **WHEN** 已通过身份门禁的请求使用非正整数、超范围路径 ID 或任何查询参数
- **THEN** 返回 400，不猜测文档身份或补默认版本

#### Scenario: 只有编辑资格而无查看资格
- **WHEN** 用户具有目标文档 edit，但没有目标文档 view
- **THEN** 能力查询仍返回统一 404，不为提供删除或元数据编辑入口而暴露文档及其能力
- **AND** 本查询不改变既有删除 API 独立执行的 edit 授权规则

### Requirement: 文档下载与编辑能力必须独立计算

canDownload SHALL 按目标文档当前 download 资格判定；canUploadVersion、canRetry、canDelete 和 canEditMetadata SHALL 按当前 edit 资格判定。各资格均遵守现有文档例外、空间归档及密级规则，不由 view、manage、功能权限、ownerId 或角色名称推导。能力只表示权限，不保证版本存在、格式可预览或任务可重试；canDelete 表示整文档删除资格，canEditMetadata 表示文档级资料编辑资格，不授权分类树或全局标签字典维护。

#### Scenario: 查看、下载和编辑的不同组合
- **WHEN** 可查看用户仅拥有 download，或仅额外拥有 edit
- **THEN** 前者只有 canDownload=true，后者 canUploadVersion/canRetry/canDelete/canEditMetadata=true，未授予的独立资格仍为 false

#### Scenario: 文档例外授予编辑或下载
- **WHEN** 可查看用户通过合法文档例外获得 edit 或 download，而没有相同的空间权限
- **THEN** 相应文档能力按例外生效，不因此把整个空间加入上传候选
- **AND** 文档例外 edit 可以产生 canDelete=true 和 canEditMetadata=true，不要求空间 manage

#### Scenario: 能力允许但版本不可操作
- **WHEN** canRetry=true 而选中版本没有可重试失败任务，或 canDownload=true 而请求文件不存在
- **THEN** 实际操作仍按原有入口的任务和文件事实拒绝，能力查询不会修改版本状态或制造可用文件

#### Scenario: 查看或管理身份不隐含删除
- **WHEN** 可查看用户是上传者、拥有 manage 或某管理功能资格，但没有目标文档 edit
- **THEN** canDelete=false 且 canEditMetadata=false，不依据这些身份或管理资格启用相应操作

#### Scenario: 处理或审核中的版本不改变整文档删除资格
- **WHEN** 可查看且拥有目标文档 edit 的用户查询能力，而某版本正在排队、解析、维护等待或审核
- **THEN** canDelete=true 且 canEditMetadata=true，不把文件版本是否完成或可重试作为额外文档级权限条件

#### Scenario: 删除前编辑权限被撤销
- **WHEN** 用户此前得到 canDelete=true 和 canEditMetadata=true，随后 edit 被撤销而 view 仍有效
- **THEN** 下一次能力查询返回两个字段均为 false，实际删除和元数据修改仍重新鉴权
- **AND** 旧能力响应不成为可重复使用的操作凭据
