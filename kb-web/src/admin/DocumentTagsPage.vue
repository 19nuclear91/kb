<script setup lang="ts">
import { useReviewScope } from '../reviews/lifecycle';
import KbAlert from '../ui/KbAlert.vue';
import { perm, useAdminAccess } from './access';
import TagManagement from './TagManagement.vue';

const scope = useReviewScope(), access = useAdminAccess();
</script>

<template>
  <KbAlert v-if="!access.has(perm.tagRead)" type="info" title="当前没有标签目录读取资格" /><TagManagement v-else-if="scope.active" :key="scope.key" />
</template>
