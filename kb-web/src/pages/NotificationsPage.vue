<script setup lang="ts">
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import { useReviewScope } from '../reviews/lifecycle';
import NotificationWorkspace from '../notifications/NotificationWorkspace.vue';

const scope = useReviewScope();
</script>

<template>
  <PageHeader title="站内通知" description="发给本人的业务提醒 · 已读不代表已处理" />
  <NotificationWorkspace v-if="scope.active" :key="scope.key" />
  <KbAlert v-else type="info" title="请先完成登录与身份确认" />
</template>
