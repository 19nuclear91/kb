<script setup lang="ts">
import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import { perm, useAdminAccess } from '../admin/access';
import type { WriteBindings } from '../admin/hooks';
import { ApiError } from '../api';
import SafeText from '../ui/SafeText.vue';
import { termApi, type Correction, type DirectoryFilters, type Term, type TermKind, type TermReceipt } from './api';
import { useTermDirectory } from './useDirectory';
import TermEditor from './TermEditor.vue';
import MeaningsPanel from './MeaningsPanel.vue';
import { useTermReadAccess } from './readAccess';
const props = defineProps<{ kind: TermKind }>();
const auth = useSession(), access = useAdminAccess(), read = useTermReadAccess(), bindings: WriteBindings = new Map();
const draftName = ref(''), draftEnabled = ref(''), submitted = shallowRef<DirectoryFilters>({ nameQuery: '', limit: 20 });
const list = useTermDirectory((cursor, signal) => termApi.query(auth.session, props.kind, submitted.value, cursor, signal), () => access.has(perm.termRead));
const terms = computed(() => props.kind === 'TERM' ? list.state.value.items as Term[] : []), corrections = computed(() => props.kind === 'CORRECTION' ? list.state.value.items as Correction[] : []);
const allowed = computed(() => read.active && access.has(perm.termWrite)), notice = ref(''), detailError = ref(''), detailLoading = ref(false);
const selection = shallowRef<{ current: Term | Correction | null; action: 'edit' | 'enabled' } | null>(null), meanings = shallowRef<{ termId: string; expression: string } | null>(null);
let request: AbortController | null = null, generation = 0, live = true;
onScopeDispose(() => { live = false; generation++; request?.abort(); bindings.clear(); selection.value = null; meanings.value = null; });
watch(allowed, value => { if (!value) { generation++; request?.abort(); bindings.clear(); selection.value = null; notice.value = ''; } }, { flush: 'sync' });
watch(() => read.active, active => {
  if (!active) {
    generation++; request?.abort(); bindings.clear(); selection.value = null; meanings.value = null;
    draftName.value = ''; draftEnabled.value = ''; submitted.value = { nameQuery: '', limit: 20 };
    detailLoading.value = false; detailError.value = ''; notice.value = '';
  }
}, { flush: 'sync' });
function query() {
  generation++; request?.abort(); detailLoading.value = false;
  submitted.value = Object.freeze({ nameQuery: draftName.value, limit: 20, ...(draftEnabled.value ? { enabled: draftEnabled.value === 'true' } : {}) });
  selection.value = null; meanings.value = null; void list.reload();
}
async function open(identity: string, action: 'edit' | 'enabled') {
  if (!allowed.value || detailLoading.value) return;
  request?.abort(); const at = ++generation, controller = new AbortController(), session = auth.session, epoch = auth.state.epoch; request = controller;
  selection.value = null; detailError.value = ''; detailLoading.value = true;
  try {
    const current = await termApi.detail(session, props.kind, identity, controller.signal);
    if (!live || at !== generation || controller.signal.aborted || epoch !== auth.state.epoch || !allowed.value) return;
    selection.value = { current, action };
  } catch (error) {
    if (!live || at !== generation || controller.signal.aborted || epoch !== auth.state.epoch) return;
    if (error instanceof ApiError && error.status === 403) read.reject();
    else detailError.value = '当前词条读取未完成，请主动重新读取。';
  }
  finally { if (live && at === generation) detailLoading.value = false; }
}
function done(receipt: TermReceipt) {
  notice.value = `已确认词典维护：${receipt.kind} ${receipt.id} · 首次版本 ${receipt.version} · 修订 ${receipt.dictionaryRevision}${receipt.replayed ? '（匹配重放）' : ''}`;
  selection.value = null; void list.reload();
}
function refresh() {
  if (selection.value?.current) { const current = selection.value.current; selection.value = null; void open(props.kind === 'TERM' ? (current as Term).termId : (current as Correction).correctionId, 'edit'); }
  void list.reload();
}
</script>
<template>
  <section class="panel" :aria-label="kind === 'TERM' ? '术语目录' : '错词目录'">
    <form @submit.prevent="query"><label>名称筛选<input v-model="draftName" aria-label="词典名称筛选" /></label>
      <label>启用状态<select v-model="draftEnabled" aria-label="词典状态筛选"><option value="">全部</option><option value="true">启用</option><option value="false">停用</option></select></label>
      <ElButton native-type="submit">查询词典</ElButton>
    </form>
    <p>已提交筛选：<SafeText :text="submitted.nameQuery || '不限名称'" /> · {{ submitted.enabled === undefined ? '全部状态' : submitted.enabled ? '启用' : '停用' }}</p>
    <p v-if="notice" role="status">{{ notice }}</p><p v-if="detailError" role="alert">{{ detailError }}</p>
    <p v-if="list.state.value.loading || detailLoading" role="status">正在读取词典…</p><p v-if="list.state.value.error" role="alert">{{ list.state.value.error }}</p>
    <ElButton @click="list.reload">重新读取词典</ElButton>
    <ElButton :disabled="!allowed || detailLoading || !!selection" @click="selection = { current: null, action: 'edit' }">{{ kind === 'TERM' ? '新建术语组' : '新建错词映射' }}</ElButton>
    <p v-if="!list.state.value.loading && !list.state.value.error && !list.state.value.items.length">当前筛选下没有词条。</p>
    <ul><li v-for="term in terms" :key="term.termId">
      <h3><SafeText :text="term.canonicalName" /> · {{ term.termId }}</h3><SafeText :text="term.description" /><p>{{ term.enabled ? '启用' : '停用' }} · 当前版本 {{ term.version }}</p>
      <ElButton v-if="term.canonicalAmbiguous" @click="meanings = { termId: term.termId, expression: term.canonicalName }">查看标准名称共享含义</ElButton>
      <ul><li v-for="alias in term.aliases" :key="alias.text"><SafeText :text="alias.text" /> · {{ alias.kind }}{{ alias.ambiguous ? '（歧义）' : '' }}<ElButton v-if="alias.ambiguous" @click="meanings = { termId: term.termId, expression: alias.text }">查看共享含义</ElButton></li></ul>
      <ElButton :disabled="!allowed || detailLoading || !!selection || !!list.state.value.error" @click="open(term.termId, 'edit')">编辑术语 {{ term.termId }}</ElButton>
      <ElButton :disabled="!allowed || detailLoading || !!selection || !!list.state.value.error" @click="open(term.termId, 'enabled')">{{ term.enabled ? '停用' : '启用' }}术语 {{ term.termId }}</ElButton>
    </li><li v-for="correction in corrections" :key="correction.correctionId">
      <h3><SafeText :text="correction.wrongExpression" /> → <SafeText :text="correction.target.canonicalName" /></h3>
      <p>映射自身：{{ correction.enabled ? '启用' : '停用' }} · 目标：{{ correction.target.enabled ? '启用' : '停用' }} · {{ correction.available ? '可提供建议' : '当前不可用于建议' }} · 当前版本 {{ correction.version }}</p>
      <ElButton :disabled="!allowed || detailLoading || !!selection || !!list.state.value.error" @click="open(correction.correctionId, 'edit')">编辑错词 {{ correction.correctionId }}</ElButton>
      <ElButton :disabled="!allowed || detailLoading || !!selection || !!list.state.value.error" @click="open(correction.correctionId, 'enabled')">{{ correction.enabled ? '停用' : '启用' }}错词 {{ correction.correctionId }}</ElButton>
    </li></ul>
    <ElButton v-if="list.state.value.cursor" :disabled="list.state.value.loading" @click="list.more">继续读取词典</ElButton><p v-if="list.state.value.revision">本次词典修订 {{ list.state.value.revision }}{{ !list.state.value.cursor && !list.state.value.loading ? ' · 已到末页' : '' }}</p>
  </section>
  <MeaningsPanel v-if="meanings" :key="`${meanings.termId}/${meanings.expression}`" v-bind="meanings" @close="meanings = null" />
  <TermEditor v-if="selection" :kind="kind" :current="selection.current" :action="selection.action" :bindings="bindings" @close="selection = null" @done="done" @refresh="refresh" />
</template>
