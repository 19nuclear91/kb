<script setup lang="ts">
import { ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import { perm, useAdminAccess } from '../admin/access';
import PageHeader from '../ui/PageHeader.vue';
import Feedback from '../ui/Feedback.vue';
import CardItems from '../answerCards/CardItems.vue';
import SpacePicker from '../answerCards/SpacePicker.vue';
import { cardApi } from '../answerCards/api';
import { useCardResource } from '../answerCards/useCardResource';
const auth = useSession(), access = useAdminAccess(), space = ref(''), cursor = ref<string | null>(null);
const result = useCardResource(() => `${space.value}:${cursor.value ?? ''}`, signal => cardApi.browse(auth.session, { spaceId: space.value || null, cursor: cursor.value }, signal));
watch(space, () => { cursor.value = null; });
watch(() => auth.state.epoch, () => { space.value = ''; cursor.value = null; });
</script>
<template>
  <PageHeader title="标准答案" description="核对经审核的答案原文、适用范围和固定依据" />
  <div class="toolbar"><RouterLink v-if="access.has(perm.answerCardWrite) || access.has(perm.answerCardReview)" to="/answer-cards/workspace">维护与审核</RouterLink>
    <RouterLink v-if="access.spaces.length || access.has(perm.spaceManageAll)" to="/answer-cards/management">责任人管理</RouterLink><RouterLink to="/search">按问题搜索</RouterLink></div>
  <SpacePicker v-model="space" /><ElButton @click="cursor = null; result.reload()">重新浏览</ElButton>
  <Feedback :loading="result.loading.value" :error="result.error.value" />
  <CardItems v-if="result.data.value" :items="result.data.value.items" />
  <ElButton v-if="result.data.value?.hasMore" @click="cursor = result.data.value.nextCursor">下一页标准答案</ElButton>
</template>
