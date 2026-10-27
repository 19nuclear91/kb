# Proposal

## Why

现有分块已经保存版本和来源信息，但检索响应缺少版本身份，AI 转换又丢弃章节及来源范围，导致引用无法可靠地回到回答时使用的材料。同版本重解析还会更换分块
ID，需要明确区分可核对的引用与已经失效的定位，而不是猜测位置或跳转最新版本。

## What Changes

- 贯通检索结果、AI 上下文和 SSE `citation` 中的 `docId + versionId + chunkId`，保留版本号及现有来源提示；不改变检索仅召回当前已发布版本的规则。
- 新增受当前用户权限保护的指定版本引用查询接口，返回仍存在的原分块正文，供调用方核对；历史版本不因新版本发布而失去引用身份。
- 同版本重解析导致旧分块不存在时，明确返回片段不可用状态。在文档和原版本仍可访问时提供该版本的业务预览/下载入口，不根据相同分块序号或最新版本替换引用。
- 每次查询、预览和下载分别重新鉴权；引用不能绕过撤权、升密、搬迁后的授权或空间归档，管理权限不自动授予正文访问。
- 保留解析器报告的页码、章节和来源块范围，缺失值保持未知；这些字段是核对提示，不承诺原件精确坐标。AI
  裁剪上下文后仍保留来源身份，并区分本轮参考材料与模型实际逐句引用。
- 对既有 JSON/SSE 字段采用增量扩展，旧的无版本引用明确降级为不可进行版本级解析，不能用当前版本补全。
- 范围不包含 PDF/Office 原件精确高亮、OCR、解析器或分块器改造、前端查看器、长期证据快照、对话历史、答案逐句证据校验和真实 Chat
  模型接入。

## Capabilities

### New Capabilities

- `citation-provenance`: 管理引用从授权检索到 AI 输出及再次读取的版本绑定、片段核对、来源提示、访问控制和失效语义。

### Modified Capabilities

无。沿用 `document-parsing` 的来源输出和重试发布契约，以及既有空间授权、搬迁、文档密级和 Embedding
重建规则；新增能力消费这些事实，不修改这些能力的要求。

## Impact

- `kb-app`: 扩展 `SearchMapper.xml`、`ChunkHit`；在 `document` 模块增加引用查询的 controller/service/mapper
  投影，复用既有授权谓词和指定版本文件访问接口。
- `kb-ai`: 扩展 `RetrievalClient`、`ChatService`、`ChatEvent.Citation` 的字段转换、上下文裁剪和兼容行为；保持无数据库访问的模块边界与既有
  SSE 终止协议。
- HTTP: 新增 `GET /api/documents/{documentId}/versions/{versionId}/citations/{chunkId}`；检索 JSON 与 SSE
  仅增加字段，既有预览/下载路径不变。
- 存储: 复用 `kb_document_version` 和 `kb_doc_chunk`，不新增引用快照表、不修改分块 ID 生命周期、不要求 Flyway 迁移或重新解析历史文档。
- 验证: 仅包含进程内单元测试、mock/stub 和静态检查，覆盖映射配置、响应字段、来源透传、历史版本及权限分支；不包含数据库执行、集成测试、端到端测试或部署联调，不要求运行
  PostgreSQL/ParadeDB、H2、Redis、MinIO 或真实模型服务。真实环境效果不作为本次任务完成条件，也不得据单元测试宣称已经验证。
