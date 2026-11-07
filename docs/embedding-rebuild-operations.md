# Embedding profile 与重建运维

## 配置清单

app 与 Worker 都读取 `kb.embedding.profiles`，不再使用单 provider 默认配置。清单中 `profile` 为不可变语义，`connection`
为连接配置；两侧应部署相同的 profile ID 和语义。连接地址、密钥、超时和批量大小可以分别配置，不影响 fingerprint。

以下为外部 YAML 配置示例；模型名、来源和 revision 必须替换为部署方确认的实际模型身份，不能仅凭同为 1024 维认定兼容。

```yaml
kb:
  embedding:
    profiles:
      - profile:
          profile-id: search-model-r1
          provider: remote
          model-source: publisher/model
          model: deployed-model-name
          model-revision: immutable-revision
          dimension: 1024
          distance: cosine
          query-transform-version: identity-v1
          document-transform-version: identity-v1
          query-task-type: query
          document-task-type: document
          adapter-version: embeddings-v1
        connection:
          endpoint: ${KB_MODEL_ENDPOINT}
          api-key: ${KB_MODEL_API_KEY}
          timeout-seconds: 60
          batch-size: 16
```

- provider 支持 `remote` 和仅限显式 dev 的 `local`；生产无默认清单，缺少必填项或未知 provider/adapter/transform 拒绝启动。
- `identity-v1` 查询/文档均原样输入；配套的 `e5-query-v1` / `e5-document-v1` 分别加 `query: ` / `passage: `
  前缀。仅使用模型发布者要求的配套规则，不能混配。task 类型固定为 `query` / `document`，用于本地角色选择，不向协议添加未支持的任务参数。
- `embeddings-v1` 向配置的 base URL 追加 `/embeddings`，发送 model、input、dimensions。响应 model
  如存在必须与请求模型精确一致；本适配器不接受隐式别名。响应缺省 model 不表示实际权重已验证。
- 本地 profile 的 model 与 adapter 均为 `local-hash-v1`。两个模块的 `application-dev.yml` 提供相同的显式开发清单；它不是生产可用模型。
- 原 `kb.search.embedding.*` 和 `kb.embedding.provider/base-url/api-key/model/dimension/batch-size/timeout-seconds`
  已废弃，应迁移到清单；不要同时保留旧配置。模型语义改变须新建 profile ID，不能改写既有登记。
- 清单应先部署到查询与 Worker 两侧，再申请切换；清单不决定数据库 active profile，也不会自动认领旧向量。
- `kb.embedding.probe.interval-ms` 默认为 30000，`kb.embedding.probe.lifetime-ms` 默认为
  90000，必须为正且间隔短于有效期。探测仅发送固定合成文本；记录绑定实例启动
  UUID、角色、指纹和协议版本。只有两侧均有新鲜成功记录才算目标就绪，探测不在数据库事务内运行。

Worker 每个 profile 默认有独立 10 秒预算，发送固定 `embedding readiness probe`，仍应用该 profile 的 document 转换。按 profile ID 顺序逐项探测，每轮完成后等待 `interval-ms` 再开始下一轮；调度以 100 ms 的轻量检查推进游标，槽位忙时直接跳过，不创建待执行队列。每个 profile 的预算从本次实际执行开始，业务处理不占用探测槽位。

只有截止前完成校验并成功交接的探测才写 ready。到期后返回的合法向量只会得到本次不就绪结果，不能覆盖为成功；结果最多保存一次，保存失败不会重写或延长旧记录。实际网络或取消动作仍在运行时，`channel=PROBE` 的 occupied／lingering 保持占用，后续 profile 等待实际退出；未刷新记录按原有效期自然失效。关闭后停止新探测。

