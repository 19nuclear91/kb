<script setup lang="ts">
import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue';
import { ElButton, ElForm, ElFormItem, ElInput } from 'element-plus';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import { isAbort, messageOf } from '../api';
import Feedback from '../ui/Feedback.vue';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import type { Conversation, QueryPage } from '../types';
import ConversationPanel from './ConversationPanel.vue';

const auth = useSession(), router = useRouter(), route = useRoute();
const conversationId = computed(() => route.params.conversationId ? String(route.params.conversationId) : undefined);
const query = useResource(signal => auth.session.json<QueryPage<Conversation>>('/api/conversations?limit=20', { signal }, false), 'conversations');
const items = shallowRef<Conversation[]>([]), cursor = ref<string | null>(null);
const error = ref(''), busy = ref(false), creating = ref(false), title = ref('');
let binding: { title?: string; requestId: string } | null = null, lock = false;
const controller = new AbortController();
onScopeDispose(() => controller.abort());
watch(() => query.data, data => {
  items.value = data?.items ?? [];
  cursor.value = data?.nextCursor ?? null;
}, { immediate: true });
function toggle() {
  creating.value = !creating.value;
  title.value = '';
}
async function create() {
  if (lock) {
    return;
  }
  lock = true; busy.value = true; error.value = '';
  const normalized = title.value.trim() || undefined;
  if (!binding || binding.title !== normalized) {
    binding = { title: normalized, requestId: crypto.randomUUID() };
  }
  try {
    const result = await auth.session.post<{ conversation: Conversation; replayed: boolean }>('/api/conversations', binding, false, controller.signal);
    if (controller.signal.aborted) {
      return;
    }
    binding = null; creating.value = false; title.value = '';
    items.value = [...new Map([result.conversation, ...items.value].map(item => [item.id, item])).values()]
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || (BigInt(a.id) > BigInt(b.id) ? -1 : 1));
    void router.push(`/assistant/${result.conversation.id}`);
  } catch (failure) {
    if (!controller.signal.aborted && !isAbort(failure)) {
      error.value = messageOf(failure);
    }
  } finally {
    lock = false; busy.value = false;
  }
}
async function more() {
  if (lock) {
    return;
  }
  lock = true; busy.value = true; error.value = '';
  try {
    const result = await auth.session.json<QueryPage<Conversation>>(`/api/conversations?limit=20&cursor=${encodeURIComponent(cursor.value!)}`, { signal: controller.signal }, false);
    if (!controller.signal.aborted) {
      items.value = [...new Map([...items.value, ...result.items].map(item => [item.id, item])).values()];
      cursor.value = result.nextCursor;
    }
  } catch (failure) {
    if (!controller.signal.aborted && !isAbort(failure)) {
      error.value = messageOf(failure);
    }
  } finally {
    lock = false; busy.value = false;
  }
}
function deleted() {
  items.value = items.value.filter(item => item.id !== conversationId.value);
  void router.replace('/assistant');
}
</script>

<template>
  <PageHeader title="AI 助手" description="私有会话 · 回答仅基于你的当前可见资料" />
  <div class="assistant-layout"><aside class="panel"><h2>我的会话</h2>
    <ElButton type="primary" style="width: 100%" :disabled="query.loading" @click="toggle">新建会话</ElButton>
    <ElForm v-if="creating" label-position="top" @submit.prevent="create">
      <ElFormItem label="标题（可选）" style="margin-top: 16px"><ElInput v-model="title" aria-label="标题（可选）" :maxlength="120" placeholder="新会话" /></ElFormItem>
      <ElButton native-type="submit" :loading="busy" :disabled="query.loading">创建</ElButton>
    </ElForm>
    <div style="margin-top: 20px"><Feedback :loading="query.loading" :error="query.error" :retry="query.reload" />
      <RouterLink v-for="item in items" :key="item.id" :class="['conversation-item', { active: conversationId === item.id }]" :to="`/assistant/${item.id}`">{{ item.title }}</RouterLink>
      <p v-if="!query.loading && !query.error && !items.length" class="muted">创建你的第一个会话</p>
      <ElButton v-if="cursor" :disabled="busy" @click="more">更多会话</ElButton><KbAlert v-if="error" type="warning" :title="error" />
    </div>
  </aside><section class="panel">
    <ConversationPanel v-if="conversationId" :key="conversationId" :conversation-id="conversationId" @deleted="deleted" />
    <div v-else style="padding: 48px 12px"><h2>从一个问题开始</h2><p class="muted">选择或新建会话，查阅历史并继续提问。</p></div>
  </section></div>
</template>
