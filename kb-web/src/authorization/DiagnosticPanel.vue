<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref, shallowRef, watch } from 'vue';
import { encodeJson } from '../api';
import { perm, useAdminAccess } from '../admin/access';
import type { WriteIntent } from '../admin/api';
import { spacePaths, type ManagedDocument, type ResourceKind } from '../admin/spaceApi';
import { useSession } from '../SessionProvider';
import type { QueryPage } from '../types';
import { diagnosticApi, fromWrite, type Binding, type Result, type User, type Users } from './api';
import { useDiagnosticRead } from './state';
import DiagnosticLayer from './DiagnosticLayer.vue';

const props = defineProps<{ kind: ResourceKind; resourceId: string; spaceId: string; blocked?: boolean; writeIntent?: WriteIntent | null; preview?: boolean; selectedUser?: User | null }>();
const emit = defineEmits<{ recheck: [status: number] }>();
const auth = useSession(), access = useAdminAccess();
const target = shallowRef<User | null>(props.selectedUser ?? null), q = ref(''), observation = ref(''), visible = ref(document.visibilityState !== 'hidden');
const intent = computed(() => { try { return props.preview && props.writeIntent ? fromWrite(props.kind, props.writeIntent) : null; } catch { return null; } });
const binding = computed<Binding>(() => ({ kind: props.kind, id: props.resourceId, spaceId: props.spaceId, documentId: observation.value || null, userId: target.value?.userId ?? '', intent: intent.value }));
const ready = () => visible.value && !props.blocked && auth.state.phase === 'ready' && access.status === 'ready' && access.spaceStatus === 'ready' && !access.checking && access.has(perm.diagnose);
const context = () => `${auth.state.epoch}:${props.kind}:${props.resourceId}:${props.spaceId}:${access.factsKey ?? ''}`;
let checked = false;
function rejected(status: number) { if (!checked) { checked = true; emit('recheck', status); } }
const result = useDiagnosticRead<Result>(() => `${context()}:${encodeJson(binding.value)}`, ready, rejected);
const conclusionsUnchanged = computed(() => {
  const value = result.data.value;
  if (!value?.after) return false;
  const conclusions = (layer: Result['before']) => ({ subjectCondition: layer.subjectCondition, currentVersionAccess: layer.currentVersionAccess,
    spacePermissions: layer.spacePermissions.map(({ sourceKeys, ...permission }) => permission),
    documentPermissions: layer.documentPermissions?.map(({ sourceKeys, ...permission }) => permission) ?? null });
  return encodeJson(conclusions(value.before)) === encodeJson(conclusions(value.after));
});
const candidates = useDiagnosticRead<Users>(() => `${context()}:${q.value}`, ready, rejected);
const documents = useDiagnosticRead<QueryPage<ManagedDocument>>(context, ready, rejected);
const canRun = computed(() => ready() && !!target.value && (!props.preview || !!intent.value) && (!intent.value?.entries.some(entry => entry.permission === 'manage') || access.has(perm.grantManage)));
function search(cursor: string | null = null) { void candidates.run(signal => diagnosticApi.users(auth.session, binding.value, q.value, cursor, signal)); }
function run() { if (canRun.value) { const captured = binding.value; void result.run(signal => diagnosticApi.diagnose(auth.session, captured, signal)); } }
function select(event: Event) { target.value = candidates.data.value?.items.find(user => user.userId === (event.target as HTMLSelectElement).value) ?? null; }
function loadDocuments(cursor: string | null = null) { void documents.run(signal => auth.session.json(spacePaths.documents({ spaceId: props.spaceId, limit: 100, cursor }), { signal })); }
function visibility() {
  visible.value = document.visibilityState !== 'hidden';
  if (!visible.value) { target.value = null; q.value = ''; observation.value = ''; result.clear(); candidates.clear(); documents.clear(); }
}
watch(() => props.selectedUser, user => { target.value = user ?? null; }, { flush: 'sync' });
watch([() => auth.state.epoch, () => auth.state.phase, () => props.resourceId, () => props.spaceId], () => { target.value = null; observation.value = ''; q.value = ''; }, { flush: 'sync' });
onMounted(() => { document.addEventListener('visibilitychange', visibility); search(); });
onScopeDispose(() => document.removeEventListener('visibilitychange', visibility));
</script>

