<script setup lang="ts">
import { watch } from 'vue';
import { useRouter } from 'vue-router';

// 对应 <Navigate replace>：挂载或目标变化时替换当前历史记录，state 只进入历史记录，不写入 URL。
const props = defineProps<{ to: string; state?: Record<string, unknown> }>();
const router = useRouter();
watch(() => props.to, to => { void router.replace({ path: to, state: props.state as never }); }, { immediate: true });
</script>

<template>
  <slot />
</template>
