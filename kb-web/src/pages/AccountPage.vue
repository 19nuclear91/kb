<script setup lang="ts">
import { computed } from 'vue';
import { useSession } from '../SessionProvider';
import PageHeader from '../ui/PageHeader.vue';
import BindingPanel from '../sso/BindingPanel.vue';
import PasswordForm from '../auth/PasswordForm.vue';

const auth = useSession();
const user = computed(() => auth.state.me!.user);
</script>

<template>
  <PageHeader title="个人账户" description="你的身份信息由服务器提供" />
  <div class="account-grid">
    <section class="panel"><h2>{{ user.displayName }}</h2>
      <p>用户名：{{ user.username }}</p><p>主部门 ID：{{ user.departmentId ?? '未提供' }}</p><p>密级：{{ user.clearanceLevel }}</p>
    </section>
    <section class="panel"><PasswordForm /></section>
  </div>
  <BindingPanel :key="auth.state.epoch" />
</template>
