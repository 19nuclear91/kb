<script setup lang="ts">
import { useReviewScope } from '../reviews/lifecycle';
import KbAlert from '../ui/KbAlert.vue';
import TargetContext from './TargetContext.vue';

const props = defineProps<{ documentId: string; versionId: string; commentId: string }>();
const scope = useReviewScope();
</script>

<template>
  <TargetContext v-if="scope.active" :key="`${scope.key}:${documentId}/${versionId}/${commentId}`" v-bind="props" />
  <KbAlert v-else type="info" title="评论目标已暂停，等待当前身份确认。" />
</template>
