[CmdletBinding()]
param([Parameter(Mandatory)][string] $OutputDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Import-Module (Join-Path $root 'scripts/Monitoring.Config.psm1') -Force -DisableNameChecking
Import-Module (Join-Path $root 'scripts/Monitoring.Dashboards.psm1') -Force -DisableNameChecking
$catalog = Read-MonitoringJson (Join-Path $root 'alert-catalog.json')
$settings = Read-MonitoringJson (Join-Path $root 'settings.example.json')
[IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($OutputDirectory)) | Out-Null
& (Join-Path $root 'scripts/render-config.ps1') -SettingsPath (Join-Path $root 'settings.example.json') -OutputDirectory (Join-Path $OutputDirectory 'config') | Out-Null

function Add-Series($Series, [string] $Name, [string] $Service, [string] $Values, $Extra=@{}, [string] $Instance='', [string] $Deployment='kb-main') {
    if (-not $Instance) { $Instance = "$Service-1" }
    $labels = [ordered]@{environment='staging';deployment=$Deployment;service=$Service;instance=$Instance;job="kb-$Service"}
    foreach ($key in $Extra.Keys) { $labels[$key] = $Extra[$key] }
    $selector = ($labels.Keys | ForEach-Object { $_ + '="' + $labels[$_] + '"' }) -join ','
    $key = "$Name{$selector}"
    $Series[$key] = @{series=$key;values=$Values}
}
function Baseline {
    $series = [ordered]@{}
    foreach ($service in @('app','worker','ai','gateway','notifier','prometheus','alertmanager','grafana')) {
        Add-Series $series 'up' $service '1+0x220'
    }
    foreach ($pair in @(@('worker','parse'),@('worker','embedding'),@('app','settlement'))) {
        Add-Series $series 'kb_monitoring_sample_available' $pair[0] '1+0x220' @{source=$pair[1]}
        Add-Series $series 'kb_monitoring_sample_last_success_timestamp_seconds' $pair[0] '0+30x220' @{source=$pair[1]}
    }
    foreach ($service in @('app','ai')) {
        foreach ($feature in @('conversation','ai_observation')) { Add-Series $series 'kb_monitoring_feature_enabled' $service '0+0x220' @{feature=$feature} }
    }
    Add-Series $series 'kb_parse_parser_configured' 'worker' '1+0x220'
    Add-Series $series 'kb_search_inflight' 'app' '0+0x220'
    Add-Series $series 'kb_alert_notifier_inflight' 'notifier' '0+0x220'
    foreach ($name in @('kb_parse_queue_depth','kb_parse_dead_letter_depth','kb_parse_queue_oldest_age_seconds')) { Add-Series $series $name 'worker' '0+0x220' }
    foreach ($state in @('ACTIVE','UNINITIALIZED','QUIESCING','REBUILDING','VERIFYING','BLOCKED','UNKNOWN')) {
        Add-Series $series 'kb_embedding_mode' 'worker' "$(if ($state -eq 'ACTIVE') {1} else {0})+0x220" @{state=$state}
    }
    foreach ($name in @('pending','review','oldest_pending_age_seconds')) { Add-Series $series "kb_conversation_settlement_$name" 'app' '0+0x220' }
    return $series
}
function Alert-Check([string] $Name, [string] $Time, [bool] $Firing, [string] $Service) {
    $entry = $catalog | Where-Object name -CEQ $Name
    $expected = @()
    if ($Firing) { $expected = @(@{exp_labels=@{environment='staging';deployment='kb-main';service=$Service;severity=$entry.severity;family=$entry.family};exp_annotations=@{summary=$entry.summary}}) }
    return @{eval_time=$Time;alertname=$Name;exp_alerts=$expected}
}
$tests = [Collections.Generic.List[object]]::new()
foreach ($entry in $catalog) {
    $series = Baseline
    $service = $entry.services[0]
    switch -CaseSensitive ($entry.name) {
        'KbServiceUnavailable' { Add-Series $series 'up' 'app' '0+0x150 1+0x70' }
        'KbServiceReplicaUnavailable' { Add-Series $series 'up' 'app' '0+0x150 1+0x70' @{} 'app-2' }
        'KbMonitoringContractMissing' { Add-Series $series 'kb_monitoring_feature_enabled' 'app' ((@('_') * 151 -join ' ') + ' 0+0x70') @{feature='conversation'} }
        'KbMetricSampleUnavailable' { Add-Series $series 'kb_monitoring_sample_available' 'app' '0+0x150 1+0x70' @{source='settlement'} }
        'KbParserNotConfigured' { Add-Series $series 'kb_parse_parser_configured' 'worker' '0+0x150 1+0x70' }
        'KbParseQueueAging' { Add-Series $series 'kb_parse_queue_oldest_age_seconds' 'worker' '1000+0x150 0+0x70' }
        'KbParseDeadLetters' {
            Add-Series $series 'kb_parse_dead_letter_depth' 'worker' '3+0x150 0+0x70'
            Add-Series $series 'kb_embedding_mode' 'worker' '0+0x220' @{state='ACTIVE'}
            Add-Series $series 'kb_embedding_mode' 'worker' '1+0x220' @{state='REBUILDING'}
        }
        'KbParseLeaseLoss' { Add-Series $series 'kb_parse_tasks_lease_lost_total' 'worker' '0+1x150 150+0x70' }
        'KbEmbeddingBlocked' { Add-Series $series 'kb_embedding_mode' 'worker' '1+0x150 0+0x70' @{state='BLOCKED'} }
        'KbEmbeddingMaintenanceLong' { Add-Series $series 'kb_embedding_mode' 'worker' '1+0x150 0+0x70' @{state='UNINITIALIZED'} }
        'KbSearchFailures' {
            Add-Series $series 'kb_search_requests_total' 'app' '0+5x150 750+0x70' @{outcome='FAILED'}
            Add-Series $series 'kb_search_requests_total' 'app' '0+5x220' @{outcome='SUCCESS'}
        }
        'KbSearchRetrievalSlow' {
            Add-Series $series 'kb_search_stage_duration_seconds_count' 'app' '0+5x220' @{stage='RETRIEVAL';outcome='SUCCESS'}
            foreach ($le in @('1','3','5','+Inf')) {
                $values = if ($le -in @('1','3')) {'0+0x150 5+5x70'} else {'0+5x220'}
                Add-Series $series 'kb_search_stage_duration_seconds_bucket' 'app' $values @{stage='RETRIEVAL';outcome='SUCCESS';le=$le}
            }
        }
        'KbSearchDegradation' {
            Add-Series $series 'kb_search_degradation_total' 'app' '0+5x150 750+0x70' @{stage='RERANK';reason='TIMEOUT'}
            Add-Series $series 'kb_search_requests_total' 'app' '0+10x220' @{outcome='SUCCESS'}
        }
        'KbModelFailures' {
            Add-Series $series 'kb_model_calls_total' 'ai' '0+5x150 750+0x70' @{outcome='UPSTREAM_ERROR'}
            Add-Series $series 'kb_model_calls_total' 'ai' '0+5x220' @{outcome='COMPLETED'}
        }
        'KbChatSystemErrors' {
            Add-Series $series 'kb_chat_streams_total' 'ai' '0+5x150 750+0x70' @{outcome='ERROR';error_code='UNKNOWN'}
            Add-Series $series 'kb_chat_streams_total' 'ai' '0+5x220' @{outcome='COMPLETED';error_code='NONE'}
        }
        'KbSettlementPendingOld' {
            Add-Series $series 'kb_conversation_settlement_pending' 'app' '2+0x150 0+0x70'
            Add-Series $series 'kb_conversation_settlement_oldest_pending_age_seconds' 'app' '600+0x150 0+0x70'
        }
        'KbSettlementReview' { Add-Series $series 'kb_conversation_settlement_review' 'app' '2+0x150 0+0x70' }
        'KbConversationConfigurationMismatch' { Add-Series $series 'kb_monitoring_feature_enabled' 'ai' '1+0x150 0+0x70' @{feature='conversation'} }
        'KbNotificationDeliveryFailures' {
            $service = 'alertmanager'
            Add-Series $series 'alertmanager_notifications_failed_total' $service '0+1x150 150+0x70' @{integration='webhook'}
        }
        'KbRuleEvaluationFailures' { Add-Series $series 'prometheus_rule_evaluation_failures_total' 'prometheus' '0+1x150 150+0x70' }
        default { throw '缺少规则测试场景' }
    }
    $checks = @(
        (Alert-Check $entry.name "$($settings.waitSeconds[$entry.name] - 30)s" $false $service),
        (Alert-Check $entry.name '70m' $true $service),
        (Alert-Check $entry.name '100m' $false $service))
    $tests.Add(@{name="$($entry.name)-wait-fire-resolve";interval='30s';input_series=@($series.Values);alert_rule_test=$checks})
}
$baseline = Baseline
$tests.Add(@{name='healthy-no-traffic';interval='30s';input_series=@($baseline.Values);
    alert_rule_test=@($catalog | ForEach-Object { Alert-Check $_.name '70m' $false $_.services[0] })})
# 缺失目标、未来时间、陈旧样本和重新成功后的恢复。
foreach ($kind in @('missing-target','future','stale','never-sampled')) {
    $series = Baseline
    $alert = 'KbMetricSampleUnavailable'
    if ($kind -eq 'missing-target') {
        Add-Series $series 'up' 'app' ((@('_') * 151 -join ' ') + ' 1+0x70')
        $alert = 'KbServiceUnavailable'
    } else {
        $values = switch ($kind) { 'future' {'100000+30x150 4530+30x70'} 'stale' {'1+0x150 4530+30x70'} default {'0+0x150 4530+30x70'} }
        Add-Series $series 'kb_monitoring_sample_last_success_timestamp_seconds' 'app' $values @{source='settlement'}
    }
    $tests.Add(@{name=$kind;interval='30s';input_series=@($series.Values);alert_rule_test=@(
        (Alert-Check $alert '70m' $true 'app'),(Alert-Check $alert '100m' $false 'app'))})
}
# 维护与缺少解析器只门控普通积压，已有死信的正向场景在上方独立覆盖。
foreach ($kind in @('maintenance','parser-missing','low-traffic','normal-rejections')) {
    $series = Baseline
    $names = @('KbParseQueueAging')
    if ($kind -in @('maintenance','parser-missing')) {
        Add-Series $series 'kb_parse_queue_oldest_age_seconds' 'worker' '5000+0x220'
        if ($kind -eq 'maintenance') {
            Add-Series $series 'kb_embedding_mode' 'worker' '1+0x220' @{state='REBUILDING'}
            Add-Series $series 'kb_embedding_mode' 'worker' '0+0x220' @{state='ACTIVE'}
        } else { Add-Series $series 'kb_parse_parser_configured' 'worker' '0+0x220' }
    } elseif ($kind -eq 'low-traffic') {
        Add-Series $series 'kb_search_requests_total' 'app' '0+0.1x220' @{outcome='FAILED'}
        Add-Series $series 'kb_model_calls_total' 'ai' '0+0.1x220' @{outcome='TIMEOUT'}
        Add-Series $series 'kb_chat_streams_total' 'ai' '0+0.1x220' @{outcome='TIMEOUT';error_code='UNKNOWN'}
        Add-Series $series 'kb_search_degradation_total' 'app' '0+0.1x220' @{stage='RERANK';reason='TIMEOUT'}
        Add-Series $series 'kb_search_stage_duration_seconds_count' 'app' '0+0.1x220' @{stage='RETRIEVAL';outcome='SUCCESS'}
        foreach ($le in @('1','3','5','+Inf')) {
            $step = if ($le -in @('1','3')) {0} else {0.1}
            Add-Series $series 'kb_search_stage_duration_seconds_bucket' 'app' "0+${step}x220" @{stage='RETRIEVAL';outcome='SUCCESS';le=$le}
        }
        $names = @('KbSearchFailures','KbSearchRetrievalSlow','KbSearchDegradation','KbModelFailures','KbChatSystemErrors')
    } else {
        foreach ($outcome in @('EMPTY','REJECTED','CANCELLED')) { Add-Series $series 'kb_search_requests_total' 'app' '0+10x220' @{outcome=$outcome} }
        foreach ($outcome in @('LIMIT','CANCELLED')) { Add-Series $series 'kb_model_calls_total' 'ai' '0+10x220' @{outcome=$outcome} }
        Add-Series $series 'kb_chat_streams_total' 'ai' '0+10x220' @{outcome='ERROR';error_code='INPUT_BUDGET_EXCEEDED'}
        Add-Series $series 'kb_chat_streams_total' 'ai' '0+10x220' @{outcome='CANCELLED';error_code='NONE'}
        $names = @('KbSearchFailures','KbModelFailures','KbChatSystemErrors')
    }
    $tests.Add(@{name=$kind;interval='30s';input_series=@($series.Values);alert_rule_test=@($names | ForEach-Object { Alert-Check $_ '70m' $false 'app' })})
}
# 同库两副本不倍增，另一部署不混入；失效副本的较大旧值必须剔除。
foreach ($kind in @('both-valid','one-stale','all-invalid')) {
    $series = Baseline
    Add-Series $series 'kb_parse_queue_depth' 'worker' '10+0x220'
    Add-Series $series 'up' 'worker' '1+0x220' @{} 'worker-2'
    Add-Series $series 'kb_parse_queue_depth' 'worker' '10+0x220' @{} 'worker-2'
    Add-Series $series 'kb_monitoring_sample_available' 'worker' '1+0x220' @{source='parse'} 'worker-2'
    Add-Series $series 'kb_monitoring_sample_last_success_timestamp_seconds' 'worker' '0+30x220' @{source='parse'} 'worker-2'
    foreach ($name in @('up','kb_monitoring_sample_available')) { Add-Series $series $name 'worker' '1+0x220' @{source='parse'} 'worker-other' 'other-db' }
    Add-Series $series 'kb_monitoring_sample_last_success_timestamp_seconds' 'worker' '0+30x220' @{source='parse'} 'worker-other' 'other-db'
    Add-Series $series 'kb_parse_queue_depth' 'worker' '99+0x220' @{} 'worker-other' 'other-db'
    if ($kind -ne 'both-valid') {
        Add-Series $series 'kb_parse_queue_depth' 'worker' '1000+0x220' @{} 'worker-2'
        Add-Series $series 'kb_monitoring_sample_last_success_timestamp_seconds' 'worker' '1+0x220' @{source='parse'} 'worker-2'
    }
    if ($kind -eq 'all-invalid') { Add-Series $series 'kb_monitoring_sample_available' 'worker' '0+0x220' @{source='parse'} }
    $expected = @(if ($kind -ne 'all-invalid') { @{labels='{}';value=10} })
    $tests.Add(@{name=$kind;interval='30s';input_series=@($series.Values);promql_expr_test=@(
        @{expr='sum(kb:parse_queue_depth{environment="staging",deployment="kb-main"})';eval_time='10m';exp_samples=$expected},
        @{expr='sum(kb:parse_queue_depth{environment="staging",deployment="other-db"})';eval_time='10m';exp_samples=@(@{labels='{}';value=99})})})
}
$series = Baseline
Add-Series $series 'kb_parse_tasks_lease_lost_total' 'worker' '0+1x14 0+1x100'
Add-Series $series 'kb_parse_tasks_lease_lost_total' 'worker' '0+2x120' @{} 'worker-2'
$tests.Add(@{name='counter-reset-before-sum';interval='30s';input_series=@($series.Values);promql_expr_test=@(
    @{expr='round(sum(increase(kb_parse_tasks_lease_lost_total[5m])),0.001)';eval_time='10m';exp_samples=@(@{labels='{}';value=28.889})})})
$series = Baseline
foreach ($replica in @(1,2)) {
    $step = if ($replica -eq 1) {1} else {9}
    Add-Series $series 'kb_search_stage_duration_seconds_count' 'app' "0+${step}x220" @{stage='RETRIEVAL';outcome='SUCCESS'} "app-$replica"
    foreach ($le in @('1','3','5','+Inf')) {
        $bucketStep = if ($replica -eq 1 -and $le -in @('1','3')) {0} else {$step}
        Add-Series $series 'kb_search_stage_duration_seconds_bucket' 'app' "0+${bucketStep}x220" @{stage='RETRIEVAL';outcome='SUCCESS';le=$le} "app-$replica"
    }
}
$tests.Add(@{name='histogram-merge-not-average';interval='30s';input_series=@($series.Values);promql_expr_test=@(
    @{expr='sum(kb:retrieval_p95_seconds{environment="staging",deployment="kb-main"})';eval_time='10m';exp_samples=@(@{labels='{}';value=4})})})
# 实际通知枚举：成功不告警，容量和各种发送失败均进入持续故障规则。
foreach ($reason in @('success','remote_http','remote_business','remote_json','timeout','network','cancelled','internal','rate','concurrency')) {
    $series = Baseline
    if ($reason -in @('rate','concurrency')) {
        Add-Series $series 'kb_alert_notifier_rejections_total' 'notifier' '0+1x150 150+0x70' @{reason=$reason}
    } else {
        Add-Series $series 'kb_alert_notifier_deliveries_total' 'notifier' '0+1x150 150+0x70' @{outcome=$reason}
    }
    $tests.Add(@{name="notifier-$reason";interval='30s';input_series=@($series.Values);alert_rule_test=@(
        (Alert-Check 'KbNotificationDeliveryFailures' '4m' $false 'notifier'),
        (Alert-Check 'KbNotificationDeliveryFailures' '70m' ($reason -ne 'success') 'notifier'),
        (Alert-Check 'KbNotificationDeliveryFailures' '100m' $false 'notifier'))})
}
foreach ($reason in @('TIMEOUT','PROTOCOL_ERROR')) {
    $series = Baseline
    Add-Series $series 'kb_model_calls_total' 'ai' '0+5x150 750+0x70' @{outcome=$reason}
    $tests.Add(@{name="model-$reason";interval='30s';input_series=@($series.Values);alert_rule_test=@(
        (Alert-Check 'KbModelFailures' '70m' $true 'ai'),(Alert-Check 'KbModelFailures' '100m' $false 'ai'))})
}
$series = Baseline
Add-Series $series 'kb_chat_streams_total' 'ai' '0+5x150 750+0x70' @{outcome='TIMEOUT';error_code='UNKNOWN'}
$tests.Add(@{name='sse-timeout-with-http-success';interval='30s';input_series=@($series.Values);alert_rule_test=@(
    (Alert-Check 'KbChatSystemErrors' '70m' $true 'ai'),(Alert-Check 'KbChatSystemErrors' '100m' $false 'ai'))})
# 单个来源／副本不可用时，其他有效样本和独立来源的故障仍应可见。
$series = Baseline
Add-Series $series 'kb_monitoring_sample_available' 'worker' '0+0x220' @{source='embedding'}
Add-Series $series 'kb_parse_dead_letter_depth' 'worker' '3+0x220'
$tests.Add(@{name='independent-source-failure';interval='30s';input_series=@($series.Values);alert_rule_test=@(
    (Alert-Check 'KbMetricSampleUnavailable' '70m' $true 'worker'),(Alert-Check 'KbParseDeadLetters' '70m' $true 'worker'))})
$series = Baseline
Add-Series $series 'kb_parse_dead_letter_depth' 'worker' '3+0x220'
Add-Series $series 'up' 'worker' '1+0x220' @{} 'worker-2'
Add-Series $series 'kb_monitoring_sample_available' 'worker' '0+0x220' @{source='parse'} 'worker-2'
$tests.Add(@{name='partial-sampling-failure-retains-business-alert';interval='30s';input_series=@($series.Values);alert_rule_test=@(
    (Alert-Check 'KbMetricSampleUnavailable' '70m' $true 'worker'),(Alert-Check 'KbParseDeadLetters' '70m' $true 'worker'))})
$series = Baseline
Add-Series $series 'kb_parse_dead_letter_depth' 'worker' '3+0x220'
Add-Series $series 'kb_monitoring_sample_available' 'worker' '0+0x220' @{source='parse'}
$tests.Add(@{name='all-source-samples-invalid-suppresses-business';interval='30s';input_series=@($series.Values);alert_rule_test=@(
    (Alert-Check 'KbMetricSampleUnavailable' '70m' $true 'worker'),(Alert-Check 'KbParseDeadLetters' '70m' $false 'worker'))})
# 直接验证实际面板查询的正向值，避免仅以空数据解析成功作为面板验收。
$panels = New-MonitoringDashboards
$series = Baseline
foreach ($replica in @(1,2)) {
    Add-Series $series 'up' 'worker' '1+0x220' @{} "worker-$replica"
    foreach ($sourceName in @('parse','embedding')) {
        Add-Series $series 'kb_monitoring_sample_available' 'worker' '1+0x220' @{source=$sourceName} "worker-$replica"
        Add-Series $series 'kb_monitoring_sample_last_success_timestamp_seconds' 'worker' '0+30x220' @{source=$sourceName} "worker-$replica"
    }
    Add-Series $series 'kb_parse_queue_depth' 'worker' '10+0x220' @{} "worker-$replica"
    Add-Series $series 'kb_embedding_progress' 'worker' '7+0x220' @{result='succeeded'} "worker-$replica"
    Add-Series $series 'kb_embedding_events_persisted' 'worker' '4+0x220' @{operation='START'} "worker-$replica"
}
Add-Series $series 'kb_conversation_settlement_pending' 'app' '2+0x220'
Add-Series $series 'kb_conversation_settlement_review' 'app' '1+0x220'
$positive = [Collections.Generic.List[object]]::new()
foreach ($case in @(@('kb-overview.json',0,8),@('kb-overview.json',2,5),
    @('kb-parse-index.json',0,10),@('kb-parse-index.json',6,7),@('kb-parse-index.json',7,4),
    @('kb-search-ai.json',0,0),@('kb-search-ai.json',13,0),@('kb-search-ai.json',14,2),@('kb-search-ai.json',15,1))) {
    $query = $panels[$case[0]].panels[$case[1]].targets[0].expr.Replace('$environment','staging').Replace('$deployment','kb-main')
    $positive.Add(@{expr="sum($query)";eval_time='10m';exp_samples=@(@{labels='{}';value=$case[2]})})
}
$tests.Add(@{name='dashboard-positive-states-and-deduplication';interval='30s';input_series=@($series.Values);promql_expr_test=@($positive.ToArray())})
# 首事件桶与实际 registry 导出契约一致；不平均副本 P95，也不混入其他部署。
$series = Baseline
$firstEventQueries = [Collections.Generic.List[object]]::new()
foreach ($case in @(@('kb_model_first_text_delay_seconds',8,1.5,2.0,0.5),
    @('kb_chat_first_event_delay_seconds',12,0.3,0.5,0.1))) {
    foreach ($replica in @(1,2)) {
        $step = if ($replica -eq 1) {1} else {9}
        $delay = if ($replica -eq 1) {$case[3]} else {$case[4]}
        Add-Series $series "$($case[0])_count" 'ai' "0+${step}x220" @{} "ai-$replica"
        foreach ($le in @('0.1','0.5','1.0','2.0','5.0','10.0','30.0','60.0','+Inf')) {
            $bound = if ($le -eq '+Inf') {[double]::PositiveInfinity} else {[double]::Parse($le,[Globalization.CultureInfo]::InvariantCulture)}
            $bucketStep = if ($bound -ge $delay) {$step} else {0}
            Add-Series $series "$($case[0])_bucket" 'ai' "0+${bucketStep}x220" @{le=$le} "ai-$replica"
            $otherStep = if ($bound -ge 60) {100} else {0}
            Add-Series $series "$($case[0])_bucket" 'ai' "0+${otherStep}x220" @{le=$le} 'ai-other' 'other-db'
            Add-Series $series "$($case[0])_bucket" 'ai' "0+${otherStep}x220" @{le=$le;environment='production'} 'ai-other'
        }
    }
    $query = $panels['kb-search-ai.json'].panels[$case[1]].targets[0].expr.Replace('$environment','staging').Replace('$deployment','kb-main')
    $firstEventQueries.Add(@{expr="round($query,0.001)";eval_time='10m';exp_samples=@(
        @{labels='{environment="staging",deployment="kb-main",service="ai"}';value=$case[2]})})
}
$tests.Add(@{name='dashboard-first-event-p95-with-traffic';interval='30s';input_series=@($series.Values);promql_expr_test=@($firstEventQueries.ToArray())})
# 每个实际面板表达式由 promtool 解析；使用不存在的部署断言不串入当前部署。
$panels = New-MonitoringDashboards
$queries = [Collections.Generic.List[object]]::new()
foreach ($dashboard in $panels.Values) {
    foreach ($panel in $dashboard.panels) {
        $query = $panel.targets[0].expr.Replace('$environment','absent').Replace('$deployment','absent')
        $queries.Add(@{expr=$query;eval_time='10m';exp_samples=@()})
    }
}
$tests.Add(@{name='dashboard-deployment-isolation';interval='30s';input_series=@((Baseline).Values);promql_expr_test=@($queries.ToArray())})
$result = @{rule_files=@('config/rules.json');evaluation_interval='30s';group_eval_order=@('kb-recording','kb-alerts');tests=@($tests.ToArray())}
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'rules.test.json'), (ConvertTo-Json $result -Depth 64), [Text.UTF8Encoding]::new($false))
Write-Output "已生成 $($tests.Count) 组合成时序，含 20 类告警和 $($queries.Count) 个面板查询"
# 显式不部署 AI 使用真实的另一份生成规则，不以缺少 up 反推预期状态。
$optional = Read-MonitoringJson (Join-Path $root 'settings.example.json')
$optional.services[2].expected = $false
$optional.services[2].targets = @()
$optionalPath = Join-Path $OutputDirectory 'optional-settings.json'
[IO.File]::WriteAllText($optionalPath, (ConvertTo-Json $optional -Depth 16), [Text.UTF8Encoding]::new($false))
& (Join-Path $root 'scripts/render-config.ps1') -SettingsPath $optionalPath -OutputDirectory (Join-Path $OutputDirectory 'config-optional') | Out-Null
$series = Baseline
foreach ($key in @($series.Keys)) {
    if ($key.Contains('service="ai"')) { $series.Remove($key) }
}
$query = $panels['kb-overview.json'].panels[0].targets[0].expr.Replace('$environment','staging').Replace('$deployment','kb-main')
$optionalTest = @{rule_files=@('config-optional/rules.json');evaluation_interval='30s';group_eval_order=@('kb-recording','kb-alerts');tests=@(
    @{name='ai-explicitly-not-deployed';interval='30s';input_series=@($series.Values);alert_rule_test=@(
        (Alert-Check 'KbServiceUnavailable' '70m' $false 'ai'),(Alert-Check 'KbConversationConfigurationMismatch' '70m' $false 'app'));
        promql_expr_test=@(@{expr="sum(($query) and on(service) kb:expected_service{service=`"ai`"})";eval_time='10m';exp_samples=@(@{labels='{}';value=-1})})})}
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'optional.test.json'), (ConvertTo-Json $optionalTest -Depth 32), [Text.UTF8Encoding]::new($false))
Write-Output '已生成额外 1 组 AI 未部署场景：optional.test.json'
