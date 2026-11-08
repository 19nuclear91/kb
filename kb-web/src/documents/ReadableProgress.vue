<script setup lang="ts">
import { watch } from 'vue';
import { ElTag } from 'element-plus';
import Feedback from '../ui/Feedback.vue';
import KbAlert from '../ui/KbAlert.vue';
import { progressLabel } from '../logic';
import { useProgress } from './useProgress';

const props = defineProps<{ documentId: string; versionId: string; revision?: number }>();
const emit = defineEmits<{ denied: [] }>();
const state = useProgress(() => props.documentId, () => props.versionId, () => props.revision ?? 0);
watch(() => state.denied, denied => {
  if (denied) {
    emit('denied');
  }
});
</script>

<template>
  <section class="panel"><h2>选中版本处理状态</h2>
    <Feedback :loading="state.loading" :error="state.error" />
    <template v-if="state.data">
      <ElTag :type="state.data.indexStatus === 'indexed' ? 'success' : 'primary'">{{ progressLabel(state.data) }}</ElTag>
      <p class="muted">文件受理与解析发布分别完成；只有已发布版本进入检索。</p>
      <KbAlert v-if="state.data.parseTruncated" type="warning" title="解析结果被截断，文档尾部可能未处理。" />
      <p v-if="state.data.parseIssueSummary || state.data.failureSummary">{{ state.data.parseIssueSummary ?? state.data.failureSummary }}</p>
    </template>
  </section>
</template>
