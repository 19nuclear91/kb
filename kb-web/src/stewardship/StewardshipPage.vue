<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue';
import { ElButton } from 'element-plus';
import { RouterLink } from 'vue-router';
import { useSession } from '../SessionProvider';
import { useCursorList } from '../admin/hooks';
import Feedback from '../ui/Feedback.vue';
import PageHeader from '../ui/PageHeader.vue';
import ApplicabilityBadge from '../validity/ApplicabilityBadge.vue';
import { validityApi, type MineItem } from '../validity/api';
import { attentionLabels, stewardshipLabels } from '../validity/labels';
import ConfirmationDialog from './ConfirmationDialog.vue';
import { useStewardshipCount } from './count';

const auth = useSession(), count = useStewardshipCount(), view = ref<'attention' | 'all'>('attention'), notice = ref('');
const asOfDate = ref('');
// 清单接口的业务日期随每页返回；不使用浏览器主机日期解释确认。
const datedLoader = computed(() => auth.state.phase === 'ready' ? async (cursor: string | null, signal: AbortSignal) => {
  const page = await validityApi.mine(auth.session, view.value, cursor, signal); if (!signal.aborted) asOfDate.value = page.asOfDate; return page;
} : null);
const datedList = useCursorList<MineItem>(datedLoader, () => `stewardship-dated:${view.value}`);
const choice = shallowRef<{ item: MineItem; key: string; done: () => void; rejected: () => void } | null>(null);
watch([view, () => auth.state.epoch, () => auth.state.phase], () => { choice.value = null; notice.value = ''; asOfDate.value = ''; }, { flush: 'sync' });
function select(item: MineItem) {
  const key = `${auth.state.epoch}|${view.value}|${item.documentId}|${crypto.randomUUID()}`;
  choice.value = { item: { ...item }, key, done: () => { if (choice.value?.key !== key) return; choice.value = null; notice.value = '复审确认成功，正在重新读取清单。'; datedList.reload(); count.refresh(); },
    rejected: () => { if (choice.value?.key !== key) return; choice.value = null; notice.value = '当前无法确认，正在重新核对本人清单。'; datedList.reload(); } };
}
</script>
<template>
  <PageHeader title="我负责的资料" description="复审逾期不等于内容失效；标识不改变资料发布与召回。" />
  <ElButton :type="view === 'attention' ? 'primary' : 'default'" @click="view = 'attention'">需要处理</ElButton><ElButton :type="view === 'all' ? 'primary' : 'default'" @click="view = 'all'">全部</ElButton>
  <p v-if="notice" role="status">{{ notice }}</p><Feedback :loading="datedList.loading" :error="datedList.error" :retry="datedList.reload" />
  <template v-if="auth.state.phase === 'ready'"><p v-if="!datedList.loading && !datedList.error && !datedList.items.length">{{ view === 'attention' ? '暂无需要处理的资料' : '暂无本人负责的资料' }}</p>
    <article v-for="item in datedList.items" :key="item.documentId" class="panel"><h2><RouterLink :to="`/documents/${item.documentId}`">{{ item.title }}</RouterLink></h2>
      <p>{{ stewardshipLabels[item.state] }} · 到期日 {{ item.dueDate }} · 最近确认 {{ item.lastConfirmedOn ?? '尚未确认' }} · 周期 {{ item.cycleMonths }} 个月</p>
      <p v-if="item.attentionReasons.length">{{ item.attentionReasons.map(reason => attentionLabels[reason]).join('；') }}</p>
      <ApplicabilityBadge v-if="item.currentVersion" :value="item.currentVersion" /><p v-else>尚无当前发布版本标识</p><ElButton @click="select(item)">确认仍然准确</ElButton>
    </article><ElButton v-if="datedList.hasMore" :disabled="datedList.loading" @click="datedList.more">加载更多</ElButton>
  </template><p v-else>当前身份尚未确认，已清除旧清单。</p>
  <ConfirmationDialog v-if="choice" :key="choice.key" :item="choice.item" :as-of-date="asOfDate" @confirmed="choice.done" @rejected="choice.rejected" @verify="datedList.reload" @close="choice = null" />
</template>
