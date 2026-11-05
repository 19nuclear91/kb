# 监控部署与运维

## 边界与固定版本

这是一套独立的单实例 Prometheus → Alertmanager → `kb-alert-notifier` → 钉钉链路，Grafana 只展示数据，`GF_UNIFIED_ALERTING_ENABLED=false` 禁用第二套告警引擎。业务应用不在本 Compose 中，不依赖或启动业务数据库、Redis。阈值、每条告警和面板解释见 [监控告警运维](monitoring-alerts-operations.md)，通知协议与参数见 [适配器运维](alert-notifier-operations.md)。

| 组件 | 固定版本 | 固定方式 |
| --- | --- | --- |
| Prometheus / promtool | 3.5.0 | `versions.env` 的 tag+manifest digest；CLI SHA-256 见 `versions.json` |
| Alertmanager / amtool | 0.28.1 | 同上；本地 CLI 核对 webhook timeout、max_alerts、credentials_file 与路由 |
| Grafana | 12.1.1 | `versions.env` 的 tag+manifest digest；provisioning/面板离线解析，不代表真实界面已验证 |
| Compose CLI | 2.39.2 | Windows amd64 独立可执行文件，版本和 SHA-256 固定；支持 `gw_priority` |
| 适配器运行时 | Temurin 21.0.8_9-jre-jammy | `notifier.Dockerfile` 固定基础镜像 tag+digest；Java 21、Spring Boot 3.3.5 |
| 自建适配器 | 本仓库 Maven 产物 | **尚未构建镜像，没有可宣称的真实镜像 digest**；部署必须输入发布后的完整 `KB_NOTIFIER_IMAGE=仓库:固定tag@sha256:实际digest` |

上游 manifest 响应及其内容 SHA-256、CLI 官方发行归档 checksum 已核对，来源 URL 在 `versions.json`。不使用 `latest`。更换组件版本时必须同时更新版本清单、校验和及对应配置契约，重跑离线验证；固定版本不是安全漏洞扫描或生产兼容性保证。

本变更只执行进程内测试和本地 CLI 静态检查。下文的镜像构建、启动、网络排障和轮换命令**仅为部署期说明，未在本变更执行**。不提供 HTTP 监听替身、容器测试、真实数据库/Redis、浏览器或真实钉钉验收入口。静态检查不证明 SQL、网络、文件权限映射、Grafana 渲染或消息送达。

## 1. 离线验证入口（PowerShell 7）

预先由受控渠道准备 `versions.json` 中三个 Windows amd64 工具，目录结构如下；脚本不下载工具，不使用容器运行工具：

```text
monitoring-tools/
  promtool-3.5.0/promtool.exe
  amtool-0.28.1/amtool.exe
  compose-2.39.2/docker-compose-windows-x86_64.exe
```

在仓库根目录运行，`ScratchDirectory` 必须是绝对临时目录：

```powershell
$tools = Join-Path $env:PI_SCRATCH_DIR 'monitoring-tools'
pwsh -NoProfile -File .\ops\monitoring\scripts\verify.ps1 -ToolDirectory $tools -ScratchDirectory $env:PI_SCRATCH_DIR
if ($LASTEXITCODE -ne 0) { throw '监控静态验证失败，检查报告中的逐步退出码' }
pwsh -NoProfile -File .\ops\monitoring\tests\deployment.tests.ps1 -TestMissingTools -ScratchDirectory $env:PI_SCRATCH_DIR
if ($LASTEXITCODE -ne 0) { throw '缺工具失败分支回归失败' }
```

普通环境没有 `PI_SCRATCH_DIR` 时，显式改为自己的临时绝对路径。每次在其中新建 `monitoring-verify-<随机ID>`，不覆盖已有配置或报告。输出 `verification-report.json` 包括每步命令、exitCode、passed/failed/not-executed 和总退出码；各命令 stdout/stderr 分开保存。缺工具记 127，依赖步骤未执行记 126，任何非零都令整体失败；不把跳过当通过。缺工具回归的子验证预期返回 1，回归脚本验证这些记录后才返回 0。

