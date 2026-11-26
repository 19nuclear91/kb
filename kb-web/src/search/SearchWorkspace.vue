<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, shallowRef, watch } from 'vue';
import { ElButton, ElEmpty, ElForm, ElFormItem, ElInput, ElTag, type FormInstance, type FormRules } from 'element-plus';
import { useSession } from '../SessionProvider';
import Feedback from '../ui/Feedback.vue';
import PageHeader from '../ui/PageHeader.vue';
import SafeText from '../ui/SafeText.vue';
import CitationDrawer from '../CitationDrawer.vue';
import { citationPath } from '../logic';
import { isAbort } from '../api';
import { searchErrorMessage, searchInputError } from '../search';
import { searchBody } from './api';
import { assistedApi, AssistanceError, type Assistance, type SearchMode, type Suggestion } from './assistedApi';
import { adoptSuggestion, searchMode, submitSearch, type SubmittedSearch } from './submitted';
import AssistancePanel from './AssistancePanel.vue';
import { draftFilters, emptyFilterDraft, filterSummary } from './draft';
import SearchFilterFields from './FilterFields.vue';
import { useFavorites } from '../favorites/useFavorites';
import FavoriteButton from '../favorites/FavoriteButton.vue';
import type { Citation, Hit } from '../types';
import { lazyPage } from '../routing/lazyPage';
import { useGapFeedback } from '../knowledgeGaps/feedback';
import ApplicabilityBadge from '../validity/ApplicabilityBadge.vue';
import { pairKey, sourcePair } from '../validity/api';
import { useValidityBatch } from '../validity/useValidityBatch';

const GapFeedbackDialog = lazyPage(() => import('../knowledgeGaps/GapFeedbackDialog.vue'));
const CardSearchPanel = lazyPage(() => import('../answerCards/SearchPanel.vue'));

const props = defineProps<{ maximum: number }>();
const auth = useSession(), form = ref<FormInstance>(), values = reactive({ query: '' });
const gapFeedback = useGapFeedback();
const hits = shallowRef<Hit[] | null>(null), error = ref(''), loading = ref(false), invalid = ref(false);
const badgeSource = shallowRef<Hit[]>([]);
const citation = shallowRef<Citation | null>(null), draft = shallowRef(emptyFilterDraft());
const submitted = shallowRef<SubmittedSearch | null>(null), assistance = shallowRef<Assistance | null>(null);
let request: AbortController | null = null, generation = 0, live = true;
const validity = useValidityBatch(() => badgeSource.value.flatMap(hit => { const pair = sourcePair(hit); return pair ? [pair] : []; }), () => String(generation));
const badgeFor = (hit: Hit) => { const pair = sourcePair(hit); return pair ? validity.badges.value.get(pairKey(pair)) : undefined; };
const favorites = useFavorites(() => (hits.value ?? []).flatMap(hit => { const id = hit.docId ?? hit.documentId; return id ? [id] : []; }), () => hits.value);
const visible = computed(() => hits.value?.filter(hit => !favorites.unavailable(hit.docId ?? hit.documentId ?? '')));
const currentCitation = computed(() => citation.value && !favorites.unavailable(citation.value.docId ?? citation.value.documentId ?? '') ? citation.value : null);
watch(currentCitation, value => {
  if (citation.value && !value) {
    citation.value = null;
  }
});
onScopeDispose(() => {
  live = false;
  generation++;
  request?.abort();
  assistance.value = null; submitted.value = null; hits.value = null; badgeSource.value = []; citation.value = null;
});
const rules: FormRules = { query: [{ validator: (_, value: string, callback) => {
  const error = searchInputError(value, props.maximum);
  callback(error ? new Error(error) : undefined);
} }] };
function clear() {
  badgeSource.value = [];
  gapFeedback.close();
  request?.abort();
  hits.value = null; assistance.value = null; error.value = ''; loading.value = false; citation.value = null; submitted.value = null;
}
async function search(mode: SearchMode = 'ASSISTED') {
  badgeSource.value = [];
  const at = ++generation, query = values.query;
  request?.abort();
  const filterDraft = { ...draft.value, tags: [...draft.value.tags] };
  const valid = await form.value?.validate().catch(() => false);
  if (!live || at !== generation) {
    return;
  }
  invalid.value = !valid;
  if (!valid) {
    clear();
    return;
  }
  if (document.visibilityState === 'hidden' || searchInputError(query, props.maximum)) {
    return;
  }
  let filters;
  try {
    filters = draftFilters(filterDraft);
    searchBody(query, filters);
  } catch (failure) {
    clear();
    error.value = searchErrorMessage(failure);
    return;
  }
  await perform(submitSearch(query, filters, filterSummary(filterDraft), mode, props.maximum));
}
async function perform(input: SubmittedSearch) {
  request?.abort();
  const at = ++generation;
  const controller = new AbortController(), session = auth.session, epoch = session.getSnapshot().epoch;
  request = controller;
  const active = () => live && at === generation && document.visibilityState !== 'hidden' && !controller.signal.aborted && request === controller && session.getSnapshot().epoch === epoch && session.getSnapshot().phase === 'ready';
  loading.value = true; hits.value = null; assistance.value = null; badgeSource.value = []; error.value = ''; citation.value = null;
  gapFeedback.close();
  submitted.value = input;
  try {
    const result = await assistedApi.search(session, input, controller.signal);
    if (active()) {
      hits.value = result.hits;
      assistance.value = result.assistance;
      badgeSource.value = result.hits;
    }
  } catch (failure) {
    if (active() && !isAbort(failure)) {
      error.value = searchErrorMessage(failure);
      if (failure instanceof AssistanceError && failure.reason === 'ASSISTANCE_STALE') {
        submitted.value = searchMode(input, 'ASSISTED');
      }
    }
  } finally {
    if (active()) {
      loading.value = false;
    }
  }
}
function mode(mode: SearchMode) {
  if (submitted.value) void perform(searchMode(submitted.value, mode));
}
function adopt(suggestion: Suggestion, termId: string) {
  if (loading.value || !submitted.value || !assistance.value) return;
  try { void perform(adoptSuggestion(submitted.value, assistance.value, suggestion, termId)); }
  catch (failure) { error.value = searchErrorMessage(failure); }
}
</script>

