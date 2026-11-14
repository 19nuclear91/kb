<script setup lang="ts">
import { RouterView, useRoute } from 'vue-router';
import { ElButton } from 'element-plus';
import AdminAccessProvider from '../admin/AdminAccessProvider.vue';
import AdminNavigation from '../admin/AdminNavigation.vue';
import { safeReturnPath } from '../navigation';
import UnreadBadge from '../notifications/UnreadBadge.vue';
import { useSession } from '../SessionProvider';
import Navigation from './Navigation.vue';
import Redirect from './Redirect.vue';

const auth = useSession();
const route = useRoute();
const logout = () => { void auth.session.logout(); };
</script>

<template>
  <Redirect v-if="auth.state.phase === 'anonymous'" to="/login" :state="{ from: safeReturnPath(route.fullPath) }" />
  <Redirect v-else-if="auth.state.phase === 'password'" to="/change-password" />
  <!-- 仅在身份 ready 后挂载本人权限上下文；强制改密阶段已在上方重定向，不预取管理权限或数据。 -->
  <AdminAccessProvider v-else :load-spaces="route.path !== '/admin/reports/operations' && !/^\/(?:admin\/)?knowledge-gaps(?:\/|$)/.test(route.path)">
    <div class="app-shell">
      <aside class="sidebar">
        <div class="brand"><span class="brand-mark">知</span><div>知序<small>企业知识库</small></div></div>
        <Navigation><template #extra="{ path }"><UnreadBadge v-if="path === '/notifications'" /></template></Navigation>
        <AdminNavigation />
        <div style="margin-top: 48px"><p class="muted">{{ auth.state.me?.user.displayName }}</p><ElButton @click="logout">退出登录</ElButton></div>
      </aside>
      <main class="workspace"><RouterView /></main>
    </div>
  </AdminAccessProvider>
</template>
