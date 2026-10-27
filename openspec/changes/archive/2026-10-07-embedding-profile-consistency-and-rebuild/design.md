# Design

## Context

动机和用户确认的范围见 `proposal.md`。这是跨 `kb-common`、`kb-app` 与 `kb-worker` 的一致性协议及数据迁移变更，需要完整设计。

- `kb-app/.../search/service/SearchService.java` 生成查询向量后调用 `SearchMapper.xml`；向量分支目前不区分模型身份，HTTP
  入口不接收裸向量，但内部兼容方法仍接收字符串。
- `kb-worker/.../parse/DefaultParsePipeline.java` 使用单个 embedding 客户端；`ChunkPublisher.java` 删除并重写版本分块，从当前
  Bean 获取 `embed_model`，并在事务内更新 `current_version_id`。
- `kb_doc_chunk` 为 `VECTOR(1024)`，有 HNSW 索引与 `(version_id, chunk_idx)` 唯一约束；只检索当前内容版本。历史版本读取不依赖向量。
- 普通任务领取会增加尝试次数；任务恢复、巡检及死信维护各自可以修改状态。单改领取循环不能阻止迟到发布或维护期间错误地耗尽重试。
- 已有发布、删除、搬迁、升密的文档行锁与权限约束必须保留。新增控制锁要位于现有锁序之前，不能反向嵌套。
- 当前生产检查未保护 local embedding，远程批次响应只排序 index，未保证 index 完整唯一。现有主要测试是离线契约测试，真实 PG
  烟测可因环境缺失而跳过。

## Goals / Non-Goals

**Goals:**

- 把数据库维护状态和 profile 身份变成跨实例事实，而非部署环境变量之间的约定。
- 用短事务栅栏阻止旧查询向量与新数据比较，并阻止过期任务提交；网络模型调用不进入数据库长事务。
- 在不替换正文分块的前提下完成可恢复重算，保留 BM25、权限及引用标识。
- 让维护失败和普通解析失败独立可见，重启与重复请求不改变已提交事实。

**Non-Goals:**

- 不实现蓝绿向量、即时回滚、全量历史重建、在线模型编辑、内容重新解析或跨维度 DDL。
- 不保证上游模型 exactly-once 计费，不把应用心跳或配置 hash 当成模型服务实际权重未变的证明。
- 不建设集成测试入口或前端，不执行集成测试、端到端测试、真实迁移或多进程部署验证；仅交付无需外部服务的单元测试、静态检查和操作说明。数据库及模型相关业务功能仍需完整实现。

## Decisions

### D1. 语义 profile 与连接配置分离

在 `kb-common` 放置纯 JDK profile/向量值对象和统一校验逻辑。两侧部署加载 `kb.embedding.profiles` 清单，不再独立依靠默认
provider 决定向量身份；本地配置各自创建 query/document 客户端注册表。

语义字段包括 `profileId`、`provider`、`modelSource`、`model`、`modelRevision`、`dimension=1024`、`distance=cosine`、
`queryTransformVersion`、`documentTransformVersion`、配套 task/input 类型、`adapterVersion`。查询与文档 transform
可以不同，但必须属于同一 profile 的配套规则；首期提供明确的已实现 transform，未知规则不得仅存字符串后忽略执行。按确定的字段排序与编码生成
`fingerprint`。

`endpoint`、`credentialRef`/外部密钥、超时和 batch size 归部署连接配置，不进入语义指纹、不通过管理 API 编辑或返回。改变模型语义必须换
profileId；密钥轮换不要求重建。模型来源或 revision 不能在同一 ID 下改写，endpoint 变更必须由部署者保证仍对应同一模型身份，探测不能数学证明模型一致。

`kb-app` 启动在迁移后仅追加登记无密钥描述；遇到已有 ID 不同 fingerprint 拒绝启动，不覆盖数据库。Worker 校验已登记身份，不自动成为
profile/active 状态权威。旧 profile 定义为历史引用保留，不能因本地清单删除而清空数据库。

替代方案：只比较 model/dimension 无法区分同名不同来源和预处理；将完整 URL/密钥纳入身份会把轮换误当模型迁移，并增加泄密面。

