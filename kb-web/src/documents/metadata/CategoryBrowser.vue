<script setup lang="ts">
import type { CategoryContext, CategoryItem, CategoryPage } from './api';
import CategoryBranch from './CategoryBranch.vue';

export interface CategoryBrowserProps {
  context: CategoryContext | { kind: 'search'; id: string };
  selected?: string | null;
  nullLabel?: string;
  disabled?: boolean;
}
const props = defineProps<CategoryBrowserProps>();
const emit = defineEmits<{ select: [category: CategoryItem | null]; ready: [page: CategoryPage | null] }>();
</script>

<template>
  <CategoryBranch :key="`${context.kind}:${context.id}`" v-bind="props" @select="emit('select', $event)" @ready="emit('ready', $event)">
    <template #actions="scope"><slot name="actions" v-bind="scope" /></template>
  </CategoryBranch>
</template>
