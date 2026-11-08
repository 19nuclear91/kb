<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { ElButton, ElForm, ElInput, type FormInstance } from 'element-plus';
import { useSession } from '../../SessionProvider';
import AdminModal from '../AdminModal.vue';
import Note from '../Note.vue';
import WriteNotice from '../WriteNotice.vue';
import { employeeInput, lifecycleWrites, verifyEmployee } from './api';
import { useVerification } from './scope';
import { useLifecycleWrite } from './useLifecycleWrite';
import type { EmployeeVerified, LifecycleUser } from './types';
import ProofNotice from './ProofNotice.vue';
import ReasonField from './ReasonField.vue';

const props = defineProps<{ user: LifecycleUser; connectionId: string; onRefresh: () => void }>();
const emit = defineEmits<{ close: []; done: [message: string] }>();
const auth = useSession(), verify = useVerification<EmployeeVerified>(), employee = ref(''), values = reactive({ reason: '' }), form = ref<FormInstance>();
const { write, submit, retry } = useLifecycleWrite(`lifecycle:map:${props.user.userId}`, { close: () => emit('close'), done: value => emit('done', value), refresh: () => props.onRefresh() }, () => '映射已保存，请以重新读取的限制与最终状态为准。', verify.reset);
const locked = computed(() => write.pending || verify.busy);
function change(value: string) {
  employee.value = value; verify.reset();
}
const check = () => { void verify.run(signal => verifyEmployee(auth.session, props.user.userId, employeeInput(props.user, props.connectionId, employee.value), signal)); };
let lock = false;
async function finish() {
  if (lock || !verify.valid || !verify.data || locked.value) {
    return;
  }
  lock = true;
  try {
    if (await form.value?.validate().catch(() => false)) {
      if (verify.valid && verify.data && !locked.value) {
        await submit(() => lifecycleWrites.map(props.user.userId, employeeInput(props.user, props.connectionId, employee.value), verify.data!, values.reason));
      }
    }
  } finally {
    lock = false;
  }
}
</script>

<template>
  <AdminModal title="核验并纳管员工" :write="write" @close="emit('close')">
    <Note>目标账号 {{ user.userId }} · 钉钉连接 {{ connectionId }}。本地登录账号同样可以纳管。请从权威目录取得准确员工标识，不按姓名或手机号猜测。</Note>
    <label for="lifecycle-employee">准确员工标识</label><ElInput id="lifecycle-employee" :model-value="employee" aria-label="准确员工标识" autocomplete="off" :maxlength="128" :disabled="locked" @update:model-value="change" />
    <ElButton aria-label="核验员工" :loading="verify.busy" :disabled="locked || !employee || !connectionId" @click="check">核验员工</ElButton>
    <ProofNotice :data="verify.data" :valid="verify.valid" :error="verify.error" /><p v-if="verify.data">核验姓名：{{ verify.data.displayName }} · 目标账号：{{ user.userId }}</p>
    <WriteNotice :write="write" :on-retry="retry" :on-verify="onRefresh" />
    <ElForm ref="form" :model="values" label-position="top" :disabled="locked" @submit.prevent="finish"><ReasonField v-model="values.reason" /><ElButton type="primary" native-type="submit" :disabled="!verify.valid || locked">确认纳管</ElButton></ElForm>
  </AdminModal>
</template>
