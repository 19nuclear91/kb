<script setup lang="ts">
import { computed, reactive, ref, shallowRef, watch } from 'vue';
import { ElButton, ElDescriptions, ElDescriptionsItem, ElInput, ElOption, ElSelect, ElSpace, ElTable, ElTableColumn } from 'element-plus';
import { ApiError, messageOf } from '../api';
import Feedback from '../ui/Feedback.vue';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import KbDrawer from '../ui/KbDrawer.vue';
import { useSession } from '../SessionProvider';
import { perm, useAdminAccess } from './access';
import { useAdminResource } from './hooks';
import OperationDialog from './OperationDialog.vue';
import { deadLetterPage, deadLetterPath, deadLetterStates, exactText, failureClasses, operationWrites, type DeadLetter, type DeadLetterFilters } from './operationsApi';

const auth = useSession(), access = useAdminAccess(), filters = shallowRef<DeadLetterFilters>({ status: 'OPEN', page: 1, size: 20 });
const draft = reactive({ status: 'OPEN', failureClass: '', from: '', to: '' }), error = ref(''), selected = ref<string | null>(null), replay = shallowRef<DeadLetter | null>(null), receipt = ref('');
let fallback = true;
const list = useAdminResource(signal => deadLetterPage(auth.session, filters.value, signal), () => deadLetterPath(filters.value));
const page = computed(() => list.data), row = computed(() => page.value?.items.find(item => item.id === selected.value));
watch([() => list.loading, page], () => {
  const current = page.value;
  if (list.loading || !current || current.items.length || current.page === 1 || !fallback) {
    return;
  }
  const total = BigInt(current.total), last = total === 0n ? 1n : (total + BigInt(current.size) - 1n) / BigInt(current.size);
  if (BigInt(current.page) > last) {
    fallback = false; filters.value = { ...filters.value, page: Number(last > 2147483647n ? 2147483647n : last) };
  }
});
function change(next: DeadLetterFilters) {
  fallback = true; selected.value = null; filters.value = next;
}
function submitFilters() {
  try {
    const iso = (value: string) => {
      if (!value) {
        return undefined;
      }
      const date = new Date(value);
      if (!Number.isFinite(date.getTime())) {
        throw new ApiError(400);
      }
      return date.toISOString();
    };
    const next = { ...filters.value, status: draft.status || undefined, failureClass: draft.failureClass || undefined, from: iso(draft.from), to: iso(draft.to), page: 1 };
    deadLetterPath(next); error.value = ''; change(next);
  } catch (failure) {
    error.value = messageOf(failure);
  }
}
const confirmed = computed(() => !!page.value && !list.loading && !list.error);
function refresh() {
  fallback = true; list.reload();
}
function done() {
  receipt.value = `死信 ${replay.value!.id} 已重放，新执行周期已重新入队；解析结果需后续核对。`; replay.value = null; selected.value = null; fallback = true; list.reload();
}
</script>