### D2. 配置和响应的明确拒绝边界

启动前校验 provider、必需语义字段、已实现 transform、1024 维、cosine、合法正数超时与批量。生产清单禁止 `local`/`local-hash`；显式
dev 可以使用 1024 维本地 profile。非法值不得悄悄回落默认值。

迁移后检查数据库向量列与 profile 维度匹配。处于 ACTIVE 的新实例无法装配 active profile 时不得成为就绪实例；已运行实例遇到数据库切换至本地缺失
profile 时，app 仅 BM25 并标记 embedding 不就绪，Worker 不领取普通任务。全库状态读取失败时返回服务错误，不使用缓存的 ACTIVE
放行；数据库仍可查询但没有兼容客户端时允许受控 BM25。

返回值统一包含 `profileId/fingerprint` 和向量。查询及文档向量都校验数量、维度、有限数值和非零范数。远程批次必须恰有 `0..n-1`
的唯一 index；缺失、重复、越界、额外结果一律拒绝。上游返回模型标识时按适配器声明的明确映射核验，不能接收无关模型；本地赋予
profile 是受控调用来源，不是从浮点值推导身份。

生产旧环境变量明确提示迁移，不静默猜测 profile。SDK/HTTP 适配器仍独立于语义描述；优先复用纯 JDK 公共校验，不给 `kb-common`
引入 Spring。

### D3. 数据模型及单作业边界

使用下一条可用 Flyway 迁移，当前预计 V15；不得修改 V1-V14。

| 结构                          | 核心内容与约束                                                                       |
|-----------------------------|-------------------------------------------------------------------------------|
| `kb_embedding_profile`      | 不可变 profileId、fingerprint、无密钥语义描述、创建时间；ID 唯一                                  |
| `kb_embedding_control`      | 单行；state、active_profile_id、target_profile_id、epoch、run_id；epoch 单调递增          |
| `kb_doc_chunk` 扩展           | 可空 `embedding_profile_id` 外键，保留原 embed_model；旧行置 NULL 表示来源未验证；保留 VECTOR(1024) |
| `kb_embedding_rebuild`      | runId、目标、创建者、state/version、resume_phase、执行租约/领取代次、尝试与退避、计数、脱敏原因、时间            |
| `kb_embedding_rebuild_item` | `(run_id, chunk_id)` 唯一；doc/version、正文摘要、状态、attempt/claim token、下次尝试时间；不复制正文  |
| `kb_embedding_operation`    | 操作域/requestId 唯一；操作者、规范化输入摘要、首次回执、前后状态与审计时间，同事务追加                             |
| `kb_embedding_readiness`    | 实例启动 UUID、app/worker 职责、profile fingerprint、最近合成探测结果与时间、协议版本；仅作就绪信息，不代替提交栅栏   |

重建 item 引用逻辑分块标识，不通过级联删除丢掉作业历史。分块被删或不再属于目标范围时标记
SKIPPED；对账依据当前文档/分块事实。作业和控制行只允许一个未完成全库重建，BLOCKED 也占用槽位。普通任务和重建任务的 claim
token 独立单调推进，不能用会因维护补偿而减少的 attempts 作为 token，防止旧执行资格被重复使用。

选择全库单作业，因为当前只有一个 active profile，且用户接受全局 BM25。首期不做空间级各自切换。

### D4. 持久状态机和幂等管理 API

```text
UNINITIALIZED --> QUIESCING --> REBUILDING --> VERIFYING --> ACTIVE
                       ^           |             |
                       |           v             v
                       +-------- BLOCKED <-------+
                                  |
                                  +--> saved phase

ACTIVE --> QUIESCING
```

UNINITIALIZED 为首次升级/空库初始化状态，BM25 可用、上传可用、普通解析暂停。通过一次显式重建选择初始
profile；空库无分块时仍执行客户端探测与校验，再 ACTIVE。没有靠重启自动选择 profile 的路径。

建议 API 基路径为 `/api/admin/embedding`：

