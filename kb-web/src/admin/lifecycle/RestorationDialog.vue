<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { ElButton, ElDescriptions, ElDescriptionsItem, ElForm, type FormInstance } from 'element-plus';
import { useSession } from '../../SessionProvider';
import AdminModal from '../AdminModal.vue';
import Note from '../Note.vue';
import WriteNotice from '../WriteNotice.vue';
import { lifecycleWrites, verifyRestoration } from './api';
import { useVerification } from './scope';
import { useLifecycleWrite } from './useLifecycleWrite';
import type { LifecycleUser, RestorationResult, RestorationVerified } from './types';
import ProofNotice from './ProofNotice.vue';
import ReasonField from './ReasonField.vue';

const props = defineProps<{ user: LifecycleUser; onRefresh: () => void }>();
const emit = defineEmits<{ close: []; done: [message: string] }>();
const auth = useSession(), verify = useVerification<RestorationVerified>(), values = reactive({ reason: '' }), form = ref<FormInstance>();
const { write, submit, retry } = useLifecycleWrite(`lifecycle:restore:${props.user.userId}`, { close: () => emit('close'), done: value => emit('done', value), refresh: () => props.onRefresh() }, value => {
  const result = value as RestorationResult;
  return result.outcome === 'RESTORED_MANUALLY_DISABLED' ? '离职限制已解除，账号仍被人工停用。' : `离职限制已解除，必须重新登录。${result.passwordChangeRequired ? '登录后仍需修改密码。' : ''}`;
}, verify.reset);
const locked = computed(() => write.pending || verify.busy);
const check = () => { void verify.run(signal => verifyRestoration(auth.session, props.user, signal)); };
let lock = false;
async function finish() {
  if (lock || !verify.valid || !verify.data || locked.value) {
    return;
  }
  lock = true;
  try {
    if (await form.value?.validate().catch(() => false)) {
      if (verify.valid && verify.data && !locked.value) {
        await submit(() => lifecycleWrites.restore(props.user.userId, verify.data!, values.reason));
      }
    }
  } finally {
    lock = false;
  }
}
</script>

<template>
  <AdminModal title="人工复职核对" :write="write" @close="emit('close')">
    <Note>仅解除生命周期限制；保留人工状态、部门、角色和密级，旧会话不会恢复。确认时服务端会再次核验当前成员。</Note>
    <ElButton aria-label="核对复职事实" :loading="verify.busy" :disabled="locked" @click="check">核对复职事实</ElButton><ProofNotice :data="verify.data" :valid="verify.valid" :error="verify.error" />
    <ElDescriptions v-if="verify.data" :column="1"><ElDescriptionsItem label="当前员工">{{ verify.data.employeeName }}</ElDescriptionsItem><ElDescriptionsItem label="主部门">{{ verify.data.departmentName }}</ElDescriptionsItem>
      <ElDescriptionsItem label="角色">{{ verify.data.roles.map(role => `${role.name}（${role.code}）`).join('、') || '无' }}</ElDescriptionsItem><ElDescriptionsItem label="密级">{{ verify.data.clearanceLevel }}</ElDescriptionsItem><ElDescriptionsItem label="人工状态">{{ verify.data.manualEnabled ? '启用' : '停用，复职后仍停用' }}</ElDescriptionsItem></ElDescriptions>
    <WriteNotice :write="write" :on-retry="retry" :on-verify="onRefresh" />
    <ElForm ref="form" :model="values" label-position="top" :disabled="locked" @submit.prevent="finish"><ReasonField v-model="values.reason" /><ElButton type="primary" native-type="submit" :disabled="!verify.valid || locked">确认解除生命周期限制</ElButton></ElForm>
  </AdminModal>
</template>
