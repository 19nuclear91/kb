<script setup lang="ts">
import { computed } from 'vue';
import { RouterLink, useRoute } from 'vue-router';

// 对应 React Router 的 NavLink：当前路径等于目标或位于其下级时激活，并标记 aria-current。
const props = defineProps<{ to: string }>();
const route = useRoute();
const active = computed(() => {
  const target = props.to.replace(/\/+$/, '') || '/';
  const path = route.path.toLowerCase(), base = target.toLowerCase();
  return path === base || (base !== '/' && path.startsWith(`${base}/`));
});
</script>

<template>
  <RouterLink v-slot="{ href, navigate }" :to="to" custom>
    <a :href="href" :class="{ active }" :aria-current="active ? 'page' : undefined" @click="navigate"><slot /></a>
  </RouterLink>
</template>
