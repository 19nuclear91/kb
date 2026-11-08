<script setup lang="ts">
import { computed, watch } from 'vue';
import type { WriteBindings } from '../admin/hooks';
import type { ReviewStatus } from '../reviews/api';
import CommentPanel from './CommentPanel.vue';

const props = defineProps<{ documentId: string; versionId: string; status: ReviewStatus | undefined }>();
const writes: WriteBindings = new Map();
const matched = computed(() => props.status && props.status.state.documentId === props.documentId && props.status.state.versionId === props.versionId ? props.status : undefined);
const published = computed(() => matched.value?.state.publicationEvidence === 'PUBLISHED' || matched.value?.state.publicationEvidence === 'OBSERVED_CURRENT');
const eligible = computed(() => published.value && matched.value?.capabilities.canRead === true);
watch([matched, eligible], ([value, canComment]) => {
  if (value && !canComment) {
    writes.clear();
  }
}, { immediate: true });
</script>

<template>
  <template v-if="matched">
    <section v-if="!published" class="panel" aria-label="版本评论"><h2>版本评论</h2><p class="muted">该版本尚无发布证据，不开放评论；审核意见使用独立的私有区域。</p></section>
    <CommentPanel v-else-if="eligible" :document-id="documentId" :version-id="versionId" :version-label="`v${matched.state.versionNo}`" :bindings="writes" />
  </template>
</template>