入口依次执行：固定二进制 SHA-256/版本检查、`config.tests.ps1 -WorkDirectory`、配置生成、`build-rule-tests.ps1 -OutputDirectory`、promtool 的 `check config`/`check rules`/`test rules`、amtool 的 `check-config`/`config routes test --config.file=... --verify.receivers=dingtalk`、`compose config --format json` 和部署运行模型正反例。路由按完整告警目录及其允许服务逐项检查；这是路由选择检查，**不是 Alertmanager 运行时抑制测试**。合成规则使用固定示例基线；`-SettingsPath` 指定部署设置时，另检查该设置生成的配置，不把示例时序当作部署阈值实测。

默认只在临时目录创建 `INVALID_STATIC_ONLY` 无效 secret，并注入 `registry.invalid/kb-alert-notifier:static-check-only@sha256:` 加 64 个零的镜像格式 fixture。它既不是真镜像，也不是可启动通知配置，**严禁把该目录用于部署**。可以传入 `-NotifierImage` 检查实际发布引用格式，但不会访问 registry 或证明镜像存在；报告始终 `deployable=false`。脚本只运行 Compose `version` 和不解析远端 digest 的 `config`，不连接 Docker daemon，不调用 `up`、`pull`、`build`、`run`、`--resolve-image-digests`。

Maven 包和进程内通知测试由通知模块回归负责，入口不重复执行 Maven、不带入现有外部依赖测试。需要另行保留其构建/测试证据；不能仅凭本脚本勾选 Maven 成功。

## 2. 部署输入与生成目录

以下均在部署窗口由操作者执行。使用固定项目名 `kb-monitoring`，以后升级/回退保持项目名不变，否则会创建另一套卷和实例。首先复制并编辑 `settings.json`：

```powershell
$repo = (Get-Location).Path
$monitoring = Join-Path $repo 'ops\monitoring'
$settings = Join-Path $monitoring 'settings.json'
if (-not (Test-Path -LiteralPath $settings)) {
    Copy-Item -LiteralPath (Join-Path $monitoring 'settings.example.json') -Destination $settings
}
# 用受控配置编辑器填写 settings.json，不解释执行其中的文本。
$release = Join-Path $monitoring ('releases\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$generated = Join-Path $release 'generated'
pwsh -NoProfile -File .\ops\monitoring\scripts\render-config.ps1 -SettingsPath $settings -OutputDirectory $generated
if ($LASTEXITCODE -ne 0) { throw '生成失败，不启动组件' }
```

必须把 `environment`、`deployment`、管理/Grafana HTTPS 根地址、预期服务和实际管理目标改成部署值；`*.example` 只是示例，适配器启动会拒绝这些根地址。根地址只允许 HTTPS 来源，不含路径前缀、凭据、查询参数或 fragment。部署 ID 代表一套业务数据库，同库副本共用，独立数据库不得混用。停用可选服务必须明确 `expected=false`；不要删除目标后把指标缺失解释为健康。

生成目录是平面文件集：`targets-*.json`、`prometheus.json`、`rules.json`、`alertmanager.json`、`alert-catalog.json`、`deployment.env`、三份 dashboard JSON 和两份 provisioning YAML，以及生成器所有权标记。不要手改 marker、混入其他文件，或把输出指定到源代码/secret 目录。每次发布使用新目录；普通配置不含 secret 内容。

Compose 将**整个生成目录**只读挂载至 `/etc/prometheus`，并设 `working_dir=/etc/prometheus`，使 `rules.json` 和 file_sd 相对路径正确。Grafana datasource UID 为 `kb-prometheus`、URL 为 `http://prometheus:9090`；provider 指向 `/var/lib/grafana/dashboards`。三个面板分别逐文件只读挂载，避免掩盖 Grafana 数据卷。

## 3. 凭据与最小权限

准备四个普通 UTF-8 无 BOM 文件，仅保存值本身：`notifier_token`（独立至少 32 字节 Bearer）、`dingtalk_token`、`dingtalk_secret`、`grafana_password`。不要写完整 webhook URL、`Bearer ` 前缀或引号；通知文件长度/字符边界见适配器文档。用受控 secret 管理器直接导出，不将内容放入命令参数、环境变量、shell 历史、日志、镜像或版本库。

