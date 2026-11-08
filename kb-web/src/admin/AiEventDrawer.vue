<script setup lang="ts">
import { computed } from 'vue';
import { ElButton, ElDescriptions, ElDescriptionsItem } from 'element-plus';
import Feedback from '../ui/Feedback.vue';
import KbAlert from '../ui/KbAlert.vue';
import KbDrawer from '../ui/KbDrawer.vue';
import type { AiQuery } from './aiUsageQuery';
import AiTime from './AiTime.vue';

const props = defineProps<{ query: AiQuery }>();
const detail = computed(() => props.query.detail!), data = computed(() => detail.value.data);
const snapshotLabels: Record<string, string> = { callId: '调用 ID', turnId: '轮次 ID', userId: '用户 ID', departmentId: '部门 ID', mode: '调用模式', generationStatus: '生成状态', resultCode: '结果码', usageStatus: '真实用量状态', settlementStatus: '结算状态', settlementPolicy: '结算策略', promptTokens: '实际输入 token', completionTokens: '实际输出 token', reservedTokens: '预留额度', plannedChargedTokens: '计划扣除额度', settledChargedTokens: '已确认结算额度', startedAt: '登记时间', finalizedAt: '生成终结时间', settledAt: '结算确认时间', elapsedMs: '耗时（毫秒）' };
</script>

<template><KbDrawer :title="`AI 事件 ${detail.eventId}`" open @close="query.close"><Feedback :loading="detail.loading" /><KbAlert v-if="detail.error" type="warning" :title="detail.status === 404 ? '该事件不存在或已清理。' : detail.error"><template v-if="detail.status !== 404" #action><ElButton @click="query.open(detail.eventId)">重试详情</ElButton></template></KbAlert>
  <template v-if="data && !detail.loading && !detail.error"><ElDescriptions :column="1"><ElDescriptionsItem label="事件 ID">{{ data.eventId }}</ElDescriptionsItem><ElDescriptionsItem label="内部编号">{{ data.id }}</ElDescriptionsItem><ElDescriptionsItem label="调用 ID">{{ data.callId }}</ElDescriptionsItem><ElDescriptionsItem label="事件类型">{{ data.eventType }}</ElDescriptionsItem><ElDescriptionsItem label="发生时间"><AiTime :value="data.occurredAt" /></ElDescriptionsItem><ElDescriptionsItem label="入库时间"><AiTime :value="data.recordedAt" /></ElDescriptionsItem></ElDescriptions>
    <KbAlert v-if="!data.snapshotAvailable" type="info" title="此事件快照不可用，以上为安全关联信息。" /><ElDescriptions v-else :column="1"><ElDescriptionsItem v-for="(label, key) in snapshotLabels" :key="key" :label="label"><span class="content-text">{{ data.snapshot[key] === null || data.snapshot[key] === undefined ? (['promptTokens', 'completionTokens'].includes(key) && data.snapshot.usageStatus === 'UNKNOWN' ? '未知（null）' : '未记录') : data.snapshot[key] }}</span></ElDescriptionsItem></ElDescriptions><p class="muted">这是该阶段提交时的快照。同一调用可有多个事件；详情不补查名称或正文。</p>
  </template>
</KbDrawer></template>
