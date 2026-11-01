# 文档资料与目录 API

## 权限与响应

文档元数据读取遵循当前文档 view、密级、空间归档和候选版本可见规则；编辑及编辑候选另需独立 edit。文档例外授权有效。空间分类管理需要对应空间 manage 或 `space:manage-all`。全局标签管理读取与维护分别需要 `document:tag:read` 和 `document:tag:write`，两项互不蕴含。

成功使用 `R`（`code=200`、`message=ok`、`data`）；失败以真实 HTTP 状态返回 `code=500`、固定 message、`data=null` 和有限 `reasonCode`。资料/目录错误包含 `INVALID_INPUT`、`PAYLOAD_TOO_LARGE`、`NOT_FOUND`、`FORBIDDEN`、`METADATA_VERSION_CONFLICT`、`TREE_VERSION_CONFLICT`、`TAG_VERSION_CONFLICT`、`CATEGORY_UNAVAILABLE`、`TAG_UNAVAILABLE`、`NAME_CONFLICT`、`REFERENCE_CONFLICT`、`TREE_STRUCTURE_CONFLICT`、`SPACE_ARCHIVED`、`REQUEST_MISMATCH`、`NO_CHANGE`、`UNAVAILABLE`。客户端对未知原因使用通用固定提示。

未登录 401；主体不可用或强制改密 403；不可见文档与不存在均为 404。输入错误 400、正文超限 413、版本/目录/无变化冲突 409、依赖故障 503。成功和失败均使用 no-store。

## 请求标识与回执

写入要求 `X-Request-Id`，规范化后 1–128 个无控制 Unicode code point。标识绑定操作域、当前操作者、目标、前置版本和规范化完整载荷；标签集合按 ID 排序，同一集合的排列等价，重复 ID 无效。

响应未确认时可以原标识、原版本、原载荷匹配重试。服务器重新检查当前资格后返回首次最小回执，并置 `replayed=true`；不再执行变更。更改任何意图字段应创建新请求标识。同标识不同意图为 409。回执不等于当前资料快照，成功后另行读取当前资料；读取失败不撤销已经确认的保存。

元数据回执为 `documentId,metadataVersion,updatedAt,changedFields,replayed`；分类回执为 `categoryId,treeVersion,replayed`；标签回执为 `tagId,version,replayed`。资料内容不进入回执或审计。内部绑定摘要不对外开放。

JSON 拒绝未知或重复字段、尾随 JSON、非法 Unicode、非整数 ID 和超限输入。写入正文上限 64 KiB，查询正文上限 16 KiB；数字身份和版本必须精确保留，不能经 JavaScript 浮点数截断。

## 分类目录

`GET /api/admin/spaces/{spaceId}/categories` 读取管理目录；`GET /api/documents/{id}/metadata-options/categories` 读取文档编辑候选。查询字段为 `parentId,limit,cursor`，缺省父节点表示根层。`POST /api/documents/query/categories` 使用相同字段的请求体并要求 `spaceId`，只返回可查看文档使用的节点及必要祖先。

每页默认 20、最大 100，按 `sortOrder,id` 排序，响应 `items,hasMore,nextCursor,treeVersion`。节点含 `id,parentId,name,sortOrder,path`。游标绑定用途、资源、父节点和树版本；条件不符 400，目录变化 409，文档搬迁后旧编辑游标失效。空筛选目录返回版本零，不公开隐藏目录状态。

管理路径下 POST 创建、PUT `/{categoryId}` 修改名称/父节点/排序、DELETE `/{categoryId}` 删除。创建和修改包含 `name,parentId,expectedTreeVersion`，`sortOrder` 缺省零；删除只含 `expectedTreeVersion`。根位置显式 `parentId=null`。名称 NFC 规范化、去边缘空白后 1–64 个 code point，同空间同父节点不区分大小写唯一。最大深度 8，移动保留节点身份和文档关联。子节点、活文档或软删除保留引用均阻止删除；空间归档后目录只读。

## 全局标签

`POST /api/admin/document-tags/query` 接收 `nameQuery,enabled,limit,cursor`，enabled 省略表示全部；返回词条 `id,name,enabled,version,createdAt,updatedAt` 和分页信息。`POST /api/documents/{id}/metadata-options/tags` 只返回启用词条，`POST /api/documents/query/tags` 只返回当前可查看文档使用的词条（含停用项）；候选请求不接受 enabled。

