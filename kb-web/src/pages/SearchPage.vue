<script setup lang="ts">
import { onScopeDispose, ref } from 'vue';
import { useSession } from '../SessionProvider';
import { searchMaxQueryCodepoints } from '../search';
import SearchWorkspace from '../search/SearchWorkspace.vue';

withDefaults(defineProps<{ maxQueryCodepoints?: number }>(), { maxQueryCodepoints: searchMaxQueryCodepoints });
const auth = useSession(), visible = ref(document.visibilityState !== 'hidden');
const change = () => { visible.value = document.visibilityState !== 'hidden'; };
document.addEventListener('visibilitychange', change);
onScopeDispose(() => document.removeEventListener('visibilitychange', change));
</script>

<template>
  <SearchWorkspace v-if="visible && auth.state.phase === 'ready'" :key="auth.state.epoch" :maximum="maxQueryCodepoints" />
</template>
