Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function New-MonitoringRules($Settings, $Catalog) {
    $group = 'environment,deployment,service'
    $instance = "$group,instance"
    $source = "$instance,source"
    $t = $Settings.thresholds
    $records = [Collections.Generic.List[object]]::new()
    $expected = @($Settings.services) + @(
        @{service='notifier'; expected=$true}, @{service='prometheus'; expected=$true},
        @{service='alertmanager'; expected=$true}, @{service='grafana'; expected=$true})
    foreach ($service in $expected) {
        $records.Add(@{record='kb:expected_service'; expr="vector($([int]$service.expected))"; labels=@{
            environment=$Settings.environment; deployment=$Settings.deployment; service=$service.service}})
    }
    $records.Add(@{record='kb:parser_expected';expr="vector($([int]$Settings.parserExpected))";labels=@{
        environment=$Settings.environment;deployment=$Settings.deployment;service='worker'}})
    $records.Add(@{record='kb:service_up';expr="max by ($group) (up{job=~`"kb-.+`"})"})
    foreach ($pair in @(@('worker','parse'),@('worker','embedding'),@('app','settlement'))) {
        $records.Add(@{record='kb:sample_expected';expr="up{service=`"$($pair[0])`"} * 0 + 1";labels=@{source=$pair[1]}})
    }
    $records.Add(@{record='kb:sample_valid';expr=@"
(kb_monitoring_sample_available == 1)
and on ($source) (kb_monitoring_sample_last_success_timestamp_seconds > 0)
and on ($source) (kb_monitoring_sample_last_success_timestamp_seconds <= time())
and on ($source) (time() - kb_monitoring_sample_last_success_timestamp_seconds <= $($t.freshnessSeconds))
and on ($instance) (up == 1)
"@})
    $contracts = @{
        worker=@('kb_parse_parser_configured','kb_monitoring_sample_available{source="parse"}',
            'kb_monitoring_sample_last_success_timestamp_seconds{source="parse"}',
            'kb_monitoring_sample_available{source="embedding"}',
            'kb_monitoring_sample_last_success_timestamp_seconds{source="embedding"}')
        app=@('kb_monitoring_sample_available{source="settlement"}',
            'kb_monitoring_sample_last_success_timestamp_seconds{source="settlement"}',
            'kb_monitoring_feature_enabled{feature="conversation"}','kb_monitoring_feature_enabled{feature="ai_observation"}',
            'kb_search_inflight')
        ai=@('kb_monitoring_feature_enabled{feature="conversation"}','kb_monitoring_feature_enabled{feature="ai_observation"}')
        notifier=@('kb_alert_notifier_inflight')
    }
    $missing = [Collections.Generic.List[string]]::new()
    foreach ($service in $contracts.Keys) {
        foreach ($metric in $contracts[$service]) {
            $missing.Add("(up{service=`"$service`"} == 1 unless on ($instance) $metric)")
        }
    }
    $records.Add(@{record='kb:contract_missing';expr="max by ($group) ($($missing -join ' or '))"})
    $records.Add(@{record='kb:service_contract_healthy';expr="kb:service_up == 1 unless on ($group) kb:contract_missing"})
    $database = @(
        @('parse','kb_parse_queue_depth','kb:parse_queue_depth',''),
        @('parse','kb_parse_dead_letter_depth','kb:parse_dead_letter_depth',''),
        @('parse','kb_parse_queue_oldest_age_seconds','kb:parse_oldest_age_seconds',''),
        @('embedding','kb_embedding_mode','kb:embedding_mode',',state'),
        @('embedding','kb_embedding_error','kb:embedding_error',',category'),
        @('embedding','kb_embedding_events_persisted','kb:embedding_events_persisted',',operation'),
        @('embedding','kb_embedding_progress','kb:embedding_progress',',result'),
        @('settlement','kb_conversation_settlement_pending','kb:settlement_pending',''),
        @('settlement','kb_conversation_settlement_review','kb:settlement_review',''),
        @('settlement','kb_conversation_settlement_oldest_pending_age_seconds','kb:settlement_oldest_age_seconds',''))
    foreach ($entry in $database) {
        $records.Add(@{record=$entry[2];expr="max by ($group$($entry[3])) ($($entry[1]) and on ($instance) kb:sample_valid{source=`"$($entry[0])`"})"})
    }
    $records.Add(@{record='kb:parser_configured';expr="max by ($group) (kb_parse_parser_configured and on ($instance) (up == 1))"})
    $records.Add(@{record='kb:feature_enabled';expr="max by ($group,feature) (kb_monitoring_feature_enabled and on ($instance) (up == 1))"})
    $records.Add(@{record='kb:search_requests_5m';expr="sum by ($group) (increase(kb_search_requests_total{outcome=~`"SUCCESS|EMPTY|FAILED`"}[5m]))"})
    $records.Add(@{record='kb:search_failure_ratio';expr="sum by ($group) (increase(kb_search_requests_total{outcome=`"FAILED`"}[5m])) / kb:search_requests_5m"})
    $records.Add(@{record='kb:retrieval_samples_5m';expr="sum by ($group) (increase(kb_search_stage_duration_seconds_count{stage=`"RETRIEVAL`"}[5m]))"})
    $records.Add(@{record='kb:retrieval_p95_seconds';expr="histogram_quantile(0.95, sum by ($group,le) (rate(kb_search_stage_duration_seconds_bucket{stage=`"RETRIEVAL`"}[5m])))"})
    $records.Add(@{record='kb:search_degradation_ratio';expr="sum by ($group,stage) (increase(kb_search_degradation_total{stage=~`"EMBEDDING|RETRIEVAL|RERANK`"}[5m])) / on ($group) group_left kb:search_requests_5m"})
    $records.Add(@{record='kb:model_calls_5m';expr="sum by ($group) (increase(kb_model_calls_total{outcome!=`"CANCELLED`"}[5m]))"})
    $records.Add(@{record='kb:model_failure_ratio';expr="sum by ($group) (increase(kb_model_calls_total{outcome=~`"TIMEOUT|PROTOCOL_ERROR|UPSTREAM_ERROR`"}[5m])) / kb:model_calls_5m"})
    $records.Add(@{record='kb:chat_streams_5m';expr="sum by ($group) (increase(kb_chat_streams_total{outcome!=`"CANCELLED`"}[5m]))"})
    $records.Add(@{record='kb:chat_error_ratio';expr="sum by ($group) (increase(kb_chat_streams_total{outcome=`"TIMEOUT`"}[5m]) or increase(kb_chat_streams_total{outcome=`"ERROR`",error_code!=`"INPUT_BUDGET_EXCEEDED`"}[5m])) / kb:chat_streams_5m"})
    $records.Add(@{record='kb:parse_lease_loss_15m';expr="sum by ($group) (increase(kb_parse_tasks_lease_lost_total[15m]))"})
    # 无事件仅在服务及固定契约健康时解释为无流量；失败计数和直方图不补零。
    foreach ($pair in @(@('app','search','kb_search_requests_total'),@('ai','model','kb_model_calls_total'),@('ai','chat','kb_chat_streams_total'))) {
        $records.Add(@{record="kb:$($pair[1])_traffic_5m";expr="(sum by ($group) (increase($($pair[2])[5m])) or on ($group) (kb:service_contract_healthy{service=`"$($pair[0])`"} * 0)) and on ($group) kb:service_contract_healthy"})
    }
    $expressions = @{
        KbServiceUnavailable="kb:expected_service == 1 unless on ($group) (kb:service_up == 1)"
        KbServiceReplicaUnavailable="(sum by ($group) (up{job=~`"kb-.+`"} == bool 0) > 0) and on ($group) (kb:service_up == 1)"
        KbMonitoringContractMissing='kb:contract_missing > 0'
        KbMetricSampleUnavailable="max by ($group) (kb:sample_expected unless on ($source) kb:sample_valid) and on ($group) (kb:service_up == 1)"
        KbParserNotConfigured="kb:parser_configured == 0 and on ($group) (kb:parser_expected == 1)"
        KbParseQueueAging="kb:parse_oldest_age_seconds > $($t.queueAgeSeconds) and on ($group) (kb:parser_configured == 1) and on ($group) (kb:embedding_mode{state=`"ACTIVE`"} == 1) unless on ($group) (kb:embedding_mode{state!=`"ACTIVE`"} == 1)"
        KbParseDeadLetters='kb:parse_dead_letter_depth > 0'
        KbParseLeaseLoss="kb:parse_lease_loss_15m >= $($t.leaseLossCount)"
        KbEmbeddingBlocked="max by ($group) (kb:embedding_mode{state=`"BLOCKED`"} == 1)"
        KbEmbeddingMaintenanceLong="max by ($group) (kb:embedding_mode{state=~`"UNINITIALIZED|QUIESCING|REBUILDING|VERIFYING`"} == 1) and on ($group) (kb:parser_expected == 1)"
        KbSearchFailures="kb:search_failure_ratio > $($t.searchFailureRatio) and on ($group) (kb:search_requests_5m >= $($t.searchMinRequests)) and on ($group) kb:service_contract_healthy"
        KbSearchRetrievalSlow="kb:retrieval_p95_seconds > $($t.retrievalP95Seconds) and on ($group) (kb:retrieval_samples_5m >= $($t.searchMinRequests)) and on ($group) kb:service_contract_healthy"
        KbSearchDegradation="max by ($group) (kb:search_degradation_ratio > $($t.degradationRatio)) and on ($group) (kb:search_requests_5m >= $($t.searchMinRequests)) and on ($group) kb:service_contract_healthy"
        KbModelFailures="kb:model_failure_ratio > $($t.modelFailureRatio) and on ($group) (kb:model_calls_5m >= $($t.modelMinRequests)) and on ($group) kb:service_contract_healthy"
        KbChatSystemErrors="kb:chat_error_ratio > $($t.chatErrorRatio) and on ($group) (kb:chat_streams_5m >= $($t.chatMinRequests)) and on ($group) kb:service_contract_healthy"
        KbSettlementPendingOld="kb:settlement_oldest_age_seconds > $($t.settlementAgeSeconds) and on ($group) (kb:settlement_pending > 0)"
        KbSettlementReview='kb:settlement_review > 0'
        KbConversationConfigurationMismatch=@"
