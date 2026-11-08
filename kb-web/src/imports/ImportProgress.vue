<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue';
import { ElButton } from 'element-plus';
import { ApiError, positiveId } from '../api';
import { useSession } from '../SessionProvider';
import { progressLabel } from '../logic';
import type { Progress } from '../types';
import type { Receipt } from './api';

const props = defineProps<{ receipt: Receipt }>();
const auth = useSession(), life = new AbortController(), epoch = auth.state.epoch;
const busy = ref(false), label = ref(''), denied = ref(false);
onBeforeUnmount(() => life.abort());
async function check() {
  if (busy.value || denied.value || life.signal.aborted) return;
  busy.value = true;
  try {
    const value = await auth.session.json<Progress>(`/api/documents/${positiveId(props.receipt.documentId)}/progress?versionId=${positiveId(props.receipt.versionId)}`,
      { signal: AbortSignal.any([life.signal, AbortSignal.timeout(15000)]) });
    if (life.signal.aborted || auth.state.epoch !== epoch) return;
    if (value.versionId !== props.receipt.versionId || !['pending', 'parsing', 'indexed', 'failed'].includes(value.indexStatus)) throw new ApiError(503);
    label.value = `处理状态：${progressLabel(value)}`;
  } catch (error) {
    if (life.signal.aborted || auth.state.epoch !== epoch) return;
    denied.value = error instanceof ApiError && [401, 403, 404].includes(error.status);
    label.value = denied.value ? '已受理，当前无法查看处理状态' : '已受理，处理状态暂未确认';
  } finally { if (!life.signal.aborted) busy.value = false; }
}
</script>
<template>
  <div><ElButton :disabled="busy || denied" @click="check">查看固定版本处理状态</ElButton><p v-if="label" role="status">{{ label }}</p></div>
</template>
