<script setup lang="ts">
import { computed, watch } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import KbAlert from '../ui/KbAlert.vue';
import KbSpin from '../ui/KbSpin.vue';
import { gapHistory } from './api';
import { useGapList } from './hooks';
import { gapEventLabels } from './types';
import GapEntryView from './GapEntryView.vue';

const props = defineProps<{ gapId: string }>();
const emit = defineEmits<{ referenceDenied: []; inaccessible: [status: number] }>();
const auth = useSession();
const loader = computed(() => (cursor: string | null, signal: AbortSignal) => gapHistory(auth.session, props.gapId, { cursor }, signal));
const history = useGapList(loader, () => props.gapId);
watch(() => history.status, status => {
  if (status === 401 || status === 403 || status === 404) emit('inaccessible', status);
}, { flush: 'sync' });
</script>

<template>
  <section class="panel" aria-label="问题处理历史">
    <div class="toolbar"><h2>共享处理历史</h2><ElButton @click="history.reload">重读处理历史</ElButton></div>
    <div v-if="history.loading" role="status"><KbSpin /> 正在读取处理历史…</div>
    <KbAlert v-else-if="history.error" type="warning" :title="history.error" />
    <template v-else-if="history.loaded">
      <p v-if="!history.items.length">当前页没有处理记录。</p>
      <ol v-else>
        <li v-for="item in history.items" :key="item.eventId">
          <p>{{ gapEventLabels[item.kind] }} · {{ item.actor.label ?? '未提供标签' }} · {{ item.occurredAt }}</p>
          <GapEntryView v-if="item.entry" :key="item.entry.entryId" :entry="item.entry" @reference-denied="emit('referenceDenied')" />
        </li>
      </ol>
      <ElButton v-if="history.hasMore" @click="history.more">读取更多历史</ElButton><p v-else class="muted">已到处理历史末页。</p>
    </template>
  </section>
</template>
