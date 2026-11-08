<script setup lang="ts">
import { reactive, ref } from 'vue';
import { ElButton, ElForm, type FormInstance } from 'element-plus';
import { adminWrites } from '../api';
import { useAdminWrite, type WriteOutcome } from '../hooks';
import { settle } from '../ui';
import type { AdminUser } from '../types';
import AdminModal from '../AdminModal.vue';
import WriteNotice from '../WriteNotice.vue';
import Note from '../Note.vue';
import PasswordFields from '../PasswordFields.vue';

const props = defineProps<{ user: AdminUser; onRefresh?: () => void }>();
const emit = defineEmits<{ close: []; done: [] }>();
const write = useAdminWrite(() => `user-password:${props.user.userId}`), form = ref<FormInstance>(), values = reactive<Record<string, string>>({ newPassword: '', newPasswordConfirm: '' });
function after(outcome: WriteOutcome<unknown>) {
  if (outcome.status !== 'ignored') {
    form.value?.resetFields(['newPassword', 'newPasswordConfirm']);
    settle(outcome, () => emit('done'), props.onRefresh);
  }
}
let lock = false;
async function submit() {
  if (lock) {
    return;
  }
  lock = true;
  try {
    if (await form.value?.validate().catch(() => false)) {
      after(await write.submit(() => adminWrites.resetPassword(props.user.userId, values.newPassword!)));
    }
  } finally {
    lock = false;
  }
}
const retry = async () => after(await write.retry());
</script>

<template>
  <AdminModal :title="`重置 ${user.username} 的密码`" :write="write" @close="emit('close')"><WriteNotice :write="write" :on-retry="retry" :on-verify="onRefresh" />
    <ElForm ref="form" :model="values" label-position="top" :disabled="write.busy" @submit.prevent="submit"><PasswordFields name="newPassword" label="新密码" :values="values" @change="(name, value) => { values[name] = value; }" />
      <Note>密码强度由服务器策略判定；重置后该账号下次登录需修改密码。</Note><ElButton type="primary" native-type="submit" :loading="write.busy">确认重置</ElButton></ElForm>
  </AdminModal>
</template>
