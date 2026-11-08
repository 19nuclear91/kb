<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue';
import { useSession } from '../../SessionProvider';
import { perm, useAdminAccess } from '../access';

const props = defineProps<{ target: string }>();
const auth = useSession(), access = useAdminAccess();
const visible = ref(document.visibilityState !== 'hidden'), revision = ref(0);
const change = () => {
  visible.value = document.visibilityState !== 'hidden';
  revision.value++;
};
document.addEventListener('visibilitychange', change);
onScopeDispose(() => document.removeEventListener('visibilitychange', change));
const allowed = computed(() => visible.value && auth.state.phase === 'ready' && access.status === 'ready' && access.has(perm.lifecycleRead));
const key = computed(() => {
  const permissions = [perm.lifecycleRead, perm.lifecycleManage, perm.lifecycleRestore, perm.emergencyManage].map(code => access.has(code)).join(':');
  return `${auth.state.epoch}|${props.target}|${access.permissionFactsKey ?? ''}|${permissions}|${revision.value}`;
});
</script>

<template><slot v-if="allowed" :key="key" /></template>
