<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { useReviewScope } from '../reviews/lifecycle';
import { validComparison } from './api';
import CompareContent from './CompareContent.vue';
const route = useRoute(), scope = useReviewScope();
const documentId = computed(() => String(route.params.documentId ?? ''));
const baseVersionId = computed(() => String(route.params.baseVersionId ?? ''));
const targetVersionId = computed(() => String(route.params.targetVersionId ?? ''));
const valid = computed(() => validComparison(documentId.value, baseVersionId.value, targetVersionId.value));
</script>
<template>
  <p v-if="!valid" role="alert">对比链接无效</p>
  <CompareContent v-else-if="scope.active" :key="`${scope.key}:${documentId}/${baseVersionId}/${targetVersionId}`"
    :document-id="documentId" :base-version-id="baseVersionId" :target-version-id="targetVersionId" />
  <p v-else>请先完成登录与身份确认</p>
</template>
