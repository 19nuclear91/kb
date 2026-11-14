<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useReviewScope } from '../reviews/lifecycle';
import { gapRouteKind } from '../knowledgeGaps/routes';
import GapCreateForm from '../knowledgeGaps/GapCreateForm.vue';
import KbAlert from '../ui/KbAlert.vue';
import PageHeader from '../ui/PageHeader.vue';
import type { GapReceipt } from '../knowledgeGaps/types';
const route = useRoute(), router = useRouter(), scope = useReviewScope();
const valid = computed(() => gapRouteKind(route.fullPath) === 'new');
function submitted(receipt: GapReceipt) { void router.push(`/knowledge-gaps/${receipt.gapId}`); }
</script>
<template>
  <PageHeader title="提出知识需求" description="先预览，再明确确认分享" />
  <KbAlert v-if="!valid" type="warning" title="知识问题地址无效，请从知识需求入口重新进入。" />
  <GapCreateForm v-else-if="scope.active" :key="scope.key" @submitted="submitted" @cancel="router.push('/knowledge-gaps')" />
  <KbAlert v-else type="info" title="请先完成登录与身份确认" />
</template>
