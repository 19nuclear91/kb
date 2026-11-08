<script setup lang="ts">
import { ElButton } from 'element-plus';
import type { Acl } from './spaceApi';

defineProps<{ acl: Acl; disabled: boolean }>();
const emit = defineEmits<{ revoke: [value: Acl] }>();
</script>

<template><ElButton :aria-label="`撤销 ${acl.principalName} ${acl.permission}`" :disabled="disabled" @click="emit('revoke', acl)">撤销</ElButton></template>
