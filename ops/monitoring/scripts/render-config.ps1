[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $SettingsPath,
    [Parameter(Mandatory)][string] $OutputDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Monitoring.Config.psm1') -Force -DisableNameChecking
Import-Module (Join-Path $PSScriptRoot 'Monitoring.Rules.psm1') -Force -DisableNameChecking
Import-Module (Join-Path $PSScriptRoot 'Monitoring.Dashboards.psm1') -Force -DisableNameChecking
$root = Split-Path $PSScriptRoot -Parent
$settings = Read-MonitoringJson $SettingsPath
$catalog = Read-MonitoringJson (Join-Path $root 'alert-catalog.json')
Assert-MonitoringSettings $settings $catalog
$rules = New-MonitoringRules $settings $catalog
$alertmanager = New-MonitoringAlertmanager $settings
$files = [ordered]@{}
$jobs = [Collections.Generic.List[object]]::new()
foreach ($service in $settings.services) {
    $files["targets-$($service.service).json"] = ConvertTo-Json -InputObject @(@{targets=@($service.targets);labels=@{
        service=$service.service;environment=$settings.environment;deployment=$settings.deployment}}) -Depth 16
    $jobs.Add(@{job_name="kb-$($service.service)";metrics_path='/actuator/prometheus';scheme='http';
        file_sd_configs=@(@{files=@("targets-$($service.service).json");refresh_interval='30s'});
        sample_limit=20000;label_limit=32;body_size_limit='8MB'})
}
foreach ($service in @('notifier','prometheus','alertmanager','grafana')) {
    $port = switch ($service) { 'alertmanager' {9093} 'grafana' {3000} default {9090} }
    $path = if ($service -eq 'notifier') {'/actuator/prometheus'} else {'/metrics'}
    $jobs.Add(@{job_name="kb-$service";metrics_path=$path;static_configs=@(@{targets=@("${service}:$port");labels=@{
        service=$service;environment=$settings.environment;deployment=$settings.deployment}});
        sample_limit=20000;label_limit=32;body_size_limit='8MB'})
}
$prometheus = @{
    global=@{scrape_interval='30s';scrape_timeout='10s';evaluation_interval='30s'}
    rule_files=@('rules.json')
    alerting=@{alertmanagers=@(@{static_configs=@(@{targets=@('alertmanager:9093')})})}
    scrape_configs=@($jobs.ToArray())
}
$files['prometheus.json'] = ConvertTo-Json $prometheus -Depth 32
$files['rules.json'] = ConvertTo-Json $rules -Depth 32
$files['alertmanager.json'] = ConvertTo-Json $alertmanager -Depth 32
$files['alert-catalog.json'] = ConvertTo-Json -InputObject $catalog -Depth 16
$dashboards = New-MonitoringDashboards
foreach ($name in $dashboards.Keys) {
    $files[$name] = ConvertTo-Json -InputObject $dashboards[$name] -Depth 32
}
# JSON 是 YAML 子集；provisioning 使用 Grafana 要求的 .yaml 扩展名。
$files['datasource.yaml'] = ConvertTo-Json -Depth 16 -InputObject @{
    apiVersion=1; datasources=@(@{name='Knowledge Base';uid='kb-prometheus';type='prometheus';access='proxy';
        url='http://prometheus:9090';isDefault=$true;editable=$false;jsonData=@{httpMethod='POST';timeInterval='30s'}})
}
$files['dashboard-provider.yaml'] = ConvertTo-Json -Depth 16 -InputObject @{
    apiVersion=1; providers=@(@{name='kb';orgId=1;folder='知识库';type='file';disableDeletion=$false;
        allowUiUpdates=$false;updateIntervalSeconds=30;options=@{path='/var/lib/grafana/dashboards'}})
}
# 只生成非敏感身份、入口和保留期。凭据只由 Compose secret 文件传入。
$files['deployment.env'] = @"
KB_MONITORING_ENVIRONMENT=$($settings.environment)
KB_MONITORING_DEPLOYMENT=$($settings.deployment)
KB_MONITORING_ADMIN_ROOT=$($settings.adminRoot.TrimEnd('/'))
KB_MONITORING_GRAFANA_ROOT=$($settings.grafanaRoot.TrimEnd('/'))
KB_MONITORING_RETENTION=$($settings.retentionDays)d
"@
Write-MonitoringOutput $OutputDirectory $files
Write-Output "已生成 $($files.Count) 个监控配置文件（未启动服务）"