建议放在仓库外的受控目录。以下是 Windows ACL 设置示意，先填写运行 Docker Desktop/文件共享的实际 Windows 账户；这不是 Linux 容器 UID。对于已有目录，先审阅现有 ACL 和所需备份账户，再决定执行：

```powershell
$secretRoot = Join-Path $env:ProgramData 'KBMonitoring\secrets'
[IO.Directory]::CreateDirectory($secretRoot) | Out-Null
$owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
$reader = Read-Host '实际 Docker 文件共享读取账户（域\账户）'
$acl = Get-Acl -LiteralPath $secretRoot
$acl.SetAccessRuleProtection($true, $false)
foreach ($rule in @($acl.Access)) { $acl.RemoveAccessRuleSpecific($rule) }
$inherit = [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'
$propagate = [Security.AccessControl.PropagationFlags]::None
$allow = [Security.AccessControl.AccessControlType]::Allow
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($owner, 'FullControl', $inherit, $propagate, $allow))
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new('S-1-5-18'), 'FullControl', $inherit, $propagate, $allow))
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($reader, 'ReadAndExecute', $inherit, $propagate, $allow))
Set-Acl -LiteralPath $secretRoot -AclObject $acl
# 由 secret 管理器导出上述四个文件，并人工确认每个文件继承该 ACL，没有额外宽泛授权。
```

Compose 文件型 secrets 是只读绑定，不是加密存储，也不替代宿主 ACL。不能依赖本地 Compose 的 secret `uid/gid/mode` 重映射：文件型源通常不支持这些重映射。部署时必须确认容器读取身份可访问：适配器 `10001:10001`、Grafana 472，以及 Prometheus/Alertmanager 镜像所用非 root 身份；不要通过 `Everyone`、`chmod 777` 或改 root 运行来解决。Windows Docker Desktop 文件共享映射与 Linux 宿主 POSIX ACL 不同，本次没有验证其运行时可读性。只读配置目录也需要非 root 读取，数据卷需要相应写权限。

`ops/monitoring/.gitignore` 仅防止常见本地 settings、生成物、secret 和环境文件被误纳入版本库，不能代替提交审查和凭据扫描；仓库外的 ACL 与备份同样要保护。

## 4. 发布适配器镜像与首次启动（仅部署期）

先由受控构建流程运行通知模块的离线 Maven package 和测试，取得 `kb-alert-notifier/target/kb-alert-notifier-0.1.0-SNAPSHOT.jar`。本 Dockerfile 不自动下载依赖、不夹带业务模块或 secret。为避免将整个工作区（包括本地 secret）发送给构建端，使用只含 JAR 的最小临时构建上下文：

```powershell
$jar = Join-Path $repo 'kb-alert-notifier\target\kb-alert-notifier-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw '缺少已验证的 Maven 产物' }
$buildContext = Join-Path ([IO.Path]::GetTempPath()) ('kb-notifier-build-' + [guid]::NewGuid().ToString('N'))
$jarDirectory = Join-Path $buildContext 'kb-alert-notifier\target'
[IO.Directory]::CreateDirectory($jarDirectory) | Out-Null
Copy-Item -LiteralPath $jar -Destination $jarDirectory
$tag = Read-Host '本次真实发布仓库和不可变 tag（不含 @digest，不得为 latest）'
docker build --file (Join-Path $monitoring 'notifier.Dockerfile') --tag $tag $buildContext
if ($LASTEXITCODE -ne 0) { throw '镜像构建失败' }
docker push $tag
if ($LASTEXITCODE -ne 0) { throw '镜像发布失败' }
$repoDigest = docker image inspect --format '{{index .RepoDigests 0}}' $tag
if ($LASTEXITCODE -ne 0 -or $repoDigest -notmatch '@(sha256:[a-f0-9]{64})$') { throw '缺少真实发布 digest' }
$notifierImage = "$tag@$($Matches[1])"
# 将 $notifierImage、JAR SHA-256、源码版本与基础镜像引用写入受控发布记录。
```

