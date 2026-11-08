<script setup lang="ts">
import { reactive, ref, useId, watch } from 'vue';
import { ElButton, ElForm, ElFormItem, ElInput, type FormInstance, type FormRules } from 'element-plus';
import { useSession } from '../SessionProvider';
import { ApiError, isAbort, messageOf } from '../api';
import KbAlert from '../ui/KbAlert.vue';

withDefaults(defineProps<{ forced?: boolean }>(), { forced: false });
const auth = useSession();
const busy = ref(false), error = ref(''), success = ref(false);
const form = ref<FormInstance>(), id = useId();
const values = reactive({ oldPassword: '', newPassword: '', confirm: '' });
let lock = false;
let binding: { oldPassword: string; newPassword: string; requestId: string } | null = null;
const rules: FormRules = {
  oldPassword: [{ required: true, message: '请输入原密码' }],
  newPassword: [{ required: true, message: '请输入新密码' }],
  confirm: [{ required: true, message: '请再次输入新密码' }, {
    validator: (_, value, callback) => callback(value === values.newPassword ? undefined : new Error('两次密码不一致')),
  }],
};
watch(() => values.newPassword, () => {
  if (values.confirm) {
    void form.value?.validateField('confirm').catch(() => {});
  }
});
async function submit() {
  if (lock) {
    return;
  }
  lock = true;
  try {
    if (!await form.value?.validate().catch(() => false)) {
      return;
    }
    busy.value = true;
    error.value = '';
    success.value = false;
    if (!binding || binding.oldPassword !== values.oldPassword || binding.newPassword !== values.newPassword) {
      binding = { oldPassword: values.oldPassword, newPassword: values.newPassword, requestId: crypto.randomUUID() };
    }
    try {
      await auth.session.post('/api/auth/password', binding);
      binding = null;
      form.value?.resetFields();
      success.value = true;
      await auth.session.refresh();
    } catch (failure) {
      if (!isAbort(failure)) {
        error.value = failure instanceof ApiError && failure.status === 400 ? '原密码错误或新密码不满足服务器强度要求。' : messageOf(failure);
      }
    }
  } finally {
    lock = false;
    busy.value = false;
  }
}
</script>

<template>
  <h2>{{ forced ? '设置新的登录密码' : '修改登录密码' }}</h2>
  <p v-if="forced" class="muted">当前账号需要先完成密码修改。</p>
  <KbAlert v-if="error" :title="error" type="error" style="margin-bottom: 16px" />
  <KbAlert v-if="success" title="密码已更新" type="success" style="margin-bottom: 16px" />
  <ElForm ref="form" :model="values" :rules="rules" label-position="top" @submit.prevent="submit">
    <ElFormItem prop="oldPassword" label="原密码" :for="`${id}-old`"><ElInput :id="`${id}-old`" v-model="values.oldPassword" aria-label="原密码" type="password" show-password autocomplete="current-password" /></ElFormItem>
    <ElFormItem prop="newPassword" label="新密码" :for="`${id}-new`"><ElInput :id="`${id}-new`" v-model="values.newPassword" aria-label="新密码" type="password" show-password autocomplete="new-password" /></ElFormItem>
    <ElFormItem prop="confirm" label="确认新密码" :for="`${id}-confirm`"><ElInput :id="`${id}-confirm`" v-model="values.confirm" aria-label="确认新密码" type="password" show-password autocomplete="new-password" /></ElFormItem>
    <ElButton native-type="submit" type="primary" :loading="busy">保存新密码</ElButton>
  </ElForm>
</template>
