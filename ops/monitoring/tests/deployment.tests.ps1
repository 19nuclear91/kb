[CmdletBinding(DefaultParameterSetName='Compose')]
param(
    [Parameter(Mandatory,ParameterSetName='Compose')][string] $ComposeJson,
    [Parameter(Mandatory,ParameterSetName='Compose')][string] $GeneratedDirectory,
    [Parameter(ParameterSetName='Compose')][switch] $AllowStaticImageFixture,
    [Parameter(Mandatory,ParameterSetName='MissingTools')][switch] $TestMissingTools,
    [Parameter(Mandatory,ParameterSetName='MissingTools')][string] $ScratchDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:checks = 0
function Check([bool] $Condition, [string] $Message) {
    if (-not $Condition) {
        throw $Message
    }
    $script:checks++
}
function Same-Set($Actual, $Expected) {
    return (@(Compare-Object @($Actual) @($Expected)).Count -eq 0)
}
if ($TestMissingTools) {
    Check ([IO.Path]::IsPathFullyQualified($ScratchDirectory)) '临时目录必须是绝对路径'
    $work = Join-Path $ScratchDirectory ('missing-tools-' + [guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($work) | Out-Null
    & (Join-Path $PSHOME 'pwsh.exe') -NoProfile -File (Join-Path $root 'scripts/verify.ps1') -ToolDirectory (Join-Path $work 'absent') -ScratchDirectory $work
    Check ($LASTEXITCODE -ne 0) '缺工具时统一验证错误地返回成功'
    $runs = @(Get-ChildItem -LiteralPath $work -Directory -Filter 'monitoring-verify-*')
    Check ($runs.Count -eq 1) '缺少唯一验证报告目录'
    $report = Get-Content -LiteralPath (Join-Path $runs[0].FullName 'verification-report.json') -Raw | ConvertFrom-Json -AsHashtable
    Check ($report.exitCode -eq 1 -and $report.failed -ge 3 -and $report.notExecuted -gt 0) '缺工具报告错误'
    foreach ($name in @('promtool','amtool','compose')) {
        $integrity = @($report.steps | Where-Object name -EQ "$name-integrity")
        Check ($integrity.Count -eq 1 -and $integrity[0].exitCode -eq 127) '缺工具未明确记录 127'
        $version = @($report.steps | Where-Object name -EQ "$name-version")
        Check ($version.Count -eq 1 -and $version[0].status -eq 'not-executed') '缺工具版本检查被标为已执行'
    }
    foreach ($name in @('promtool-check-config','promtool-check-rules','promtool-test-rules','amtool-check-config','compose-config','deployment-tests')) {
        $step = @($report.steps | Where-Object name -EQ $name)
        Check ($step.Count -eq 1 -and $step[0].exitCode -eq 126 -and $step[0].status -eq 'not-executed') '依赖工具步骤被假报通过'
    }
    Write-Output "缺工具分支通过：$script:checks 项；子进程预期失败，未启动服务"
    exit 0
}

$versions = Get-Content -LiteralPath (Join-Path $root 'versions.json') -Raw | ConvertFrom-Json -AsHashtable
$modelText = Get-Content -LiteralPath $ComposeJson -Raw
$model = $modelText | ConvertFrom-Json -AsHashtable
$generated = [IO.Path]::GetFullPath($GeneratedDirectory)
$fixture = 'registry.invalid/kb-alert-notifier:static-check-only@sha256:' + ('0' * 64)
function Assert-Deployment($Model) {
    Check (Same-Set $Model.services.Keys @('prometheus','alertmanager','grafana','notifier')) '只允许四个独立监控组件'
    Check (Same-Set $Model.volumes.Keys @('prometheus_data','alertmanager_data','grafana_data')) '持久卷声明错误'
    Check (Same-Set $Model.networks.Keys @('monitoring','prometheus_egress','notifier_egress')) '网络声明错误'
    Check ($Model.networks.monitoring.internal -eq $true) '监控网络必须 internal'
    $expectedNetworks = @{prometheus=@('monitoring','prometheus_egress');alertmanager=@('monitoring');grafana=@('monitoring');notifier=@('monitoring','notifier_egress')}
    $expectedSecrets = @{prometheus=@();alertmanager=@('notifier_token');grafana=@('grafana_password');notifier=@('notifier_token','dingtalk_token','dingtalk_secret')}
    $expectedMounts = @{
        prometheus=@{'/etc/prometheus'='';'/prometheus'='prometheus_data'}
        alertmanager=@{'/etc/alertmanager/alertmanager.json'='alertmanager.json';'/alertmanager'='alertmanager_data'}
        grafana=@{'/var/lib/grafana'='grafana_data';'/etc/grafana/provisioning/datasources/kb.yaml'='datasource.yaml';'/etc/grafana/provisioning/dashboards/kb.yaml'='dashboard-provider.yaml';
            '/var/lib/grafana/dashboards/kb-overview.json'='kb-overview.json';'/var/lib/grafana/dashboards/kb-parse-index.json'='kb-parse-index.json';'/var/lib/grafana/dashboards/kb-search-ai.json'='kb-search-ai.json'}
        notifier=@{'/etc/notifier/alert-catalog.json'='alert-catalog.json'}
    }
    $portCount = 0
    foreach ($name in $Model.services.Keys) {
        $service = $Model.services[$name]
        Check ($service.image -cmatch '^[a-z0-9][a-z0-9./:_-]*:[A-Za-z0-9_][A-Za-z0-9_.-]*@sha256:[a-f0-9]{64}$' -and $service.image -notmatch ':latest@') '镜像必须固定 tag 和 SHA-256 digest'
        if ($name -ne 'notifier') {
            Check ($service.image -ceq $versions.images[$name].reference) '镜像与版本清单不一致'
        } elseif (-not $AllowStaticImageFixture -or $service.image -cne $fixture) {
            Check ($service.image -notmatch 'registry\.invalid|@sha256:0{64}$') '测试镜像不能作为发布镜像'
        }
        Check (-not $service.Contains('ports') -or $name -ne 'notifier') '通知及其管理入口禁止端口发布'
        foreach ($port in $service['ports']) {
            Check ($port.host_ip -eq '127.0.0.1') 'UI 必须只发布到回环'
            $portCount++
        }
        Check (-not $service.Contains('depends_on') -and -not $service.Contains('build')) '部署不允许业务依赖或隐式镜像构建'
        Check (-not $service.Contains('network_mode') -and -not $service.Contains('privileged')) '禁止 host 网络和特权模式'
        Check ($service.read_only -eq $true -and 'ALL' -in $service.cap_drop -and 'no-new-privileges:true' -in $service.security_opt) '容器权限未收口'
        Check (Same-Set $service.networks.Keys $expectedNetworks[$name]) '组件出站网络没有隔离'
        $secrets = @()
        foreach ($secret in $service['secrets']) {
            $secrets += $secret.source
        }
        Check (Same-Set $secrets $expectedSecrets[$name]) '组件 secret 不满足最小授权'
        Check (Same-Set @($service.volumes | ForEach-Object { $_.target }) $expectedMounts[$name].Keys) '挂载清单错误'
        foreach ($mount in $service.volumes) {
            $expected = $expectedMounts[$name][$mount.target]
            if ($mount.type -eq 'volume') {
                Check ($mount.source -ceq $expected -and $Model.volumes.Contains($mount.source)) '持久卷没有正确挂载'
            } else {
                Check ($mount.type -eq 'bind' -and $mount.read_only -eq $true) '生成配置只允许只读绑定'
                Check (-not $mount.bind['create_host_path']) '挂载缺失不应自动创建目录'
                $expectedPath = if ($expected) {Join-Path $generated $expected} else {$generated}
                Check ([IO.Path]::GetFullPath($mount.source) -eq $expectedPath) '配置绑定来源不符'
                Check (Test-Path -LiteralPath $mount.source) '实际配置挂载来源不存在'
            }
        }
        if ($service.Contains('environment')) {
            foreach ($key in $service.environment.Keys) {
                if ($key -match '(PASSWORD|SECRET|TOKEN)') {
                    Check ($key -match '(_FILE|__FILE)$' -and $service.environment[$key] -like '/run/secrets/*') '凭据只能通过 secret 文件路径交付'
                }
            }
        }
    }
    Check ($portCount -eq 3) 'UI 发布端口数量错误'
    Check (Same-Set $Model.secrets.Keys @('notifier_token','dingtalk_token','dingtalk_secret','grafana_password')) 'secret 声明不完整'
    foreach ($secret in $Model.secrets.Values) {
        Check ($secret.Contains('file') -and -not $secret.Contains('environment') -and -not $secret.Contains('content')) 'secret 必须来自文件'
        Check (Test-Path -LiteralPath $secret.file -PathType Leaf) 'secret 文件不存在'
    }
    $notifier = $Model.services.notifier
    Check ($notifier.deploy.replicas -eq 1 -and $notifier.deploy.update_config.order -eq 'stop-first') '适配器必须单实例停止后更新'
    $ip = [Net.IPAddress]::Parse($notifier.networks.monitoring.ipv4_address)
    $bytes = $ip.GetAddressBytes()
    Check ($bytes.Count -eq 4 -and ($bytes[0] -eq 10 -or ($bytes[0] -eq 172 -and $bytes[1] -ge 16 -and $bytes[1] -le 31) -or ($bytes[0] -eq 192 -and $bytes[1] -eq 168))) '适配器必须绑定明确 RFC1918 地址'
    Check ($notifier.environment.KB_NOTIFIER_ADDRESS -eq $ip.ToString() -and $notifier.environment.KB_NOTIFIER_MANAGEMENT_ADDRESS -eq $ip.ToString()) '双端口必须绑定实际监控网络地址'
    Check ($notifier.environment.KB_NOTIFIER_PORT -eq '8090' -and $notifier.environment.KB_NOTIFIER_MANAGEMENT_PORT -eq '9090') '通知双端口不符合抓取及 webhook 契约'
    Check ($notifier.environment.KB_NOTIFIER_BEARER_SECRET_FILE -eq '/run/secrets/notifier_token') 'Alertmanager 和通知认证文件没有对齐'
    Check ($Model.services.prometheus.working_dir -eq '/etc/prometheus') 'Prometheus 相对规则及目标路径缺少 working_dir'
    Check ('--config.file=/etc/prometheus/prometheus.json' -in $Model.services.prometheus.command) 'Prometheus 配置入口错误'
    Check ('--cluster.listen-address=' -in $Model.services.alertmanager.command) '首期 Alertmanager 不应开放集群监听'
    $grafana = $Model.services.grafana.environment
    Check ($grafana.GF_AUTH_ANONYMOUS_ENABLED -eq 'false' -and $grafana.GF_USERS_ALLOW_SIGN_UP -eq 'false') 'Grafana 禁止匿名及自行注册'
    Check ($grafana.GF_UNIFIED_ALERTING_ENABLED -eq 'false') 'Grafana 不能启用第二套告警引擎'
    Check ($grafana.GF_SECURITY_ADMIN_PASSWORD__FILE -eq '/run/secrets/grafana_password') 'Grafana 密码必须来自只读 secret'
}

# 检查 Compose 解析后的运行模型，不读取或断言文档、Markdown、注释。
Assert-Deployment $model
$positiveChecks = $script:checks
$mutations = @(
    {param($m) $m.services.notifier.ports = @(@{host_ip='0.0.0.0';target=8090;published='8090'})},
    {param($m) $m.services.grafana.ports[0].host_ip = '0.0.0.0'},
    {param($m) $m.services.notifier.image = 'notifier:latest'},
    {param($m) $m.services.notifier.deploy.replicas = 2},
    {param($m) $m.services.notifier.environment.KB_NOTIFIER_MANAGEMENT_ADDRESS = '0.0.0.0'},
    {param($m) $m.services.notifier.environment.KB_NOTIFIER_DINGTALK_TOKEN = 'INVALID'},
    {param($m) $m.services.notifier.depends_on = @{redis=@{condition='service_started'}}},
    {param($m) $m.networks.monitoring.internal = $false},
    {param($m) $m.services.notifier.volumes[0].read_only = $false},
    {param($m) $m.services.grafana.environment.GF_AUTH_ANONYMOUS_ENABLED = 'true'},
    {param($m) $m.services.grafana.environment.GF_UNIFIED_ALERTING_ENABLED = 'true'},
    {param($m) $m.services.prometheus.working_dir = '/'},
    {param($m) $m.services.prometheus.volumes = @($m.services.prometheus.volumes | Where-Object type -NE 'volume')}
)
foreach ($mutate in $mutations) {
    $copy = $modelText | ConvertFrom-Json -AsHashtable
    & $mutate $copy
    $rejected = $false
    try {
        Assert-Deployment $copy
    } catch {
        $rejected = $true
    }
    Check $rejected '非法部署模型没有被拒绝'
}
$prometheus = Get-Content -LiteralPath (Join-Path $generated 'prometheus.json') -Raw | ConvertFrom-Json -AsHashtable
Check (Same-Set $prometheus.rule_files @('rules.json')) '规则文件必须是同目录相对路径'
foreach ($job in $prometheus.scrape_configs) {
    if ($job.Contains('file_sd_configs')) {
        foreach ($discovery in $job.file_sd_configs) {
            foreach ($file in $discovery.files) {
                Check (-not [IO.Path]::IsPathRooted($file) -and (Test-Path -LiteralPath (Join-Path $generated $file) -PathType Leaf)) 'file_sd 相对文件不可达'
            }
        }
    }
}
$am = Get-Content -LiteralPath (Join-Path $generated 'alertmanager.json') -Raw | ConvertFrom-Json -AsHashtable
Check ($am.receivers.Count -eq 1) '只允许一条通知链路'
$webhook = $am.receivers[0].webhook_configs[0]
Check ($webhook.url -eq 'http://notifier:8090/internal/alerts/dingtalk') 'webhook 地址与 Compose 不一致'
Check ($webhook.timeout -eq '15s' -and $webhook.max_alerts -eq 100 -and $webhook.send_resolved -eq $true) 'webhook 资源契约错误'
Check ($webhook.http_config.authorization.credentials_file -eq '/run/secrets/notifier_token' -and $webhook.http_config.follow_redirects -eq $false) 'webhook 认证或重定向契约错误'
Write-Output "部署语义检查通过：$positiveChecks 项模型检查、$($mutations.Count) 项拒绝场景、9 项生成配置联接检查；未启动组件"
