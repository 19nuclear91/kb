<script setup lang="ts">
import { onScopeDispose, ref, shallowRef } from 'vue';
import { ElButton, ElEmpty, ElSpace, ElTag } from 'element-plus';
import { ApiError, isAbort, messageOf } from '../api';
import { useSession } from '../SessionProvider';
import KbAlert from '../ui/KbAlert.vue';
import { markRead, notificationLabels, notificationPage, type NotificationItem, type NotificationPage } from './api';
import NotificationTargetPanel from './NotificationTargetPanel.vue';

const props = defineProps<{ first: NotificationPage; reload: () => void; onRead: () => void }>();
const auth = useSession();
type Receipt = 'sending' | 'uncertain' | 'failed';
const without = (value: Record<string, Receipt>, id: string) => Object.fromEntries(Object.entries(value).filter(([key]) => key !== id));
const items = shallowRef(props.first.items), cursor = ref(props.first.nextCursor);
const busy = ref(false), error = ref(''), notice = ref('');
const receipts = shallowRef<Record<string, Receipt>>({}), opened = shallowRef<NotificationItem | null>(null);
let paging = false;
const sending = new Set<string>(), controller = new AbortController();
onScopeDispose(() => controller.abort());
async function read(id: string) {
  if (sending.has(id)) {
    return;
  }
  sending.add(id);
  receipts.value = { ...receipts.value, [id]: 'sending' };
  notice.value = '';
  try {
    const receipt = await markRead(auth.session, id, controller.signal);
    if (controller.signal.aborted) {
      return;
    }
    items.value = items.value.map(item => item.notificationId === id ? { ...item, readAt: receipt.readAt } : item);
    receipts.value = without(receipts.value, id);
    props.onRead();
  } catch (failure) {
    if (controller.signal.aborted || isAbort(failure)) {
      return;
    }
    if (failure instanceof ApiError && failure.status === 404) {
      items.value = items.value.filter(item => item.notificationId !== id);
      opened.value = opened.value?.notificationId === id ? null : opened.value;
      receipts.value = without(receipts.value, id);
      notice.value = '该通知已不存在或不属于当前账号。';
    } else {
      receipts.value = { ...receipts.value, [id]: failure instanceof ApiError && failure.status === 0 ? 'uncertain' : 'failed' };
    }
  } finally {
    sending.delete(id);
  }
}
async function more() {
  if (paging || !cursor.value) {
    return;
  }
  paging = true;
  busy.value = true;
  error.value = '';
  try {
    const page = await notificationPage(auth.session, cursor.value, controller.signal);
    if (controller.signal.aborted) {
      return;
    }
    items.value = [...new Map([...items.value, ...page.items].map(item => [item.notificationId, item])).values()];
    cursor.value = page.nextCursor;
  } catch (failure) {
    if (!controller.signal.aborted && !isAbort(failure)) {
      error.value = messageOf(failure);
    }
  } finally {
    paging = false;
    if (!controller.signal.aborted) {
      busy.value = false;
    }
  }
}
</script>

<template>
  <section class="panel"><div class="toolbar"><ElButton @click="reload">刷新通知</ElButton>
    <span class="muted">每次读取按当前权限核对；不可见的通知不显示占位或数量</span></div>
    <KbAlert v-if="notice" style="margin-bottom: 12px" type="info" :title="notice" />
    <ul v-if="items.length" class="notification-list"><li v-for="item in items" :key="item.notificationId"><ElSpace wrap>
      <ElTag :type="item.readAt ? 'info' : 'primary'">{{ item.readAt ? '已读' : '未读' }}</ElTag>
      <span>{{ notificationLabels[item.type] }}</span>
      <span class="muted">{{ new Date(item.createdAt).toLocaleString('zh-CN') }}
        <template v-if="item.target.kind === 'KNOWLEDGE_GAP'"> · 问题 {{ item.target.gapId }}</template>
        <template v-else> · 文档 {{ item.target.documentId }} · 版本 {{ item.target.versionId }}</template>
      </span>
      <ElButton @click="opened = item">打开目标</ElButton>
      <ElButton v-if="!item.readAt" :loading="receipts[item.notificationId] === 'sending'" :disabled="receipts[item.notificationId] === 'sending'" @click="read(item.notificationId)">{{ receipts[item.notificationId] === 'uncertain' || receipts[item.notificationId] === 'failed' ? '重试标记已读' : '标记已读' }}</ElButton>
    </ElSpace>
      <KbAlert v-if="receipts[item.notificationId] === 'uncertain'" type="warning" title="已读结果待核对：可重试同一条标记，或刷新通知确认。这不表示业务已处理或问题已解决。" />
      <KbAlert v-if="receipts[item.notificationId] === 'failed'" type="error" title="标记已读未完成，请稍后重试。" />
    </li></ul>
    <ElEmpty v-else description="暂无可见通知" />
    <KbAlert v-if="error" style="margin-top: 16px" type="warning" :title="error" />
    <ElButton v-if="cursor" style="margin-top: 16px" :loading="busy" :disabled="busy" @click="more">更多通知</ElButton>
    <p v-else-if="items.length > 0" class="muted">已显示全部可见通知</p>
  </section>
  <NotificationTargetPanel v-if="opened" :key="opened.notificationId" :item="opened" @close="opened = null" />
</template>
