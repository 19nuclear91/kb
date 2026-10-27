# P0 1–4 修复与部署说明

## 行为变更

- 归档状态成为文档读取与两路检索的独立前置条件。空间授权、文档例外 ACL，以及请求中已过期的可见空间集合，都不能绕过归档。manage
  仍保留取消归档能力。
- 业务错误使用 HTTP 400/403/404/409/415，未认证保持 401；参数校验、JSON、缺少参数等错误为 400，超大 multipart 为 413。响应体沿用现有
  R.fail 格式，客户端应以 HTTP 状态判断。
- 网关允许 PATCH、HEAD，并把预检交给 CORS 处理；预检无需登录，实际业务请求仍需会话与业务权限。
- 首次上传与新版本上传都检查文件内容，客户端 Content-Type 不再决定数据库中的 MIME。

## 开发与生产配置

默认 profile 为 prod。本地开发须显式设置：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'dev'
mvn clean install -DskipTests
mvn -pl kb-app spring-boot:run
```

其他服务在各自终端中同样设置 dev。第一次升级须 clean 构建，避免旧 target/classes/db/migration 下残留 V3/V6。

生产使用 prod，并从部署环境注入下列配置。不要将实际凭据提交到仓库。

| 服务                      | 必需配置                                                                                                  |
|-------------------------|-------------------------------------------------------------------------------------------------------|
| kb-app、kb-worker        | KB_DB_URL、KB_DB_USERNAME、KB_DB_PASSWORD、KB_S3_ENDPOINT、KB_S3_ACCESS_KEY、KB_S3_SECRET_KEY、KB_S3_BUCKET |
| kb-app、kb-ai、kb-gateway | KB_REDIS_HOST；受密码保护的 Redis 另设 KB_REDIS_PASSWORD                                                       |
| kb-ai、kb-gateway        | KB_APP_BASE_URL                                                                                       |
| kb-gateway              | KB_AI_BASE_URL、KB_CORS_ALLOWED_ORIGINS（逗号分隔的精确来源，如 https://kb.example.com）                            |
| 新生产库首次引导                | KB_BOOTSTRAP_ADMIN_USERNAME、KB_BOOTSTRAP_ADMIN_PASSWORD；仅传给 kb-app                                    |

检查发生在 Spring 创建连接池和执行 Flyway 之前。缺少关键配置、示例数据库/S3 密码、dev 与 prod
混用、生产开发登录、开发迁移路径、自动基线化、详细日志或通配 CORS 均拒绝启动。生产也禁止内存 AI 审计。外部属性文件或命令行可使用对应的
Spring 属性名。

V3/V6 原文移动到 db/dev，脚本内容和版本未改变。dev 同时加载 db/migration 和 db/dev，已有开发库可以继续校验迁移历史。生产仅加载
db/migration；已有种子迁移历史的数据库不会被自动清理或通过 Flyway repair 绕过。应使用新生产库，或为既有库单独制定并审查数据迁移方案。

## 文件格式与预览

允许上传 PDF、UTF-8 TXT/Markdown、DOC/XLS/PPT、DOCX/XLSX/PPTX。PDF 和旧 Office 检查文件签名；OOXML
检查包内容类型及主文档部件。HTML、SVG、可执行文件、宏专用扩展名和不支持的格式返回 415。

OOXML 包最多 2000 条目、解压后 100 MiB、单份关系/内容类型 XML 最多 1
MiB；禁止宏部件、嵌入附件、危险路径、DTD/外部实体及外部图片/模板关系。普通超链接可以保留但不被下载。旧 Office
的宏不抽取、不执行；这不是病毒扫描。

预览只允许纯文本和签名匹配的 PDF，附带 Content-Security-Policy sandbox、nosniff、no-store、no-referrer。Markdown
按纯文本展示，Office 原件预览返回 415。需要下载的用户必须通过独立 download 权限校验，预览失败不会自动降级为附件下载。历史记录中的
MIME 同样受到预览清单约束。

## 解析资源与外部访问

原件内容抽取已统一移交第三方服务，本地解析引擎及独立解析 JVM 已移除。未接入服务时 Worker 暂停领取解析任务，不消耗重试次数；接入接口和健康状态见 [第三方文档解析接入](third-party-document-parsing.md)。

上传文件格式与安全检查保留。第三方适配器负责调用前的实际文件限制、远程响应和字符预算、超时与取消、质量问题及截断标记；外层任务超时仍由 `KB_TASK_TIMEOUT_SECONDS` 控制。服务尚未选定，不声明已完成远程解析的内容隔离、OCR 或安全验证。

## 验证范围

- H2 执行实际 MyBatis XML 的访问谓词，覆盖文档例外授权、过期空间范围、归档与恢复；另校验所有文档读语句及 BM25/向量两路均包含归档条件。
- MockMvc 验证 HTTP 错误契约，真实 CORS 处理器验证 PATCH 预检；实际 PATCH 仍要求登录。
- Spring 配置加载验证 dev/prod 合并、生产拒绝策略和种子资源位置。
- 文件检查覆盖伪装 MIME、格式不匹配、恶意 XML、外部关系、宏/附件与压缩包上限；上传两条入口与预览响应有回归。
- 历史验证曾覆盖本地独立 JVM 的文本、PDF、DOCX 解析；该实现与测试现已移除，不代表当前第三方接口已完成联调。

H2 不验证 ParadeDB 操作符、PostgreSQL 执行计划或真实对象存储。本次未包含 P0 第 5 项的中间件集成工程；现有真实 PG 烟测仍须
KB_TEST_POSTGRES_URL 才执行。

以下为原 P0 加固时的历史记录，不是移除本地解析后的验证结论：`mvn verify` 共 488 个测试，485 个通过，3 个真实 PostgreSQL 烟测因未配置环境跳过，失败与错误均为 0，所有模块完成打包。