首次启用新规则前，先部署提供新增固定指标契约的 app、Worker、AI，核对版本与管理绑定；不要修改任务、额度或会话开关来迎合告警。Grafana/管理入口应由受控 HTTPS 认证代理提供，目标根地址与设置一致。创建只含路径及镜像引用的发布环境文件：

```powershell
$localEnv = Join-Path $release 'deployment.local.env'
@"
KB_MONITORING_CONFIG_DIR=$($generated.Replace('\','/'))
KB_NOTIFIER_IMAGE=$notifierImage
KB_NOTIFIER_TOKEN_FILE=$($secretRoot.Replace('\','/'))/notifier_token
KB_DINGTALK_TOKEN_FILE=$($secretRoot.Replace('\','/'))/dingtalk_token
KB_DINGTALK_SECRET_FILE=$($secretRoot.Replace('\','/'))/dingtalk_secret
KB_GRAFANA_PASSWORD_FILE=$($secretRoot.Replace('\','/'))/grafana_password
KB_MONITORING_SUBNET=172.30.80.0/24
KB_NOTIFIER_IPV4=172.30.80.10
"@ | Set-Content -LiteralPath $localEnv -Encoding utf8NoBOM
$compose = Join-Path $tools 'compose-2.39.2\docker-compose-windows-x86_64.exe'
$composeArgs = @('--project-name','kb-monitoring','--file',(Join-Path $monitoring 'compose.yml'),
    '--env-file',(Join-Path $monitoring 'versions.env'),'--env-file',(Join-Path $generated 'deployment.env'),'--env-file',$localEnv)
pwsh -NoProfile -File .\ops\monitoring\scripts\verify.ps1 -ToolDirectory $tools -ScratchDirectory $env:PI_SCRATCH_DIR -SettingsPath $settings -NotifierImage $notifierImage
if ($LASTEXITCODE -ne 0) { throw '静态检查失败' }
$modelPath = Join-Path $release 'compose.resolved.json'
& $compose @composeArgs config --format json --output $modelPath
if ($LASTEXITCODE -ne 0) { throw '实际部署 Compose 解析失败' }
pwsh -NoProfile -File .\ops\monitoring\tests\deployment.tests.ps1 -ComposeJson $modelPath -GeneratedDirectory $generated
if ($LASTEXITCODE -ne 0) { throw '实际部署安全约束不满足' }
& $compose @composeArgs pull
if ($LASTEXITCODE -ne 0) { throw '固定镜像拉取失败' }
& $compose @composeArgs up -d --no-build
if ($LASTEXITCODE -ne 0) { throw '启动失败，不视为已送达' }
```

操作前清除当前 shell 中同名 `PROMETHEUS_IMAGE`、`ALERTMANAGER_IMAGE`、`GRAFANA_IMAGE` 和 `KB_*` 部署变量的旧值，因为 shell 环境优先于 `--env-file`；只清除本次相关变量，不批量修改业务环境。上述 `config` 输出不含 secret 内容但含内网地址/文件位置，应作为受控运维记录。`up` 成功、本地 health UP 或抓取成功都不等于钉钉送达。

## 5. 网络定位与容量

