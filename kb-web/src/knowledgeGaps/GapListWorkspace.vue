<script setup lang="ts">
import { computed, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { ElButton, ElOption, ElSelect } from 'element-plus';
import { useSession } from '../SessionProvider';
import { useAdminAccess } from '../admin/access';
import KbAlert from '../ui/KbAlert.vue';
import KbSpin from '../ui/KbSpin.vue';
import { gapList, gapQueue } from './api';
import { useGapList } from './hooks';
import { gapCategoryLabels, gapStateLabels, gapStates, type GapState } from './types';

const props = withDefaults(defineProps<{ queue?: boolean }>(), { queue: false });
const auth = useSession(), access = useAdminAccess();
const view = ref<'mine' | 'assigned'>('mine'), status = ref<GapState | null>(null);
const canAssigned = computed(() => access.has('knowledge:gap:handle'));
const loader = computed(() => props.queue ? (access.has('knowledge:gap:read')
  ? (cursor: string | null, signal: AbortSignal) => gapQueue(auth.session, { status: status.value, cursor }, signal) : null)
  : view.value === 'assigned' && !canAssigned.value ? null
    : (cursor: string | null, signal: AbortSignal) => gapList(auth.session, { view: view.value, status: status.value, cursor }, signal));
const key = computed(() => `${props.queue ? 'queue' : view.value}:${status.value ?? ''}:${props.queue || view.value === 'assigned' ? access.permissionFactsKey : ''}`);
const list = useGapList(loader, key);
</script>

<template>
  <section class="panel" aria-label="知识问题列表">
    <div class="toolbar">
      <ElButton v-if="!queue" :disabled="view === 'mine'" @click="view = 'mine'">我提出的问题</ElButton>
      <ElButton v-if="!queue && canAssigned" :disabled="view === 'assigned'" @click="view = 'assigned'">分派给我</ElButton>
      <label>状态筛选 <ElSelect v-model="status" clearable placeholder="全部状态" aria-label="状态筛选" style="width: 160px">
        <ElOption v-for="state in gapStates" :key="state" :value="state" :label="gapStateLabels[state]" />
      </ElSelect></label>
      <ElButton @click="list.reload">重新读取</ElButton><RouterLink to="/knowledge-gaps/new">提出新需求</RouterLink>
    </div>
    <KbAlert v-if="queue && !access.has('knowledge:gap:read')" type="warning" title="当前受理读取资格未确认或已失效。" />
    <KbAlert v-else-if="!queue && view === 'assigned' && !canAssigned" type="warning" title="当前处理资格未确认或已失效，已清理原分派列表。" />
    <div v-else-if="list.loading" role="status"><KbSpin /> 正在读取问题…</div>
    <KbAlert v-else-if="list.error" type="warning" :title="list.error" />
    <template v-else-if="list.loaded">
      <p v-if="!list.items.length">当前视图没有可见问题。</p>
      <ul v-else class="gap-list">
        <li v-for="item in list.items" :key="item.gapId">
          <RouterLink :to="`${queue ? '/admin' : ''}/knowledge-gaps/${item.gapId}`">问题 {{ item.gapId }}</RouterLink>
          <span>{{ gapCategoryLabels[item.category] }} · {{ gapStateLabels[item.state] }}</span>
          <span v-if="item.assignee">负责人：{{ item.assignee.label ?? '未提供标签' }}</span>
          <span v-if="queue && item.needsReassignment">需人工重新分派</span>
          <span>{{ item.createdAt }}</span>
        </li>
      </ul>
      <ElButton v-if="list.hasMore" @click="list.more">读取下一页</ElButton><p v-else class="muted">已到当前可见范围末页。</p>
    </template>
  </section>
</template>

<style scoped>
.gap-list { padding: 0; list-style: none; }
.gap-list li { display: flex; flex-wrap: wrap; gap: 16px; padding: 14px 0; border-bottom: 1px solid var(--border-color, #ddd); }
</style>
