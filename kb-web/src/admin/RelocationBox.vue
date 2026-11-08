<script setup lang="ts">
import { computed, ref, shallowRef } from 'vue';
import { ElButton } from 'element-plus';
import KbAlert from '../ui/KbAlert.vue';
import CategoryBrowser from '../documents/metadata/CategoryBrowser.vue';
import type { CategoryItem } from '../documents/metadata/api';
import { useDocumentOperations } from '../documents/OperationContext';
import { useSession } from '../SessionProvider';
import { useAdminAccess } from './access';
import { useAdminResource } from './hooks';
import { relocationContext, relocationWrite, spaceWrites, type Acl, type RelocationResult } from './spaceApi';
import ResourceStatus from './ResourceStatus.vue';
import ResourceWriteDialog from './ResourceWriteDialog.vue';

const props = defineProps<{ id: string; source: string; acl: Acl[] | undefined; refresh: () => void; done: (result: RelocationResult) => void; onConflict?: (message: string) => void }>();
const access = useAdminAccess(), auth = useSession(), documents = useDocumentOperations();
const open = ref(false), target = ref(''), boundTarget = ref<string | null>(null), category = shallowRef<CategoryItem | null | undefined>(), categoryReady = ref(false), categoryRevision = ref(0);
const context = useAdminResource(computed(() => open.value ? signal => relocationContext(auth.session, props.id, signal) : null), () => `relocation-context:${props.id}:${props.source}`);
const contextLoaded = computed(() => !context.loading && !context.error && !!context.data), contextReady = computed(() => contextLoaded.value && context.data?.fromSpaceId === props.source);
const retryReady = computed(() => contextLoaded.value && access.spaceStatus === 'ready' && boundTarget.value !== null && [props.source, boundTarget.value, context.data!.fromSpaceId].every(access.canManageSpace));
const candidates = computed(() => access.spaceStatus === 'ready' ? access.spaces.filter(space => !space.archived && space.spaceId !== props.source) : []), available = computed(() => candidates.value.some(space => space.spaceId === target.value));
function reload() {
  category.value = undefined; categoryReady.value = false; categoryRevision.value++; context.reload(); props.refresh(); access.check(true);
}
function start() {
  target.value = ''; boundTarget.value = null; category.value = undefined; categoryReady.value = false; open.value = true;
}
function change(event: Event) {
  target.value = (event.target as HTMLSelectElement).value; category.value = undefined; categoryReady.value = false;
}
function build() {
  const intent = spaceWrites.relocate(props.id, context.data!.fromSpaceId, target.value, category.value?.id ?? null, context.data!.metadataVersion);
  boundTarget.value = target.value; return intent;
}
function done(result: RelocationResult) {
  open.value = false; props.done(result);
}
</script>

<template><section class="panel"><h2>文档搬迁</h2><p v-if="access.spaceStatus === 'loading'" role="status">正在确认搬迁目标…</p>
  <KbAlert v-if="access.spaceStatus === 'unavailable'" type="warning" title="搬迁目标暂不可读取。"><template #action><ElButton @click="access.check(true)">重新读取目标</ElButton></template></KbAlert>
  <p v-if="access.spaceStatus === 'ready' && !candidates.length">没有可迁入的空间</p><ElButton :disabled="!candidates.length || acl === undefined || documents.state.exclusive" @click="start">搬迁文档</ElButton><p v-if="acl === undefined">请等待例外授权列表就绪，确认将清除的条数。</p>
  <ResourceWriteDialog v-if="open" title="搬迁文档" :scope="`document:${id}:relocate:${source}`" :on-refresh="reload" :on-verify="context.reload" :on-rejected="error => { if ([404, 409].includes(error.status)) { onConflict?.(error.message); reload(); } }" :execute="relocationWrite" :disabled="!available || acl === undefined || access.spaceStatus !== 'ready' || !contextReady || category === undefined || !categoryReady" :retry-disabled="!retryReady" :build="build" :on-done="done" @close="open = false">
    <ResourceStatus :loading="context.loading" :error="context.error" :retry="context.reload" /><KbAlert v-if="!context.loading && context.data && context.data.fromSpaceId !== source" type="warning" title="文档归属已变化，请重新读取后确认。" />
    <label>目标空间<select aria-label="目标空间" :value="available ? target : ''" @change="change"><option value="">请选择目标空间</option><option v-for="space in candidates" :key="space.spaceId" :value="space.spaceId">{{ space.name }}</option></select></label>
    <CategoryBrowser v-if="available" :key="categoryRevision" :context="{ kind: 'manage', id: target }" :selected="category?.id" @select="category = $event" @ready="categoryReady = $event !== null" />
    <p>目标分类：{{ category === undefined ? '尚未选择' : category?.path.map(node => node.name).join(' / ') ?? '未分类' }}。</p><p>来源空间 {{ source }}；将清除 {{ acl === undefined ? '尚未确认数量的' : acl.length }} 条文档例外授权且不可恢复，需由目标空间管理员重新授出。搬迁切换到所选分类并保留全局标签，不改变密级。</p>
  </ResourceWriteDialog>
</section></template>
