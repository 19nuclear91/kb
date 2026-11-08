<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElButton, ElDescriptions, ElDescriptionsItem, ElSpace } from 'element-plus';
import Feedback from '../../ui/Feedback.vue';
import KbAlert from '../../ui/KbAlert.vue';
import { useSession } from '../../SessionProvider';
import { perm, useAdminAccess } from '../access';
import { useAdminResource } from '../hooks';
import Note from '../Note.vue';
import { lifecycleStatus, lifecycleUser, lifecycleWrites } from './api';
import MappingDialog from './MappingDialog.vue';
import RestorationDialog from './RestorationDialog.vue';
import LifecycleActionDialog from './LifecycleActionDialog.vue';

const props = defineProps<{ userId: string }>();
const emit = defineEmits<{ changed: [] }>();
const restrictionLabel = { CLEAR: '无限制', OFFBOARDED: '离职停权', REVIEW: '待人工核对（限制访问）' } as const;
const kindLabel = { NONE: '未纳管', MANAGED: '受管员工', EMERGENCY: '独立应急管理员' } as const;
const auth = useSession(), access = useAdminAccess();
const detail = useAdminResource(signal => lifecycleUser(auth.session, props.userId, signal), () => `lifecycle-user:${props.userId}`);
const status = useAdminResource(computed(() => access.has(perm.lifecycleManage) ? signal => lifecycleStatus(auth.session, signal) : null), 'lifecycle-status');
const dialog = ref<'map' | 'restore' | 'exit' | null>(null), receipt = ref('');
const user = computed(() => detail.data), confirmed = computed(() => !!user.value && !detail.loading && !detail.error);
function refresh() {
  detail.reload(); status.reload(); emit('changed');
}
function done(message: string) {
  receipt.value = message; dialog.value = null; refresh();
}
</script>

<template>
  <section aria-label="账号生命周期"><h3>员工生命周期</h3><KbAlert v-if="receipt" type="success" :title="receipt" /><Feedback :loading="detail.loading" :error="detail.error" :retry="detail.reload" />
    <template v-if="user"><ElDescriptions :column="1" size="small"><ElDescriptionsItem label="管理类型">{{ kindLabel[user.kind] }}</ElDescriptionsItem><ElDescriptionsItem label="人工状态">{{ user.manualEnabled ? '启用' : '停用' }}</ElDescriptionsItem>
      <template v-if="user.kind === 'MANAGED'"><ElDescriptionsItem label="受管来源">钉钉</ElDescriptionsItem><ElDescriptionsItem label="连接标识">{{ user.connectionId ?? '不可确认' }}</ElDescriptionsItem></template>
      <ElDescriptionsItem label="生命周期限制">{{ restrictionLabel[user.restriction] }}</ElDescriptionsItem><ElDescriptionsItem label="最终状态">{{ user.enabled ? '启用' : '停用' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="最近状态更新时间">{{ user.updatedAt ?? '暂无记录' }}</ElDescriptionsItem><ElDescriptionsItem label="最近复职核对时间">{{ user.restoredAt ?? '暂无记录' }}</ElDescriptionsItem></ElDescriptions>
      <Note>生命周期映射独立于 SSO 绑定。普通启用不能解除离职或待核对限制。</Note>
      <ElSpace wrap><ElButton :disabled="!!dialog || detail.loading" @click="refresh">重读生命周期</ElButton>
        <ElButton v-if="access.has(perm.lifecycleManage) && user.kind !== 'EMERGENCY' && user.restriction === 'CLEAR'" :disabled="!confirmed || !!dialog || !status.data?.configured || status.loading || !!status.error" @click="dialog = 'map'">{{ user.kind === 'MANAGED' ? '变更员工映射' : '纳管员工' }}</ElButton>
        <ElButton v-if="access.has(perm.lifecycleManage) && user.kind === 'MANAGED' && user.restriction === 'CLEAR'" :disabled="!confirmed || !!dialog" @click="dialog = 'exit'">退出纳管</ElButton>
        <ElButton v-if="access.has(perm.lifecycleRestore) && user.kind === 'MANAGED' && user.restriction !== 'CLEAR'" :disabled="!confirmed || !!dialog" @click="dialog = 'restore'">人工复职</ElButton>
      </ElSpace>
    </template>
    <Feedback v-if="status.error" :error="status.error" :retry="status.reload" />
    <MappingDialog v-if="user && dialog === 'map' && status.data" :user="user" :connection-id="status.data.connectionId" :on-refresh="refresh" @close="dialog = null" @done="done" />
    <RestorationDialog v-if="user && dialog === 'restore'" :user="user" :on-refresh="refresh" @close="dialog = null" @done="done" />
    <LifecycleActionDialog v-if="user && dialog === 'exit'" title="退出员工纳管" :scope="`lifecycle:exit:${userId}`" description="仅在无限制、无未解决事件及在途复职时允许退出；历史处置仍保留。" :build="reason => lifecycleWrites.exit(user!, reason)" result="已退出纳管，历史处置记录保留。" :on-refresh="refresh" @close="dialog = null" @done="done" />
  </section>
</template>