<template>
  <PageHeader title="知识搜索" description="在当前可检索的已发布资料中组合筛选，按相关性查看片段" />
  <CardSearchPanel :original-query="values.query" />
  <section class="panel"><ElForm ref="form" :model="values" :rules="rules" @submit.prevent="search()" @validate="(_, valid) => { invalid = !valid; }">
    <div class="toolbar"><ElFormItem prop="query" style="flex: 1; margin: 0"><ElInput v-model="values.query" size="large" placeholder="输入关键词或描述你要查找的内容" aria-label="搜索词" :aria-invalid="invalid ? 'true' : undefined" /></ElFormItem><ElButton type="primary" native-type="submit" size="large" :loading="loading">搜索</ElButton></div>
    <SearchFilterFields :value="draft" @change="draft = $event" />
   </ElForm><ElButton @click="search('ORIGINAL')">提交原词搜索</ElButton><div class="muted">搜索词最多 {{ maximum }} 个字符 · 每次最多显示 20 条相关片段 · 搜索内容仅在本页使用</div></section>
  <Feedback :loading="loading" :error="error" />
  <section v-if="submitted" class="panel" aria-label="已提交条件"><h2>已提交条件</h2><SafeText :text="submitted.originalQuery" /><p>{{ submitted.summary }}</p>
    <ElButton @click="gapFeedback.open(submitted.originalQuery)">反馈知识需求</ElButton>
    <ElButton @click="mode('ORIGINAL')">仅搜原词</ElButton>
    <ElButton @click="mode('ASSISTED')">{{ submitted.mode === 'ORIGINAL' ? '重新启用辅助' : '重新搜索' }}</ElButton>
  </section>
  <AssistancePanel v-if="assistance" :value="assistance" :busy="loading" @adopt="adopt" />
  <section v-if="visible" class="panel">
    <p v-if="validity.unavailable.value" class="muted">适用期暂不可确认</p>
    <ElEmpty v-if="!visible.length" description="当前条件下未找到可用结果" />
    <template v-else><h2>相关片段 <span class="muted">{{ visible.length }} 条</span></h2>
      <article v-for="(hit, index) in visible" :key="`${hit.chunkId}/${index}`" class="search-hit">
        <h2><ElButton link type="primary" :disabled="!citationPath(hit)" @click="citation = hit">{{ hit.docTitle ?? '参考材料' }}</ElButton></h2>
        <div style="margin-bottom: 12px"><ElTag type="info">{{ hit.versionNo == null ? '版本未知' : `v${hit.versionNo}` }}</ElTag><span class="muted">{{ hit.pageNo == null ? '页码未知' : `第 ${hit.pageNo} 页` }}</span></div>
        <ApplicabilityBadge v-if="badgeFor(hit)" :value="badgeFor(hit)!" />
        <SafeText :text="hit.content" /><FavoriteButton v-if="hit.docId ?? hit.documentId" :id="(hit.docId ?? hit.documentId)!" :favorites="favorites" />
      </article>
    </template>
  </section>
  <CitationDrawer :citation="currentCitation" @close="citation = null" />
  <GapFeedbackDialog v-if="gapFeedback.description !== null" source="SEARCH" :initial-description="gapFeedback.description" @close="gapFeedback.close" />
</template>
