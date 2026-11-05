# 检索评测结果采集

`kb-eval-collector` 是独立 Java 21 CLI，通过现有 `GET /api/auth/me` 核对身份，按输入顺序串行调用
`POST /api/search`，将最终有序结果交给离线工具 [`kb-eval`](retrieval-evaluation.md)。运行时仅依赖 JDK 和 Jackson，
不启动 Spring、前端或中间件，不读取数据库，不提供算法阶段采样、自动登录、重试或断点续采。

## 输入与配置

四个必需参数为 `--dataset <dataset.json> --cases <cases.jsonl> --config <配置文件> --output <新目录>`。
未知、重复、缺值参数均拒绝。独立 `--help` 返回 0，不读取文件、环境变量，不联网或写目录。

数据集与标注沿用[离线评测 v1 格式](retrieval-evaluation.md#数据格式)。`labelsFile` 相对数据集清单目录解析。
标注必须非空，每条包含唯一 caseId、tags、expectedEmpty 和 relevance；等级为 0–3，同一文档版本不可重复。
expectedEmpty 为 false 必须存在正相关标注，为 true 不允许正相关标注。额外顶层标注字段被忽略，相关项的未知字段拒绝。
查询、身份或筛选不同的场景分别使用唯一 caseId，caseId 集合必须与标注完整一一对应。

`cases.jsonl` 每行只接受以下三个字段，顺序也是采集和结果写出顺序：

```json
{"caseId":"q001-staff","identity":"staff","request":{"query":"示例报销说明","fileType":"PDF"}}
```

request 接受 query、spaceId、categoryId、uncategorized、tagIds、fileType、updatedFrom、updatedTo，
规则见[搜索筛选接口](search-filtering-api.md#主搜索请求)。query 保持原值，非空白、无 NUL 或孤立代理字符，
最多 8,000 个 Unicode code point。ID 为精确 JSON 正整数，最大 Long.MAX_VALUE，不接受字符串、小数或溢出值。
spaceId/categoryId 可省略或为 null；uncategorized 必须为布尔值，与非空 categoryId 互斥；tagIds 至多 20 个唯一正整数，
null 非法；fileType 仅允许已定义的七种类型，null 非法；时间必须带时区，双边存在时起点严格早于终点。
不接受 candidates/topK、用户、角色、向量或算法模式；数量由运行配置统一注入。

配置形态如下，所有逻辑标识与身份别名采用 `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`：

```json
{
  "schemaVersion": 1,
  "runId": "baseline",
  "configurationId": "baseline-v1",
  "endpointId": "evaluation",
  "baseUrl": "https://evaluation.example.invalid/kb",
  "identities": {
    "staff": {"tokenEnv": "KB_EVAL_TOKEN_STAFF", "expectedUserId": 7}
  },
  "candidates": 50,
  "topK": 20,
  "connectTimeoutMs": 5000,
  "requestTimeoutMs": 15000,
  "maxResponseBytes": 4194304,
  "allowHttpLoopback": false
}
```

schemaVersion、三个逻辑标识、baseUrl 和非空 identities 必填。tokenEnv 是环境变量名，使用
`[A-Za-z_][A-Za-z0-9_]{0,127}`，配置不接受令牌值。expectedUserId 是必须匹配的内部用户正整数。
配置、用例、请求拒绝未知字段；所有输入拒绝重复 JSON 属性、非法 UTF-8、尾随 JSON 和空 JSONL 行。

| 限制 | 默认值 | 允许范围 |
| --- | --- | --- |
| candidates / topK | 50 / 20 | 分别为整数 1–200，允许 topK 大于 candidates |
| connectTimeoutMs | 5,000 ms | 1–60,000 ms |
| requestTimeoutMs | 15,000 ms | 1–300,000 ms，且不小于连接超时 |
| maxResponseBytes | 4 MiB | 1 byte–64 MiB，按实际正文计数 |
| 数据集清单 / 配置 | 固定 | 各 1 MiB |
| 标注 / 用例 | 固定 | 各 32 MiB，每行 128 KiB，每批最多 10,000 场景 |
| JSON 深度 / 编码请求 | 固定 | 32 / 128 KiB |

路径仅接受明确的本地文件，拒绝 URI、UNC 网络共享和通配符。输入只读，全部读取和本地校验先于网络调用；
解析、执行与 SHA-256 使用同一份冻结字节，换行与空白也影响文件摘要。采集中修改输入不会改变当次请求或摘要。
请保留该次原始数据集与标注；后续评测会核对实际标签字节，不能用修改后的标注代替。

## 凭据、身份与网络行为

由受控终端或密钥管理设施向采集进程注入 tokenEnv 指定的环境变量。不要把令牌写入 JSON、脚本、命令行参数或版本库。
工具不会打印令牌、环境变量值或远程异常。每批冻结全部被引用身份的令牌；缺失、空白或不适合作为 HTTP 头的值在联网前失败。
未引用身份不读取凭据、不发请求。同一用户换会话令牌不会改变场景绑定摘要。

全部引用身份必须先通过 `/me`，之后才开始任何搜索：HTTP 200、整数 code=200、user.userId 匹配、enabled=true、
mustChangePassword=false。第二身份失败也不会先采集第一身份。搜索仍逐次由服务端鉴权，中途 401/403 立即停止。
工具不登录、续期、改密、注销或换身份，不提交客户端用户/角色声明。

baseUrl 默认仅接受 HTTPS，拒绝 URL 用户信息、查询串、fragment、路径回退及歧义编码。合法 `/kb` 前缀保留，
实际路径为 `/kb/api/auth/me`、`/kb/api/search`。仅显式 allowHttpLoopback=true 时允许 localhost、127.0.0.1 或 ::1 的 HTTP；
这不开放其他 HTTP 主机。客户端不继承 Cookie、Authenticator、代理、追踪头；请求仅包含对应 satoken、JSON 协议头和必要的 HTTP 传输头。

独立 CLI 在初始化 JDK HttpClient 前固定 `disableRetryConnect=true`、`enableAllMethodRetry=false`、
`redirects.retrylimit=1`，使用 HTTP/1.1 并关闭自动重定向。相关设置为进程级 JDK 属性，工具按独立进程交付，
不作为共享 JVM 的网络库使用。属性含义见 [JDK 21 网络属性说明](https://docs.oracle.com/en/java/javase/21/core/java-networking.html)。
3xx、401、403、429、503、断连和超时均不重放；Retry-After 不触发等待重试，不向 Location 转发令牌。

单次绝对期限覆盖连接、响应头、正文读取和 JSON 校验；持续到达小块不会重置期限，无 Content-Length 仍限制实际字节。
超时或中断取消本地请求、关闭响应流并停止后续场景，不能保证服务端或模型供应商的执行、计费同时停止。
客户端限制不代表服务端容量承诺；后端更小的限制导致拒绝时，采集同样停止。

## 响应、输出与诊断

搜索仅接受 HTTP 200、UTF-8 application/json（可省略 charset）、无压缩或 identity 内容编码、整数 code=200 和数组 data。
有有效筛选时还要求唯一且精确的 `X-Search-Filter-Version: 1`；无筛选，以及 null ID、false、空标签数组组成的无效限制，
不强制版本头。无法确认筛选支持时直接失败，不删除条件重发。

`data=[]` 是合法空结果；204、错误包装、缺少或 null data、重复键、尾随数据、损坏命中都属于失败。
每条命中必须有精确正整数 docId/versionId/chunkId，重复三元组也失败。整个有序数组完整保留，
同版本不同分块保留其位置，不按 score 排序、不合并版本、不补版本、不跳过坏项。标题、正文、score 和远程 message 不输出。

只在全批成功时发布新目录中的三个文件：

| 文件 | 内容 |
| --- | --- |
| run.json | 严格沿用 v1 字段，sourceType=RECORDED，原数据集身份、实际 labelsSha256、resultsFile=results.jsonl |
| results.jsonl | 每场景一条 caseId、status=OK、完整有序三元组 hits、latencyMs |
| collection.json | schemaVersion=1、工具/逻辑配置标识、UTC 起止时间、数量和限制、场景数、引用身份核对结果与摘要 |

latencyMs 使用单调时钟，从搜索发送前计至完整响应校验结束；包含慢正文，排除身份预检、输入和写盘，
是客户端观测值，不表示数据库或算法阶段耗时，也不是吞吐指标。

元数据记录 datasetSha256、labelsSha256、casesSha256、resultsSha256 和 scenarioBindingSha256。
场景绑定按 caseId 排序，包含 caseId、完整 request、expectedUserId；递归按对象键字典序序列化，数组保序，
UTF-8 JSON 和十进制整数保持精度。摘要排除令牌、环境变量名、身份别名、端点和运行标识，数量设置单独记录。
查询、筛选或预期用户改变会改变绑定摘要，不会将这些查询/筛选明文放入输出。

元数据固定标记 resultSemantics=FINAL_RESPONSE、degradationStatus=UNOBSERVED、snapshotVerification=DECLARED_ONLY。
configurationId 和 corpusSnapshotId 是运行方声明，不能证明实际模型/索引配置、语料或 ACL 已冻结；
成功数组也不能证明向量或 rerank 没有降级。仅输出端点逻辑名，不保存 baseUrl、用户名、角色或完整 `/me`。
现有评测器不读取 collection.json；比较前需自行核对场景绑定摘要、身份、数量及环境一致性。

已有输出目标在联网前拒绝。结果逐条写入同父目录 `.kb-eval-collector-` 前缀的本次暂存目录，
全部成功后补齐元数据和运行清单，再用不覆盖目标的目录移动发布。竞争创建目标、写入或清理失败不会输出成功。
只清理本次暂存固定文件；不修改输入、已有目标或其他批次。崩溃残留暂存目录不是完成结果，不得用于评测，不自动恢复或续采。

| 采集器退出码 | 含义 |
| --- | --- |
| 0 | 三个产物全部发布；stdout 仅 COLLECT_COMPLETE |
| 2 | 参数、输入、配置、凭据缺失、输出或本地处理失败 |
| 3 | 身份核对、HTTP、期限、中断或响应协议失败 |

stderr 格式为 `COLLECT_ERROR <固定类别> role=<受控角色> line=<行号> field=<固定字段>`，不输出实际路径、URL、查询、
令牌或异常 cause。行号为 0 表示非 JSONL 位置。清理失败可能将退出码改为 2，但不会把失败变为成功。

## PowerShell 使用

在仓库根目录构建两个独立 JAR：

```powershell
mvn -pl kb-eval-collector -am package '-DskipTests'
java -jar '.\kb-eval-collector\target\kb-eval-collector-0.1.0-SNAPSHOT.jar' --help
```

合成文件在 `kb-eval-collector/examples/demo`。域名 `.invalid`、用户和文档 ID 均为示意，不能直接用于实际采集。
使用前准备与真实评测环境对应的数据集、标注、两个部署配置及受控会话环境变量。固定语料和权限条件，
每次使用唯一 runId 和尚不存在的输出目录。下面仅为运行方使用说明，本次开发没有执行真实采集：

```powershell
java -jar '.\kb-eval-collector\target\kb-eval-collector-0.1.0-SNAPSHOT.jar' `
  --dataset '.\kb-eval-collector\examples\demo\dataset.json' `
  --cases '.\kb-eval-collector\examples\demo\cases.jsonl' `
  --config '.\kb-eval-collector\examples\demo\baseline.config.json' `
  --output '.\target\collected-baseline'
$baselineExit = $LASTEXITCODE
if ($baselineExit -ne 0) {
  throw 'baseline 采集失败'
}

java -jar '.\kb-eval-collector\target\kb-eval-collector-0.1.0-SNAPSHOT.jar' `
  --dataset '.\kb-eval-collector\examples\demo\dataset.json' `
  --cases '.\kb-eval-collector\examples\demo\cases.jsonl' `
  --config '.\kb-eval-collector\examples\demo\candidate.config.json' `
  --output '.\target\collected-candidate'
$candidateExit = $LASTEXITCODE
if ($candidateExit -ne 0) {
  throw 'candidate 采集失败'
}

$baselineMeta = Get-Content -LiteralPath '.\target\collected-baseline\collection.json' -Raw | ConvertFrom-Json
$candidateMeta = Get-Content -LiteralPath '.\target\collected-candidate\collection.json' -Raw | ConvertFrom-Json
if ($baselineMeta.scenarioBindingSha256 -ne $candidateMeta.scenarioBindingSha256) {
  throw '场景绑定不同，不能直接比较'
}

java -jar '.\kb-eval\target\kb-eval-0.1.0-SNAPSHOT.jar' `
  --dataset '.\kb-eval-collector\examples\demo\dataset.json' `
  --run '.\target\collected-baseline\run.json' `
  --run '.\target\collected-candidate\run.json' `
  --baseline baseline --k 5,10,20 `
  --thresholds '.\kb-eval\examples\demo\thresholds.json' `
  --output '.\target\collected-comparison'
$evaluationExit = $LASTEXITCODE
```

门禁示例阈值不是生产质量门槛。评测器退出 0 表示通过或未配置门禁，1 表示质量未达标但完整报告已发布，2 表示本地错误。
采集成功的 0 不代表质量通过。重新采集或比较需要新的输出目录，不覆盖原运行与原标注。

## 开发验证范围

测试仅使用进程内传输/响应流替身、可控时钟、合成数据和临时文件。未启动前端、业务服务、HTTP 监听端口、
数据库、Redis、MinIO、Docker 或模型；未编写文档内容断言测试。示例和使用文档通过人工契约核对交付。
构建和 `--help` 检查不执行真实采集，不能证明真实网络、部署兼容性、模型效果或权限快照已验收。

明确测试白名单与实际执行记录见[本变更验证记录](../openspec/changes/archive/2026-11-05-collect-retrieval-evaluation-results/verification.md)。
