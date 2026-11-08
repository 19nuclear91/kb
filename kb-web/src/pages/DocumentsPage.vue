<script setup lang="ts">
import { useReviewScope } from '../reviews/lifecycle';
import KbAlert from '../ui/KbAlert.vue';
import DocumentsContent from './DocumentsContent.vue';

const scope = useReviewScope();
</script>

<template>
  <DocumentsContent v-if="scope.active" :key="scope.key" />
  <KbAlert v-else type="info" title="请先完成登录与身份确认" />
</template>