| API                             | 资格                  | 行为                                                                  |
|---------------------------------|---------------------|---------------------------------------------------------------------|
| `GET /profiles`                 | `embedding:read`    | 已登记 profile 的无密钥摘要及就绪状态，不返回 endpoint                                |
| `GET /status`                   | `embedding:read`    | 全局模式、active/target、epoch、当前作业及等待原因                                  |
| `POST /rebuilds`                | `embedding:rebuild` | `{profileId, expectedEpoch, reason, requestId?}`，通过预检后 202 返回 runId |
| `GET /rebuilds/{runId}`         | `embedding:read`    | 状态/version、分块计数、退避与受限错误码，不返回正文/文件路径                                 |
| `POST /rebuilds/{runId}/resume` | `embedding:rebuild` | `{expectedVersion, reason, requestId?}`，只恢复该 run 的固定目标，202          |

同 profile 也允许重建，用于修复未知/损坏向量。未完成 run 禁止新作业、目标替换、取消或强制激活；回到旧模型是在当前作业完成后再发起另一轮完整重建。

requestId 从请求体或 `X-Request-Id` 取得，两处不一致拒绝；长度 1-128，reason 去空白后 1-500，未知字段、负版本、缺输入、未知
profile 返回 400。当前状态不允许或版本过期返回 409；未认证 401，无资格 403，未知 run 404，预检不可用
503。合法匹配重放先于乐观版本冲突判断，但仍重新鉴权；同标识不同操作者/输入 409，不返回他人回执。操作记录与控制修改同事务提交，后台完成另记去重事件。

预检在数据库事务外用固定非敏感合成输入同时验证 app query 与 Worker document 客户端。清单提前部署到参与服务，至少一台就绪
app 和一台就绪 Worker 对目标 fingerprint 有新鲜成功探测；缺失时不离开 ACTIVE/UNINITIALIZED。最后提交请求时重新校验
epoch/profile/探测有效期；探测过期后重新进行，不长时间持锁等待网络。

选择短期 readiness 记录而不是新增公开 Worker 管理 HTTP 入口；两侧有界周期探测已配置 profile 并按实例启动 UUID
更新记录，探测间隔与有效期独立配置且前者短于后者。真实查询和写入仍逐次校验，本地缺失清单的实例不能绕过协议。run 的 version
只在可观察状态转移或控制命令成功时递增，不因心跳或每批计数变化而制造恢复冲突。

### D5. 查询与状态转换使用短事务栅栏

控制行锁约定：正常受控数据库操作先 `FOR SHARE`，状态转换先 `FOR UPDATE`。查询 embedding 和 rerank 均在锁外；禁止给整个
`SearchService.search` 包含 HTTP 的方法加事务。

一次查询：读取主库 state/epoch/profile，若 ACTIVE 则用固定 profile 生成查询向量；随后在专用短事务内共享锁定控制行，重新比较
state、epoch、fingerprint。只有仍为 ACTIVE 且一致，才执行带 `embedding_profile_id` 条件的向量 SQL；否则该次查询改为仅
BM25。SQL 仍保留 published/current_version、归档、ACL、密级全部条件；BM25 不增加 profile 过滤。没有可见范围的短路不变。

共享锁覆盖完整 SQL 执行与结果物化，不覆盖 rerank/响应发送。启动 QUIESCING 的独占控制锁会等待先前向量 SQL 结束；还在模型
HTTP 阶段的旧请求无需无限等待，它稍后会因 epoch 不符而走 BM25。内存旧结果可以完成原请求，不撤回已授权内容，但不会与新向量比较。

去掉正常路径中裸字符串向量的兼容绕过，或将其仅保留为显式失败的旧接口；新内部调用必须携带 profile/epoch。SQL
主库与锁的连接必须属于同一事务，不以读副本或独立连接分开读取状态。

替代方案：只在调用模型前检查会有 TOCTOU；跨 HTTP 持有共享锁会让慢模型阻塞整个维护切换；等待全实例心跳不能防僵尸实例。

### D6. 普通解析的暂停、在途收敛和发布

