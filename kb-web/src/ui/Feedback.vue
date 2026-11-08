<script setup lang="ts">
import { ElButton, ElEmpty } from 'element-plus';
import KbAlert from './KbAlert.vue';
import KbSpin from './KbSpin.vue';

defineOptions({ inheritAttrs: false });
defineProps<{ loading?: boolean; error?: string; empty?: boolean; retry?: () => void }>();
</script>

<template>
  <div v-if="loading" class="panel" role="status"><KbSpin /> <span class="muted">正在读取…</span></div>
  <KbAlert v-else-if="error" type="error" show-icon :title="error">
    <template v-if="retry" #action><ElButton @click="retry()">重试</ElButton></template>
  </KbAlert>
  <ElEmpty v-else-if="empty" description="暂无可用内容" />
</template>
