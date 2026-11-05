# Office 原生预览

Office 预览只读打开 docx、xlsx、pptx，默认关闭。PDF、文本和独立原件下载沿用已有接口。功能使用独立 ONLYOFFICE 服务与隔离预览 Origin，所有内容读取仍需当前知识库授权。

## 固定协议

适配标识为 `onlyoffice-10.0.0-v1`，镜像为：

```text
onlyoffice/documentserver:10.0.0@sha256:ca17889f10b11141cc6441836aebdd2835cf3e1b49681b2fd6e155c94b7850b3
```

版本与摘要来自 [ONLYOFFICE 10.0.0 发布页](https://github.com/ONLYOFFICE/DocumentServer/releases/tag/v10.0.0) 及 [官方镜像元数据](https://hub.docker.com/v2/repositories/onlyoffice/documentserver/tags/10.0.0)。这固定了部署输入，不表示已启动该镜像或完成真实服务验证。

静态协议依据为服务端提交 `f037d2c42fab6ef627f323bd8f8f73b4f99b0cd0`，以及 SDK 10.0.0.176 的提交 `c1a29cc83824661d799711be14ef12b6125302f3`：

- [server.js](https://github.com/ONLYOFFICE/server/blob/f037d2c42fab6ef627f323bd8f8f73b4f99b0cd0/DocService/sources/server.js) 定义下载、打印、保存和转换端点，代理默认拒绝这些入口。
- [DocsCoServer.js](https://github.com/ONLYOFFICE/server/blob/f037d2c42fab6ef627f323bd8f8f73b4f99b0cd0/DocService/sources/DocsCoServer.js) 与 [docscoapi.js](https://github.com/ONLYOFFICE/sdkjs/blob/c1a29cc83824661d799711be14ef12b6125302f3/common/docscoapi.js) 定义 Socket.IO 的握手、`message` 事件和 `/doc/{key}/c` 路径。SDK 使用 Engine.IO 4 的 WebSocket 和 polling 传输。
- [storage-base.js](https://github.com/ONLYOFFICE/server/blob/f037d2c42fab6ef627f323bd8f8f73b4f99b0cd0/Common/sources/storage/storage-base.js) 定义 `/cache/files/data/{key}/.../{filename}` 缓存路径。供应商签名不代替平台当前授权，原件和导出缓存不在代理阅读白名单内。
- [jwtService.js](https://github.com/ONLYOFFICE/server/blob/f037d2c42fab6ef627f323bd8f8f73b4f99b0cd0/DocService/sources/jwtService.js) 和 [请求签名说明](https://api.onlyoffice.com/docs/docs-api/additional-api/signature/request/token-in-header) 区分浏览器/inbox、session 与 outbox；原件取件使用独立 outbox 秘密，验证 `operation: download` 及签名中的完整目标 URL。
- [web-apps api.js 10.0.0.176](https://github.com/ONLYOFFICE/web-apps/blob/493e500c56ec96ccaa0452ab092cbce23b6dcc2e/apps/api/documents/api.js) 定义内层 iframe 启动查询参数；`parentOrigin` 固定为预览 Origin，`frameEditorId=viewer`、`mode=view`、`isForm=false`，不接受外部启动地址。

允许的只读客户端消息和资源分类由 `OfficeProtocol` 定义。打开文档仅使用 `open`，局部阅读资源可使用受限 `pathurl/pathurls`；编辑、保存、批注、聊天、外部图片取件和任意 RPC 均拒绝。路径必须规范且绑定当前会话的 `document.key`，不识别的协议返回安全失败。

服务端初始化的 `license` 消息在白名单内，要求许可对象及整数类型标识，继续经过逐消息授权和 URL 清理；客户端不能发送同名命令。内存协议回放覆盖连接、许可、认证和打开结果，不以放行未知消息修复初始化兼容性。

## 验证范围

开发验证仅使用隔离单元/组件测试、进程内替身和静态构建，不启动前端、应用服务、数据库、Redis、ONLYOFFICE 或容器。没有文档内容断言测试。此范围不证明实际排版、供应商协议兼容性、部署网络隔离或 Redis 原子脚本并发行为。

## 指定版本预览信息

`GET /api/documents/{documentId}/versions/{versionId}/preview-info` 沿用普通登录认证头和强制改密门禁，先执行现有 `view` 与版本资格查询，再返回 `R` 包装的协议版本 1：

| mode | 含义 |
| --- | --- |
| DIRECT | PDF/TXT/Markdown，`previewPath` 指向原受保护文件流 |
| OFFICE | docx/xlsx/pptx 的原生预览会话入口可用 |
| UNAVAILABLE | 支持的 Office 格式但功能关闭，`reason=DISABLED` |
| UNSUPPORTED | 其他格式，包括旧 doc/xls/ppt |

`documentId`、`versionId` 为精确十进制字符串，不返回对象存储信息。不存在或不可见先返回 404，授权依赖异常返回 503；描述响应禁止缓存。原二进制 `/preview` 和 `/download` 不改变返回类型。

## 有界会话

`POST /api/documents/{documentId}/versions/{versionId}/office-preview/sessions` 创建原登录绑定的会话，返回一次性交接正文和预览 Origin 的 bootstrap 地址。`POST /api/office-preview/sessions/{sessionId}/heartbeat` 只接受同一个原登录；`DELETE /api/office-preview/sessions/{sessionId}` 幂等关闭，不披露其他用户的会话。

默认交接有效期 60 秒、活动租约 45 秒、保活间隔 15 秒、绝对有效期 30 分钟、每用户最多 3 个会话、全局最多 100 个。交接只消费一次，保活不能改变版本或超过绝对期限，页面意外关闭后依靠租约回收。Redis 失效拒绝建立与读取，不在本地缓存允许结果。

前端预览信息与会话创建各最多等待 15 秒，超时取消请求并显示打开结果未确认，由用户主动重试。已知的迟到会话只发起关闭，不恢复查看器；无法确认创建结果时仍由服务端有限 TTL 回收。会话建立后的查看器就绪等待另限 30 秒。

独立请求通过原登录令牌查询 Sa-Token 权威会话并核对活动期限，复用当前主体启用/改密门禁和现有指定版本 `view` 查询。原令牌只存在于服务端短期存储，不返回查看器。Redis 脚本中的用户、文档和版本 ID 为字符串，防止 Lua 双精度数字损失精度；原子脚本未连接真实 Redis 执行。

## 浏览器交接与服务签名

主站、预览站使用同一可注册域名下的不同 HTTPS 子域，例如 `https://kb.example.invalid` 与 `https://preview.example.invalid`。两者不能共享 Origin；主站 CSP 的 `frame-src` 和 `form-action` 需精确增加预览 Origin。浏览器不需要第三方 Cookie，也不需扩大 API CORS。主站通过隐藏表单将 `launchToken` 定向 POST 到当前 iframe，令牌不出现在地址栏或 Referer；入口严格核对主站 Origin，缺失、`null` 或不匹配均拒绝。只读 iframe 的沙箱仅允许脚本、自身 Origin 和表单，不允许下载、弹窗或顶层导航。

成功交接返回 303 到 `/s/{SID}/view`，设置 `__Secure-kb-office-preview`：HttpOnly、Secure、SameSite=Strict、无 Domain、Path=`/s/{SID}/`，有效期不超过绝对期限。同一浏览器不同文档互不共用证明。所有 `/s/` 请求都要求精确预览 Host；查看器外壳及配置也重新授权。主站登录令牌不进入查看器、Cookie 或供应商请求。

三类秘密严格分离，长度至少 32 字符：

| 配置 | 用途与校验 |
| --- | --- |
| `KB_OFFICE_CONFIGURATION_SECRET` | 只读完整配置的 HS256 JWT；固定 key、源地址、view 模式及关闭写入/下载/打印的权限。ONLYOFFICE 10 的 browser/inbox 共用此秘密，不声称二者在供应商中隔离 |
| `KB_OFFICE_OUTBOX_SECRET` | ONLYOFFICE 取件请求的 `Authorization: Bearer ...`；只接受 HS256、根 `operation=download`、`payload.url` 精确匹配当前会话源地址，以及最长 300 秒 iat/exp |
| `KB_OFFICE_GATEWAY_SECRET` | 网关 POST 内部 bootstrap/authorize/configuration；HMAC 绑定方法、路径、30 秒时间窗、随机值与原始正文摘要 |

供应商 session 另用独立秘密文件。网关内部签名不替代一次性交接证明或路径 Cookie；授权请求即使在时间窗内重复，也重新查当前资格。配置令牌由会话创建时间稳定签发，多应用副本输出一致。没有保存回调；`/internal/office-preview/callback` 不映射，不能触发下载、写版本、审核或发布。原件桥接仅支持有签名的 GET，拒绝附加查询参数；先核对原登录和当前 view，再检查原件类型并有界流式读取，没有 S3 URL，也不跨网络等待持有数据库事务。

内部桥接和应用端口只能在私有网络访问；公共 API 路由仅匹配既有 `/api/` 白名单，没有 `/internal/**` 通配。代理只构造供应商必要的虚拟路径头，剥离主站令牌、Cookie、Authorization、追踪 baggage 和用户输入的转发头。外部响应、配置、错误与资源均 `no-store`、`no-referrer`；错误不包含原始上游响应。状态为 400 输入、401 原登录/服务签名失效、403 强制改密、404 不可见或预览失效、415 文件/协议不支持、429 会话/并发上限、503 依赖异常。浏览器未知创建或关闭结果不自动重试 POST，旧会话最终按 TTL 回收。

## 受控资源与长连接

`/s/{SID}` 是供应商虚拟路径前缀，转发使用 `X-Forwarded-Host: preview-host/s/{SID}`。允许当前 key 的 Editor.bin/Editor.json 和有限媒体缓存、固定版本 web-apps/sdkjs/fonts 静态资源及 `/doc/{key}/c` Engine.IO 4 传输。所有资源读取均授权，包括缓存命中和静态资源；原件、导出、转换、打印、上传、WOPI、插件、未知路径、编码穿越与跨 key 缓存全部拒绝。上游重定向不跟随，也不传给浏览器。供应商消息中的原件/外域 URL 从读取结果中移除，未知或错误消息安全终止。

WebSocket 与 polling 使用同一逐消息检查：客户端仅允许打开/有限资源定位/关闭/保活消息；双向每条文本消息等待独立当前授权结果后再转发。二进制或超限消息拒绝，分片由 Netty 在有限消息长度内聚合；空闲连接每 10 秒重新核对。polling 的 Engine.IO SID 绑定本机预览 SID，不能用另一会话的传输标识读取。当前请求已准入并开始输出后可以完成，撤权阻断后续请求和后续消息。

默认上限：网关 100 个连接绑定、400 个在途 HTTP 请求、100 个供应商连接及 100 个连接等待项；授权连接池 16/等待 32，等待 2 秒；授权超时 3 秒、上游总请求时限 30 秒；单条消息 4 MiB、polling 每批最多 256 条、单资源流 100 MiB、原件 50 MiB。响应逐块计数，不把整份资源聚合进内存。并发流在取消/结束时释放许可，消息流以顺序授权和反压限制队列。调整时保持主站保活间隔小于租约的一半，并同步外层 Nginx 的消息大小上限。

`kb.office.preview.failures` 只记录固定 route=`office-resource` 与有限 HTTP 状态类别，不把 SID、文档 ID、key 或 URL 作为指标标签。使用现有安全遥测约定；不要启用 HTTP wiretap、请求正文或供应商调试日志。示例关闭供应商和预览反代请求日志，以免正文/证明/缓存键进入诊断；通过安全计数器定位问题。

## 可选部署

`ops/office/compose.yml` 是独立、带 `office` profile 的示例，不改变根编排的默认服务。预览外层反代见 `preview-nginx.conf`，内部原件单路由桥见 `source.conf`。这些配置已静态审查，未启动容器。示例假设 kb-app 的容器 DNS 为 `kb-app:8081`，并使用部署方已有的私有应用网络。

1. 准备两个同站点 HTTPS 域名和证书，替换反代示例域名。只将预览域名转发到网关 `/s/`；ONLYOFFICE 和 source 桥不映射宿主机端口。网关加入 `kb-office-private` 以访问供应商，kb-app 仅加入应用私有网络。
2. 在秘密管理系统生成四份互不相同的随机秘密：配置、outbox、gateway、供应商 session。供应商秘密文件为 UTF-8 无 BOM、无末尾换行，应用配置值须与文件字节一致；不要提交秘密。设置编排中 `*_SECRET_FILE` 和 `KB_APPLICATION_NETWORK`。`NODE_CONFIG` 使用秘密文件覆盖镜像入口写入 local.json 的默认共享值，依据 [node-config 环境配置优先级](https://github.com/node-config/node-config/wiki/Environment-Variables)。
3. 主站、应用与网关保持关闭，先交付可选编排配置和网络规则。部署环境显式选择 `office` profile 才会启动此服务；本次开发不执行启动。供应商只在 internal 网络，出站 `externalRequest` 仅允许 `http://office-source/internal/office-preview/sessions/`，其余请求关闭；元数据地址拒绝。source 桥只允许有认证头的指定路径 GET，其他地址全部 404。
4. 给应用、网关配置下表参数，再设置各自 `KB_OFFICE_PREVIEW_ENABLED=true`；前端以 `VITE_OFFICE_PREVIEW_ORIGIN` 构建。配置不完整时启用失败，不退回无签名模式。生产入口应有独立的限流及 TLS 终止。

| 变量 | 位置/值 |
| --- | --- |
| `KB_OFFICE_PREVIEW_ENABLED` | app/gateway，默认 false |
| `KB_OFFICE_APPLICATION_ORIGIN` / `KB_OFFICE_PREVIEW_ORIGIN` | app/gateway，精确 HTTPS Origin，无尾斜线 |
| `VITE_OFFICE_PREVIEW_ORIGIN` | 前端构建，必须等于后端预览 Origin |
| `KB_OFFICE_SOURCE_BASE_URL` | app，示例 `http://office-source`，必须与供应商出站前缀一致 |
| `KB_APP_BASE_URL` | gateway，私网 app 地址，不能使用公网回环代理 |
| `KB_OFFICE_UPSTREAM_URL` | gateway，示例 `http://office-documents` |
| `KB_OFFICE_CONFIGURATION_SECRET` / `KB_OFFICE_OUTBOX_SECRET` | app，与供应商文件一致 |
| `KB_OFFICE_GATEWAY_SECRET` | app/gateway，相同的独立服务秘密 |
| `KB_OFFICE_DEPLOYMENT_ID` | app，部署隔离标识，默认 kb |
| `KB_OFFICE_LAUNCH_SECONDS` / `LEASE_SECONDS` / `HEARTBEAT_SECONDS` / `ABSOLUTE_SECONDS` | app；各后缀均带 `KB_OFFICE_`，默认 60/45/15/1800 |
| `KB_OFFICE_MAX_PER_USER` / `MAX_TOTAL` / `MAX_FILE_BYTES` | app；各后缀均带 `KB_OFFICE_`，默认 3/100/52428800 |
| `KB_OFFICE_MAX_CONNECTIONS` / `MAX_MESSAGE_BYTES` / `MAX_RESOURCE_BYTES` | gateway；各后缀均带 `KB_OFFICE_`，默认 100/4194304/104857600 |
| `KB_OFFICE_AUTHORIZATION_TIMEOUT_SECONDS` / `KB_OFFICE_UPSTREAM_TIMEOUT_SECONDS` | gateway，默认 3/30 |

供应商内部缓存保留 1 小时，每 5 分钟清理，默认单实例缓存 tmpfs 限 1 GiB；会话绝对期限 30 分钟。该临时格式是供应商原生阅读协议，不生成 PDF 或打印件；doc/xls/ppt 不进入此流程。缓存到期不等同于立刻物理擦除，重建容器清空 tmpfs；容量满时请求安全失败。不能备份缓存、开放缓存静态目录或将其挂到 CDN。

## 多副本与回退

应用副本共享 Redis、秘密和部署标识。网关按 `/s/{SID}` 粘滞，示例使用 Nginx 一致性哈希；网关重启或迁移丢失本机传输绑定时拒绝旧 polling/升级，用户重新打开。供应商缓存按每会话随机 key 隔离；扩展供应商副本需要固定会话到同一供应商及对应内部缓存，不应直接轮询多个独立实例。示例仅配置单供应商实例。

关闭功能时先将所有 app 与 gateway 的开关设为 false 并更新实例；新读取、保活与授权均拒绝，已建立连接在逐消息/下一次空闲核对时终止，网关实例停止会立即断开连接。浏览器卸载 iframe、停止保活并尽力发送最长 3 秒的关闭请求；关闭结果未知不恢复显示。会话与额度按 45 秒活动租约（待交接 60 秒）回收，绝对期限提供最终上限，不需要清空共享登录 Redis。必要时停止供应商并重建其临时缓存，勿删除业务对象存储或数据库。

PDF/文本预览、独立下载、上传、解析、审核与发布不依赖 Office 开关；无数据库迁移或 worker 操作需要回滚。前端遇到旧后端 404/405 会显示不可用，PDF/文本可由原受保护二进制接口继续读取，未知协议不会猜测外部直链。恢复时重新核对配置、网络和身份，用户主动打开生成新会话；不续用停用前的 Cookie 或旧 key。

审核或引用未提供文件名时，兼容读取只接受原预览入口实际返回的 PDF/文本 MIME，其他内容不读取为 Blob。文件入口返回 415 或非预览 MIME 时显示服务不支持或目标不可访问，不误报已确认支持的 Office 格式不可用；文件入口自身的 401/403/404 仍执行现有拒绝清理，不能仅凭新增端点的 404 判定文档被删除。