领取入口在同一短事务内先共享锁控制行，仅 ACTIVE 且本地具备 active profile 时才能增加普通任务 attempts 并领取。领取保存
profile、control epoch 和本次 attempt/claim token；流水线结果携带这些身份，发布不再临时读取另一个 Bean 的 model。

开始维护先独占锁控制行，切 QUIESCING 并推进
epoch；此前已进入发布事务的任务会先完成，此后所有旧结果不得发布。普通领取停止；巡检、错误写回和手工重试路径必须理解维护状态。待处理任务不改变失败状态，手工
retry 可以依既有资格入队，但不能绕过暂停。

在途任务因维护取消时使用独立 MAINTENANCE_DEFERRED 路径：按 claim token 幂等释放/失效租约，回到
pending，保留此前真实失败历史，仅补偿此次明确被维护中止的计数。不得直接清零
attempts，亦不得让通用巡检把这次中止落为死信。已提交真实失败与维护取消通过相同锁序仲裁；只能有一个结果。失联任务由协调器失效旧
token 并记录维护收敛，迟到的心跳、失败写回和发布均不生效。

QUIESCING 必须确认所有旧普通 claim 已完成或已栅栏隔离，再建立当前版本目标清单。不会为等待队列逐个增加重试。解析进度 DTO
增加非敏感 `waitingReason=EMBEDDING_MAINTENANCE`，通过全局状态推导；已 indexed 的旧版本保持 indexed，现有上传回执不伪装为已发布。

统一锁序：控制行 -> 普通任务行 -> 文档/版本 -> 分块；涉及文档集合按 ID 排序。重建用控制行 -> run/item -> 文档 ->
分块；状态转换在独占控制锁下处理。现有删除仍 task -> document，搬迁/升密仍 document ->
chunks，二者不反向获取控制锁。实现需静态审核所有恢复/发布写路径的锁序与事务边界，并通过 Mock 检查调用顺序；本次不运行真实 PG
并发测试，不能据此宣称数据库锁互斥或无死锁已验证。

### D7. 当前版本分块的独立重建

收敛后按 `doc.status <> deleted AND chunk.version_id = doc.current_version_id` 创建 item，包含归档、非 published
当前版本；不按操作者正文权限裁剪全库维护范围。当前版本为 NULL 的新上传不入清单，历史分块原状保留。没有分块但有
current_version
的异常文档记录并阻止伪完成；维护中不能靠重放普通解析修复，因为发布已暂停。需由有既有删除资格的操作者删除异常文档使其退出范围，或经过独立、经审查的数据修复后恢复校验；本能力不得静默删除、清空版本指针或自行重新解析。无
current_version 的排队文档不算损坏。

复用 PG 租约/退避模式，新增独立作业与 item 处理，不复用会删除分块的 `ChunkPublisher`。以固定 chunkId 顺序分批，读取原正文及摘要，在事务外调用目标
document 客户端。返回后短事务共享锁控制行，核验 run/epoch、有效租约与 claim token，按 ID 锁文档并重查未删除、版本与正文摘要。

只更新 `embedding`、`embedding_profile_id`、`embed_model`，同时提交 item 成功和计数；不能更新
content、来源、space_id、security_level、内容版本指针或文档状态。已退出范围的 item 标记
SKIPPED。没有按约定暂停却出现正文变更时拒绝当前结果并进入可诊断阻塞，不能把旧文本向量写入新文本。

删除在模型调用期间发生：提交重查后跳过；删除在重建提交后发生：原删除路径生效。搬迁/升密按现有文档锁串行，只更新它们自己的字段，重建永不回填调用前快照中的权限冗余值。

原有 HNSW 随逐批 UPDATE 维护，无需每次重建索引 DDL；关注 WAL/表膨胀，完成后按运维策略 ANALYZE。不引入第二份向量表。

### D8. 失败恢复、完成校验和激活

重建执行器有稳定 runId 与递增 lease/claim token；新持有者只接续未提交 item。超时、429、暂时故障有界退避，超限或永久配置/响应错误进入
BLOCKED，并记录 resume_phase。BLOCKED 不恢复向量召回或普通解析，也不占用普通解析死信的语义。

