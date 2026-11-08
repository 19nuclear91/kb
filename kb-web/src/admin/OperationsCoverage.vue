<script setup lang="ts">
import { ElTag } from 'element-plus';
import type { OperationsCoverage } from './operationsReportsApi';
import { coverageLabels } from './operationsReportLabels';

defineProps<{ value: OperationsCoverage }>();
</script>

<template><div><ElTag>{{ coverageLabels[value.status] }}</ElTag><p>采集起点：{{ value.coverageStartedAt ?? '尚未开始采集' }}</p><p>有效观测区间：{{ value.effectiveFrom && value.effectiveTo ? `[${value.effectiveFrom}, ${value.effectiveTo})` : '无' }}</p><p v-if="value.status !== 'COMPLETE'" class="muted">未覆盖历史保持未知，不能解释为零或业务下滑。</p></div></template>
