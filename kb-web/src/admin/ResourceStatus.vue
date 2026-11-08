<script setup lang="ts">
import { ElButton } from 'element-plus';
import KbAlert from '../ui/KbAlert.vue';

defineProps<{ loading: boolean; error?: string; retry: () => void }>();
</script>

<template>
  <KbAlert v-if="error" type="warning" :title="error"><template #action><ElButton @click="retry">重新读取</ElButton></template></KbAlert>
  <p v-else-if="loading" role="status">正在读取…</p>
</template>
