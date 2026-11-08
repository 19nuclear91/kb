<script setup lang="ts">
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import { useSso } from '../sso/useSso';
import PasswordForm from '../auth/PasswordForm.vue';
import Redirect from '../routing/Redirect.vue';

const auth = useSession(), enterprise = useSso();
const logout = () => { void auth.session.logout(); };
</script>

<template>
  <Redirect v-if="auth.state.phase === 'anonymous'" to="/login" />
  <Redirect v-else-if="auth.state.phase === 'ready'" :to="enterprise.flow.hints.navigation()?.returnPath ?? '/documents'" />
  <div v-else class="auth-page"><div class="auth-card"><PasswordForm forced /><ElButton link type="primary" @click="logout">退出当前账号</ElButton></div></div>
</template>
