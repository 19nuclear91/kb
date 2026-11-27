<script setup lang="ts">
import { RouterLink } from 'vue-router';
import type { Update } from './types';
defineProps<{ items: Update[] }>();
</script>
<template>
  <p v-if="!items.length">当前没有可读更新。</p>
  <ul v-else><li v-for="item in items" :key="item.updateId">
    <RouterLink :to="`/updates/${item.updateId}`">{{ item.title }} · V{{ item.versionNo }}</RouterLink>
    <span> · {{ item.publishedAt }}</span>
  </li></ul>
</template>
