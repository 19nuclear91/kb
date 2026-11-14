<script setup lang="ts">
import { shallowRef } from 'vue';
import { RouterLink } from 'vue-router';
import { ElButton } from 'element-plus';
import KbDialog from '../ui/KbDialog.vue';
import GapCreateForm from './GapCreateForm.vue';
import type { GapReceipt, GapSource } from './types';

defineProps<{ source: GapSource; initialDescription: string }>();
const emit = defineEmits<{ close: [] }>();
const receipt = shallowRef<GapReceipt | null>(null);
</script>

<template>
  <KbDialog :open="true" title="知识需求反馈" :width="680" @close="emit('close')">
    <template v-if="receipt">
      <p>已提交问题 {{ receipt.gapId }}，等待人工受理；通知是否生成或已读不等于已解决。</p>
      <RouterLink :to="`/knowledge-gaps/${receipt.gapId}`">查看已提交问题</RouterLink>
      <ElButton @click="emit('close')">返回原页面</ElButton>
    </template>
    <template v-else>
      <p>以下仅预填你自己的搜索词或已保存提问，可自由改写。关闭弹窗不会撤销已经发送的提交。</p>
      <GapCreateForm :source="source" :initial-description="initialDescription" @submitted="receipt = $event" @cancel="emit('close')" />
    </template>
  </KbDialog>
</template>
