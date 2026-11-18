<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { ElButton } from 'element-plus';
import { ApiError, messageOf } from '../../api';
import { useSession } from '../../SessionProvider';
import { isDocumentAccessDenied, metadataApi, MetadataError, type Metadata } from './api';
import { suggestionRequest, suggestionReasons, type Operation, type SuggestedField, type SuggestionRequestBinding, type SuggestionResult, type SuggestionView } from './suggestions';

const props = defineProps<{ data: Metadata; source?: string | null; versionNo?: number | null;
  draft: Record<SuggestedField, string>; adopted: SuggestedField[]; originalRequest?: SuggestionRequestBinding }>();
const emit = defineEmits<{ adopt: [field: SuggestedField, result: SuggestionResult, binding: SuggestionRequestBinding];
  validated: [requestId: string, result: SuggestionResult]; invalid: []; denied: [status: number];
  pending: [binding: SuggestionRequestBinding | undefined] }>();
const binding = shallowRef(props.originalRequest);
const key = computed(() => binding.value?.requestId);
const sameBaseline = computed(() => binding.value?.documentId === props.data.documentId && binding.value.sourceVersionId === props.source
  && String(binding.value?.metadataVersion) === String(props.data.metadataVersion));
const auth = useSession(), view = ref<SuggestionView>(), message = ref(''), busy = ref(false);
const uncertainCreate = ref(false), restartable = ref(false);
let controller: AbortController | undefined, timer: ReturnType<typeof setTimeout> | undefined;
let expiryTimer: ReturnType<typeof setTimeout> | undefined;
const names = { title: '标题', summary: '摘要', categoryId: '分类' };
const fields: SuggestedField[] = ['title', 'summary', 'categoryId'];
const sourceLabel = computed(() => props.versionNo != null ? `V${props.versionNo}` : props.source);
function stop() {
  clearTimeout(timer);
  controller?.abort();
  controller = undefined;
  busy.value = false;
}
function clear() {
  stop();
  clearTimeout(expiryTimer);
  binding.value = undefined;
  emit('pending', undefined);
  view.value = undefined;
  message.value = '';
  uncertainCreate.value = false;
  restartable.value = false;
  if (props.adopted.length) emit('invalid');
}
watch([() => props.data.documentId, () => auth.state.epoch, () => auth.state.phase], clear, { flush: 'sync' });
onMounted(() => { if (binding.value) void request('query'); });
onBeforeUnmount(() => { stop(); clearTimeout(expiryTimer); });

function expire() {
  if (view.value) view.value = { ...view.value, result: null, reasonCode: 'SUGGESTION_EXPIRED' };
  message.value = suggestionReasons.SUGGESTION_EXPIRED;
  restartable.value = true;
  emit('invalid');
}
function awaitingSettlement(state: SuggestionView) {
  return state.status !== 'RUNNING' && ['RESERVING', 'RESERVED', 'PENDING'].includes(state.settlementStatus ?? '');
}
async function request(op: Operation, fresh = false) {
  if (auth.state.phase !== 'ready' || (busy.value && op !== 'cancel')) return;
  if (fresh) {
    if (!props.source || props.adopted.length) return;
    clear();
    binding.value = { requestId: crypto.randomUUID(), documentId: props.data.documentId, sourceVersionId: props.source,
      metadataVersion: props.data.metadataVersion };
    emit('pending', binding.value);
  }
  const target = binding.value;
  if (!target || (op === 'create' && !sameBaseline.value)) return;
  stop();
  const call = new AbortController();
  controller = call;
  busy.value = true;
  try {
    const next = await suggestionRequest(auth.session, op, target, call.signal);
    if (call.signal.aborted) return;
    binding.value = { ...target, generationStatus: next.status };
    emit('pending', next.status === 'RUNNING' || awaitingSettlement(next) ? binding.value : undefined);
    if (next.result && !sameBaseline.value) {
      next.result = null;
      next.reasonCode = target.sourceVersionId !== props.source ? 'SOURCE_CHANGED' : 'METADATA_VERSION_CONFLICT';
    }
    uncertainCreate.value = false;
    clearTimeout(expiryTimer);
    view.value = next;
    restartable.value = next.status !== 'RUNNING';
    message.value = next.reasonCode ? suggestionReasons[next.reasonCode] ?? '整理失败。'
      : next.status === 'RUNNING' ? (sameBaseline.value ? '正在整理当前发布版本…' : '原整理请求仍在运行。') : '建议已生成，可逐项核对。';
    if (next.status === 'RUNNING') timer = setTimeout(() => void request('query'), 2000);
    if (next.status === 'COMPLETED' && next.result) {
      const remaining = Date.parse(next.expiresAt ?? '') - Date.now();
      if (!Number.isFinite(remaining) || remaining <= 0) expire();
      else {
        expiryTimer = setTimeout(expire, remaining);
        emit('validated', target.requestId, next.result);
      }
    } else if (props.adopted.length) emit('invalid');
  } catch (error) {
    if (call.signal.aborted) return;
    if (op !== 'create' && !target.generationStatus && error instanceof ApiError && error.status === 404) {
      try {
        await metadataApi.categories(auth.session, { kind: 'edit', id: props.data.documentId }, null, null, call.signal);
        if (call.signal.aborted) return;
        uncertainCreate.value = sameBaseline.value && op === 'query';
        restartable.value = !sameBaseline.value;
        message.value = !sameBaseline.value ? '尚未查到原请求，资料已变化，可继续核对或重新生成。'
          : op === 'cancel' ? '取消结果未确认，请核对原请求。' : '尚未查到原请求，可按原标识重试生成请求。';
        return;
      } catch (probeError) {
        if (call.signal.aborted) return;
        if (isDocumentAccessDenied(probeError)) {
          clear();
          emit('denied', probeError.status);
        } else {
          message.value = '暂时无法核对编辑资格，请稍后核对原请求。';
        }
        return;
      }
    }
    if (isDocumentAccessDenied(error)) {
      clear();
      emit('denied', error.status);
      return;
    }
    if (error instanceof ApiError && error.status === 429) {
      uncertainCreate.value = !target.generationStatus && sameBaseline.value && op !== 'cancel';
      message.value = uncertainCreate.value ? '请求过于频繁，请稍后核对原请求或按原标识重试。' : '请求过于频繁，请稍后核对原请求。';
      return;
    }
    if (error instanceof MetadataError || (error instanceof ApiError && [400, 409].includes(error.status))) {
      message.value = messageOf(error);
      uncertainCreate.value = false;
      if (op === 'create') {
        restartable.value = true;
        emit('pending', undefined);
      }
    } else {
      message.value = op === 'cancel' ? '取消结果未确认，请核对原请求。' : '整理请求结果未确认，可按原标识核对。';
      if (op === 'create') uncertainCreate.value = true;
    }
  } finally {
    if (controller === call) busy.value = false;
  }
}
function candidate(field: SuggestedField) {
  const result = view.value?.result;
  if (!result) return null;
  if (field === 'categoryId') {
    return result.category ? { text: result.category.value.path.join(' / '), sources: result.category.sources } : null;
  }
  return { text: result[field].value, sources: result[field].sources };
}
function adopt(field: SuggestedField) {
  if (!binding.value || !sameBaseline.value || !view.value?.result || !candidate(field)) return;
  if (Date.parse(view.value.expiresAt ?? '') <= Date.now()) { expire(); return; }
  emit('adopt', field, view.value.result, binding.value);
}
</script>

