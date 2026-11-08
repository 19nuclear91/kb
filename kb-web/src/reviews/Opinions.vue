<script setup lang="ts">
import { ref, watch } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import Feedback from '../ui/Feedback.vue';
import { reviewPaths, type ReviewStatus } from './api';

const props = defineProps<{ documentId: string; versionId: string }>();
const emit = defineEmits<{ denied: [] }>();
const auth = useSession(), cursor = ref<string | null>(null);
const query = useResource(signal => auth.session.json<ReviewStatus>(reviewPaths.status(props.documentId, props.versionId, true, cursor.value), { signal }), () => `${props.documentId}/${props.versionId}/opinions/${cursor.value}`);
watch(() => query.status, status => {
  if (status && [401, 403, 404].includes(status)) {
    emit('denied');
  }
});
</script>

<template>
  <section aria-label="私有审核意见"><h3>私有审核意见</h3><Feedback :loading="query.loading" :error="query.error" :retry="query.reload" />
    <article v-for="round in query.data?.rounds" :key="round.reviewId"><p>第 {{ round.roundNo }} 轮 · {{ round.state }}</p><p style="white-space: pre-wrap">{{ round.reason ?? '本轮无驳回意见' }}</p></article>
    <ElButton v-if="query.data?.hasMore && query.data.nextCursor" @click="cursor = query.data!.nextCursor">下一页意见</ElButton>
  </section>
</template>