<template>
  <section aria-label="指定用户权限解释"><h3>{{ preview ? '授权预演' : '权限解释' }}</h3>
    <p>仅分析所选用户及资源。{{ kind === 'documents' ? `文档 ${resourceId}` : `空间 ${resourceId}` }}。预演可选，正式提交将重新鉴权。</p>
    <p v-if="blocked" role="status">写入在途、结果待核对或删除确认期间，诊断已暂停。</p>
    <label>搜索用户显示名<input v-model="q" aria-label="诊断用户显示名" :disabled="!ready()" @keydown.enter.prevent="search()" /></label><button type="button" :disabled="!ready() || candidates.loading.value" @click="search()">搜索诊断用户</button>
    <p v-if="candidates.error.value" role="alert">{{ candidates.error.value }}</p>
    <label v-if="candidates.data.value">目标用户<select aria-label="诊断目标用户" :value="target?.userId ?? ''" :disabled="!ready()" @change="select"><option value="">请选择用户</option><option v-for="user in candidates.data.value.items" :key="user.userId" :value="user.userId">{{ user.label }}</option></select></label>
    <button v-if="candidates.data.value?.hasMore" type="button" :disabled="!ready()" @click="search(candidates.data.value.nextCursor)">下一页用户</button>
    <p v-if="target">所选用户：{{ target.label }}</p>
    <template v-if="kind === 'spaces'"><button type="button" :disabled="!ready()" @click="loadDocuments()">选择同空间文档（可选）</button><p v-if="documents.error.value" role="alert">{{ documents.error.value }}</p>
      <label v-if="documents.data.value">观察文档<select v-model="observation" aria-label="观察文档" :disabled="!ready()"><option value="">只看空间授权</option><option v-for="document in documents.data.value.items" :key="document.documentId" :value="document.documentId">文档 {{ document.documentId }}</option></select></label>
      <button v-if="documents.data.value?.hasMore" type="button" :disabled="!ready()" @click="loadDocuments(documents.data.value.nextCursor)">下一页文档</button>
    </template>
    <p v-if="preview">当前草稿 {{ intent?.entries.length ?? 0 }} 条授权意图；只观察一位用户，不表示影响人数。未预演仍可确认提交。</p>
    <p v-if="intent?.entries.some(entry => entry.permission === 'manage') && !access.has(perm.grantManage)">预演 manage 变更需要额外授出资格。</p>
    <button type="button" :disabled="!canRun" @click="run">{{ preview ? '计算预演' : '查看权限解释' }}</button>
    <p v-if="result.loading.value" role="status">正在读取当前权限事实…</p><p v-if="result.error.value" role="alert">{{ result.error.value }}</p>
    <article v-if="result.data.value"><p>本次分析用户：{{ result.data.value.user.label }}</p><p>计算时间：{{ result.data.value.evaluatedAt }}。仅代表本次读取；不检查原件可用性，不作为后续提交凭据。</p>
      <DiagnosticLayer :layer="result.data.value.before" title="当前" /><DiagnosticLayer v-if="result.data.value.after" :layer="result.data.value.after" title="若提交后" />
      <template v-if="preview"><p v-if="conclusionsUnchanged">所选用户的授权判定和访问结果不变，来源变化见前后明细。</p><ul aria-label="拟议授权变化"><li v-for="change in result.data.value.changes" :key="`${change.sourceKind}:${change.principalType}:${change.principalId}:${change.permission}`">{{ change.principalType }} {{ change.principalId }} · {{ change.permission }} · {{ change.outcome === 'ADDED' ? '拟新增' : change.outcome === 'REMOVED' ? '拟移除' : '无变化' }}</li></ul></template>
    </article>
  </section>
</template>
