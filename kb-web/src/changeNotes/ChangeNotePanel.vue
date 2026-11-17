<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import type { ApiError } from '../api';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import { useDocumentOperations } from '../documents/OperationContext';
import { changeNotesApi, type Note } from './api';
import { useChangeNoteWrite } from './useChangeNoteWrite';
import { useChangeNoteDraft, type ChangeNoteDraft } from './useChangeNoteDraft';
import ChangeNoteHistory from './ChangeNoteHistory.vue';
import ChangeNoteEditor from './ChangeNoteEditor.vue';

const props = defineProps<{ documentId: string; versionId: string; readonly?: boolean; comparisonBaseId?: string; targetVersionNo?: number; draftState?: ChangeNoteDraft }>();
const emit = defineEmits<{ changed: []; loaded: [note: Note]; denied: [status: number] }>();
const auth = useSession(), documents = useDocumentOperations(), editing = ref(false), history = ref(false), notice = ref('');
const draftState = props.draftState ?? useChangeNoteDraft(() => props.documentId, () => props.versionId);
const { draft, form } = draftState;
const write = useChangeNoteWrite(() => `change-note:${props.documentId}/${props.versionId}`, draftState.bindings);
const query = useResource(signal => changeNotesApi.get(auth.session, props, signal), () => `${auth.state.epoch}:${props.documentId}/${props.versionId}`);
watch(() => query.data, note => { if (note) emit('loaded', note); });
function open() {
  if (props.readonly || !query.data?.editable || query.data.reviewFrozen || !query.data.writeBaseVersionId
    || documents.state.exclusive || documents.state.validityUncertain || write.pending) return;
  draft.value = query.data; form.text = query.data.text ?? ''; form.reason = ''; editing.value = true;
}
function close() { editing.value = false; if (!write.pending) draftState.clear(); }
function saved() { editing.value = false; draftState.clear(); notice.value = '变更说明已保存。'; query.reload(); emit('changed'); }
function rejected(error: ApiError) {
  editing.value = false; draftState.clear(); notice.value = error.message; query.reload();
  if ([403, 404].includes(error.status)) emit('denied', error.status);
}
function clear() { editing.value = false; history.value = false; draftState.clear(); notice.value = ''; }
watch([() => auth.state.epoch, () => auth.state.phase, () => props.documentId, () => props.versionId, () => props.readonly], () => {
  clear(); write.discard();
}, { flush: 'sync' });
watch(() => write.phase, (phase, previous) => { if (phase === 'idle' && previous === 'uncertain') { editing.value = false; draftState.clear(); } });
const hidden = () => { if (document.visibilityState === 'hidden') clear(); };
document.addEventListener('visibilitychange', hidden);
onScopeDispose(() => document.removeEventListener('visibilitychange', hidden));
</script>
<template>
  <section class="panel" aria-label="版本变更说明"><h2>版本变更说明</h2><p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="query.loading" role="status">正在读取变更说明…</p><p v-else-if="query.error" role="alert">变更说明暂不可确认</p>
    <template v-if="query.data"><p v-if="!query.data.hasNote">没有变更说明</p>
      <template v-else><p>相对 v{{ query.data.baseVersionNo }} · <span v-if="query.data.corrected">已更正</span> <span v-if="query.data.createdAfterVisible">发布后补写</span></p>
        <pre>{{ query.data.text }}</pre>
        <p v-if="comparisonBaseId && comparisonBaseId !== query.data.baseVersionId">此说明对应 v{{ query.data.baseVersionNo }} 到 {{ targetVersionNo ? `v${targetVersionNo}` : '目标版本' }}。
          <RouterLink :to="`/documents/${documentId}/compare/${query.data.baseVersionId}/${versionId}`">按说明基准打开对比</RouterLink></p>
        <p v-else-if="comparisonBaseId">本次对比的变更说明</p>
      </template>
      <p v-if="query.data.reviewFrozen">审核期间变更说明已冻结，修改需先撤回。</p>
      <p v-if="!readonly && query.data.editable && !query.data.writeBaseVersionId">没有更早的已发布版本，第一个版本不能写变更说明。</p>
      <button v-if="!readonly && query.data.editable && query.data.writeBaseVersionId && !query.data.reviewFrozen"
        :disabled="documents.state.exclusive || write.pending || !!documents.state.validityUncertain" @click="open">编辑变更说明</button>
      <button @click="history = !history">{{ history ? '关闭说明修改历史' : '查看说明修改历史' }}</button>
      <ChangeNoteHistory v-if="history" :key="`${documentId}/${versionId}`" :document-id="documentId" :version-id="versionId" />
    </template>
    <button v-if="!readonly && draft && write.pending && !editing" @click="editing = true">继续核对说明结果</button>
    <ChangeNoteEditor v-if="editing && draft && !readonly" :data="draft" :form="form" :write="write" @saved="saved" @rejected="rejected" @verify="query.reload" @close="close" />
    <button :disabled="query.loading" @click="query.reload">重新读取变更说明</button>
  </section>
</template>
<style scoped>pre { white-space: pre-wrap; overflow-wrap: anywhere; }</style>
