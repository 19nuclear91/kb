<script setup lang="ts">
import { computed, ref, shallowReactive, shallowRef, watch } from 'vue';
import { ElButton } from 'element-plus';
import type { AdminWrite, WriteOutcome } from '../../admin/hooks';
import CategoryBrowser from './CategoryBrowser.vue';
import SuggestionPanel from './SuggestionPanel.vue';
import type { SuggestedField, SuggestionRequestBinding, SuggestionResult } from './suggestions';
import TagPicker from './TagPicker.vue';
import { MetadataError, metadataWrites, type Metadata, type Receipt, type Tag } from './api';

const props = defineProps<{ data: Metadata; write: AdminWrite; canEdit: boolean; sourceVersionId?: string | null; versionNo?: number | null }>();
const emit = defineEmits<{ receive: [result: WriteOutcome<Receipt>]; denied: [status: number] }>();
type CategoryChoice = { id: string; path: string[] } | null;
interface Fields { title: string; summary: string; categoryId: CategoryChoice }
function fields(data: Metadata): Fields {
  return { title: data.title, summary: data.summary ?? '', categoryId: data.category
    ? { id: data.category.id, path: data.category.path.map(node => node.name) } : null };
}
const manual = shallowReactive(fields(props.data)), applied = shallowReactive<Partial<Fields>>({});
const adopted = computed(() => Object.keys(applied) as SuggestedField[]);
const tags = ref<Tag[]>(props.data.tags), ready = ref(false), panelRevision = ref(0);
const adoptedRequest = shallowRef<SuggestionRequestBinding>(), pending = shallowRef<SuggestionRequestBinding>();
const suggestionCheck = ref<'valid' | 'query' | 'reload'>('valid');
const invalid = computed(() => adopted.value.length > 0 && suggestionCheck.value !== 'valid');
function draftField<K extends SuggestedField>(key: K) {
  return computed<Fields[K]>({
    get() { return key in applied ? applied[key] as Fields[K] : manual[key]; },
    set(value) {
      if (key in applied) applied[key] = value;
      else manual[key] = value;
    },
  });
}
const title = draftField('title'), summary = draftField('summary'), category = draftField('categoryId');
const categoryPath = computed(() => category.value?.path.join(' / ') ?? '未分类');
watch(() => props.canEdit, allowed => { if (!allowed) ready.value = false; });
function refreshSuggestions() {
  suggestionCheck.value = 'query';
  panelRevision.value++;
}
watch(() => props.sourceVersionId, refreshSuggestions);
watch(() => props.data, (next, previous) => {
  const fresh = fields(next);
  if (manual.title === previous.title) manual.title = fresh.title;
  if (manual.summary === (previous.summary ?? '')) manual.summary = fresh.summary;
  if (next.spaceId !== previous.spaceId || manual.categoryId?.id === previous.category?.id
      || manual.categoryId?.id === next.category?.id) manual.categoryId = fresh.categoryId;
  const removed = new Set(previous.tags.filter(old => !tags.value.some(tag => tag.id === old.id)).map(tag => tag.id));
  const added = tags.value.filter(tag => !previous.tags.some(old => old.id === tag.id) && !next.tags.some(item => item.id === tag.id));
  tags.value = [...next.tags.filter(tag => !removed.has(tag.id)), ...added];
  refreshSuggestions();
});
function adopt(field: SuggestedField, result: SuggestionResult, binding: SuggestionRequestBinding) {
  adoptedRequest.value = binding;
  if (field === 'title') applied.title = result.title.value;
  if (field === 'summary') applied.summary = result.summary.value;
  if (field === 'categoryId' && result.category) applied.categoryId = result.category.value;
}
function validateSuggestions(requestId: string, result: SuggestionResult) {
  if (adoptedRequest.value && adoptedRequest.value.requestId !== requestId) return;
  if (suggestionCheck.value !== 'reload') {
    suggestionCheck.value = adopted.value.includes('categoryId') && !result.category ? 'query' : 'valid';
  }
}
function invalidateSuggestions() {
  if (suggestionCheck.value === 'valid') suggestionCheck.value = 'query';
}
function discardSuggestions() {
  for (const field of adopted.value) delete applied[field];
  adoptedRequest.value = undefined;
  suggestionCheck.value = 'valid';
  panelRevision.value++;
}
let submittedGeneration: string | undefined;
function receive(result: WriteOutcome<Receipt>) {
  if (submittedGeneration && adoptedRequest.value?.requestId === submittedGeneration
      && result.status === 'rejected' && result.error instanceof MetadataError
      && ['SOURCE_CHANGED', 'TREE_VERSION_CONFLICT', 'METADATA_VERSION_CONFLICT', 'SUGGESTION_EXPIRED'].includes(result.error.reason)) {
    suggestionCheck.value = 'reload';
  }
  emit('receive', result);
}
function retry() {
  void props.write.retry<Receipt>().then(receive);
}
defineExpose({ retry });
function submit() {
  if (props.canEdit && ready.value && !props.write.pending && !props.write.blocked && !invalid.value) {
    submittedGeneration = adoptedRequest.value?.requestId;
    void props.write.submit<Receipt>(() => {
      const intent = metadataWrites.update(props.data.documentId, { title: title.value, summary: summary.value,
        categoryId: category.value?.id ?? null, tagIds: tags.value.map(tag => tag.id), expectedMetadataVersion: props.data.metadataVersion });
      if (!adoptedRequest.value) return intent;
      return { ...intent, path: `/api/documents/${props.data.documentId}/metadata-suggestions/${adoptedRequest.value.requestId}/metadata`,
        body: { ...intent.body, adoptedFields: [...adopted.value].sort() } };
    }).then(receive);
  }
}
</script>

<template>
  <form @submit.prevent="submit"><fieldset :disabled="write.busy || write.blocked || !canEdit">
    <p>资料版本 {{ String(data.metadataVersion) }} · 保存后立即生效</p>
    <label>标题<input v-model="title" aria-label="资料标题" required /></label>
    <label>人工摘要<textarea v-model="summary" aria-label="人工摘要" /></label>
    <p>已选分类：{{ categoryPath }}</p>
    <CategoryBrowser v-if="canEdit" :context="{ kind: 'edit', id: data.documentId }" :selected="category?.id"
      @select="category = $event ? { id: $event.id, path: $event.path.map(node => node.name) } : null" @ready="ready = $event !== null" @denied="emit('denied', $event)" />
    <TagPicker kind="edit" :document-id="data.documentId" :value="tags" @change="tags = $event" @denied="emit('denied', $event)" />
    <SuggestionPanel v-if="canEdit" :key="panelRevision" :data="data" :source="sourceVersionId" :version-no="versionNo" :adopted="adopted" :original-request="adoptedRequest ?? pending" @pending="pending = $event"
      :draft="{ title, summary, categoryId: categoryPath }" @adopt="adopt" @validated="validateSuggestions" @invalid="invalidateSuggestions" @denied="emit('denied', $event)" />
    <p v-if="invalid">已采用的建议需要重新核对。{{ suggestionCheck === 'reload' ? '请重新读取资料。' : '请核对原请求，或舍弃已采用的建议后继续手工编辑。' }}</p>
    <ElButton v-if="adopted.length" @click="discardSuggestions">舍弃已采用的建议并恢复手工草稿</ElButton>
    <ElButton native-type="submit" type="primary" :disabled="!ready || write.pending || !canEdit || write.blocked || invalid">保存资料</ElButton>
  </fieldset></form>
</template>
