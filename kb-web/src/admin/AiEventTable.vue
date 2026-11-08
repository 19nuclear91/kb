<script setup lang="ts">
import { ElButton, ElTable, ElTableColumn, ElTag } from 'element-plus';
import type { AiEvent } from './aiUsageApi';
import type { AiQuery } from './aiUsageQuery';
import AiTime from './AiTime.vue';

defineProps<{ query: AiQuery }>();
const usageLabels: Record<string, string> = { KNOWN: '已知', UNKNOWN: '未知', NOT_INVOKED: '未调用模型' };
const beforeUsage = { mode: '模式', userId: '用户 ID', departmentId: '部门 ID', generationStatus: '生成状态', resultCode: '结果码' };
const afterUsage = { plannedChargedTokens: '计划扣除额度', settledChargedTokens: '已确认结算额度', settlementStatus: '结算状态' };
</script>

<template><ElTable row-key="eventId" :data="query.items as AiEvent[]" empty-text="当前窗口没有已记录 AI 事件，需结合覆盖信息判断。"><ElTableColumn label="发生时间"><template #default="{ row }"><AiTime :value="row.occurredAt" /></template></ElTableColumn><ElTableColumn label="调用 ID" prop="callId" /><ElTableColumn label="事件类型" prop="eventType" />
  <ElTableColumn v-for="(label, key) in beforeUsage" :key="key" :label="label"><template #default="{ row }">{{ row.snapshot[key] ?? '未记录' }}</template></ElTableColumn><ElTableColumn label="真实用量"><template #default="{ row }"><ElTag v-if="row.snapshot.usageStatus">{{ usageLabels[row.snapshot.usageStatus] ? `${usageLabels[row.snapshot.usageStatus]} · ` : '' }}{{ row.snapshot.usageStatus }}</ElTag><template v-else>未记录</template></template></ElTableColumn>
  <ElTableColumn v-for="(label, key) in { promptTokens: '实际输入 token', completionTokens: '实际输出 token' }" :key="key" :label="label"><template #default="{ row }">{{ row.snapshot[key] ?? (row.snapshot.usageStatus === 'UNKNOWN' ? '未知（null）' : '未记录') }}</template></ElTableColumn><ElTableColumn v-for="(label, key) in afterUsage" :key="key" :label="label"><template #default="{ row }">{{ row.snapshot[key] ?? '未记录' }}</template></ElTableColumn>
  <ElTableColumn label="详情"><template #default="{ row }"><ElButton size="small" @click="query.open(row.eventId)">查看事件 {{ row.eventId }}</ElButton></template></ElTableColumn>
</ElTable></template>
