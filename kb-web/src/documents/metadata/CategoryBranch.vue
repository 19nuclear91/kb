<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../../SessionProvider';
import { isAbort, messageOf } from '../../api';
import KbAlert from '../../ui/KbAlert.vue';
import { metadataApi, type CategoryItem, type CategoryPage } from './api';
import { searchApi, searchOptionsMessage } from '../../search/api';
import type { CategoryBrowserProps } from './CategoryBrowser.vue';

const props = withDefaults(defineProps<CategoryBrowserProps>(), { nullLabel: '未分类', disabled: false });
const emit = defineEmits<{ select: [category: CategoryItem | null]; ready: [page: CategoryPage | null] }>();
const auth = useSession();
const parent = shallowRef<CategoryItem | null>(null), revision = ref(0);
type State = { key: string; page: CategoryPage | null; loading: boolean; error?: string };
const state = shallowRef<State>({ key: '', page: null, loading: true });
let controller: AbortController | null = null;
const key = computed(() => `${props.context.kind}:${props.context.id}:${parent.value?.id ?? ''}:${revision.value}`);
const current = computed<State>(() => state.value.key === key.value ? state.value : { key: key.value, page: null, loading: true });
function load(cursor: string | null, previous: CategoryPage | null) {
  controller?.abort();
  const request = new AbortController();
  controller = request;
  const session = auth.session, epoch = session.getSnapshot().epoch, at = key.value, context = props.context;
  const active = () => !request.signal.aborted && controller === request && session.getSnapshot().epoch === epoch;
  emit('ready', null);
  state.value = { key: at, page: previous, loading: true };
  const operation = context.kind === 'search' ? searchApi.categories(session, context.id, parent.value?.id ?? null, cursor, request.signal)
    : metadataApi.categories(session, context, parent.value?.id ?? null, cursor, request.signal);
  void operation.then(page => {
    if (!active()) {
      return;
    }
    const combined = { ...page, items: [...(previous?.items ?? []), ...page.items] };
    state.value = { key: at, page: combined, loading: false };
    emit('ready', combined);
  }, error => {
    if (active() && !isAbort(error)) {
      state.value = { key: at, page: null, loading: false, error: context.kind === 'search' ? searchOptionsMessage(error) : messageOf(error) };
      emit('ready', null);
    }
  });
}
watch([key, () => auth.session], (_, __, onCleanup) => {
  load(null, null);
  onCleanup(() => {
    controller?.abort();
    emit('ready', null);
  });
}, { immediate: true });
const available = computed(() => !props.disabled && !current.value.loading && !current.value.error && !!current.value.page);
function reload() {
  parent.value = null;
  revision.value++;
}
</script>

<template>
  <div class="metadata-categories"><div class="toolbar">
    <ElButton size="small" @click="parent = null">根目录</ElButton>
    <span v-if="parent">{{ parent.path.map(node => node.name).join(' / ') }}</span>
    <ElButton size="small" :disabled="!available" @click="emit('select', null)">{{ nullLabel }}</ElButton>
    <ElButton size="small" @click="reload">重新读取目录</ElButton>
  </div>
    <p v-if="current.loading" role="status">正在读取分类…</p><KbAlert v-if="current.error" type="warning" :title="current.error" />
    <p v-if="available && !current.page?.items.length">此层暂无分类</p>
    <ul><li v-for="item in current.page?.items" :key="item.id">
      <ElButton :aria-label="item.name" :type="selected === item.id ? 'primary' : undefined" size="small" :disabled="!available" @click="emit('select', item)">{{ item.name }}</ElButton>{{ ' ' }}<span class="muted">排序 {{ item.sortOrder }}</span>{{ ' ' }}
      <ElButton size="small" :disabled="!available" @click="parent = item">展开 {{ item.name }}</ElButton><slot name="actions" :item="item" :page="current.page!" />
    </li></ul>
    <ElButton v-if="current.page?.hasMore" :disabled="current.loading" @click="load(current.page!.nextCursor, current.page)">继续读取分类</ElButton>
    <p v-if="available && current.page?.items.length && !current.page.hasMore" class="muted">本层分类已全部读取</p>
  </div>
</template>
