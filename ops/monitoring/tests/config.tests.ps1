[CmdletBinding()]
param([Parameter(Mandatory)][string] $WorkDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Import-Module (Join-Path $root 'scripts/Monitoring.Config.psm1') -Force -DisableNameChecking
Import-Module (Join-Path $root 'scripts/Monitoring.Rules.psm1') -Force -DisableNameChecking
[IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($WorkDirectory)) | Out-Null
$script:count = 0
function Check([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
    $script:count++
}
function Reject([scriptblock] $Action) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    Check $rejected '非法配置未被拒绝'
}
function Fresh-Settings { Read-MonitoringJson (Join-Path $root 'settings.example.json') }
$catalog = Read-MonitoringJson (Join-Path $root 'alert-catalog.json')
$settings = Fresh-Settings
Assert-MonitoringSettings $settings $catalog
$script:count++
foreach ($bad in @('http://internal.test','https://user:pwd@internal.test','https://internal.test/other','https://internal.test/?token=x','https://127.0.0.1','https://internal.test/#fragment')) {
    $settings = Fresh-Settings
    $settings.adminRoot = $bad
    Reject { Assert-MonitoringSettings $settings $catalog }
}
foreach ($bad in @('$(throw 1)','x"or"1','a/b','')) {
    $settings = Fresh-Settings
    $settings.environment = $bad
    Reject { Assert-MonitoringSettings $settings $catalog }
}
foreach ($bad in @(-1,0,1.1,100001,'20',$true)) {
    $settings = Fresh-Settings
    $settings.thresholds.searchMinRequests = $bad
    Reject { Assert-MonitoringSettings $settings $catalog }
}
foreach ($bad in @('localhost:9081','127.0.0.1:9081','0.0.0.0:9081','host:0','host:65536','host:8080/path','host:8080?x=y')) {
    $settings = Fresh-Settings
    $settings.services[0].targets = @($bad)
    Reject { Assert-MonitoringSettings $settings $catalog }
}
$settings = Fresh-Settings
$settings.services[1].targets = $settings.services[0].targets
Reject { Assert-MonitoringSettings $settings $catalog }
$settings = Fresh-Settings
$settings.services[1].service = 'app'
Reject { Assert-MonitoringSettings $settings $catalog }
$settings = Fresh-Settings
$settings.services[2].expected = $false
$settings.services[2].targets = @()
Assert-MonitoringSettings $settings $catalog
$rules = New-MonitoringRules $settings $catalog
$expectedAi = @($rules.groups[0].rules | Where-Object { $_.record -eq 'kb:expected_service' -and $_.labels.service -eq 'ai' })
Check ($expectedAi.Count -eq 1 -and $expectedAi[0].expr -eq 'vector(0)') '未部署服务预期没有保留'
$settings = Fresh-Settings
$rules = New-MonitoringRules $settings $catalog
Check ($rules.groups[1].rules.Count -eq $catalog.Count) '告警目录与规则不完整'
foreach ($entry in $catalog) {
    $rule = @($rules.groups[1].rules | Where-Object { $_.alert -ceq $entry.name })
    Check ($rule.Count -eq 1 -and $rule[0].labels.severity -ceq $entry.severity -and $rule[0].labels.family -ceq $entry.family) '告警身份不一致'
}
foreach ($json in @('{"x":1,"x":2}','{"x":1,"X":2}','{"x":1} {}','{"x":NaN}')) {
    $path = Join-Path $WorkDirectory 'invalid.json'
    [IO.File]::WriteAllText($path, $json)
    Reject { Read-MonitoringJson $path }
}
$output = Join-Path $WorkDirectory 'generated'
& (Join-Path $root 'scripts/render-config.ps1') -SettingsPath (Join-Path $root 'settings.example.json') -OutputDirectory $output
$generated = Read-MonitoringJson (Join-Path $output 'prometheus.json')
Check ($generated.scrape_configs.Count -eq 8) '抓取服务不完整'
$discovery = @(Read-MonitoringJson (Join-Path $output 'targets-app.json'))
Check ($discovery[0].labels.service -ceq 'app' -and $discovery[0].targets.Count -eq 1) '目标生成错误'
$datasource = Read-MonitoringJson (Join-Path $output 'datasource.yaml')
Check ($datasource.apiVersion -eq 1 -and $datasource.datasources[0].uid -ceq 'kb-prometheus' -and $datasource.datasources[0].url -ceq 'http://prometheus:9090') 'Grafana 数据源绑定错误'
$provider = Read-MonitoringJson (Join-Path $output 'dashboard-provider.yaml')
Check ($provider.providers[0].options.path -ceq '/var/lib/grafana/dashboards' -and -not $provider.providers[0].allowUiUpdates) '面板 provisioning 错误'
foreach ($uid in @('kb-overview','kb-parse-index','kb-search-ai')) {
    $dashboard = Read-MonitoringJson (Join-Path $output "$uid.json")
    Check ($dashboard.uid -ceq $uid -and $dashboard.panels.Count -gt 0 -and -not $dashboard.editable) '面板身份或编辑策略错误'
    foreach ($panel in $dashboard.panels) {
        Check ($panel.datasource.uid -ceq 'kb-prometheus') '面板数据源不一致'
        Check ($panel.targets[0].expr.Contains('environment="$environment",deployment="$deployment"')) '面板丢失部署筛选'
        if ($panel.type -ceq 'stat') { Check $panel.targets[0].instant '状态面板不得沿用历史状态' }
    }
}
$settings = Fresh-Settings
$settings['token'] = 'not-a-credential'
Reject { Assert-MonitoringSettings $settings $catalog }
Reject { Write-MonitoringOutput ([IO.Path]::GetPathRoot([IO.Path]::GetFullPath($WorkDirectory))) ([ordered]@{'x.json'='{}'}) }
$linkTarget = Join-Path $WorkDirectory 'link-target'
[IO.Directory]::CreateDirectory($linkTarget) | Out-Null
$link = Join-Path $WorkDirectory 'linked-output'
New-Item -ItemType Junction -Path $link -Target $linkTarget | Out-Null
Reject { Write-MonitoringOutput (Join-Path $link 'child') ([ordered]@{'x.json'='{}'}) }
Check (-not (Test-Path -LiteralPath (Join-Path $linkTarget 'child'))) '拒绝链接后仍写入了目标'
$untouched = Join-Path $WorkDirectory 'user-file.txt'
[IO.File]::WriteAllText($untouched, 'do-not-overwrite')
Reject { Write-MonitoringOutput $WorkDirectory ([ordered]@{'user-file.txt'='changed'}) }
Check ([IO.File]::ReadAllText($untouched) -ceq 'do-not-overwrite') '覆盖了非目标文件'
Reject { Write-MonitoringOutput $output ([ordered]@{'../user-file.txt'='changed'}) }
[IO.File]::WriteAllText((Join-Path $output 'unrelated.txt'), 'untouched')
Reject { & (Join-Path $root 'scripts/render-config.ps1') -SettingsPath (Join-Path $root 'settings.example.json') -OutputDirectory $output }
Check ([IO.File]::ReadAllText((Join-Path $output 'unrelated.txt')) -ceq 'untouched') '覆盖了用户文件'
Write-Output "配置行为检查通过：$script:count 项；未启动任何服务"
