# 用户资源能力查询

两个入口均使用当前 `satoken`，无需管理功能权限；受强制改密、用户及主部门启用门禁保护，成功与错误响应带 `Cache-Control: no-store`。

`GET /api/auth/upload-spaces` 仅允许 `limit`（默认20，1–100）和 `cursor`。返回 `R` 封装的 `{items:[{spaceId,name}],hasMore,nextCursor}`，按空间 ID 升序，不返回全局总数。只按当前空间 edit 资格选择未归档空间；view、manage、space:manage-all 或单文档编辑例外不代表新文档上传资格。旧游标不保留已撤销资格。

`GET /api/documents/{documentId}/capabilities` 不接受查询参数。父文档必须通过当前 view 门禁，成功 data 仅包含 `{documentId,canDownload,canUploadVersion,canRetry,canDelete}`。下载按 download 独立判断，后三项复用同次 edit 判定；所有者、manage 和角色名称不提供特权。`canDelete` 表示整份文档及全部版本的删除资格，不是单版本删除或恢复能力，不因尚无发布版本、正在解析或审核而禁用。这些布尔值不保证文件存在或指定版本有失败任务，实际操作仍重新鉴权。

只有 edit 而无 view 的用户仍无法读取能力或通过详情页删除；既有 DELETE 接口独立按 edit 授权，不因新增入口扩大阅读资格。权限撤销后下一次查询会返回新的资格。新前端仅在 `canDelete === true` 时启用删除；旧服务缺少字段、字段类型错误或能力读取失败时均禁用，原下载、上传及重试能力保持兼容。

两者无会话401，停用/强制改密403，参数非法400，依赖故障503。文档能力入口对不存在、删除、归档、超密级和无权目标统一404。查询无文件、审计、解析或模型副作用，不改变既有空间目录、文档 DTO、密级和权限目录。
