<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { useReviewScope } from '../reviews/lifecycle';
import { adminGapRouteKind } from '../knowledgeGaps/routes';
import GapListWorkspace from '../knowledgeGaps/GapListWorkspace.vue';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import { useAdminAccess, perm } from './access';
const route = useRoute(), scope = useReviewScope(), access = useAdminAccess();
const valid = computed(() => adminGapRouteKind(route.fullPath) === 'list');
</script>
<template>
  <PageHeader title="知识问题受理" description="统一队列 · 人工分派，不自动判定解决" />
  <KbAlert v-if="!valid" type="warning" title="受理地址无效，请从固定入口重新进入。" />
  <GapListWorkspace v-else-if="scope.active && access.has(perm.gapRead)" :key="`${scope.key}:${access.permissionFactsKey}`" :queue="true" />
  <KbAlert v-else type="info" title="当前受理读取资格未确认或已失效。" />
</template>