PowerShell 中仅设置配置文件位置，密钥由部署环境注入，不将其写进命令历史：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'prod'
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:D:/kb-config/embedding.yml'
```

## 首次初始化

### Worker 调用资源限额

Worker 的载荷和执行限额使用独立的 `kb.worker.embedding` 配置，默认值如下。这些参数不参与 profile 指纹，调整时不重标已有向量；超出校验范围、零、负数、小数或错误类型会阻止启动。

| 配置后缀 | 环境变量 | 默认值 | 允许范围 |
| --- | --- | ---: | --- |
| `max-request-bytes` | `KB_WORKER_EMBEDDING_MAX_REQUEST_BYTES` | 1048576 | 1–16777216 字节 |
| `max-response-bytes` | `KB_WORKER_EMBEDDING_MAX_RESPONSE_BYTES` | 4194304 | 1–67108864 字节 |
| `max-json-depth` | `KB_WORKER_EMBEDDING_MAX_JSON_DEPTH` | 64 | 1–128 层 |
| `rebuild-generation-timeout-seconds` | `KB_WORKER_EMBEDDING_REBUILD_TIMEOUT_SECONDS` | 300 | 1–3600 秒 |
| `probe-timeout-seconds` | `KB_WORKER_EMBEDDING_PROBE_TIMEOUT_SECONDS` | 10 | 1–60 秒 |

普通解析沿用 `kb.worker.task-timeout-seconds` / `KB_TASK_TIMEOUT_SECONDS`，默认 900 秒。各 HTTP 子批还受 profile 的 `connection.timeout-seconds` 和所属执行剩余时间约束；条数上限仍来自 `connection.batch-size`。初始值应按供应商实际延迟和响应分布校准，调用参数不用于证明模型权重或向量语义。

### HTTP 子批与数据库批次

发送前按 `connection.batch-size` 与实际 JSON 字节双限制拆批。例如条数上限 16，但字节预算只能容纳前 10 条，则顺序发送 10 条和余下 6 条；后一个子批使用同一执行剩余时间。编码预算包含文档前缀、UTF-8、转义和全部协议字段，恰好达到上限允许发送。

所有单条输入先检查可容纳性，任何一条连同协议包络仍超限，会在首次发送前失败并保留原文。响应中的 index 只属于本次 HTTP 子批，系统校验后映射回完整输入位置。

HTTP 子批不是数据库提交点：一份文档或一个重建领取批次的全部子批成功后，才交给原原子提交。后续子批失败时不会发布成功前缀；重试可能重新调用此前成功但未提交的子批，已提交的其他重建批次仍保留。响应超限后不会自动拆小重发。

### HTTP 回收与调用诊断

每个 HTTP 子批使用隔离的 JDK 客户端，禁用重定向、Cookie／用户身份及内部追踪传播；Worker 在最早的环境处理阶段固定 `jdk.httpclient.disableRetryConnect=true`、`jdk.httpclient.enableAllMethodRetry=false`。这些 JDK 属性作用于 Worker 进程内的其他 JDK HTTP 客户端，公共模块不修改它们。每个子批结束后关闭客户端并确认本地终止，再继续下一批；正常调用也会承担客户端回收开销。

非 200 响应在正文订阅入口取消，不消费供应商错误体。成功响应使用实际字节限界及严格 UTF-8 JSON，拒绝压缩编码、重复字段、尾随根值和超深结构。关闭或中断句柄完成不代表客户端已经退出，等待期间仍占用原通道。

`kb.worker.embedding.calls` 与 `kb.worker.embedding.call.duration` 按固定 `purpose=PARSE/REBUILD/PROBE`、`outcome` 记录一次完整 Embedding 调用（包含其全部 HTTP 子批与向量校验），不是每个 HTTP 子批各计一次。结果包括 SUCCESS、TIMEOUT、NETWORK、UPSTREAM_RETRYABLE、REQUEST_LIMIT、RESPONSE_LIMIT、INVALID_RESPONSE、AUTH_REJECTED、HTTP_REJECTED、LEASE_LOST、LEASE_UNKNOWN、SHUTDOWN 和 INTERNAL_ERROR。时长桶为 0.1、1、10、60、300、900 秒；指标不携带 profile、任务、文档或用户身份。

`kb.worker.embedding.execution.stops{purpose="PARSE|REBUILD|PROBE",reason="TIMEOUT|..."}` 在已经开始的执行首次选定停止原因时立即递增，所有标签组合预注册为零。原因取上述有限停止原因，不含 SUCCESS／INTERNAL_ERROR。该计数独立于网络返回和取消排空线程：调用或关闭动作仍阻塞时，已能同时看到停止原因与 occupied／lingering；重复超时、失租、关闭及最终退出都不重复计数。空领取和成功交接不计为停止。

stops 表达本地执行停止，calls／call.duration 表达完整 Embedding 调用的返回结果和耗时；二者不直接相加。解析还可能在 Embedding 返回后、整条流水线交接前超时，此时一次调用成功与执行 TIMEOUT 可以同时成立。持久恢复动作仍按任务资格与既有失败分类决定。

离线回归以真实 BodyHandler、内存响应流和受控客户端验证正文需求、编码、取消与终止等待；启动策略检查验证初始化顺序和 JDK 属性。它们没有执行真实网络重试或连接终止验收，也不代表真实供应商停止计算或计费。

### 初始化执行

Worker 每实例有一个普通解析／重建共用业务槽位，以及一个独立探测槽位。没有业务槽位时不领取任务，不增加尝试次数；HTTP 子批顺序执行。生成已超时、失租或取消，不等于底层网络及关闭动作已经退出，槽位一直保留到相关工作实际结束。

`kb.worker.embedding.execution.occupied{channel="BUSINESS|PROBE"}` 表示实际占用，`kb.worker.embedding.execution.lingering` 表示生成逻辑已终结但尚未释放的占用，空闲时均为零。生成成功后仍在提交的短窗口也可能计入 lingering；它不是数据库失败计数。持续 lingering 应结合线程与依赖状态排查，不能靠增加新线程或重复提交来证明资源已恢复。取消和截止调度独立运行，业务通道的关闭阻塞不会占用探测通道的取消线程。

由新 kb-app 执行 V15 迁移后，控制行为 `UNINITIALIZED`、epoch 为 0，旧分块的 `embedding_profile_id` 保持 NULL。app
在迁移完成后只追加登记 profile；ID 指纹冲突或存储维度不匹配时拒绝启动。Worker 必须在 app 完成登记后启动，仅校验既有
profile，不负责迁移或认领旧数据。

空库也不能根据部署清单自动激活，必须通过后续管理重建流程完成显式选择及零目标校验。重启只读取数据库既有模式和
epoch，不重置状态；服务对象重新构造的 Stub 单测不等价于真实崩溃恢复验证。首次协议升级需停止不识别 profile/epoch 的旧 app 和
Worker，不支持新旧二进制混部。

## 查询降级

查询固定生成时的 profile 和 epoch，模型调用与 rerank 不占用数据库事务；检索短事务共享锁定控制行后再次复核。维护中、代次改变或实例缺少
active profile 客户端时仅执行 BM25；控制状态读取或加锁失败返回错误，不凭缓存 ACTIVE 放行。向量 SQL 只召回匹配 profile
的当前版本，BM25 不依赖 profile，两路保留发布、归档、ACL 与密级谓词。内部旧裸向量参数非 null 时明确拒绝，HTTP 仍只接受文本问题。

## 普通解析身份

普通领取在短事务内先共享锁控制行，仅 ACTIVE 且本地有兼容 document 客户端时增加 attempts；同事务保存 profile、epoch 和独立递增的
claim token，并更新版本解析状态。等待维护不消耗尝试次数。流水线按领取身份选择注册表客户端，结果保留生成 profile
和执行资格；发布顺序为控制行、任务、文档/版本、分块，旧 epoch/token 或不兼容结果不得发布。

两侧生产调用链均使用 profile 注册表，旧单客户端类不再注册为 Spring Bean。向量统一检查 index 完整唯一、数量、1024
维、有限值和非零数值；提交使用生成结果中的 model/profile，不从当前默认客户端补标。分块空间及密级仍读取提交时的文档事实。

QUIESCING 协调器按任务 ID 分批失效在途普通领取，按 `(taskId, claimToken)` 记录一次 `MAINTENANCE_DEFERRED`
，仅退还这次带身份领取的一次预算，不清零既有失败信息。无身份的旧协议领取仅失效，不猜测退款；上线前仍须停止旧二进制。等待队列不做逐项退款。确认没有
running 任务后才转入 REBUILDING。

心跳、失败写回和耗尽巡检先取控制共享锁，再操作任务；维护中不做续租、失败或死信写回。迟到回调必须匹配保存的
token/epoch/profile，过期心跳不复活租约。用户重试及管理员死信重放仍可入队，但先取控制锁，保持任务、死信、版本锁序，推进独立
token，不能绕过 ACTIVE 领取门禁。删除维持任务到文档锁序且不反向取控制锁；搬迁和升密维持文档到分块锁序。这些顺序仅做静态和
Mock 验证，尚未验证真实并发和回滚。

## 只读管理 API

文档进度 `GET /api/documents/{documentId}/progress?versionId=...` 仍使用原有 view 权限；维护期间待领取或在途任务增加
`waitingReason=EMBEDDING_MAINTENANCE`，不覆盖真实失败信息，已 indexed 的版本仍显示 indexed。上传及新版本创建继续保存原件、版本和
pending 任务，不替换旧 current_version；上传回执仅表示入队成功。新文档尚无当前版本时，应使用上传回执中的 versionId
查询等待进度。合法重试仍需 edit 权限，维护中重试只入队，不触发解析。

接口使用既有会话，要求启用的用户及主部门和精确的 `embedding:read` 资格。`embedding:rebuild` 不自动授予读取资格，两项权限也不授予正文
view/download。V16 仅向迁移时已有 ROLE_ADMIN 一次性授权，后续启动不补回撤权。

| 请求                                          | 返回的 data                                                                       |
|---------------------------------------------|--------------------------------------------------------------------------------|
| `GET /api/admin/embedding/profiles`         | 已登记的无密钥 profile 描述、fingerprint、query/document 两侧联合就绪状态                         |
| `GET /api/admin/embedding/status`           | state、activeProfileId、targetProfileId、epoch、runId、waitingReason、localEmbedding |
| `GET /api/admin/embedding/rebuilds/{runId}` | 作业状态、版本、总数/成功/跳过数、下次重试时间和白名单错误码                                                |

成功为 200，未认证 401，停用主体或无权限 403，未知作业 404。响应沿用 `R` 包装；不返回 endpoint、密钥、对象路径、正文或执行租约。非
ACTIVE 状态的等待原因为 `EMBEDDING_MAINTENANCE`。

`localEmbedding` 是本次响应所在 app 实例的 embedding 状态，含 `instanceId`（与周期探测相同的启动 UUID）、`profileId`、
`fingerprint`、`epoch`、`ready` 和固定 `reason`；与 `/profiles` 的集群双侧就绪汇总不是同一个概念。负载均衡下不同响应可能来自不同实例，应按返回的
instanceId 识别，不能用一台实例的结果代表全体。

原因包括 `READY`、`MISSING_PROFILE`、`PROBE_UNAVAILABLE`、`EMBEDDING_MAINTENANCE`、`CONTROL_UNAVAILABLE`。只有当前 ACTIVE
身份在本机有兼容查询客户端，且本机查询合成探测仍新鲜成功时才报告 ready。没有新 profile 时保持 BM25 并报告
MISSING_PROFILE，绝不借用旧模型；失效/失败的探测不会由别的实例成功记录替代。周期调度和查询/状态读取都会刷新本机所见控制事实，迟到的较旧
epoch 不覆盖较新状态。控制读取失败时管理状态/搜索仍返回服务错误，不以缓存 ACTIVE 放行。

此状态是观测信号，不是新的检索放行缓存，也不是整站存活/流量就绪门禁；维护中仍应允许
BM25、上传和原有读接口。未新增公开健康端点或修改网关权限。部署补齐清单通常需要重启实例，新启动 UUID 必须重新探测；同一实例切换回已配置的
active 身份时也重新按该身份与探测有效期判断。

`/api/admin/embedding/**` 由网关既有 `/api/admin/**` 路由转发至 kb-app，不走 AI 流式路由。网关会话认证与 app
强制改密拦截保持生效，持有写权限也不能绕过改密要求。

## 验证范围

2026-11-07 的 Worker 调用限界实现与三路径组件回归见 `docs/embedding-offline-validation.md` 和 `docs/embedding-openspec-verification.md` 同日章节；本次统一白名单为 241 项全部通过，结果不与历史验证数量累加。

本次仅执行无外部服务的单元测试、编译和静态检查，不启动数据库、Redis、对象存储、Docker 或真实模型，不执行部署与迁移。HTTP
使用进程内传输层 Stub，控制器使用无监听服务的测试客户端。真实事务回滚、锁互斥、崩溃恢复及检索效果尚未验证，不能将单测通过作为生产验证完成。

重建执行器、实际覆盖扫描和最终激活门禁已接入；仅完成编码和离线验证不代表可直接上线。部署前仍须独立评估真实数据库迁移、锁等待、恢复及检索效果，本次不执行这些外部环境验证。

## 独立重建租约

Worker 的 `kb.embedding.rebuild.lease-seconds` 默认 300（30-3600），`batch-size` 默认 16（1-100），`max-attempts` 默认
3（1-10）；非法值拒绝绑定。重建不领取或重置普通解析任务。清单封存后才允许领取 run；run 和 item 各自递增 token，批次绑定控制
epoch、目标 profile 和实例 UUID。数据库时钟决定租约到期及退避时间，到期不得续租或释放新持有者资格；接管只选 PENDING 或失效
RUNNING 项，不选成功/跳过项。返回行按 chunk ID 排序，不依赖 UPDATE RETURNING 的自然顺序。

运行时续租必须在独立短事务中先锁控制行再操作 run/item，任何影响行数不匹配都视为失去资格，不能用普通任务 attempts 充当
token。配置和时钟仓储 Stub 已覆盖到期、续租、退避、接管及迟到释放；不代表真实多实例互斥已验证。

## 断点续建与故障预算

重建通过共享业务通道处理模型调用，独立调度每 2 秒尝试领取及续租；旧执行失去资格后不再续租或写回，不与普通解析共用失败预算。模型
429、408、5xx、网络超时和数据库临时错误按 30、60、120 秒指数退避，最多 900 秒；每个 item 的预算由 `max-attempts`
控制。非法响应、永久故障或预算耗尽进入 BLOCKED，保存 REBUILDING 恢复阶段；维持 BM25 和普通解析等待，不提供即时回滚。

生成阶段在读取分块前启动默认 300 秒单调截止，读取、摘要核对、编码、所有子批和向量校验都计入其中；续租成功不延长这个截止。全部生成完成后原子交给提交阶段，关闭生成计时器；提交仍在原短事务中核对 run/item token、epoch、目标 profile 和当前正文。生成超时但资格仍有效时按原退避处理，失租或续租结果未知则停止输出并依持久租约恢复。

公开接口继续使用粗粒度错误码。`REQUEST_LIMIT`、`RESPONSE_LIMIT`、`INVALID_RESPONSE` 对应 `INVALID_RESPONSE` 并立即 BLOCKED；`AUTH_REJECTED`、`HTTP_REJECTED` 对应 `MODEL_UNAVAILABLE` 并立即 BLOCKED。`TIMEOUT`、`NETWORK`、`UPSTREAM_RETRYABLE` 也对应 `MODEL_UNAVAILABLE`，但允许任务级退避。通过 Worker 固定 reason 日志和调用指标区分这些分支，供应商正文不进入诊断。

人工恢复推进 epoch 和独立 token，仅对 FAILED/RUNNING/PENDING 项重置本轮 attempts，保留
total_attempts、错误历史、正文摘要以及所有成功/跳过记录。成功项不会再次领取或调用模型；远程调用成功但数据库提交前退出的项会重新生成，可能重复计费，不承诺
exactly-once。仓储 Stub 重建服务的单测仅验证续建分支，未验证真实进程崩溃和持久恢复。

## 发起与恢复 API

`POST /api/admin/embedding/rebuilds` 接收 `profileId`、非负整数 `expectedEpoch`、`reason` 和可选 `requestId`；
`POST /api/admin/embedding/rebuilds/{runId}/resume` 接收非负整数 `expectedVersion`、`reason` 和可选 `requestId`。请求标识也可放入
`X-Request-Id`；至少提供一处，两处必须完全一致，长度为 1–128 个字符。原因去除首尾空白后为 1–500 个字符，禁止填写正文或凭据。额外字段（包括
endpoint、apiKey、模型覆盖）一律拒绝。

两接口均要求启用主体的独立 `embedding:rebuild` 权限。202 回执含
operationId、runId、profileId、state、version、epoch、replayed；相同请求重放返回首次回执并设
replayed=true，仍重新鉴权。标识绑定操作域、操作者、目标、预期版本和规范化原因；他人复用或内容冲突为 409，未知 profile/非法输入为
400，未知作业为 404，缺少新鲜 query/document 双侧 readiness 为 503。状态未满足或版本过期同样为 409。

发起先核验双侧周期合成探测记录，再在控制独占锁内重查；持锁阶段不调用模型。开始进入 QUIESCING 并推进 epoch；恢复仅回到原作业保存阶段，推进
epoch、version 和执行 token，成功及跳过 item 不重置。首次回执与 `kb_embedding_operation`
追加操作审计同事务保存，失败不返回成功；这里仅验证异常传播与事务声明，未验证真实数据库回滚。

## 升级与操作步骤

1. 按独立运维流程备份数据库与配置，确认维护窗口暂停新内容发布。将目标 profile 清单先部署到新 app/Worker 两侧，不删除历史
   profile 登记。
2. 首次升级先排空流量，停止全部不支持 profile/epoch 的旧 app 和 Worker，确认旧事务结束；必要时按运维流程撤销旧实例数据库连接。不得新旧二进制混部，不得回退到不认识新权限的旧
   app。
3. 由新 app 执行 V15、V16、V17；Worker 不运行 Flyway。V15 旧向量身份保持 NULL；V16 仅一次性给已有 ROLE_ADMIN 绑定新权限；V17
   仅增加目标清单封存标记。不要执行 Flyway repair 或手工改写旧向量身份。
4. app 登记 profile 后启动新 Worker，再恢复新入口。UNINITIALIZED 时 BM25 和上传可用，普通解析等待；空库也须显式发起重建，不会靠重启自动
   ACTIVE。
5. 有读取资格的操作者检查目标 ready，再由有写资格的操作者发起；进度查询必须另有 `embedding:read`。后续日常切换不必停用同协议实例，但清单必须先部署。
6. 等待 SUCCEEDED/ACTIVE。VERIFYING 会扫描实际范围，检查 1024 维、有限非零向量、目标身份、输入摘要、漏项和旧资格；最终事务重查覆盖与新鲜双侧
   readiness，成功后才启用目标。最终事务设置 2 秒锁等待和 8 秒语句上限，失败保持维护模式。

以下 PowerShell 示例仅供部署后操作，本文编写和静态检查时不执行请求。会话值由环境安全注入；不要把凭据写进命令历史。重试同一次请求必须复用原
JSON/requestId，不要在每次网络重试时重新生成 ID。

```powershell
$base = 'https://kb.example.invalid/api/admin/embedding'
$headers = @{ satoken = $env:KB_SESSION_TOKEN }
$profiles = Invoke-RestMethod -Method Get -Uri "$base/profiles" -Headers $headers
$status = (Invoke-RestMethod -Method Get -Uri "$base/status" -Headers $headers).data
$startBody = @{
    profileId = 'search-model-r1'
    expectedEpoch = [long]$status.epoch
    reason = 'scheduled model rebuild'
    requestId = [guid]::NewGuid().ToString()
} | ConvertTo-Json
$receipt = (Invoke-RestMethod -Method Post -Uri "$base/rebuilds" -Headers $headers -ContentType 'application/json' -Body $startBody).data
$runId = $receipt.runId
$run = (Invoke-RestMethod -Method Get -Uri "$base/rebuilds/$runId" -Headers $headers).data
```

修复原因后，仅对 BLOCKED 的同一作业恢复；不能中途换目标、取消后恢复向量、强制激活，亦不能只改回旧配置实现回滚。

```powershell
$run = (Invoke-RestMethod -Method Get -Uri "$base/rebuilds/$runId" -Headers $headers).data
if ($run.state -ne 'BLOCKED') { throw 'Run is not BLOCKED' }
$resumeBody = @{
    expectedVersion = [long]$run.version
    reason = 'dependency restored'
    requestId = [guid]::NewGuid().ToString()
} | ConvertTo-Json
$resumed = Invoke-RestMethod -Method Post -Uri "$base/rebuilds/$runId/resume" -Headers $headers -ContentType 'application/json' -Body $resumeBody
```

## 阻塞处置与历史限制

| 错误分类                                | 处置原则                                                                  |
|-------------------------------------|-----------------------------------------------------------------------|
| MODEL_UNAVAILABLE / RETRY_EXHAUSTED | 修复模型连接、配额或服务后检查双侧探测，再恢复原作业；不能返回远程错误体                                  |
| INVALID_RESPONSE                    | 核查受控模型身份、index、数量和向量形状；禁止放宽校验接受错误结果                                   |
| CONTENT_CHANGED / COVERAGE_INVALID  | 检查是否有绕过维护协议的写入。经独立审查的数据修复后再恢复；不得改标旧向量伪造覆盖                             |
| MISSING_CHUNKS                      | 当前版本非 NULL 却无分块；由有既有删除资格者删除异常文档使其退出范围，或执行独立、经审查的数据修复；本能力不会清空版本指针或重跑解析 |
| READINESS_EXPIRED                   | 修复目标清单、实例及合成探测；至少一台 query 和一台 document 有新鲜记录后恢复                       |
| DATABASE_ERROR                      | 排查数据库可用性、锁争用和扫描耗时；状态保持维护，按原阶段恢复                                       |

无 current_version
的排队上传不是损坏数据。目标包含所有未删除文档当前版本的已有分块，包括文档/空间归档内容；不按操作者正文权限裁剪，也不因此授予正文权限。历史版本向量与来源身份保持原样。恢复旧模型必须在本轮完成后另发一轮完整重建；灾难恢复是独立的备份恢复流程，不是即时回滚。

## 指标与审计

Worker 每 30 秒采样 `kb.embedding.mode{state}`、`kb.embedding.error{category}`、`kb.embedding.progress{result}`、
`kb.embedding.events.persisted{operation}` 和 `kb.embedding.metrics.available`。标签只含固定枚举；多实例上的持久事件数是同一数据库事实，聚合用
max，不要跨实例求和。available=0 时其他 gauge 为上次采样，不能将旧 ACTIVE 采样当成运行时放行依据。

START/RESUME 按操作域和 requestId 去重，BLOCKED/SUCCEEDED 按 runId 与状态 version 形成唯一事件标识；均在状态事务内写入
`kb_embedding_operation`。后台事件可通过 run 关联创建者和目标 profile，携带
epoch、时间和固定原因；失败重试不会吞掉审计失败来伪造成功。日志仅输出固定状态和错误分类，不输出正文、查询词、endpoint、密钥或管理员
reason。

app 仅在实例就绪原因变化时记录 `embedding instance readiness reason=...`，不把 profile、UUID
或查询内容作为日志字段，不增加高基数指标。激活协调本身不调用模型，因此只依赖独占控制锁下的持久目标 ID/fingerprint 和集群双侧新鲜
readiness，不要求协调 Worker 本机有目标客户端；缺配置的 Worker 仍不能领取目标生成或新 profile 的普通解析任务。真正缺少双侧有效探测时仍进入
READINESS_EXPIRED 阻塞，不因本修复取消覆盖、租约或审计门禁。