<template>
  <section aria-label="AI 整理建议">
    <h3>AI 整理建议</h3>
    <p v-if="source">来源为当前发布版本 {{ sourceLabel }}，采用的内容会进入整文档资料草稿。</p>
    <p v-else>文档尚无发布版本。</p>
    <ElButton v-if="!key || (restartable && !busy)" :disabled="!source || adopted.length > 0"
      @click="request('create', true)">{{ key ? '重新生成建议' : '根据当前发布版本生成建议' }}</ElButton>
    <ElButton v-if="uncertainCreate" :disabled="busy" @click="request('create')">按原标识重试生成请求</ElButton>
    <ElButton v-if="key" :disabled="busy" @click="request('query')">核对原请求</ElButton>
    <ElButton v-if="binding && (!binding.generationStatus || binding.generationStatus === 'RUNNING')" @click="request('cancel')">取消整理</ElButton>
    <p role="status">{{ message }}</p>
    <p v-if="view?.usageStatus === 'UNKNOWN'">实际用量未知，按预留 {{ view.plannedCharge }} token 结算。</p>
    <p v-else-if="view?.usageStatus === 'KNOWN'">实际用量：输入 {{ view.promptTokens }}、输出 {{ view.completionTokens }} token。</p>
    <p v-else-if="view?.usageStatus === 'NOT_INVOKED'">未调用模型，应扣 0 token。</p>
    <p v-if="view && awaitingSettlement(view)">额度结算待确认</p>
    <p v-if="view?.settlementStatus === 'REVIEW'">额度结算待核查</p>
    <p v-if="view?.settlementStatus === 'SETTLED'">已确认扣除 {{ view.settledCharge }} token</p>
    <template v-if="view?.result">
      <div v-for="field in fields" :key="field">
        <h4>{{ names[field] }}</h4>
        <p>当前草稿：{{ draft[field] || '空' }}</p>
        <template v-if="candidate(field)">
          <p>建议：{{ candidate(field)?.text }}</p>
          <p>依据：<span v-for="s in candidate(field)?.sources" :key="s.index">
            {{ sourceLabel }} · 结构块 {{ s.index }}{{ s.heading ? ' · ' + s.heading : '' }}{{ s.page != null ? ' · 第 ' + s.page + ' 页' : '' }}
          </span></p>
          <ElButton @click="adopt(field)">{{ adopted.includes(field) ? '再次采用' : '采用' }}{{ names[field] }}</ElButton>
        </template><p v-else>无合适分类建议</p>
      </div>
    </template>
  </section>
</template>
