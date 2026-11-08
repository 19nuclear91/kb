<script setup lang="ts">
import { RouterView, useRoute } from 'vue-router';
import { ElButton, ElSpace } from 'element-plus';
import SsoResultPage from '../pages/SsoResultPage.vue';
import { useSession } from '../SessionProvider';
import { RESULT_PATH } from '../sso/storage';
import KbAlert from '../ui/KbAlert.vue';
import KbSpin from '../ui/KbSpin.vue';

const auth = useSession();
const route = useRoute();
const refresh = () => { void auth.session.refresh(); };
const logout = () => { void auth.session.logout(); };
</script>

<template>
  <SsoResultPage v-if="route.path === RESULT_PATH" />
  <div v-else-if="auth.state.phase === 'loading'" class="auth-page" role="status"><ElSpace><KbSpin />正在确认登录状态…</ElSpace></div>
  <div v-else-if="auth.state.phase === 'unavailable'" class="auth-page">
    <section class="auth-card">
      <KbAlert type="warning" :title="auth.state.notice" />
      <ElSpace style="margin-top: 20px"><ElButton @click="refresh">重新确认身份</ElButton><ElButton @click="logout">退出</ElButton></ElSpace>
    </section>
  </div>
  <RouterView v-else />
</template>
