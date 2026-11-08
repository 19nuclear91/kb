<script setup lang="ts">
import { reactive, ref, useId } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElButton, ElForm, ElFormItem, ElInput, type FormInstance, type FormRules } from 'element-plus';
import { useSession } from '../SessionProvider';
import { ApiError, isAbort, messageOf } from '../api';
import { safeReturnPath } from '../navigation';
import { locationState } from '../routing/locationState';
import Redirect from '../routing/Redirect.vue';
import EnterpriseLogin from '../sso/EnterpriseLogin.vue';
import { useSso } from '../sso/useSso';
import type { Platform } from '../sso/api';
import KbAlert from '../ui/KbAlert.vue';

const auth = useSession(), enterprise = useSso();
const router = useRouter(), route = useRoute();
const form = ref<FormInstance>();
const values = reactive({ username: '', password: '' });
const busy = ref(false), error = ref('');
const id = useId();
let lock = false;
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名' }],
  password: [{ required: true, message: '请输入密码' }],
};
// hints 存在 sessionStorage 中；每次渲染或提交重读，避免把过期/更新的导航提示缓存下来。
const returnPath = () => {
  void route.fullPath;
  return enterprise.flow.hints.navigation()?.returnPath ?? safeReturnPath(locationState(router).from);
};
async function startEnterprise(platform: Platform) {
  if (lock) {
    return;
  }
  lock = true;
  busy.value = true;
  error.value = '';
  try {
    await enterprise.flow.start('LOGIN', platform, undefined, returnPath());
  } finally {
    lock = false;
    busy.value = false;
  }
}
async function login() {
  if (lock) {
    return;
  }
  // 校验也是异步的；先上同步锁，连续提交不会在校验完成后各发一次请求。
  lock = true;
  try {
    if (!await form.value?.validate().catch(() => false)) {
      return;
    }
    busy.value = true;
    error.value = '';
    try {
      if (!enterprise.flow.hints.navigation()) {
        enterprise.flow.hints.navigateTo(returnPath());
      }
    } catch { /* 企业流程存储失败不阻止本地凭据校验。 */ }
    try {
      await auth.session.login(values.username, values.password);
    } catch (failure) {
      if (!isAbort(failure)) {
        error.value = failure instanceof ApiError && failure.status === 401 ? '用户名或密码不正确。' : messageOf(failure);
      }
    } finally {
      values.password = '';
      form.value?.clearValidate('password');
    }
  } finally {
    lock = false;
    busy.value = false;
  }
}
</script>

<template>
  <Redirect v-if="auth.state.phase === 'ready'" :to="returnPath()" />
  <Redirect v-else-if="auth.state.phase === 'password'" to="/change-password" />
  <div v-else class="auth-page"><section class="auth-card">
    <div class="brand"><span class="brand-mark">知</span><div>知序<small>连接知识，让工作更清晰</small></div></div>
    <h2>登录你的知识空间</h2><p class="muted">使用企业知识库本地账号继续</p>
    <KbAlert v-if="auth.state.notice" :title="auth.state.notice" type="info" style="margin-bottom: 16px" />
    <KbAlert v-if="error" :title="error" type="error" style="margin-bottom: 16px" />
    <KbAlert v-if="enterprise.state.phase === 'rejected' || enterprise.state.phase === 'uncertain'" :title="enterprise.state.message" type="warning" style="margin-bottom: 16px" />
    <ElForm ref="form" :model="values" :rules="rules" label-position="top" @submit.prevent="login">
      <ElFormItem prop="username" label="用户名" :for="`${id}-username`"><ElInput :id="`${id}-username`" v-model="values.username" aria-label="用户名" autocomplete="username" size="large" /></ElFormItem>
      <ElFormItem prop="password" label="密码" :for="`${id}-password`"><ElInput :id="`${id}-password`" v-model="values.password" aria-label="密码" type="password" show-password autocomplete="current-password" size="large" /></ElFormItem>
      <ElButton native-type="submit" type="primary" size="large" :loading="busy" :disabled="busy" style="width: 100%">登录</ElButton>
    </ElForm>
    <EnterpriseLogin :busy="busy" @start="startEnterprise" />
    <p class="muted" style="margin-top: 24px">忘记密码请联系平台管理员。</p>
  </section></div>
</template>
