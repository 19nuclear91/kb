<script setup lang="ts">
import { ElTable, ElTableColumn } from 'element-plus';
import { metricLabels, type AiGroup, type Metrics } from './aiUsageApi';
import AiTime from './AiTime.vue';

defineProps<{ items: AiGroup[]; groupBy: string }>();
const keys: (keyof Metrics)[] = ['callCount', 'modelDispatchCount', 'knownCount', 'unknownCount', 'notInvokedCount', 'promptTokens', 'completionTokens', 'plannedChargedTokens', 'settledChargedTokens', 'pendingCount', 'reviewCount'];
</script>

<template><p>每页分组有独立 asOf，活跃调用可能变化；跨页不代表同一冻结快照。</p><ElTable row-key="key" :data="items" empty-text="当前窗口没有已记录分组，需结合覆盖信息判断。"><ElTableColumn :label="groupBy === 'day' ? 'UTC 日期' : groupBy === 'department' ? '部门 ID' : '用户 ID'" prop="key" fixed="left" /><ElTableColumn v-for="key in keys" :key="key" :label="metricLabels[key]"><template #default="{ row }">{{ row.metrics[key] === null ? '无已知样本' : row.metrics[key] }}</template></ElTableColumn><ElTableColumn label="该页 asOf"><template #default="{ row }"><AiTime :value="row.asOf" /></template></ElTableColumn></ElTable></template>
