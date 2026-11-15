<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElButton } from 'element-plus';
import { RouterLink } from 'vue-router';
import { useSession } from '../SessionProvider';
import { useCursorList } from './hooks';
import Feedback from '../ui/Feedback.vue';
import { validityApi, type SpaceItem, type StewardshipState } from '../validity/api';
import { stewardshipLabels } from '../validity/labels';

const props = defineProps<{ id: string; archived: boolean }>();
const emit = defineEmits<{ recheck: [] }>();
const auth = useSession(), state = ref<StewardshipState>('UNASSIGNED'), revoked = ref(false);
const states: StewardshipState[] = ['UNASSIGNED', 'NEEDS_REASSIGN', 'DUE_SOON', 'OVERDUE'];
const loader = computed(() => revoked.value ? null : (cursor: string | null, signal: AbortSignal) => validityApi.space(auth.session, props.id, state.value, cursor, signal));
const list = useCursorList<SpaceItem>(loader, () => `space-stewardship:${props.id}:${state.value}`);
watch(() => list.status, status => { if (status === 404) { revoked.value = true; emit('recheck'); } });
</script>
<template><section class="panel" aria-label="空间复审概况"><h2>空间复审概况</h2><p v-if="archived">空间已归档，复审暂停，清单只读；取消归档后仍按原到期日判断。</p>
  <ElButton v-for="value in states" :key="value" :type="state === value ? 'primary' : 'default'" :disabled="revoked" @click="state = value">{{ stewardshipLabels[value] }}</ElButton>
  <p v-if="revoked" role="alert">当前管理资格需重新核对，已清除旧清单。<ElButton @click="emit('recheck')">重新核对管理资格</ElButton></p>
  <template v-else><Feedback :loading="list.loading" :error="list.error" :retry="list.reload" />
    <p v-if="!list.loading && !list.error && !list.items.length">此状态下暂无文档</p>
    <article v-for="item in list.items" :key="item.documentId"><RouterLink :to="`/admin/documents/${item.documentId}`">文档 {{ item.documentId }}</RouterLink> · {{ stewardshipLabels[item.state] }} · 到期 {{ item.dueDate ?? '未设置' }} · 责任人 {{ item.responsible?.displayName ?? '未指定' }}</article>
    <ElButton v-if="list.hasMore" :disabled="list.loading" @click="list.more">加载更多复审文档</ElButton>
  </template>
</section></template>
