<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElButton, ElForm, ElInput, ElSpace, ElTag } from 'element-plus';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import KbAlert from '../ui/KbAlert.vue';
import KbDialog from '../ui/KbDialog.vue';
import { platformNames, type Platform } from './api';
import { useSso } from './useSso';

const auth = useSession(), enterprise = useSso();
const resource = useResource(signal => auth.session.sso({ kind: 'bindings' }, signal), () => `bindings:${auth.state.epoch}`);
const selected = ref<Platform | null>(null), password = ref(''), busy = ref(false);
let lock = false;
watch(() => auth.state.epoch, () => {
  password.value = '';
  selected.value = null;
});
const suggestion = () => {
  const platform = enterprise.flow.hints.navigation()?.platform;
  return platform ? `请核对当前本地账号，再主动绑定${platformNames[platform]}身份。` : undefined;
};
const notice = computed(() => {
  const state = enterprise.state;
  return (state.intent === 'BIND' && (state.phase === 'rejected' || state.phase === 'uncertain') ? state.message : undefined) ?? auth.state.notice;
});
function open(platform: Platform) {
  password.value = '';
  selected.value = platform;
}
function close() {
  if (!busy.value) {
    password.value = '';
    selected.value = null;
  }
}
async function submit() {
  if (lock || !password.value.trim() || !selected.value) {
    return;
  }
  lock = true;
  const submitted = password.value;
  password.value = '';
  busy.value = true;
  try {
    await enterprise.flow.start('BIND', selected.value, submitted, '/account');
  } finally {
    lock = false;
    password.value = '';
    busy.value = false;
    selected.value = null;
  }
}
</script>

<template>
  <section class="panel" aria-label="企业身份绑定" style="margin-top: 24px">
    <h2>企业身份绑定</h2>
    <p class="muted">绑定后可使用企业账号登录。解除绑定请联系管理员。</p>
    <KbAlert v-if="suggestion()" type="info" :title="suggestion()" />
    <KbAlert v-if="notice" type="warning" :title="notice" style="margin-top: 12px" />
    <p v-if="resource.loading" role="status">正在读取本人绑定状态…</p>
    <KbAlert v-if="resource.error" type="warning" title="绑定状态暂不可确认。">
      <template #action><ElButton @click="resource.reload">重新读取绑定状态</ElButton></template>
    </KbAlert>
    <div v-for="item in resource.data" :key="item.platform" style="display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 16px">
      <ElSpace><span>{{ platformNames[item.platform] }}</span><ElTag type="info">{{ item.bound ? '已绑定' : '未绑定' }}</ElTag><ElTag type="info">{{ item.connectionEnabled ? '连接已启用' : '连接已关闭' }}</ElTag></ElSpace>
      <ElButton :disabled="busy || item.bound || !item.connectionEnabled" @click="open(item.platform)">绑定{{ platformNames[item.platform] }}</ElButton>
    </div>
    <ElButton v-if="resource.data" style="margin-top: 16px" :disabled="busy" @click="resource.reload">重新读取绑定状态</ElButton>
    <KbDialog :open="selected !== null" :title="selected ? `绑定${platformNames[selected]}身份` : undefined" :show-close="!busy" :close-on-click-modal="!busy" @close="close">
      <p>请确认正在绑定本人企业身份。同一浏览器新发起会替代之前的授权。</p>
      <ElForm label-position="top" @submit.prevent="submit">
        <label for="sso-binding-password">当前本地密码</label>
        <ElInput id="sso-binding-password" v-model="password" type="password" show-password autocomplete="current-password" :disabled="busy" />
        <ElButton type="primary" native-type="submit" style="margin-top: 16px" :loading="busy" :disabled="!password.trim()">验证并继续企业授权</ElButton>
      </ElForm>
    </KbDialog>
  </section>
</template>
