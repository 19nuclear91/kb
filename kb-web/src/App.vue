<script lang="ts">
import { Session } from './session';

let defaultSession: Session | undefined;
</script>

<script setup lang="ts">
import { computed, watch } from 'vue';
import { useRoute } from 'vue-router';
import AppRoutes from './routing/AppRoutes.vue';
import SessionHost from './SessionHost.vue';
import { getSsoFlow } from './sso/flow';
import { browserStorage, RESULT_PATH } from './sso/storage';

const props = defineProps<{ session?: Session }>();
const route = useRoute();
const current = computed(() => props.session ?? (defaultSession ??= new Session(browserStorage)));
let previous = { session: current.value, path: route.path };
// 离开企业认证相关页面或切换会话时结束旧授权流程，不影响新流程。
watch([current, () => route.path], ([session, path]) => {
  const before = previous;
  if ([RESULT_PATH, '/login', '/account'].includes(before.path) && (path !== before.path || before.session !== session)) {
    getSsoFlow(before.session).leave();
  }
  previous = { session, path };
}, { flush: 'post' });
</script>

<template>
  <SessionHost :session="current" :handoff="route.path === RESULT_PATH"><AppRoutes /></SessionHost>
</template>