名称查询去边缘空白后最多 64 个 code point，字面包含匹配；分页默认 20、最大 100，按 ID 升序。游标绑定用途、资源、名称和状态条件；编辑候选还绑定文档实际空间。

`POST /api/admin/document-tags` 接收 name，默认启用且 version=0；`PUT /api/admin/document-tags/{id}` 接收完整 `name,enabled,expectedVersion`。名称 NFC 规范化后 1–32 个无控制 code point，全局不区分大小写唯一，停用词条也占用名称。停用不移除关联，原关联可保留或移除；移除后在停用期间不能重新附加。词条改名、启停不增加文档 metadataVersion 或更新时间。

## 文档资料

`GET /api/documents/{id}/metadata` 无查询参数，返回 `documentId,spaceId,title,summary,category,tags,metadataVersion,updatedAt`。category 为 null 或当前 ID、名称、路径；tags 按 ID 升序展示启停状态。资料属于整文档，即使选择历史文件版本，仍读取当前资料。

`PUT /api/documents/{id}/metadata` 必须提交 `title,summary,categoryId,tagIds,expectedMetadataVersion`，summary/categoryId 可显式 null，tagIds 可为 []。标题去边缘空白后 1–512 个 code point，摘要最多 2000，空白摘要归 null，标签最多 20 个不重复正整数。成功立即生效，不创建文件版本或审核轮次；`metadataVersion` 只因实际资料变更或搬迁增加。

保存以当前 view+edit 重新鉴权，分类必须属于实际空间，新增标签须启用，原停用关联可保留或移除。版本冲突应重读并人工重新编辑，不自动覆盖；无实际变化返回 409。回执只确认该次提交，成功后重读失败应单独提示读取失败。

## 组合筛选

`POST /api/documents/query` 通过 JSON 体接收 `titleQuery,spaceId,categoryId,uncategorized,tagIds,fileType,updatedFrom,updatedTo,page,size`，拒绝 URL 查询参数。旧 `GET /api/documents` 空间分页继续可用。条件间 AND，标签内部全部匹配；分类包含后代，且与 uncategorized=true 互斥。合法但不存在的分类或标签不放宽结果。

标题去边缘空白后最多 128 个 code point，字面、不区分大小写包含匹配；百分号、下划线和反斜杠都按普通字符匹配。page 默认 1，size 默认 20、最大 100。时间必须带时区，含起点、不含终点；同时给出时起点须早于终点。

fileType 可为 PDF、TXT、MARKDOWN、WORD、EXCEL、POWERPOINT、OTHER、UNPUBLISHED，按当前发布版本文件名与 MIME 共同判定；TXT/Markdown 即使同为 text/plain，也按扩展名区分。未知或不一致类型为 OTHER，没有发布版本为 UNPUBLISHED。隐藏候选类型不参与筛选。

响应为 `page,size,total,items`，按 updatedAt DESC、id DESC 排序，items 增加当前分类、标签和 metadataVersion。一个请求的计数、分页和目录装配使用同一只读 REPEATABLE_READ 事务，事务和语句预算 5 秒；超时固定 503。不同页面请求重新鉴权，不冻结跨页快照。本次未执行真实 SQL、事务隔离或性能验证。

## 严格搬迁协议

`GET /api/admin/documents/{id}/relocation-context` 返回 `documentId,fromSpaceId,metadataVersion`，只核对实际空间管理资格，不提供标题、摘要、分类名或文件信息。归档源空间允许读取，不存在或不可管理统一 404。

`PATCH /api/admin/documents/{id}/space` 必须提交 `fromSpaceId,toSpaceId,targetCategoryId,expectedMetadataVersion`；targetCategoryId 显式 null 表示未分类，省略为 400。头/体都提供请求标识时必须一致。目标分类必须属于目标空间；错误原因保持既有管理协议：资料版本过期 INVALID_VERSION，分类不可选 INVALID_PARENT，绑定不匹配 CONFLICT。成功回执新增 targetCategoryId、metadataVersion。

搬迁在同一事务改变空间和分类、推进资料版本、清除例外、取消活动审核并同步分块，全局标签保持。匹配重放重新检查当前空间及原源/目标管理资格，返回首次回执，不再清除新授予的例外。旧审计标识缺少新绑定时返回 409。