人工 resume 重新鉴权/探测，按版本推进控制 epoch 与执行 token，保留成功 item，记录恢复事件；只有已经提交成功的批次保证不因恢复重调。HTTP
已成功但提交前崩溃的批次可能再次计费，状态和回执不得承诺 exactly-once。

全部 item 处理后进入 VERIFYING。分批读实际范围并验证：目标 profile、1024 维、有限值、非零范数、范围覆盖、没有未完成 item
或仍可发布的旧 claim。随后在独占控制锁的有界事务内进行最终覆盖 SQL 复核并检查目标 query/document 就绪凭据未过期；通过后原子标记
run SUCCEEDED、active=target、清空 target、state=ACTIVE、推进 epoch 和写审计。验证超时/失败进入 BLOCKED，决不能按进度 100%
直接激活。

不在激活事务内做远程探测；需等待或过期时退出事务重新探测。零目标空库也执行相同控制检查。归档/取消归档不改变目标包含关系，删除只收缩范围，普通发布已暂停，因此不存在无穷增量追赶。

激活后客户端仍每次按 active profile 选择。新协议但缺目标配置的旧实例只能 BM25/停止领取并发告警；不能因 heartbeat
存活就默认兼容。真正旧二进制不受此规则保护，必须按迁移步骤停用。

### D9. 权限、审计和可观测性

新增 `embedding:read` 与 `embedding:rebuild` 两项功能权限，独立于角色 code、空间管理、文档密级和解析死信权限；read 不蕴含
rebuild，反向也不隐含 read。首期按全局运维动作授权，不自动授予正文 view/download；作业查询只给汇总和受限错误码。

迁移向当时已有 ROLE_ADMIN 一次绑定两项权限；新引导角色纳入目录，既有角色后续撤销不被启动恢复。拒绝未知功能权限的既有启动约束继续生效，因此旧
app 也不能无计划回滚。

记录启动/恢复请求、阻塞、完成事件，至少含
actor/run/profile/epoch/requestId/时间/原因，操作回执与状态原子提交。脱敏异常禁止正文、查询词、endpoint、令牌、密钥和远程响应体。指标只使用状态、结果和固定错误分类等低基数标签，不以
runId/chunkId/profileId 形成无界时序。

新增管理路径确认被网关路由并强制改密拦截；不要假定现有 `/api/admin/spaces` 路由自动覆盖所有 admin 路径。文档读取和 `kb-ai`
语料入口不改变。管理员的自由文本 reason 不发往模型，按现有审计策略受限存储而不直接写应用日志；接口文档明确禁止在 reason
填入正文或凭据。

## Risks / Trade-offs

- [维护窗口随文档量和模型限流增长] -> 发起前展示目标分块估算，设置批量/退避/超时；BLOCKED 时明确新内容无法发布，不提供虚假可用性承诺。
- [原地覆盖后没有即时回滚] -> 失败续建原目标；保留必要模型访问能力直至完成。灾难恢复属于另行备份恢复流程，不能只切回旧配置。
- [云端同名模型暗中变更] -> 使用可追踪 revision 与变更流程；合成探测只能验证协议、可达性与向量形状，不能证明语义等价。
- [控制行争用和最终复核扫描较重] -> 共享锁仅覆盖短数据库操作，网络在锁外；最终核验设置 statement/lock
  timeout，失败保持维护模式。本次仅以 Mock/Stub 检查超时分支，真实数据规模下的锁等待和扫描性能未验证，不属于本次任务范围。
- [升级旧实例绕过栅栏] -> 首次升级排空入口并停全部旧 app/Worker，必要时撤销旧实例 DB 连接；不承诺混部升级。
- [维护中止与真实失败的计数竞态] -> 领取 token、一次性维护事件与事务锁仲裁，并用 Mock/Stub 回归 sweep/heartbeat/failure
  分支，不泛化重试次数退款；真实并发仲裁效果未验证。
- [新增 profile 过滤导致 HNSW 过滤召回下降] -> 保留权限和身份约束，不靠取消过滤改善召回；本次仅检查 SQL
  谓词与参数，真实执行计划及当前/历史混合样本的检索效果未验证，不属于本次任务范围。
