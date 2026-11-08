<script setup lang="ts">
import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue';
import { ElButton, ElInput } from 'element-plus';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import { ApiError, isAbort, messageOf, positiveId } from '../api';
import { bindTurn, mergeTurns, reconcileTurn, type TurnBinding } from '../logic';
import { openTurn } from '../stream';
import Feedback from '../ui/Feedback.vue';
import SafeText from '../ui/SafeText.vue';
import References from '../ui/References.vue';
import TurnCard from '../ui/TurnCard.vue';
import KbAlert from '../ui/KbAlert.vue';
import KbDialog from '../ui/KbDialog.vue';
import CitationDrawer from '../CitationDrawer.vue';
import type { Citation, Conversation, Turn, TurnPage } from '../types';

const props = defineProps<{ conversationId: string }>();
const emit = defineEmits<{ deleted: [] }>();
const auth = useSession();
const query = useResource(async signal => {
  const id = positiveId(props.conversationId);
  const [meta, history] = await Promise.all([auth.session.json<Conversation>(`/api/conversations/${id}`, { signal }, false),
    auth.session.json<TurnPage>(`/api/conversations/${id}/turns?limit=20`, { signal }, false)]);
  return { meta, history };
}, () => props.conversationId);
const turns = shallowRef<Turn[]>([]), cursor = ref<bigint | null>(null), question = ref('');
const running = ref(false), error = ref(''), hint = ref('');
const temporary = shallowRef<{ question: string; text: string; citations: Citation[] } | null>(null);
const citation = shallowRef<Citation | null>(null), confirmDelete = ref(false), quarantined = ref(false);
const deleting = ref(false), paging = ref(false), liveMeta = shallowRef<Conversation | null>(null);
const binding = shallowRef<TurnBinding>();
let lock = false, deleteLock = false, pageLock = false, generation = 0;
let stream: AbortController | null = null, reconciliation: AbortController | null = null;
const context = new AbortController();
function dispose() {
  generation++;
  stream?.abort();
  reconciliation?.abort();
}
function quarantine() {
  dispose();
  quarantined.value = true; temporary.value = null; turns.value = []; citation.value = null; question.value = '';
  binding.value = undefined; lock = false; running.value = false;
}
const hidden = () => {
  if (document.visibilityState === 'hidden') {
    quarantine();
    error.value = ''; hint.value = '';
  }
};
document.addEventListener('visibilitychange', hidden);
onScopeDispose(() => {
  context.abort();
  dispose();
  document.removeEventListener('visibilitychange', hidden);
});
watch(() => query.data, data => {
  turns.value = mergeTurns([], data?.history.items ?? []);
  cursor.value = data?.history.nextCursor ?? null;
  liveMeta.value = data?.meta ?? null;
  citation.value = null;
}, { immediate: true });
const meta = computed(() => liveMeta.value ?? query.data?.meta);
async function send(fresh: boolean) {
  if (lock || !query.data || !meta.value || quarantined.value) {
    return;
  }
  const currentMeta = meta.value, session = auth.session;
  if (currentMeta.activeTurnId && binding.value?.question !== question.value) {
    hint.value = '当前有处理中轮次，请稍后刷新核对。';
    return;
  }
  let request: TurnBinding;
  try {
    request = !fresh && binding.value?.question === question.value ? binding.value : bindTurn(question.value, currentMeta.lastTurnSeq);
  } catch (failure) {
    error.value = messageOf(failure);
    return;
  }
  binding.value = request; lock = true; running.value = true; error.value = ''; hint.value = ''; citation.value = null;
  const controller = new AbortController(); stream = controller;
  const currentGeneration = ++generation, epoch = session.getSnapshot().epoch;
  const active = () => !context.signal.aborted && generation === currentGeneration && session.getSnapshot().epoch === epoch && session.getSnapshot().phase === 'ready';
  let turnId: string | null = null;
  temporary.value = { question: request.question, text: '', citations: [] };
  try {
    const result = await openTurn(session, props.conversationId, request, controller.signal, id => {
      turnId = id;
      if (active()) {
        liveMeta.value = { ...(liveMeta.value ?? currentMeta), activeTurnId: id };
      }
    }, event => {
      if (!active()) {
        return;
      }
      if (event.type === 'delta' && temporary.value) {
        temporary.value = { ...temporary.value, text: temporary.value.text + event.text };
      }
      if (event.type === 'citation' && temporary.value) {
        temporary.value = { ...temporary.value, citations: [...temporary.value.citations, event.citation] };
      }
      if (event.type === 'error') {
        error.value = event.code === 'SEARCH_UNAVAILABLE' ? '检索暂不可用，请稍后重试。' : '生成或保存暂未完成，请查看历史状态。';
      }
    });
    if (active() && result === 'eof') {
      hint.value = '连接已结束，正在核对已保存内容。';
    }
  } catch (failure) {
    if (active() && !isAbort(failure)) {
      error.value = failure instanceof ApiError && failure.status === 503 ? '生成服务暂不可用，请稍后重试。' : messageOf(failure);
      if (failure instanceof ApiError && failure.status === 409) {
        binding.value = undefined;
        query.reload();
      }
    }
  } finally {
    if (active() && turnId) {
      const controller = new AbortController(); reconciliation = controller;
      try {
        const finished = await reconcileTurn(() => session.json<Turn>(`/api/conversations/${props.conversationId}/turns/${turnId}`, { signal: controller.signal }, false), controller.signal, turn => {
          if (active()) {
            temporary.value = null;
            turns.value = mergeTurns(turns.value, [turn]);
            const previous = liveMeta.value ?? currentMeta;
            liveMeta.value = { ...previous, activeTurnId: turn.status === 'RUNNING' ? turn.id : null, lastTurnSeq: turn.turnSeq > previous.lastTurnSeq ? turn.turnSeq : previous.lastTurnSeq };
          }
        });
        if (active()) {
          if (finished) {
            const updated = await session.json<Conversation>(`/api/conversations/${props.conversationId}`, { signal: controller.signal }, false);
            if (active()) {
              liveMeta.value = updated; binding.value = undefined; question.value = ''; hint.value = '';
            }
          } else {
            hint.value = '持久状态仍在处理中，请稍后刷新核对。';
          }
        }
      } catch (failure) {
        if (active() && !isAbort(failure)) {
          temporary.value = null; turns.value = []; error.value ||= messageOf(failure); hint.value = '请稍后刷新核对保存结果。';
        }
      }
    } else if (active()) {
      temporary.value = null;
      hint.value = '尚未确认受理结果；相同输入可使用匹配重试。';
    }
    if (active()) {
      running.value = false; lock = false;
    }
  }
}
function refresh() {
  quarantined.value = false;
  query.reload();
}
async function more() {
  if (pageLock) {
    return;
  }
  pageLock = true; paging.value = true; error.value = '';
  const gen = generation;
  try {
    const page = await auth.session.json<TurnPage>(`/api/conversations/${props.conversationId}/turns?limit=20&cursor=${String(cursor.value)}`, { signal: context.signal }, false);
    if (!context.signal.aborted && gen === generation) {
      turns.value = mergeTurns(turns.value, page.items);
      cursor.value = page.nextCursor;
    }
  } catch (failure) {
    if (!context.signal.aborted && gen === generation && !isAbort(failure)) {
      error.value = messageOf(failure);
    }
  } finally {
    pageLock = false; paging.value = false;
  }
}
function stop() {
  stream?.abort();
  hint.value = '已停止接收，正在核对保存结果。';
}
function cancelDelete() {
  if (!deleting.value) {
    confirmDelete.value = false;
  }
}
async function remove() {
  if (deleteLock) {
    return;
  }
  deleteLock = true; deleting.value = true; error.value = '';
  quarantine();
  try {
    await auth.session.json(`/api/conversations/${positiveId(props.conversationId)}`, { method: 'DELETE', signal: context.signal }, false);
    if (!context.signal.aborted) {
      emit('deleted');
    }
  } catch (failure) {
    if (!context.signal.aborted && !isAbort(failure)) {
      if (failure instanceof ApiError && failure.status === 404) {
        emit('deleted');
      } else {
        error.value = '删除结果未确认，请刷新核对；不会自动重复删除。';
      }
    }
  } finally {
    deleteLock = false; deleting.value = false; confirmDelete.value = false;
  }
}
</script>

