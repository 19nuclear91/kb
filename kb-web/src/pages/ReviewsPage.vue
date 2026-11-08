<script setup lang="ts">
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import { useReviewScope } from '../reviews/lifecycle';
import ReviewWorkspace from '../reviews/ReviewWorkspace.vue';

const scope = useReviewScope();
</script>

<template>
  <PageHeader title="版本审核" description="本人版本与当前合格审核待办" />
  <ReviewWorkspace v-if="scope.active" :key="scope.key" />
  <KbAlert v-else type="info" title="请先完成登录与身份确认" />
</template>