- [离线验证范围有限] -> Mock/Stub 和静态检查只证明被检查的逻辑、调用顺序及结构，不能证明真实事务回滚、锁互斥、原子提交或持久恢复；外部环境验证未纳入本次交付，不将单测通过视为生产验证完成。

## Migration Plan

以下为实际部署时的运维步骤说明，本次仅编写迁移脚本与操作文档并静态核对，不执行备份、部署、数据库迁移或端到端验收。运行时目标探测、范围校验和激活门禁仍是必须实现的业务功能；真实升级结果未验证。

1. 部署前备份并盘点旧向量、文档当前版本和未完成普通任务；明确本次会暂停新内容发布，预配置 query/document 配套 profile 与凭据。
2. 短暂排空流量并停止所有旧 kb-app/Worker，确认其数据库事务结束；kb-ai/gateway 不允许继续路由到旧 app。与升级后的日常重建维护窗口区分。
3. 仅由新 kb-app 执行新增迁移，登记语义 profile。旧 `embedding_profile_id` 全部保持 NULL，原 embed_model、正文、分块与索引不删。控制行初始化
   UNINITIALIZED，不能根据旧模型名称批量认领。
4. 新 app/Worker 就绪后恢复入口：BM25、上传与既有读路径可用，普通解析暂停。使用管理 API 显式对所选 profile
   发起首次重建；空库亦通过该流程激活。
5. 完成目标探测、当前版本重建和校验后进入 ACTIVE；验证新上传正常解析，历史向量未被改标，角色权限撤销未被恢复。
6. 后续同协议模型切换可在服务运行期间使用上述维护流程；运行时 profile/epoch 栅栏仍是唯一正确性依据。
7. 失败保留维护状态并续建；不运行 Flyway repair，不回退迁移，不启动不识别新权限/profile
   的旧二进制。灾难恢复须协调恢复数据库与匹配配置，不能将它宣传为本能力的即时回滚。

## Verification Strategy

本次与 `tasks.md` 保持一致，仅执行无需外部服务的编译/打包、单元测试和静态检查。不新增或运行集成测试、端到端测试、真实数据库迁移及多进程部署验证，不启动或连接
PostgreSQL/ParadeDB/pgvector、Redis、MinIO、Docker 或真实模型服务，也不启动监听服务或完整业务应用。

- 纯单测覆盖 profile 不变性、配置拒绝、严格响应 index/数值；模型错误、迟到响应、429 和超时由进程内客户端传输层 Stub
  模拟，不使用监听端口的 HTTP fixture。
- HTTP 契约使用独立 MockMvc 或绑定控制器的测试客户端，Mock 身份、会话及服务依赖；覆盖权限、输入校验、幂等、状态码与脱敏，不启动网关、app
  或 Redis。
- 数据库、对象存储、时钟和模型使用进程内 Mock/Stub；用保存状态的仓储 Stub
  重新构造服务，检查状态机、维护计数、租约资格、成功批次续建及删除/搬迁/升密分支。此类模拟不等同于真实重启、崩溃恢复或多实例并发验证。
- 迁移和查询仅做 SQL/映射静态检查、无连接的 MyBatis 动态 SQL 渲染及参数断言；检查旧行 NULL 身份、一次性权限初始化、profile
  与权限谓词、字段白名单和既有 V1-V14 未改。事务与锁仅静态审核边界、检查 Mock 调用顺序及异常传播，不实际执行生产 SQL 或迁移。
- 构建和回归按明确的无外部服务单测列表或标签执行，排除已有集成、端到端及 PostgreSQL 烟测，不直接运行未筛选的 `mvn verify`
  ；记录实际命令及通过、失败、排除数量，不将排除项记为通过。

运行时目标预检、VERIFYING 范围扫描和激活前校验必须保留，不能以测试使用 Stub
为由省略。上述检查不能证明真实数据库回滚、锁互斥、无死锁、原子提交、持久恢复、执行计划或检索效果；这些真实环境验证不在本次交付范围内，必须在结果与运维说明中标记为未验证。