<template>
  <div class="toolbar"><h2 style="flex: 1; margin: 0">{{ query.data?.meta.title ?? '会话' }}</h2>
    <ElButton :disabled="running" @click="refresh">刷新历史</ElButton><ElButton type="danger" plain :disabled="deleting || !query.data" @click="confirmDelete = true">删除会话</ElButton></div>
  <Feedback :loading="query.loading" :error="query.error" :retry="query.reload" />
  <template v-if="!query.loading && !query.error && !quarantined">
    <TurnCard v-for="turn in turns" :key="turn.id" :value="turn" @open="citation = $event" />
    <ElButton v-if="cursor != null" :loading="paging" @click="more">更多轮次</ElButton>
    <article v-if="temporary" class="turn"><div class="question"><SafeText :text="temporary.question" /></div><p class="muted">生成中 · 内容尚待保存确认</p>
      <SafeText :text="temporary.text" /><References :citations="temporary.citations" @open="citation = $event" /></article>
    <div class="composer"><ElInput v-model="question" type="textarea" aria-label="问题" :disabled="running" :autosize="{ minRows: 3, maxRows: 10 }" placeholder="向知识库提问…" />
      <div class="toolbar" style="margin-top: 12px"><ElButton type="primary" :disabled="running || !question.trim() || (!!meta?.activeTurnId && binding?.question !== question)" @click="send(false)">{{ binding ? '匹配重试' : '发送问题' }}</ElButton>
        <ElButton v-if="binding" :disabled="running || !!meta?.activeTurnId" @click="send(true)">主动重新发送</ElButton>
        <ElButton v-if="running" @click="stop">停止</ElButton><span class="muted">最多 8000 个字符 · 请核对参考材料</span></div>
    </div>
  </template>
  <KbAlert v-if="quarantined" type="info" title="已关闭当前连接并清除本页内容。可刷新核对保存状态。" />
  <KbAlert v-if="error" type="warning" :title="error" /><p v-if="hint" role="status" class="muted">{{ hint }}</p>
  <KbDialog :title="`删除会话「${query.data?.meta.title ?? conversationId}」？`" :open="confirmDelete" @close="cancelDelete">
    <p>将删除本人会话正文与历史。目标：{{ query.data?.meta.title ?? conversationId }}。</p>
    <template #footer><ElButton @click="cancelDelete">取消</ElButton><ElButton type="danger" :loading="deleting" @click="remove">删除会话</ElButton></template>
  </KbDialog>
  <CitationDrawer :citation="query.data && !quarantined ? citation : null" @close="citation = null" />
</template>
