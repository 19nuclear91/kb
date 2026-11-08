<script setup lang="ts">
import { ElTag } from 'element-plus';

defineProps<{ status: string }>();
</script>

<template><ElTag :type="status === 'SUCCESS' ? 'success' : status === 'FAILED' ? 'danger' : 'info'">{{ ({ SUCCESS: '成功', FAILED: '失败' } as Record<string, string>)[status] ?? status }}</ElTag></template>
