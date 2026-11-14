<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { useReviewScope } from '../reviews/lifecycle';
import { gapRouteKind } from '../knowledgeGaps/routes';
import GapListWorkspace from '../knowledgeGaps/GapListWorkspace.vue';
import KbAlert from '../ui/KbAlert.vue';
import PageHeader from '../ui/PageHeader.vue';
const route = useRoute(), scope = useReviewScope();
const valid = computed(() => gapRouteKind(route.fullPath) === 'list');
</script>
<template>
  <PageHeader title="知识需求" description="本人问题与当前分派 · 不依赖 AI 功能" />
  <KbAlert v-if="!valid" type="warning" title="知识问题地址无效，请从知识需求入口重新进入。" />
  <GapListWorkspace v-else-if="scope.active" :key="scope.key" />
  <KbAlert v-else type="info" title="请先完成登录与身份确认" />
</template>
