<script setup lang="ts">
import { useReviewScope } from '../reviews/lifecycle';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import ImportWorkspace from '../imports/ImportWorkspace.vue';
const scope = useReviewScope();
</script>
<template>
  <PageHeader title="我的导入任务" description="本地文件批量导入 · 固定清单 · 逐项受理" />
  <ImportWorkspace v-if="scope.active" :key="scope.key" /><KbAlert v-else type="info" title="请先完成登录与身份确认" />
</template>