label_replace((max by (environment,deployment) (kb_monitoring_feature_enabled{service=~"app|ai",feature="conversation"} and on ($instance) (up == 1))
!= min by (environment,deployment) (kb_monitoring_feature_enabled{service=~"app|ai",feature="conversation"} and on ($instance) (up == 1)))
and on (environment,deployment) (count by (environment,deployment) (count by ($group) (kb_monitoring_feature_enabled{service=~"app|ai",feature="conversation"} and on ($instance) (up == 1))) == 2), "service", "app", "", "")
"@
        KbNotificationDeliveryFailures="(sum by ($group) (increase(alertmanager_notifications_failed_total{integration=`"webhook`"}[5m])) > 0) or (sum by ($group) (increase(kb_alert_notifier_deliveries_total{outcome!=`"success`"}[5m])) > 0) or (sum by ($group) (increase(kb_alert_notifier_rejections_total{reason=~`"rate|concurrency`"}[5m])) > 0)"
        KbRuleEvaluationFailures="sum by ($group) (increase(prometheus_rule_evaluation_failures_total[5m])) > 0"
    }
    $alerts = [Collections.Generic.List[object]]::new()
    foreach ($entry in $Catalog) {
        if (-not $expressions.ContainsKey($entry.name)) { throw '目录缺少规则实现' }
        $alerts.Add(@{alert=$entry.name;expr="($($expressions[$entry.name])) and on ($group) (kb:expected_service == 1)";
            for="$($Settings.waitSeconds[$entry.name])s";labels=@{severity=$entry.severity;family=$entry.family};annotations=@{summary=$entry.summary}})
    }
    return @{groups=@(@{name='kb-recording';interval='30s';rules=@($records.ToArray())},@{name='kb-alerts';interval='30s';rules=@($alerts.ToArray())})}
}

function New-MonitoringAlertmanager($Settings) {
    $equal = @('environment','deployment','service')
    $downstream = 'KbParserNotConfigured|KbParseQueueAging|KbParseDeadLetters|KbParseLeaseLoss|KbEmbeddingBlocked|KbEmbeddingMaintenanceLong|KbSearchFailures|KbSearchRetrievalSlow|KbSearchDegradation|KbModelFailures|KbChatSystemErrors|KbSettlementPendingOld|KbSettlementReview'
    return @{
        route=@{receiver='dingtalk';group_by=@('environment','deployment','service','alertname');group_wait='30s';group_interval='5m';repeat_interval='4h'}
        receivers=@(@{name='dingtalk';webhook_configs=@(@{url='http://notifier:8090/internal/alerts/dingtalk';send_resolved=$true;max_alerts=100;timeout='15s';
            http_config=@{follow_redirects=$false;authorization=@{type='Bearer';credentials_file='/run/secrets/notifier_token'}}})})
        # 采样根因由业务表达式的来源级有效样本门控处理；不能用服务级抑制掩盖其他健康副本／来源。
        inhibit_rules=@(
            @{source_matchers=@('alertname="KbServiceUnavailable"');target_matchers=@("alertname=~`"$downstream|KbMetricSampleUnavailable|KbMonitoringContractMissing`"");equal=$equal},
            @{source_matchers=@('severity="critical"');target_matchers=@('severity="warning"');equal=@($equal)+@('family')})
    }
}

Export-ModuleMember -Function New-MonitoringRules,New-MonitoringAlertmanager
