<script setup lang="ts">
import { computed } from 'vue';
import { ElButton, ElSpace } from 'element-plus';
import { useDocumentOperations } from '../documents/OperationContext';
import type { Favorites } from './useFavorites';

const props = defineProps<{ id: string; favorites: Favorites }>();
const documents = useDocumentOperations();
const status = computed(() => props.favorites.status(props.id));
const ready = computed(() => status.value.phase === 'ready');
const label = computed(() => ready.value ? status.value.favorited ? '取消收藏' : '收藏文档' : '收藏状态待确认');
const toggle = () => { void props.favorites.set(props.id, !status.value.favorited); };
const check = () => { void props.favorites.check(props.id); };
</script>

<template>
  <ElSpace v-if="status.phase !== 'unavailable'" direction="vertical" :size="4">
    <ElButton size="small" :aria-label="label" :aria-pressed="ready ? status.favorited : undefined" :loading="!!status.busy" :disabled="!ready || status.busy || documents.state.exclusive" @click="toggle">{{ label }}</ElButton>
    <template v-if="status.phase === 'error' || status.phase === 'uncertain'">
      <span role="status" class="muted">{{ status.message }}</span>
      <ElButton link type="primary" size="small" :disabled="status.busy" @click="check">核对收藏状态</ElButton>
    </template>
  </ElSpace>
</template>
