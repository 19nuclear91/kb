<script setup lang="ts">
import { ref } from 'vue';
import { ElButton, ElOption, ElSelect, ElSpace, ElTable, ElTableColumn } from 'element-plus';
import Feedback from '../../ui/Feedback.vue';
import KbAlert from '../../ui/KbAlert.vue';
import KbDrawer from '../../ui/KbDrawer.vue';
import { useSession } from '../../SessionProvider';
import { perm, useAdminAccess } from '../access';
import { useCursorList } from '../hooks';
import Note from '../Note.vue';
import { eventStatuses, lifecycleEvents, lifecycleWrites } from './api';
import LifecycleActionDialog from './LifecycleActionDialog.vue';
import Items from './Items.vue';
import { labels } from './labels';

const emit = defineEmits<{ changed: [] }>();
const auth = useSession(), access = useAdminAccess();
const status = ref('UNMAPPED'), selected = ref<string | null>(null), retry = ref<string | null>(null), receipt = ref('');
const list = useCursorList((cursor, signal) => lifecycleEvents(auth.session, cursor, status.value, signal), () => `lifecycle-events:${status.value}`);
function refresh() {
  list.reload(); emit('changed');
}
function done(message: string) {
  receipt.value = message; retry.value = null; selected.value = null; refresh();
}
function change(value: string) {
  selected.value = null; status.value = value;
}
</script>

<template>
  <section class="panel"><h2>事件处置</h2>
    <ElSpace><ElSelect :model-value="status" aria-label="事件处置状态" :disabled="!!retry" style="min-width: 160px" @update:model-value="change"><ElOption value="" label="全部状态" /><ElOption v-for="value in eventStatuses" :key="value" :value="value" :label="labels[value]" /></ElSelect><ElButton :disabled="!!retry || list.loading" @click="refresh">刷新事件</ElButton></ElSpace>
    <KbAlert v-if="receipt" type="success" :title="receipt" /><Feedback :loading="list.loading" :error="list.error" :retry="list.reload" />
    <ElTable row-key="id" size="small" :data="list.items"><ElTableColumn label="记录" prop="id" /><ElTableColumn label="记录时间" prop="recordedAt" />
      <ElTableColumn label="已停权项"><template #default="{ row }">{{ String(row.restricted) }}</template></ElTableColumn><ElTableColumn label="未匹配项"><template #default="{ row }">{{ String(row.unmapped) }}</template></ElTableColumn>
      <ElTableColumn label="待核对项"><template #default="{ row }">{{ String(row.review) }}</template></ElTableColumn><ElTableColumn label="失败项"><template #default="{ row }">{{ String(row.failed) }}</template></ElTableColumn>
      <ElTableColumn label="冲突"><template #default="{ row }">{{ row.conflicted ? '需核对' : '无' }}</template></ElTableColumn>
      <ElTableColumn label="操作"><template #default="{ row }"><ElSpace><ElButton size="small" :disabled="!!retry" @click="selected = row.id">查看员工项 {{ row.id }}</ElButton>
        <ElButton v-if="access.has(perm.lifecycleManage)" size="small" :disabled="!!retry || list.loading || !!list.error" @click="retry = row.id">重试原事件 {{ row.id }}</ElButton></ElSpace></template></ElTableColumn>
    </ElTable><Note>当前已读取 {{ list.items.length }} 条记录，非全局总数；每次最多读取 20 条。未匹配需先核对准确员工映射，重试不会建号或自动复职。</Note>
    <ElButton v-if="list.hasMore" :loading="list.loading" :disabled="!!retry" @click="list.more">更多事件</ElButton>
    <KbDrawer v-if="selected" :title="`事件 ${selected} 的员工处置项`" open :size="720" @close="selected = null"><Items :key="selected" :event-id="selected" /></KbDrawer>
    <LifecycleActionDialog v-if="retry" title="重试原事件" :scope="`lifecycle:retry:${retry}`" :description="`只处理记录 ${retry} 的服务端原始事实；不会替换载荷或解除已有离职限制。`" :build="reason => lifecycleWrites.retry(retry!, reason)" result="原事件已重试，请读取逐员工结果；未匹配及待核对不表示成功停权。" :on-refresh="refresh" @close="retry = null" @done="done" />
  </section>
</template>
