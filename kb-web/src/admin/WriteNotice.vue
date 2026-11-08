<script setup lang="ts">
import { ElButton, ElSpace } from 'element-plus';
import type { AdminWrite } from './hooks';
import KbAlert from '../ui/KbAlert.vue';

withDefaults(defineProps<{ write: AdminWrite; onRetry: () => void; onVerify?: () => void; disabled?: boolean }>(), { disabled: false });
</script>

<template>
  <KbAlert v-if="write.phase === 'uncertain'" type="warning" show-icon style="margin-bottom: 16px" :title="write.message" description="放弃核对后再提交会生成新的请求标识；放弃不会撤回已经发出的写入，关闭页面也不能撤回。">
    <template #action><ElSpace direction="vertical">
      <ElButton size="small" type="primary" :disabled="disabled" @click="onRetry">按原内容重试</ElButton>
      <ElButton v-if="onVerify" size="small" @click="onVerify">重新读取核对</ElButton><ElButton size="small" @click="write.reset">放弃核对</ElButton>
    </ElSpace></template>
  </KbAlert>
  <KbAlert v-else-if="write.phase === 'rejected'" type="error" show-icon style="margin-bottom: 16px" :title="write.message" />
  <KbAlert v-else-if="write.phase === 'submitting'" type="info" show-icon style="margin-bottom: 16px" title="正在提交，请勿关闭…" />
</template>
