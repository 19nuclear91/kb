<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { useSession } from '../SessionProvider';
import PageErrorBoundary from './PageErrorBoundary.vue';

const route = useRoute();
const auth = useSession();
// pathname 同时代表资源目标；尾部斜杠不代表新的资源，身份变化则重置页面边界。
const key = computed(() => `${auth.state.epoch}:${route.path.replace(/\/+$/, '') || '/'}`);
</script>

<template>
  <PageErrorBoundary :key="key"><slot /></PageErrorBoundary>
</template>