<template><PageHeader title="解析死信" description="筛选失败记录，查看安全摘要并按需单条重放"><template #extra><ElButton :disabled="list.loading || !!replay" @click="refresh">刷新列表</ElButton></template></PageHeader><KbAlert v-if="receipt" type="success" :title="receipt" style="margin-bottom: 16px" />
  <section class="panel"><form @submit.prevent="submitFilters"><ElSpace wrap alignment="end"><div><label for="dead-letter-status">状态</label><ElSelect id="dead-letter-status" v-model="draft.status" aria-label="状态" :disabled="!!replay" style="display: block; min-width: 140px"><ElOption value="" label="全部状态" /><ElOption v-for="value in deadLetterStates" :key="value" :value="value" :label="value" /></ElSelect></div>
    <div><label for="dead-letter-class">失败分类</label><ElSelect id="dead-letter-class" v-model="draft.failureClass" aria-label="失败分类" :disabled="!!replay" style="display: block; min-width: 140px"><ElOption value="" label="全部分类" /><ElOption v-for="value in failureClasses" :key="value" :value="value" :label="value" /></ElSelect></div>
    <div><label for="dead-letter-from">起始时间（含）</label><ElInput id="dead-letter-from" v-model="draft.from" aria-label="起始时间（含）" type="datetime-local" :disabled="!!replay" /></div><div><label for="dead-letter-to">截止时间（不含）</label><ElInput id="dead-letter-to" v-model="draft.to" aria-label="截止时间（不含）" type="datetime-local" :disabled="!!replay" /></div><ElButton native-type="submit" :disabled="!!replay">筛选</ElButton>
  </ElSpace></form><p class="muted">时间按本地时区输入；列表手动刷新，每次读取保留查询审计。</p><KbAlert v-if="error" type="error" :title="error" /></section>
  <Feedback :loading="list.loading" :error="list.error" :retry="list.reload" />
  <section v-if="page" class="panel"><ElTable row-key="id" size="small" :data="page.items"><ElTableColumn label="死信" prop="id" /><ElTableColumn label="任务" prop="taskId" /><ElTableColumn label="版本" prop="versionId" /><ElTableColumn label="执行周期"><template #default="{ row: item }">{{ exactText(item.executionGeneration) }}</template></ElTableColumn><ElTableColumn label="尝试次数" prop="attempts" /><ElTableColumn label="失败分类" prop="failureClass" /><ElTableColumn label="错误代码" prop="failureCode" /><ElTableColumn label="状态" prop="status" />
    <ElTableColumn label="操作"><template #default="{ row: item }"><ElSpace><ElButton size="small" :disabled="!!replay || !confirmed" @click="selected = item.id">查看摘要 {{ item.id }}</ElButton><ElButton v-if="access.has(perm.deadLetterReplay)" size="small" :disabled="!!replay || !confirmed || item.status !== 'OPEN'" @click="replay = item as DeadLetter">重放 {{ item.id }}</ElButton></ElSpace></template></ElTableColumn>
  </ElTable><ElSpace wrap style="margin-top: 16px"><span>第 {{ filters.page }} 页 · 共 {{ exactText(page.total) }} 条</span><ElButton :disabled="!confirmed || !!replay || filters.page <= 1" @click="change({ ...filters, page: filters.page - 1 })">上一页</ElButton><ElButton :disabled="!confirmed || !!replay || filters.page === 2147483647 || BigInt(filters.page) * BigInt(filters.size) >= BigInt(page.total)" @click="change({ ...filters, page: filters.page + 1 })">下一页</ElButton><ElSelect aria-label="每页条数" :model-value="filters.size" :disabled="!!replay" @update:model-value="size => change({ ...filters, page: 1, size })"><ElOption v-for="value in [20, 50, 100]" :key="value" :value="value" :label="`每页 ${value} 条`" /></ElSelect></ElSpace></section>
  <KbDrawer v-if="row" :title="`死信摘要 ${row.id}`" open @close="selected = null"><ElDescriptions :column="1"><ElDescriptionsItem label="任务标识">{{ row.taskId }}</ElDescriptionsItem><ElDescriptionsItem label="版本标识">{{ row.versionId }}</ElDescriptionsItem><ElDescriptionsItem label="执行周期">{{ exactText(row.executionGeneration) }}</ElDescriptionsItem><ElDescriptionsItem label="尝试次数">{{ row.attempts }}</ElDescriptionsItem><ElDescriptionsItem label="失败分类">{{ row.failureClass }}</ElDescriptionsItem><ElDescriptionsItem label="错误代码">{{ row.failureCode }}</ElDescriptionsItem><ElDescriptionsItem label="脱敏摘要"><pre class="content-text">{{ row.errorSummary }}</pre></ElDescriptionsItem><ElDescriptionsItem label="进入时间">{{ row.enteredAt }}</ElDescriptionsItem><ElDescriptionsItem label="处置时间">{{ row.resolvedAt ?? '未处置' }}</ElDescriptionsItem></ElDescriptions></KbDrawer>
  <OperationDialog v-if="replay" title="确认重放死信" :scope="`dead-letter:${replay.id}`" :max="2000" :allowed="confirmed && access.has(perm.deadLetterReplay) && !!page?.items.some(item => item.id === replay!.id && item.status === 'OPEN')" :build="reason => operationWrites.replay(replay!.id, reason)" @close="replay = null" @refresh="list.reload" @done="done"><template #description>死信 {{ replay.id }} · 任务 {{ replay.taskId }} · 版本 {{ replay.versionId }}。重放会保留原失败记录并开启新周期；只表示重新入队，仍受 Embedding 维护等待约束。</template></OperationDialog>
</template>