- 监控网络 `internal=true`，默认 `172.30.80.0/24`。适配器两个端口都绑定其中 `172.30.80.10`，与 Compose 固定 IP 相同。若与宿主/VPN/其他网络重叠，必须同时调整 subnet 与 IP 并检查 IP 属于该网段且不占用网关。**不改成 `0.0.0.0`、`::` 或任意公网地址**。
- Alertmanager 和 Grafana 仅连监控网；适配器另外连接独立 `notifier_egress` 获取钉钉出站，Prometheus 另外连接 `prometheus_egress` 访问业务管理目标。`gw_priority=1` 明确出站默认网关。此处并非按域名强制限制的网络策略，生产防火墙还需限制出口；适配器生产 URL 固定且禁止重定向，不支持任意代理 URL。
- 部署主机须提供 Linux 容器运行环境及支持 `gw_priority` 的 Docker Engine API（部署前核对 Engine 版本和网络驱动）。仅有固定 Compose CLI 不代表 daemon 支持该字段；本次没有连接或验证任何 daemon。
- 不发布适配器 8090 或管理 9090，不配置业务网关路由。三个 UI 只发布到宿主 `127.0.0.1:9090/9093/3000`；远程通过受控管理入口，绝不把 Prometheus/Alertmanager 未认证 UI 直接暴露公网。
- 业务抓取目标是**管理端口**，不是公网网关。`host.docker.internal` 配合 `host-gateway` 只是地址映射，不保证应用在那个地址监听，也不保证 Windows/VPN/防火墙放行。容器的 localhost 是它自己；宿主应用仅绑定 127.0.0.1 时通常不可从容器访问。须为原服务配置具体可达私网管理绑定地址及独立管理端口，并限制来源。现有生产管理校验不能放宽。
- 排查顺序：先核对渲染目标和 environment/deployment/service 标签；再核对宿主实际管理绑定、DNS/host-gateway 对应地址、路由与防火墙；最后检查管理指标端点和采样状态。部署现场可用 `Get-NetTCPConnection` 查看已有监听、`Get-NetRoute` 查看路由，但从宿主可达不能证明从容器可达。本变更没有执行这些网络检查或建立任何监听。
- 默认 Prometheus 保留 15 天，由 `settings.retentionDays` 生成；按抓取序列数、磁盘容量和查询需求调整。Prometheus `/prometheus`、Alertmanager `/alertmanager`、Grafana `/var/lib/grafana` 各有独立持久卷。磁盘/数据库底层容量 exporter 不在本次范围，操作者仍需主机容量管理、备份和时钟同步。

## 6. 升级、凭据轮换与回退（仅部署期）

### 升级与重载

保留旧源码/镜像引用、旧 settings、完整生成目录、发布环境文件和持久卷备份；用新 release 目录生成并离线检查，不原地覆盖挂载文件。通知目录是启动时加载，Grafana provisioning 和文件绑定也不应假定可无损热替换。对业务指标先升级应用，再启用依赖新契约的规则。更新 `$composeArgs` 指向新发布文件后：

```powershell
& $compose @composeArgs pull
if ($LASTEXITCODE -ne 0) { throw '升级镜像拉取失败' }
# 同一项目同一机器人，必须先停旧适配器，禁止滚动重叠或 --scale notifier=2。
& $compose @composeArgs stop notifier
if ($LASTEXITCODE -ne 0) { throw '旧适配器未停止' }
& $compose @composeArgs up -d --no-build --force-recreate
if ($LASTEXITCODE -ne 0) { throw '升级失败，按发布记录回退' }
```

这是有短暂停机的单实例方案。Alertmanager 持久状态尽量保留，重启后适配器内存 60 秒限流窗口重置；频繁重启或多份 Compose 项目共享同一个机器人会绕过总额度，禁止这样部署。Alertmanager 承担重试，回执丢失可能重复，不能承诺恰好一次。

### 凭据轮换

入站 Bearer 必须让 Alertmanager 和适配器使用同一文件版本；机器人 token/签名 secret 成对维护。轮换前先停止 Alertmanager 与适配器，由 secret 管理器原子替换文件并保留/重新确认最小 ACL，然后**重新创建**两个容器，使文件型 bind 的 inode 更新也能生效。不要靠关闭认证/签名过渡，不把新旧凭据打印比较：

```powershell
& $compose @composeArgs stop alertmanager notifier
if ($LASTEXITCODE -ne 0) { throw '轮换前停止失败' }
# 在此由 secret 管理器更新文件及 ACL。
& $compose @composeArgs up -d --no-build --force-recreate notifier alertmanager
if ($LASTEXITCODE -ne 0) { throw '凭据轮换后重建失败' }
```

适配器不热读 secret，单纯修改文件不会更新内存。Grafana 的 `GF_SECURITY_ADMIN_PASSWORD__FILE` 是初始管理员引导配置；已有持久数据库时修改此文件并重启**不会自动重置已保存的账户密码**。已有账户通过 Grafana 的受控认证账户管理流程轮换，同步 secret 管理器后重建 Grafana；不要删除数据卷强制“重置”。

### 回退监控

先停对应通知路由/组件，避免切换目录或版本时继续发送。记录旧 `$composeArgs`，将其切回旧 compose/versions、deployment.env、generated 和真实 notifier digest；按组件文档确认数据格式可向后兼容，必要时从停机备份恢复，而不是盲目让旧 Grafana/Prometheus 打开新格式数据。

