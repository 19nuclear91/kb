<script setup lang="ts">
import { ElTag } from 'element-plus';
import type { Applicability, Badge } from './api';
import { applicabilityLabel } from './labels';

defineProps<{ value: Applicability | Badge; showCurrent?: boolean }>();
</script>
<template>
  <span class="applicability-badge"><ElTag :type="value.state === 'EXPIRED' ? 'danger' : value.state === 'EXPIRING_SOON' || value.state === 'NOT_YET_EFFECTIVE' ? 'warning' : 'info'">{{ applicabilityLabel(value) }}</ElTag>
    <ElTag v-if="value.corrected" type="warning">已更正</ElTag><span v-if="showCurrent && 'current' in value && !value.current" class="muted"> · 非当前版本</span>
  </span>
</template>
