<script setup lang="ts">
import { computed, watch } from 'vue';
import type { WriteBindings } from '../admin/hooks';
import { useReviewScope } from '../reviews/lifecycle';
import KbAlert from '../ui/KbAlert.vue';
import CommentContext from './CommentContext.vue';

const props = defineProps<{ documentId: string; versionId: string; versionLabel?: string; bindings?: WriteBindings; onDenied?: () => void }>();
const scope = useReviewScope();
const local: WriteBindings = new Map();
const writes = computed(() => props.bindings ?? local);
watch([() => scope.active, writes], ([active, bindings]) => {
  if (!active) {
    bindings.clear();
  }
}, { immediate: true });
</script>

<template>
  <CommentContext v-if="scope.active" :key="`${scope.key}:${documentId}/${versionId}`" :document-id="documentId" :version-id="versionId" :version-label="versionLabel ?? versionId" :writes="writes" :on-denied="onDenied" />
  <section v-else class="panel" aria-label="版本评论"><KbAlert type="info" title="评论已暂停，等待当前身份确认。" /></section>
</template>
