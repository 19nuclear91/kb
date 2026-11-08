<script setup lang="ts">
import { ref, shallowRef, watch } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import { ApiError, isAbort } from '../api';
import type { Space } from '../types';
import KbAlert from '../ui/KbAlert.vue';
import CategoryBrowser from '../documents/metadata/CategoryBrowser.vue';
import TagPicker from '../documents/metadata/TagPicker.vue';
import { searchFileTypes } from './api';
import { emptyFilterDraft, type FilterDraft } from './draft';

const props = defineProps<{ value: FilterDraft }>();
const emit = defineEmits<{ change: [value: FilterDraft] }>();
const auth = useSession();
const spaces = shallowRef<Space[]>([]), loading = ref(true), failed = ref(false), revision = ref(0);
watch([() => auth.session, revision], ([session], _, onCleanup) => {
  const controller = new AbortController(), epoch = session.getSnapshot().epoch;
  const active = () => !controller.signal.aborted && session.getSnapshot().epoch === epoch;
  spaces.value = []; loading.value = true; failed.value = false;
  void session.json<Space[]>('/api/admin/spaces', { signal: controller.signal }).then(result => {
    if (!active()) {
      return;
    }
    if (!Array.isArray(result)) {
      throw new ApiError(503);
    }
    spaces.value = result.filter(space => space.viewable && !space.archived);
    loading.value = false;
  }).catch(error => {
    if (active() && !isAbort(error)) {
      failed.value = true;
      loading.value = false;
    }
  });
  onCleanup(() => controller.abort());
}, { immediate: true });
const change = (patch: Partial<FilterDraft>) => emit('change', { ...props.value, ...patch });
const inputValue = (event: Event) => (event.target as HTMLInputElement | HTMLSelectElement).value;
</script>

<template>
  <div class="search-filters">
    <div class="toolbar"><label>知识空间<select aria-label="知识空间" :value="value.space" :disabled="loading || failed" @change="change({ space: inputValue($event), category: null, uncategorized: false })">
      <option value="">全部可见空间</option><option v-for="space in spaces" :key="space.spaceId" :value="space.spaceId">{{ space.name }}</option>
    </select></label>
      <label>文件类型<select aria-label="文件类型" :value="value.type" @change="change({ type: inputValue($event) })"><option value="">全部发布类型</option><option v-for="type in searchFileTypes" :key="type" :value="type">{{ type }}</option></select></label>
      <label>更新起点<input aria-label="更新起点" type="datetime-local" step="any" :value="value.from" @input="change({ from: inputValue($event) })" /></label>
      <label>更新终点<input aria-label="更新终点" type="datetime-local" step="any" :value="value.to" @input="change({ to: inputValue($event) })" /></label>
    </div>
    <p v-if="loading" role="status">正在读取空间…</p><KbAlert v-if="failed" type="warning" title="空间候选暂不可用"><template #action><ElButton @click="revision++">重新读取空间</ElButton></template></KbAlert>
    <p>分类：{{ value.uncategorized ? '未分类' : value.category ? `${value.category.path.map(node => node.name).join(' / ')}（包含后代）` : '不限分类' }}{{ ' ' }}
      <ElButton size="small" @click="change({ category: null, uncategorized: false })">不限分类</ElButton>{{ ' ' }}<ElButton size="small" @click="change({ category: null, uncategorized: true })">仅未分类</ElButton></p>
    <CategoryBrowser v-if="value.space" :context="{ kind: 'search', id: value.space }" :selected="value.category?.id" @select="change({ category: $event, uncategorized: $event === null })" />
    <p v-else class="muted">选择空间后可浏览分类；父分类包含后代。</p>
    <TagPicker kind="search" :value="value.tags" @change="change({ tags: $event })" />
    <ElButton @click="emit('change', emptyFilterDraft())">清除筛选</ElButton>
    <p class="muted">条件按 AND 组合；标签为全局已发布候选，不保证组合后命中。文件类型按当前发布版本判定，更新时间含起点不含终点。修改或清除筛选后需点击搜索。</p>
  </div>
</template>
