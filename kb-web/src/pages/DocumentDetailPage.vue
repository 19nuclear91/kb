<script setup lang="ts">
import { useRoute } from 'vue-router';
import { useReviewScope } from '../reviews/lifecycle';
import KbAlert from '../ui/KbAlert.vue';
import DocumentWorkspace from './DocumentWorkspace.vue';

const route = useRoute(), scope = useReviewScope();
const documentId = () => String(route.params.documentId ?? '');
</script>

<template>
  <DocumentWorkspace v-if="scope.active" :key="`${scope.key}:${documentId()}`" :document-id="documentId()" />
  <KbAlert v-else type="info" title="请先完成登录与身份确认" />
</template>
