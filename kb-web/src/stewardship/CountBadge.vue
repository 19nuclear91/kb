<script setup lang="ts">
import { computed } from 'vue';
import { useStewardshipCount } from './count';
import { countLabel } from './poller';
const count = useStewardshipCount(), label = computed(() => countLabel(count.state));
</script>
<template><span v-if="label" class="nav-badge" :aria-label="label === '?' ? '复审数量待确认' : `需要处理 ${label} 份资料`">{{ label }}</span></template>
