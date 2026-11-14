<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { useReviewScope } from '../reviews/lifecycle';
import { gapRouteKind } from '../knowledgeGaps/routes';
import GapDetailWorkspace from '../knowledgeGaps/GapDetailWorkspace.vue';
import KbAlert from '../ui/KbAlert.vue';
import PageHeader from '../ui/PageHeader.vue';
const route = useRoute(), scope = useReviewScope();
const valid = computed(() => gapRouteKind(route.fullPath) === 'detail');
const gapId = computed(() => String(route.params.gapId ?? ''));
</script>
<template>
  <PageHeader title="知识问题详情" description="按当前参与资格读取共享业务记录" />
  <KbAlert v-if="!valid" type="warning" title="知识问题地址无效，请从知识需求入口重新进入。" />
  <GapDetailWorkspace v-else-if="scope.active" :key="`${scope.key}:${gapId}`" :gap-id="gapId" />
  <KbAlert v-else type="info" title="请先完成登录与身份确认" />
</template>
