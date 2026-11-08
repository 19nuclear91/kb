<script setup lang="ts">
import type { CommentList } from './hooks';
import Failure from './Failure.vue';

defineProps<{ list: CommentList; empty: string; retryLabel: string }>();
</script>

<template>
  <p v-if="list.loading && !list.loaded" role="status" class="muted">正在读取…</p>
  <Failure v-else-if="list.failed && !list.loaded" :list="list" :retry-label="retryLabel" />
  <p v-else-if="list.loaded && !list.items.length && !list.hasMore" class="muted">{{ empty }}</p>
</template>
