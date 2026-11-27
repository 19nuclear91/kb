<script setup lang="ts">
import { computed } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { useReviewScope } from '../reviews/lifecycle';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import SubscriptionWorkspace from '../subscriptions/SubscriptionWorkspace.vue';
import UpdatesWorkspace from '../subscriptions/UpdatesWorkspace.vue';
import UpdateWorkspace from '../subscriptions/UpdateWorkspace.vue';
import DigestsWorkspace from '../subscriptions/DigestsWorkspace.vue';
import { knowledgeRoute } from '../subscriptions/routes';
const route = useRoute(), scope = useReviewScope();
const valid = computed(() => knowledgeRoute(route.path) && !route.hash && Object.keys(route.query).length === 0);
const section = computed(() => route.path.split('/')[1]), id = computed(() => route.params.id ? String(route.params.id) : undefined);
const documentId = computed(() => route.params.documentId ? String(route.params.documentId) : undefined);
</script>
<template>
  <PageHeader title="知识订阅与更新" description="关注知识的正式发布，按需阅读人工变更说明" />
  <nav class="toolbar" aria-label="知识订阅导航"><RouterLink to="/subscriptions">我的订阅</RouterLink><RouterLink to="/updates">我的更新</RouterLink><RouterLink to="/digests">每周汇总</RouterLink></nav>
  <KbAlert v-if="!valid" type="warning" title="知识订阅页面地址无效。" />
  <template v-else-if="scope.active">
    <SubscriptionWorkspace v-if="section === 'subscriptions'" :key="`${scope.key}:${route.path}`" :document-id="documentId" />
    <UpdateWorkspace v-else-if="section === 'updates' && id" :key="`${scope.key}:${route.path}`" :id="id" />
    <UpdatesWorkspace v-else-if="section === 'updates'" :key="`${scope.key}:${route.path}`" />
    <DigestsWorkspace v-else :key="`${scope.key}:${route.path}`" :id="id" />
  </template>
  <KbAlert v-else type="info" title="请先完成登录与身份确认" />
</template>
