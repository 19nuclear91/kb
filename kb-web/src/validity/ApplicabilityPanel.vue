<script setup lang="ts">
import { onScopeDispose, ref, shallowRef, watch } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import { useDocumentOperations } from '../documents/OperationContext';
import type { ApiError } from '../api';
import ApplicabilityBadge from './ApplicabilityBadge.vue';
import ApplicabilityHistory from './ApplicabilityHistory.vue';
import ApplicabilityEditor from './ApplicabilityEditor.vue';
import { validityApi, type Applicability } from './api';
import { declarationLabel } from './labels';
import { useValidityWrite } from './useValidityWrite';

const props = defineProps<{ documentId: string; versionId: string; current?: boolean; readonly?: boolean }>();
const emit = defineEmits<{ changed: []; denied: [status: number] }>();
const auth = useSession(), documents = useDocumentOperations(), history = ref(false), editing = ref(false), notice = ref('');
const draft = shallowRef<Applicability | null>(null), write = useValidityWrite(() => `applicability:${props.documentId}/${props.versionId}`);
const query = useResource(signal => validityApi.get(auth.session, props, signal), () => `${auth.state.epoch}:${props.documentId}/${props.versionId}`);
function open() {
  if (props.readonly || !query.data?.canEdit || query.data.reviewFrozen || documents.state.exclusive || documents.state.validityUncertain || write.pending) return;
  draft.value = query.data; editing.value = true;
}
function close() { editing.value = false; if (!write.pending) draft.value = null; }
function saved() { editing.value = false; draft.value = null; notice.value = '适用期已保存，正在重新读取当前声明。'; query.reload(); emit('changed'); }
function rejected(error: ApiError) { editing.value = false; draft.value = null; notice.value = error.message; query.reload(); if ([403, 404].includes(error.status)) emit('denied', error.status); }
function clearDraft() { editing.value = false; draft.value = null; history.value = false; notice.value = ''; }
watch([() => auth.state.epoch, () => auth.state.phase, () => props.documentId, () => props.versionId, () => props.readonly], () => {
  clearDraft(); write.discard();
}, { flush: 'sync' });
watch(() => write.phase, (phase, previous) => { if (phase === 'idle' && (previous === 'uncertain' || previous === 'submitting')) { editing.value = false; draft.value = null; } });
const hidden = () => { if (document.visibilityState === 'hidden') clearDraft(); };
document.addEventListener('visibilitychange', hidden);
onScopeDispose(() => document.removeEventListener('visibilitychange', hidden));
</script>
<template>
  <section class="panel" aria-label="版本适用期"><h2>版本适用期</h2>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="query.loading" role="status">正在读取适用期…</p>
    <p v-else-if="query.error" role="alert">适用期暂不可确认</p>
    <template v-if="query.data"><ApplicabilityBadge :value="query.data" /><p>{{ declarationLabel(query.data) }} · 业务日期 {{ query.data.asOfDate }}</p>
      <p v-if="current && query.data.state === 'NOT_YET_EFFECTIVE'">当前发布版本尚未生效；上一发布版本可能仍在施行，可在版本历史中查看。页面不会自动切换版本。</p>
      <p v-if="query.data.reviewFrozen && !readonly">审核期间适用期已冻结，修改需先撤回。</p>
      <ElButton v-if="!readonly && query.data.canEdit && !query.data.reviewFrozen" :disabled="documents.state.exclusive || write.pending || !!documents.state.validityUncertain" @click="open">编辑适用期</ElButton>
      <ElButton @click="history = !history">{{ history ? '关闭适用期修改历史' : '查看适用期修改历史' }}</ElButton>
      <ApplicabilityHistory v-if="history" :key="`${documentId}/${versionId}`" :document-id="documentId" :version-id="versionId" />
    </template>
    <ElButton v-if="!readonly && draft && write.pending && !editing" @click="editing = true">继续核对适用期结果</ElButton>
    <ApplicabilityEditor v-if="editing && draft && !readonly" :data="draft" :write="write" @saved="saved" @rejected="rejected" @verify="query.reload" @close="close" />
    <ElButton :disabled="query.loading" @click="query.reload">重新读取适用期</ElButton>
  </section>
</template>
