<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, shallowRef, watch } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import { perm, useAdminAccess } from '../admin/access';
import ResourceWriteDialog from '../admin/ResourceWriteDialog.vue';
import { useAdminWrite, type WriteBindings } from '../admin/hooks';
import { ApiError, isAbort } from '../api';
import SafeText from '../ui/SafeText.vue';
import TargetPicker from './TargetPicker.vue';
import { aliasKinds, termApi, termText, termWrite, termWrites, type Correction, type Term, type TermDraft, type TermKind, type TermReceipt, type Target } from './api';
import { useTermReadAccess } from './readAccess';

const props = defineProps<{ kind: TermKind; current: Term | Correction | null; action: 'edit' | 'enabled'; bindings: WriteBindings }>();
const emit = defineEmits<{ close: []; done: [receipt: TermReceipt]; refresh: [] }>();
const auth = useSession(), access = useAdminAccess(), read = useTermReadAccess();
const term = props.kind === 'TERM' ? props.current as Term | null : null, correction = props.kind === 'CORRECTION' ? props.current as Correction | null : null;
const identity = term?.termId ?? correction?.correctionId ?? 'new', scope = `search-term:${props.kind}:${identity}`;
const write = useAdminWrite(scope, termWrite, props.bindings);
const values = reactive<TermDraft>({ canonicalName: term?.canonicalName ?? '', description: term?.description ?? '', aliases: term?.aliases.map(alias => ({ text: alias.text, kind: alias.kind })) ?? [] });
const wrong = ref(correction?.wrongExpression ?? ''), target = shallowRef<Target | null>(correction?.target ?? null), targetReady = ref(false), receiptMessage = ref('');
const allowed = computed(() => read.active && access.has(perm.termWrite)), title = computed(() => props.action === 'enabled' ? `${props.current?.enabled ? '停用' : '启用'}${props.kind === 'TERM' ? '术语组' : '错词映射'}` : `${props.current ? '编辑' : '新建'}${props.kind === 'TERM' ? '术语组' : '错词映射'}`);
const disabled = computed(() => !allowed.value || (props.action !== 'enabled' && props.kind === 'CORRECTION' && !targetReady.value));
const preview = computed(() => { try { return termText(props.kind === 'TERM' ? values.canonicalName : wrong.value); } catch { return '输入待修正'; } });
let controller: AbortController | null = null, live = true;
watch(() => write.phase, phase => {
  if (phase !== 'uncertain') { controller?.abort(); controller = null; receiptMessage.value = ''; }
}, { flush: 'sync' });
watch(allowed, value => { if (!value) { controller?.abort(); controller = null; write.discard(); receiptMessage.value = ''; } }, { flush: 'sync' });
onScopeDispose(() => { live = false; controller?.abort(); });
function build() {
  if (props.action === 'enabled' && props.current) return termWrites.enabled(props.kind, identity, props.current.version, !props.current.enabled);
  return props.kind === 'TERM' ? termWrites.term(term, values) : termWrites.correction(correction, wrong.value, target.value);
}
async function verifyReceipt() {
  const bindingKey = `${auth.state.epoch}|${scope}`, entry = props.bindings.get(bindingKey);
  if (!entry || !allowed.value || write.phase !== 'uncertain') return;
  controller?.abort(); const active = new AbortController(), session = auth.session, epoch = auth.state.epoch; controller = active;
  const current = () => live && controller === active && !active.signal.aborted && epoch === auth.state.epoch
    && allowed.value && write.phase === 'uncertain' && props.bindings.get(bindingKey) === entry;
  receiptMessage.value = '正在核对本人回执…';
  try {
    const receipt = await termApi.receipt(session, entry.requestId, active.signal);
    if (!current()) return;
    if (receipt.kind !== props.kind || (identity !== 'new' && receipt.id !== identity)) throw new ApiError(503);
    write.reset(); emit('done', receipt);
  } catch (error) {
    if (!current() || isAbort(error)) return;
    if (error instanceof ApiError && error.status === 403) { read.reject(); return; }
    receiptMessage.value = error instanceof ApiError && error.status === 404 ? '未取得本人回执，原提交结果仍未知；可主动按原意图重试。' : '本人回执暂不可确认，原提交仍待核对。';
  }
}
</script>
<template>
  <ResourceWriteDialog :title="title" :scope="scope" :write="write" :execute="termWrite" :bindings="bindings" :disabled="disabled" :retry-disabled="!allowed" :build="build" :on-done="receipt => emit('done', receipt as TermReceipt)" :on-refresh="() => emit('refresh')" :on-verify="verifyReceipt" :on-rejected="error => { if (error.status === 403) { write.discard(); emit('close'); } }" @close="emit('close')">
    <p>启用词条向全公司有效登录用户提供公开提示；不授予文档读取权限。词条不包含文档命中数或搜索历史。</p>
    <template v-if="action === 'enabled'">
      <SafeText :text="term?.canonicalName ?? correction?.wrongExpression ?? ''" />
      <p v-if="kind === 'TERM'">停用时名称、别名及指向它的纠错建议退出后续辅助；错词自身启停保留，文档不变。已显示给他人的文本不保证撤回。</p>
      <p v-else>只改变此错词映射自身启停；目标停用时仍可独立关闭映射，之后不会随目标恢复自动开启。</p>
    </template>
    <template v-else-if="kind === 'TERM'">
      <label>标准名称<input v-model="values.canonicalName" aria-label="标准名称" /></label>
      <label>简短释义<textarea v-model="values.description" aria-label="简短释义" /></label>
      <div v-for="(alias, index) in values.aliases" :key="index">
        <label>别名<input v-model="alias.text" :aria-label="`别名 ${index + 1}`" /></label>
        <select v-model="alias.kind" :aria-label="`关系类型 ${index + 1}`"><option v-for="kind in aliasKinds" :key="kind" :value="kind">{{ ({ SYNONYM: '同义词', ABBREVIATION: '简称', FORMER_NAME: '旧称' })[kind] }}</option></select>
        <ElButton @click="values.aliases.splice(index, 1)">移除别名 {{ index + 1 }}</ElButton>
      </div>
      <ElButton :disabled="values.aliases.length >= 20" @click="values.aliases.push({ text: '', kind: 'SYNONYM' })">增加别名</ElButton>
      <p>跨组共享别名允许保存，搜索时由用户选择含义；组内规范等价重复不允许。</p>
    </template>
    <template v-else>
      <label>错误表达<input v-model="wrong" aria-label="错误表达" /></label>
      <TargetPicker :value="target" @change="target = $event" @ready="targetReady = $event" />
    </template>
    <p v-if="action !== 'enabled'">规范化名称预览：<SafeText :text="preview" /></p>
    <p v-if="receiptMessage" role="status">{{ receiptMessage }}</p>
    <ElButton v-if="write.phase === 'rejected'" @click="emit('refresh')">重新读取当前词条</ElButton>
  </ResourceWriteDialog>
</template>
