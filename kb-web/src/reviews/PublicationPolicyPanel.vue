<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import { useAdminResource } from '../admin/hooks';
import ResourceStatus from '../admin/ResourceStatus.vue';
import { reviewPaths, type PublicationPolicy } from './api';
import PolicyDialog from './PolicyDialog.vue';

const props = defineProps<{ spaceId: string }>();
const emit = defineEmits<{ changed: [] }>();
const auth = useSession();
const query = useAdminResource<PublicationPolicy>(signal => auth.session.json(reviewPaths.policy(props.spaceId), { signal }), () => `publication-policy:${props.spaceId}`);
const open = ref(false);
const ready = computed(() => !query.loading && !query.error && !!query.data && ['AUTO', 'REVIEW_REQUIRED'].includes(query.data.policy));
function done() {
  open.value = false;
  query.reload();
  emit('changed');
}
</script>

<template>
  <section class="panel"><h2>新版本发布策略</h2><ResourceStatus :loading="query.loading" :error="query.error" :retry="query.reload" />
    <p>策略只影响之后创建的新版本；已创建版本的策略不变，关闭审核不会自动放行待审稿。</p>
    <p>空间管理不自动授予阅读或审核资格；审核另需 view、空间 review、足够密级，并禁止自审。</p>
    <template v-if="ready"><p>当前策略：{{ query.data!.policy === 'AUTO' ? '自动发布' : '需审核后发布' }} · 版本 {{ String(query.data!.version) }}</p>
      <ElButton @click="open = true">修改发布策略</ElButton></template>
    <PolicyDialog v-if="open && query.data" :value="query.data" :disabled="!ready" :refresh="query.reload" @close="open = false" @done="done" />
  </section>
</template>
