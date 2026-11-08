<script setup lang="ts">
import { computed, watch } from 'vue';
import { RouterLink } from 'vue-router';
import { ElButton, ElSpace } from 'element-plus';
import { useSession } from '../SessionProvider';
import { safeReturnPath } from '../navigation';
import { useSso } from '../sso/useSso';
import Redirect from '../routing/Redirect.vue';
import KbAlert from '../ui/KbAlert.vue';
import KbSpin from '../ui/KbSpin.vue';

const auth = useSession(), enterprise = useSso();
watch(() => enterprise.flow, flow => { void flow.exchange(); }, { immediate: true });
watch([() => auth.session, () => enterprise.state.phase, () => auth.state.phase], ([session, phase, identity]) => {
  if (phase === 'confirmed' && identity === 'loading') {
    void session.refresh();
  }
}, { immediate: true });
const working = computed(() => enterprise.state.phase === 'idle' || enterprise.state.phase === 'pending' || (enterprise.state.phase === 'confirmed' && auth.state.phase === 'loading'));
const refresh = () => { void auth.session.refresh(); };
const logout = () => { void auth.session.logout(); };
</script>

<template>
  <Redirect v-if="enterprise.state.phase === 'confirmed' && auth.state.phase === 'ready'" :to="enterprise.state.intent === 'BIND' ? '/account' : safeReturnPath(enterprise.state.returnPath)" />
  <Redirect v-else-if="enterprise.state.phase === 'confirmed' && auth.state.phase === 'password'" to="/change-password" />
  <div v-else class="auth-page"><section class="auth-card">
    <h1>企业认证结果</h1>
    <ElSpace v-if="working" role="status"><KbSpin />正在确认企业认证结果…</ElSpace>
    <template v-else>
      <KbAlert type="warning" show-icon :title="enterprise.state.phase === 'confirmed' ? auth.state.notice ?? '当前登录状态尚未确认。' : enterprise.state.message ?? '授权流程已失效，请重新发起。'" />
      <p v-if="enterprise.state.correlationId" class="muted">排查编号：{{ enterprise.state.correlationId }}</p>
      <p v-if="enterprise.state.code === 'SSO_BINDING_REQUIRED'">登录已有本地账号并完成改密后，请在个人账户输入当前密码，重新发起企业身份绑定。</p>
      <ElSpace wrap style="margin-top: 20px">
        <ElButton v-if="enterprise.state.phase === 'confirmed' && auth.state.phase === 'unavailable'" @click="refresh">重新确认身份</ElButton>
        <RouterLink v-if="enterprise.state.intent === 'BIND'" to="/account">查看当前绑定状态</RouterLink>
        <RouterLink :to="{ path: '/login', state: enterprise.state.code === 'SSO_BINDING_REQUIRED' ? { from: '/account' } : undefined }">使用本地账号登录</RouterLink>
        <ElButton v-if="auth.state.phase === 'ready' || auth.state.phase === 'unavailable' || auth.state.phase === 'password'" @click="logout">退出当前账号</ElButton>
      </ElSpace>
    </template>
  </section></div>
</template>
