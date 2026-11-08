<script setup lang="ts">
import { computed } from 'vue';
import type { AdminAccess } from '../admin/access';
import AdminGuard from '../admin/AdminGuard.vue';
import * as pages from './lazyPages';
import PageLoadBoundary from './PageLoadBoundary.vue';

export interface GuardOptions { code?: string; title: string; spaces?: boolean; allow?: (access: AdminAccess) => boolean }
// 页面组件在渲染时读取，路由记录只保存名称；管理路由先过守卫，再进入页面加载边界。
const props = defineProps<{ page: keyof typeof pages; guard?: GuardOptions }>();
const component = computed(() => pages[props.page]);
</script>

<template>
  <AdminGuard v-if="guard" v-bind="guard">
    <PageLoadBoundary><component :is="component" /></PageLoadBoundary>
  </AdminGuard>
  <PageLoadBoundary v-else><component :is="component" /></PageLoadBoundary>
</template>
