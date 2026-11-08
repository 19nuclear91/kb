<script setup lang="ts">
import { exact } from './operationsReportLabels';

defineProps<{ label: string; value: string | null }>();
</script>

<template><div><dt>{{ label }}</dt><dd>{{ exact(value) }}</dd></div></template>
