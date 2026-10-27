# 用户资源能力查询

两个入口均使用当前 `satoken`，无需管理功能权限；受强制改密、用户及主部门启用门禁保护，成功与错误响应带 `Cache-Control: no-store`。

`GET /api/auth/upload-spaces` 仅允许 `limit`（默认20，1–100）和 `cursor`。返回 `R` 封装的 `{items:[{spaceId,name}],hasMore,nextCursor}`，按空间 ID 升序，不返回全局总数。只按当前空间 edit 资格选择未归档空间；view、manage、space:manage-all 或单文档编辑例外不代表新文档上传资格。旧游标不保留已撤销资格。

`GET /api/documents/{documentId}/capabilities` 不接受查询参数。父文档必须当前可查看，成功 data 仅包含 `{documentId,canDownload,canUploadVersion,canRetry}`。下载按 download 独立判断，后两项按 edit 判断；所有者和角色名称不提供特权。这些布尔值不保证文件存在或指定版本有失败任务，实际操作仍重新鉴权。

两者无会话401，停用/强制改密403，参数非法400，依赖故障503。文档能力入口对不存在、删除、归档、超密级和无权目标统一404。查询无文件、审计、解析或模型副作用，不改变既有空间目录、文档 DTO、密级和权限目录。