```powershell
& $compose @composeArgs stop alertmanager notifier
if ($LASTEXITCODE -ne 0) { throw '通知组件未停止，禁止继续回退' }
& $compose @composeArgs stop prometheus grafana
if ($LASTEXITCODE -ne 0) { throw '数据组件未停止，禁止继续回退' }
# 此处从受控发布记录恢复旧 $composeArgs；项目名保持 kb-monitoring。
& $compose @composeArgs up -d --no-build --force-recreate
if ($LASTEXITCODE -ne 0) { throw '监控回退失败' }
```

只停止组件不改变业务数据。**不执行 `down --volumes`、`down -v`、volume prune 或删除 named volumes**。若只是停用监控，`stop` 即可；Alertmanager 静默/通知状态、指标历史和 Grafana 数据的持久性仍受各组件保留范围约束。

### 应用回退到旧指标版本

**先停用新契约规则，后回退应用**；否则旧版本缺少质量/开关 gauge 会被误判。保留通用 `up` 及预期目标告警，而不是把缺失填零或宣称恢复。可以在新的兼容发布目录中，从当前规则保留两条通用告警及其两个 recording 依赖：

```powershell
# $compatGenerated 是新建的完整生成目录副本，不能等于当前挂载目录。
$compatGenerated = Join-Path $monitoring ('releases\compat-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '\generated')
if (Test-Path -LiteralPath $compatGenerated) { throw '兼容目录已存在，改用新的发布目录' }
[IO.Directory]::CreateDirectory((Split-Path $compatGenerated -Parent)) | Out-Null
Copy-Item -LiteralPath $generated -Destination $compatGenerated -Recurse
$rulesPath = Join-Path $compatGenerated 'rules.json'
$rules = Get-Content -LiteralPath $rulesPath -Raw | ConvertFrom-Json -AsHashtable
foreach ($group in $rules.groups) {
    $group.rules = @($group.rules | Where-Object {
        $_['record'] -in @('kb:expected_service','kb:service_up') -or
        $_['alert'] -in @('KbServiceUnavailable','KbServiceReplicaUnavailable')
    })
}
$rules | ConvertTo-Json -Depth 64 | Set-Content -LiteralPath $rulesPath -Encoding utf8NoBOM
Push-Location $compatGenerated
try {
    & (Join-Path $tools 'promtool-3.5.0\promtool.exe') check config prometheus.json
    if ($LASTEXITCODE -ne 0) { throw '兼容规则检查失败' }
} finally { Pop-Location }
# 更新受控发布环境文件 KB_MONITORING_CONFIG_DIR 指向 $compatGenerated，重建 $composeArgs。
& $compose @composeArgs stop alertmanager
if ($LASTEXITCODE -ne 0) { throw '通知组件未停止，禁止切换规则' }
& $compose @composeArgs up -d --no-build --force-recreate prometheus
if ($LASTEXITCODE -ne 0) { throw '新契约规则尚未停用，禁止回退应用' }
# 操作者核对当前加载的是兼容规则后，才进行原业务服务的版本回退。
& $compose @composeArgs up -d --no-build alertmanager
```

目录生成器不得再次覆盖人工保留的兼容规则；下次正常升级应从 settings 生成新 release。该兼容期业务面板会显示契约/数据不可用，不能将旧业务告警消失视为恢复；结束通知只表示条件解除，仍须核对采样和当前状态。

## 7. 自监控失效边界

通知错误、持续限流、适配器失联与规则求值失败可在指标/面板定位。401 先查双方只读 Bearer 文件版本，400/413 查目录身份、max_alerts 与协议，503 查低基数失败类别和出口/DNS/TLS/时钟，不输出机器人 URI、token、签名或供应商原始错误体。

单套 Prometheus、Alertmanager、监控主机或钉钉出口整体失效时，同一链路**不能保证报告自身故障**。持久卷、容器自动重启和本地 health 不提供通知 HA。独立外部探活、备援通道、机器人全局共享限流及高可用均为后续独立部署设计，不是本次离线验证成果。
