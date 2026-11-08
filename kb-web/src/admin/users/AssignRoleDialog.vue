<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { ElButton, ElForm, ElFormItem, ElOption, ElSelect, type FormInstance } from 'element-plus';
import { useSession } from '../../SessionProvider';
import { adminPaths, adminWrites } from '../api';
import { useAdminResource, useAdminWrite } from '../hooks';
import { settle } from '../ui';
import type { AdminUser, RoleSummary } from '../types';
import AdminModal from '../AdminModal.vue';
import WriteNotice from '../WriteNotice.vue';
import Note from '../Note.vue';
import KbAlert from '../../ui/KbAlert.vue';

const props = defineProps<{ user: AdminUser; bound: RoleSummary[]; onRefresh?: () => void }>();
const emit = defineEmits<{ close: []; done: [] }>();
const auth = useSession(), write = useAdminWrite(() => `user-role:${props.user.userId}`), form = ref<FormInstance>(), values = reactive({ roleId: undefined as string | undefined });
const catalog = useAdminResource<RoleSummary[]>(signal => auth.session.json(adminPaths.roles, { signal }), 'roles');
const options = computed(() => (catalog.data ?? []).filter(role => !props.bound.some(item => item.roleId === role.roleId)));
let lock = false;
async function submit() {
  if (lock) {
    return;
  }
  lock = true;
  try {
    if (await form.value?.validate().catch(() => false)) {
      settle(await write.submit(() => adminWrites.assignRole(props.user.userId, values.roleId!)), () => emit('done'), props.onRefresh);
    }
  } finally {
    lock = false;
  }
}
const retry = async () => settle(await write.retry(), () => emit('done'), props.onRefresh);
</script>

<template>
  <AdminModal :title="`为 ${user.username} 分配角色`" :write="write" @close="emit('close')"><WriteNotice :write="write" :on-retry="retry" :on-verify="onRefresh" />
    <KbAlert v-if="catalog.error" type="warning" :title="`角色目录读取失败：${catalog.error}`" style="margin-bottom: 12px"><template #action><ElButton @click="catalog.reload">重试</ElButton></template></KbAlert>
    <ElForm ref="form" :model="values" label-position="top" :disabled="write.busy" @submit.prevent="submit">
      <ElFormItem prop="roleId" label="角色" :rules="[{ required: true, message: '请选择角色' }]"><ElSelect v-model="values.roleId" aria-label="角色" :loading="catalog.loading"><ElOption v-for="role in options" :key="role.roleId" :value="role.roleId" :label="`${role.name}（${role.code}）`" /></ElSelect></ElFormItem>
      <Note>每次提交只分配一个角色，权限在后续请求生效。</Note><ElButton type="primary" native-type="submit" :loading="write.busy" :disabled="!catalog.data">确认分配</ElButton>
    </ElForm>
  </AdminModal>
</template>
