Set-StrictMode -Version Latest
function New-MonitoringDashboards {
    $scope = 'environment="$environment",deployment="$deployment"'
    $definitions = @(
        @('kb-overview','知识库 · 服务总览',@(
            @('服务状态：1 存活／0 失联／-1 未部署','kb:expected_service{@scope@} * (1 + (kb:service_up{@scope@} or on(environment,deployment,service) (kb:expected_service{@scope@} * 0))) - 1','short','service'),
            @('固定指标契约：1 缺失／0 完整','kb:contract_missing{@scope@} or on(environment,deployment,service) (kb:service_contract_healthy{@scope@} * 0)','short','contract'),
            @('可信采样：1 有效／0 不可用','kb:sample_valid{@scope@} or on(environment,deployment,service,instance,source) (kb:sample_expected{@scope@} * 0)','short','sample'),
            @('距最后成功采样秒数（负数代表未来时间）','time() - kb_monitoring_sample_last_success_timestamp_seconds{@scope@}','s',''),
            @('规则求值失败 / 秒','sum by(environment,deployment,service)(rate(prometheus_rule_evaluation_failures_total{@scope@}[5m]))','ops',''),
            @('Alertmanager 失败 / 秒','sum by(environment,deployment,service)(rate(alertmanager_notifications_failed_total{@scope@,integration="webhook"}[5m]))','ops',''),
            @('通知尝试结果 / 秒','sum by(environment,deployment,service,outcome)(rate(kb_alert_notifier_deliveries_total{@scope@}[5m]))','ops',''),
            @('通知容量拒绝 / 秒','sum by(environment,deployment,service,reason)(rate(kb_alert_notifier_rejections_total{@scope@}[5m]))','ops',''),
            @('通知当前并发','kb_alert_notifier_inflight{@scope@}','short',''),
            @('HTTP 4xx / 5xx 趋势','sum by(environment,deployment,service,status)(rate(http_server_requests_seconds_count{@scope@,status=~"4..|5.."}[5m]))','ops','')
        )),
        @('kb-parse-index','知识库 · 解析与索引',@(
            @('可尝试 pending 队列（同库 max 去重）','kb:parse_queue_depth{@scope@}','short',''),
            @('pending 最老等待（包含未来退避，下限零）','kb:parse_oldest_age_seconds{@scope@}','s',''),
            @('OPEN 死信（维护期间仍显示）','kb:parse_dead_letter_depth{@scope@}','short',''),
            @('15 分钟租约失守增量','kb:parse_lease_loss_15m{@scope@}','short',''),
            @('解析器本地 configured（不是远程探活）','kb_parse_parser_configured{@scope@} and on(environment,deployment,service,instance) (up{@scope@} == 1)','short','feature'),
            @('索引状态（one-hot；副本差异保守显示）','kb:embedding_mode{@scope@}','short',''),
            @('索引重建进度（同库 max 去重）','kb:embedding_progress{@scope@}','short',''),
            @('持久索引事件数（Gauge，不累加副本）','kb:embedding_events_persisted{@scope@}','short',''),
            @('索引错误类别','kb:embedding_error{@scope@}','short',''),
            @('来源可用性（0 不是空队列）','kb:sample_valid{@scope@,service="worker"} or on(environment,deployment,service,instance,source) (kb:sample_expected{@scope@,service="worker"} * 0)','short','sample')
        )),
        @('kb-search-ai','知识库 · 检索与 AI',@(
            @('搜索 5 分钟流量（健康契约下零为无流量）','kb:search_traffic_5m{@scope@}','short','traffic'),
            @('搜索业务结果 / 秒','sum by(environment,deployment,service,outcome)(rate(kb_search_requests_total{@scope@}[5m]))','ops',''),
            @('搜索失败率（SUCCESS + EMPTY + FAILED）','kb:search_failure_ratio{@scope@}','percentunit',''),
            @('召回 RETRIEVAL 阶段 P95（不是搜索总耗时）','kb:retrieval_p95_seconds{@scope@}','s',''),
            @('分阶段降级率','kb:search_degradation_ratio{@scope@}','percentunit',''),
            @('搜索拒绝 / 秒','sum by(environment,deployment,service,reason)(rate(kb_search_rejections_total{@scope@}[5m]))','ops',''),
            @('模型 5 分钟流量','kb:model_traffic_5m{@scope@}','short','traffic'),
            @('模型调用结果（含 LIMIT、CANCELLED）','sum by(environment,deployment,service,outcome)(rate(kb_model_calls_total{@scope@}[5m]))','ops',''),
            @('模型首字 P95','histogram_quantile(0.95,sum by(environment,deployment,service,le)(rate(kb_model_first_text_delay_seconds_bucket{@scope@}[5m])))','s',''),
            @('模型 usage KNOWN / UNKNOWN','sum by(environment,deployment,service,status)(rate(kb_model_usage_total{@scope@}[5m]))','ops',''),
            @('SSE 5 分钟流量','kb:chat_traffic_5m{@scope@}','short','traffic'),
            @('SSE 业务结果（HTTP 200 不代表成功）','sum by(environment,deployment,service,outcome,error_code)(rate(kb_chat_streams_total{@scope@}[5m]))','ops',''),
            @('SSE 首个内容事件 P95（可能是 citation）','histogram_quantile(0.95,sum by(environment,deployment,service,le)(rate(kb_chat_first_event_delay_seconds_bucket{@scope@}[5m])))','s',''),
            @('有效功能开关（逐实例显示，不把缺失当关闭）','kb_monitoring_feature_enabled{@scope@} and on(environment,deployment,service,instance) (up{@scope@} == 1)','short','feature'),
            @('持久结算 PENDING（关闭新会话仍采样）','kb:settlement_pending{@scope@}','short',''),
            @('持久结算 REVIEW（不是观测副本）','kb:settlement_review{@scope@}','short',''),
            @('PENDING 最老 finalized_at 年龄','kb:settlement_oldest_age_seconds{@scope@}','s',''),
            @('结算来源有效性（观测页面可能无记录）','kb:sample_valid{@scope@,source="settlement"} or on(environment,deployment,service,instance,source) (kb:sample_expected{@scope@,source="settlement"} * 0)','short','sample')
        ))
    )
    $result = [ordered]@{}
    foreach ($definition in $definitions) {
        $panels = [Collections.Generic.List[object]]::new()
        $index = 0
        foreach ($item in $definition[2]) {
            $mapping = switch ($item[3]) {
                'service' { @{'-1'=@{text='未部署';color='gray'};'0'=@{text='失联';color='red'};'1'=@{text='存活';color='green'}} }
                'contract' { @{'0'=@{text='契约完整';color='green'};'1'=@{text='契约缺失';color='red'}} }
                'sample' { @{'0'=@{text='采样不可用';color='red'};'1'=@{text='有效样本';color='green'}} }
                'feature' { @{'0'=@{text='关闭／未配置';color='gray'};'1'=@{text='开启／已配置';color='blue'}} }
                'traffic' { @{'0'=@{text='无流量';color='gray'}} }
                default { @{} }
            }
            $panels.Add(@{id=($index+1);title=$item[0];type=$(if ($item[3]) {'stat'} else {'timeseries'});
                datasource=@{type='prometheus';uid='kb-prometheus'};gridPos=@{x=(($index%2)*12);y=([int][Math]::Floor($index/2)*8);w=12;h=8};
                targets=@(@{refId='A';expr=$item[1].Replace('@scope@',$scope);instant=[bool]$item[3];range=(-not [bool]$item[3]);legendFormat='{{service}} {{instance}} {{source}} {{feature}} {{outcome}} {{state}} {{result}} {{operation}}'});
                fieldConfig=@{defaults=@{unit=$item[2];noValue='不可用／尚无样本';mappings=@(@{type='value';options=$mapping});color=@{mode='palette-classic'}};overrides=@()};
                options=@{reduceOptions=@{calcs=@('lastNotNull');fields='';values=$false};colorMode='value';graphMode='none'}})
            $index++
        }
        $result["$($definition[0]).json"] = @{
            uid=$definition[0];title=$definition[1];schemaVersion=41;version=1;editable=$false;refresh='30s';timezone='browser';
            time=@{from='now-6h';to='now'};tags=@('kb','monitoring');panels=@($panels.ToArray());
            templating=@{list=@(
                @{name='environment';label='环境';type='query';datasource=@{type='prometheus';uid='kb-prometheus'};query='label_values(kb:expected_service,environment)';refresh=1;multi=$false;includeAll=$false},
                @{name='deployment';label='部署';type='query';datasource=@{type='prometheus';uid='kb-prometheus'};query='label_values(kb:expected_service{environment="$environment"},deployment)';refresh=1;multi=$false;includeAll=$false})};
            links=@(@{title='服务总览';url='/d/kb-overview';type='link';includeVars=$true},@{title='解析索引';url='/d/kb-parse-index';type='link';includeVars=$true},@{title='检索 AI';url='/d/kb-search-ai';type='link';includeVars=$true})
        }
    }
    return $result
}
Export-ModuleMember -Function New-MonitoringDashboards
