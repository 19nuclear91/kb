<script setup lang="ts">
import { ElButton } from 'element-plus';
import type { CommentList } from './hooks';
import Failure from './Failure.vue';

defineProps<{ list: CommentList; label: string; end?: string; retryLabel: string }>();
</script>

<template>
  <template v-if="list.failed"><Failure v-if="list.loaded" :list="list" :retry-label="retryLabel" /></template>
  <ElButton v-else-if="list.hasMore" :loading="list.loading" @click="list.more">{{ label }}</ElButton>
  <p v-else-if="list.loaded && !list.loading && end" class="muted">{{ end }}</p>
</template>
