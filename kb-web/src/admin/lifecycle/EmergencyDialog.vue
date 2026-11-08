<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { ElButton, ElForm, ElInput, type FormInstance } from 'element-plus';
import { useSession } from '../../SessionProvider';
import AdminModal from '../AdminModal.vue';
import Note from '../Note.vue';
import WriteNotice from '../WriteNotice.vue';
import { lifecycleWrites, verifyEmergency } from './api';
import { useVerification } from './scope';
import { useLifecycleWrite } from './useLifecycleWrite';
import type { EmergencyUser, LifecycleUser, Proof } from './types';
import ProofNotice from './ProofNotice.vue';
import ReasonField from './ReasonField.vue';

const props = defineProps<{ user: LifecycleUser; replacement: EmergencyUser | null; onRefresh: () => void }>();
const emit = defineEmits<{ close: []; done: [message: string] }>();
const auth = useSession(), verify = useVerification<Proof>(), password = ref(''), values = reactive({ reason: '' }), form = ref<FormInstance>();
const { write, submit, retry } = useLifecycleWrite(`lifecycle:emergency:${props.user.userId}:${props.replacement?.userId ?? 'new'}`,
  { close: () => emit('close'), done: value => emit('done', value), refresh: () => props.onRefresh() },
  () => props.replacement ? '本人应急登记与原保障退役已完成，请重新核对保障资格。' : '本人应急登记已完成，请重新核对保障资格。', verify.reset);
const locked = computed(() => write.pending || verify.busy);
function change(value: string) {
  password.value = value; verify.reset();
}
function check() {
  const current = password.value;
  password.value = '';
  void verify.run(signal => verifyEmergency(auth.session, props.user, props.replacement, current, signal));
}
let lock = false;
async function finish() {
  if (lock || !verify.valid || !verify.data || locked.value) {
    return;
  }
  lock = true;
  try {
    if (await form.value?.validate().catch(() => false)) {
      if (verify.valid && verify.data && !locked.value) {
        await submit(() => lifecycleWrites.emergency(props.user.userId, verify.data!, props.replacement, values.reason));
      }
    }
  } finally {
    lock = false;
  }
}
</script>

<template>
  <AdminModal :title="replacement ? '本人登记并轮换应急保障' : '登记本人为应急管理员'" :write="write" @close="emit('close')">
    <Note>候选仅限当前本人（账号 {{ user.userId }}），须通过真实本地密码登录、完成改密并具备完整管理资格。SSO 或开发会话不能用于登记。</Note>
    <p v-if="replacement">先登记本人，再退役 {{ replacement.displayName }}（账号 {{ replacement.userId }}）；任一步冲突将整体拒绝。不收集被替换者的密码。</p>
    <label for="lifecycle-password">本人当前密码</label><ElInput id="lifecycle-password" :model-value="password" aria-label="本人当前密码" type="password" show-password autocomplete="off" :disabled="locked" @update:model-value="change" />
    <ElButton aria-label="验证本人本地凭据" :loading="verify.busy" :disabled="locked || !password" @click="check">验证本人本地凭据</ElButton>
    <ProofNotice :data="verify.data" :valid="verify.valid" :error="verify.error" /><WriteNotice :write="write" :on-retry="retry" :on-verify="onRefresh" />
    <ElForm ref="form" :model="values" label-position="top" :disabled="locked" @submit.prevent="finish"><ReasonField v-model="values.reason" /><ElButton type="primary" native-type="submit" :disabled="!verify.valid || locked">{{ replacement ? '确认先建后换' : '确认登记本人' }}</ElButton></ElForm>
  </AdminModal>
</template>
