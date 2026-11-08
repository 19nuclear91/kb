<script setup lang="ts">
import { ref, watch } from 'vue';
import { ElButton, ElSpace } from 'element-plus';
import { RouterLink } from 'vue-router';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import KbSpin from '../ui/KbSpin.vue';
import { messageOf } from '../api';
import { perm, useAdminAccess } from './access';
import { operationsReportsPath } from './operationsReportsApi';
import { useOperationsReportsQuery } from './operationsReportsQuery';
import OperationsReport from './OperationsReport.vue';

const access = useAdminAccess(), query = useOperationsReportsQuery(), draft = ref({ from: '', to: '' }), error = ref('');
watch(() => query.resetKey, () => { draft.value = { from: '', to: '' }; error.value = ''; }, { immediate: true });
function submit() {
  try {
    const filters = draft.value.from || draft.value.to ? { ...draft.value } : {};
    operationsReportsPath(filters); query.submit(filters); error.value = '';
  } catch (cause) {
    error.value = messageOf(cause);
  }
}
function reset() {
  draft.value = { from: '', to: '' }; error.value = ''; query.reset();
}
</script>

<template>
  <template v-if="access.status === 'ready' && access.has(perm.operationsReportsRead)"><PageHeader title="运营概览" description="全局固定汇总 · 当前库存与历史事件分开展示"><template #extra><RouterLink v-if="access.has(perm.aiUsageRead)" to="/admin/ai/usage">查看 AI 用量</RouterLink></template></PageHeader>
    <form class="panel" aria-label="报表时间窗口" @submit.prevent="submit"><p>默认最近 7 天；可显式提交最长 31 天的带偏移 ISO 时间。筛选只影响事件，不改变当前库存。条件仅保存在当前页面内存。</p><ElSpace wrap><label>开始时间（带偏移 ISO）<input v-model="draft.from" aria-label="开始时间" placeholder="2026-10-20T00:00:00Z" /></label><label>结束时间（带偏移 ISO）<input v-model="draft.to" aria-label="结束时间" placeholder="2026-10-27T00:00:00Z" /></label><ElButton native-type="submit" aria-label="查询">查询</ElButton><ElButton @click="reset">重置为最近 7 天</ElButton><ElButton :disabled="query.loading || query.forbidden" @click="query.refresh">刷新已提交条件</ElButton></ElSpace><KbAlert v-if="error" type="error" :title="error" /></form>
    <div v-if="query.loading" role="status" class="panel"><KbSpin /> 正在读取运营概览…</div><KbAlert v-else-if="query.error" type="warning" :title="query.error" description="当前没有可展示的成功结果。可显式重试；历史未知不会补零。"><template #action><ElButton @click="query.retry">重试读取</ElButton></template></KbAlert><OperationsReport v-else-if="query.data" :data="query.data" />
  </template>
</template>
