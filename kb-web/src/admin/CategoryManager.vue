<script setup lang="ts">
import { useReviewScope } from '../reviews/lifecycle';
import CategoryManagement from './CategoryManagement.vue';

defineProps<{ spaceId: string; archived: boolean }>();
const scope = useReviewScope();
</script>

<template><CategoryManagement v-if="scope.active" :key="`${scope.key}:${spaceId}`" :space-id="spaceId" :archived="archived" /></template>
