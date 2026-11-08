<script setup lang="ts">
import { computed, ref, shallowRef } from 'vue';
import { ElButton, ElSpace, ElTable, ElTableColumn } from 'element-plus';
import Feedback from '../../ui/Feedback.vue';
import KbAlert from '../../ui/KbAlert.vue';
import { useSession } from '../../SessionProvider';
import { perm, useAdminAccess } from '../access';
import { useAdminResource, useCursorList } from '../hooks';
import Note from '../Note.vue';
import { emergencyUsers, lifecycleUser } from './api';
import EmergencyDialog from './EmergencyDialog.vue';
import type { EmergencyUser } from './types';

const emit = defineEmits<{ changed: [] }>();
const auth = useSession(), access = useAdminAccess(), self = computed(() => auth.state.me!.user.userId);
const list = useCursorList((cursor, signal) => emergencyUsers(auth.session, cursor, signal), 'lifecycle-emergency');
const canManage = computed(() => access.has(perm.emergencyManage));
const own = useAdminResource(computed(() => canManage.value ? signal => lifecycleUser(auth.session, self.value, signal) : null), () => `lifecycle-self:${self.value}`);
const dialog = shallowRef<{ replacement: EmergencyUser | null } | null>(null), receipt = ref('');
const candidate = computed(() => own.data && own.data.kind !== 'MANAGED' && own.data.restriction === 'CLEAR' && own.data.enabled);
function refresh() {
  list.reload(); own.reload(); emit('changed');
}
function done(message: string) {
  receipt.value = message; dialog.value = null; refresh();
}
</script>

<template>
  <section class="panel"><h2>独立应急保障</h2><Note>只允许候选本人本地验证后登记或先建后换。保障资格由服务端按当前账号、部门、凭据和权限事实判断。</Note>
    <KbAlert v-if="receipt" type="success" :title="receipt" /><Feedback :loading="list.loading || own.loading" :error="list.error || own.error" :retry="refresh" />
    <ElTable row-key="userId" size="small" :data="list.items"><ElTableColumn label="账号" prop="userId" /><ElTableColumn label="显示名" prop="displayName" />
      <ElTableColumn label="当前保障资格"><template #default="{ row }">{{ row.qualified ? '合格' : '不合格，需处理' }}</template></ElTableColumn>
      <ElTableColumn label="操作"><template #default="{ row }"><ElButton v-if="canManage && row.userId !== self" :disabled="!candidate || own.loading || !!own.error || !!dialog || list.loading || !!list.error" @click="dialog = { replacement: row as EmergencyUser }">由本人替换 {{ row.displayName }}</ElButton></template></ElTableColumn>
    </ElTable><ElSpace><ElButton v-if="list.hasMore" :loading="list.loading" :disabled="!!dialog" @click="list.more">更多保障账号</ElButton><ElButton :disabled="!!dialog" @click="refresh">刷新应急保障</ElButton>
      <ElButton v-if="canManage" :disabled="!candidate || own.loading || !!own.error || !!dialog || own.data?.kind === 'EMERGENCY'" @click="dialog = { replacement: null }">登记本人</ElButton></ElSpace>
    <Note v-if="canManage && own.data && !candidate">本人当前状态不符合候选条件，不能登记或轮换。</Note>
    <EmergencyDialog v-if="dialog && own.data" :user="own.data" :replacement="dialog.replacement" :on-refresh="refresh" @close="dialog = null" @done="done" />
  </section>
</template>
