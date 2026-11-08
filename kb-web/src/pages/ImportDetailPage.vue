<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { positiveId } from '../api';
import { useReviewScope } from '../reviews/lifecycle';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import ImportTask from '../imports/ImportTask.vue';
const scope = useReviewScope(), route = useRoute();
const id = computed(() => { try { return positiveId(route.params.importId); } catch { return null; } });
</script>
<template>
  <PageHeader title="导入任务详情" description="持久受理历史与当前上传资格分别核对" />
  <ImportTask v-if="scope.active && id" :key="`${scope.key}:${id}`" :import-id="id" />
  <KbAlert v-else type="info" :title="id ? '请先完成登录与身份确认' : '导入任务身份无效'" />
</template>
