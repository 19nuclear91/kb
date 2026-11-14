<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { useReviewScope } from '../reviews/lifecycle';
import { adminGapRouteKind } from '../knowledgeGaps/routes';
import GapDetailWorkspace from '../knowledgeGaps/GapDetailWorkspace.vue';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import { useAdminAccess, perm } from './access';
const route = useRoute(), scope = useReviewScope(), access = useAdminAccess();
const gapId = computed(() => String(route.params.gapId ?? ''));
const valid = computed(() => adminGapRouteKind(route.fullPath) === 'detail');
</script>
<template>
  <PageHeader title="知识问题受理详情" description="按当前问题能力协调处理；资料独立鉴权" />
  <KbAlert v-if="!valid" type="warning" title="受理地址无效，请从固定入口重新进入。" />
  <GapDetailWorkspace v-else-if="scope.active && access.has(perm.gapRead)" :key="`${scope.key}:${gapId}:${access.permissionFactsKey}`" :gap-id="gapId" :reception="true" />
  <KbAlert v-else type="info" title="当前受理读取资格未确认或已失效。" />
</template>
