<script setup lang="ts">
import { useReviewScope } from './lifecycle';
import type { ReviewStatus } from './api';
import KbAlert from '../ui/KbAlert.vue';
import ReviewContext from './ReviewContext.vue';

const props = withDefaults(defineProps<{ documentId: string; versionId: string; files?: boolean }>(), { files: true });
const emit = defineEmits<{ changed: []; status: [value: ReviewStatus | undefined]; denied: [] }>();
const scope = useReviewScope();
</script>

<template>
  <ReviewContext v-if="scope.active" :key="`${scope.key}:${documentId}/${versionId}`" v-bind="props" @changed="emit('changed')" @status="emit('status', $event)" @denied="emit('denied')" />
  <KbAlert v-else type="info" title="版本内容已暂停，等待当前身份确认。" />
</template>
