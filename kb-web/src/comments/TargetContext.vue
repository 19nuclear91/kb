<script setup lang="ts">
import { ref } from 'vue';
import { ElButton } from 'element-plus';
import type { WriteBindings } from '../admin/hooks';
import KbAlert from '../ui/KbAlert.vue';
import CommentPanel from './CommentPanel.vue';
import TargetDetail from './TargetDetail.vue';

defineProps<{ documentId: string; versionId: string; commentId: string }>();
const writes: WriteBindings = new Map();
const thread = ref(false), hidden = ref(false), check = ref(0);
function recover() {
  hidden.value = false;
  thread.value = false;
  check.value++;
}
const hide = () => { hidden.value = true; };
</script>

<template>
  <section aria-label="评论目标">
    <KbAlert v-if="hidden" type="warning" title="本版本讨论当前不可访问，已清理评论目标内容；不会改为显示其他版本的讨论。">
      <template #action><ElButton @click="recover">重新核对目标</ElButton></template>
    </KbAlert>
    <TargetDetail v-else :key="check" :document-id="documentId" :version-id="versionId" :comment-id="commentId" :thread="thread" @toggle="thread = !thread" />
    <CommentPanel v-if="thread" :document-id="documentId" :version-id="versionId" :bindings="writes" :on-denied="hide" />
  </section>
</template>
