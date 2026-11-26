<script setup lang="ts">
import type { Dates } from './types';
defineProps<{ value: Dates }>();
</script>
<template><span>生效：{{ value.effectiveFrom ?? '未声明' }}；截止：{{ value.untilKind === 'OPEN_ENDED' ? '长期有效' : value.effectiveUntil ?? '未声明' }}（含当天）</span></template>
