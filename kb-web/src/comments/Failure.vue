<script setup lang="ts">
import { ElButton } from 'element-plus';
import KbAlert from '../ui/KbAlert.vue';
import type { CommentList } from './hooks';
import { DENIED, failureText } from './thread';

defineProps<{ list: CommentList; retryLabel: string }>();
</script>

<template>
  <KbAlert v-if="!DENIED.has(list.status ?? 0)" type="error" :title="failureText(list.status)"><template #action><ElButton @click="list.retry">{{ retryLabel }}</ElButton></template></KbAlert>
</template>
